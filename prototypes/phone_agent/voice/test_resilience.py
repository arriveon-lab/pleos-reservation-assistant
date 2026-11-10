"""Fault and turn-boundary tests. No calls, model requests, or Cloud resources."""
import asyncio
from dataclasses import replace
import os
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from livekit.agents import StopResponse
from livekit.agents.llm import ChatMessage

from ..service import DomainError
from .config import VoiceConfig, ConfigurationError
from .diagnostics import AudioDiagnostics
from .resilience import (CallSupervisor, SerializedStore, control_intent, fallback_audio,
                         room_options, turn_options)


def fake_session():
    return SimpleNamespace(user_state="listening", agent_state="listening", say=AsyncMock(),
                           interrupt=AsyncMock(), update_agent=Mock(), wait_for_idle=AsyncMock(),
                           current_agent=object())


class ResilienceTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.now = 0
        self.session = fake_session()
        self.close = AsyncMock()
        self.diagnostics = AudioDiagnostics()
        self.supervisor = CallSupervisor(self.session, self.close, self.diagnostics,
                                         lambda: False, clock=lambda: self.now)

    async def asyncTearDown(self):
        await self.supervisor.stop()

    async def test_hold_request_suppresses_reply_and_waits_before_one_check(self):
        with self.assertRaises(StopResponse):
            await self.supervisor.user_turn("네, 잠시만요. 확인해 볼게요.")
        self.assertEqual(1, self.session.say.await_count)
        self.now = 19
        await self.supervisor.tick()
        self.assertEqual(1, self.session.say.await_count)
        self.now = 21
        await self.supervisor.tick()
        self.assertEqual("아직 확인 중이실까요?", self.session.say.await_args.args[0])
        self.now = 34
        await self.supervisor.tick()
        self.close.assert_awaited_once_with("no_response")

    async def test_correction_releases_hold_and_is_not_discarded_as_wait_or_backchannel(self):
        with self.assertRaises(StopResponse):
            await self.supervisor.user_turn("잠시만요")
        await self.supervisor.user_turn("잠시만요, 일곱 시 말고 여덟 시요.")
        self.assertIsNone(self.supervisor.hold_until)
        await self.supervisor.user_turn("네")
        self.close.assert_not_awaited()

    async def test_repeated_hold_does_not_extend_the_same_hold_forever(self):
        for self.now in (0, 10, 15):
            with self.assertRaises(StopResponse):
                await self.supervisor.user_turn("잠시만요")
        self.assertEqual(20, self.supervisor.hold_until)
        self.assertEqual(1, self.session.say.await_count)

    async def test_idle_after_any_answer_prompts_once_then_finishes(self):
        await self.supervisor.user_turn("예약 가능합니다")
        self.now = 13
        await self.supervisor.tick()
        self.now = 26
        await self.supervisor.tick()
        self.now = 40
        await self.supervisor.tick()
        self.assertEqual(1, self.diagnostics.idle_prompts)
        self.close.assert_awaited_once_with("no_response")

    async def test_speaking_caller_or_agent_does_not_receive_idle_prompt(self):
        for user, agent in [("speaking", "listening"), ("listening", "speaking"), ("listening", "thinking")]:
            self.now += 20
            self.session.user_state, self.session.agent_state = user, agent
            await self.supervisor.tick()
        self.session.say.assert_not_awaited()

    async def test_return_from_hold_resets_silence_timer(self):
        with self.assertRaises(StopResponse):
            await self.supervisor.user_turn("확인하고 올게요")
        self.now = 18
        await self.supervisor.user_turn("네, 두 명 예약 가능합니다")
        self.now = 22
        await self.supervisor.tick()
        self.assertEqual(1, self.session.say.await_count)

    async def test_automated_answer_and_call_refusal_do_not_reach_booking_model(self):
        for utterance, reason in [("삐 소리 후 메시지를 남겨 주세요", "automated_answer"),
                                   ("전화 끊어 주세요", "callee_unavailable")]:
            self.supervisor.terminal = False
            with self.assertRaises(StopResponse):
                await self.supervisor.user_turn(utterance)
            self.assertEqual(reason, self.close.await_args.args[0])

    async def test_interrupted_fragments_do_not_use_completed_turn_allowance(self):
        for _ in range(20):
            self.supervisor.assistant_message(ChatMessage(role="assistant", content=["추가"], interrupted=True))
        self.assertEqual(0, self.supervisor.completed_turns)
        for _ in range(12):
            self.supervisor.assistant_message(ChatMessage(role="assistant", content=["가상 질문입니다."]))
        await asyncio.gather(*self.supervisor.tasks)
        self.close.assert_awaited_once_with("turn_limit")

    async def test_recoverable_sdk_error_does_not_start_duplicate_app_retry(self):
        error = SimpleNamespace(type="stt_error", recoverable=True)
        self.supervisor.error(SimpleNamespace(error=error))
        self.session.update_agent.assert_not_called()
        self.assertFalse(self.supervisor.tasks)

    async def test_stt_fatal_error_resets_once_then_second_failure_closes(self):
        for attempt in range(2):
            error = SimpleNamespace(type="stt_error", recoverable=False)
            self.supervisor.error(SimpleNamespace(error=error))
            self.assertTrue(error.recoverable)
            await asyncio.gather(*self.supervisor.tasks)
        self.session.update_agent.assert_called_once_with(self.session.current_agent)
        self.assertEqual(1, self.diagnostics.recoveries)
        self.close.assert_awaited_once_with("stt_error")

    async def test_llm_recovery_requests_repeat_without_regenerating_partial_tool_call(self):
        self.supervisor.error(SimpleNamespace(error=SimpleNamespace(type="llm_error", recoverable=False)))
        await asyncio.gather(*self.supervisor.tasks)
        self.session.update_agent.assert_not_called()
        self.assertIn("audio", self.session.say.await_args.kwargs)
        self.close.assert_not_awaited()

    async def test_tts_failure_plays_pcm_without_calling_tts_and_closes_once(self):
        frames = []
        async def say(text, **kwargs):
            if "audio" not in kwargs:
                raise RuntimeError("TTS unavailable")
            async for frame in kwargs["audio"]:
                frames.append(frame)
        self.session.say = say
        event = SimpleNamespace(error=SimpleNamespace(type="tts_error", recoverable=False))
        self.supervisor.error(event)
        await asyncio.gather(*self.supervisor.tasks)
        await self.supervisor.finish("duplicate")
        self.assertGreater(len(frames), 100)
        self.close.assert_awaited_once_with("tts_error")

    async def test_fallback_playback_failure_still_cleans_up_call(self):
        self.session.say.side_effect = RuntimeError("media closed")
        await self.supervisor.finish("media_error")
        self.close.assert_awaited_once_with("media_error")
        self.assertEqual(1, self.diagnostics.fallback_failures)

    async def test_saved_result_closing_is_not_reopened_by_model_failure(self):
        self.supervisor.closing = True
        self.supervisor.error(SimpleNamespace(error=SimpleNamespace(type="llm_error", recoverable=False)))
        await asyncio.gather(*self.supervisor.tasks)
        self.assertEqual(0, self.diagnostics.recoveries)
        self.close.assert_awaited_once_with("llm_error")

    async def test_store_failure_ends_without_asking_to_repeat_booking(self):
        self.supervisor.store = SimpleNamespace(failed=True)
        await self.supervisor.tick()
        self.close.assert_awaited_once_with("storage_error")

    async def test_database_queue_preserves_evidence_order_off_audio_thread(self):
        store, rows = SerializedStore(), []
        main_thread = threading.get_ident()
        try:
            for i in range(5):
                store.enqueue(lambda i=i: rows.append((i, threading.get_ident())))
            result = await store.run(lambda: list(rows))
            self.assertEqual(list(range(5)), [x[0] for x in result])
            self.assertTrue(all(x[1] != main_thread for x in result))
        finally:
            await store.close(lambda: None)

    async def test_user_evidence_is_persisted_before_hold_suppresses_model_reply(self):
        from .worker import BookingVoiceAgent
        workflow = SimpleNamespace(transcript=Mock())
        store = SerializedStore()
        agent = BookingVoiceAgent(workflow, "fake", {}, self.close,
                                  supervisor=self.supervisor, store=store)
        try:
            with self.assertRaises(StopResponse):
                await agent.on_user_turn_completed(None, ChatMessage(role="user", content=["잠시만요"]))
            workflow.transcript.assert_called_once_with("fake", "restaurant", "잠시만요")
        finally:
            await store.close(lambda: None)

    async def test_awaited_store_failure_is_fatal_but_domain_rejection_is_not(self):
        store = SerializedStore()
        try:
            with self.assertRaises(DomainError):
                await store.run(Mock(side_effect=DomainError("REJECTED", "가상 조건 불일치")))
            self.assertFalse(store.failed)
            with self.assertRaises(OSError):
                await store.run(Mock(side_effect=OSError("fake disk failure")))
            self.supervisor.store = store
            await self.supervisor.tick()
            self.close.assert_awaited_once_with("storage_error")
        finally:
            await store.close(lambda: None)

    async def test_bundled_audio_decodes_without_provider_and_stays_short(self):
        for name in ("call_end", "please_repeat"):
            frames = [frame async for frame in fallback_audio(name)]
            duration = sum(x.samples_per_channel / x.sample_rate for x in frames)
            self.assertGreater(duration, 1)
            self.assertLess(duration, 8)

    async def test_actual_sdk_constructors_accept_options_without_connecting(self):
        from .worker import create_session
        # This catches real SDK signature drift; mocked factories alone cannot do so.
        with patch.dict(os.environ, {"LIVEKIT_URL": "wss://example.invalid",
                                    "LIVEKIT_API_KEY": "test-key", "LIVEKIT_API_SECRET": "test-secret" * 4}), \
             patch("socket.socket.connect", side_effect=AssertionError("Network forbidden")):
            session = create_session(VoiceConfig(), {})
            try:
                self.assertEqual(1, session.conn_options.llm_conn_options.max_retry)
                self.assertEqual(1, session.conn_options.stt_conn_options.max_retry)
                self.assertEqual(1, session.conn_options.tts_conn_options.max_retry)
            finally:
                await session.aclose()


class AudioConfigurationTest(unittest.TestCase):
    def test_disabled_deployment_worker_never_imports_or_registers_sdk_worker(self):
        from . import launch
        config = VoiceConfig(livekit_url="wss://example.invalid", api_key="test-key",
                             api_secret="test-secret", outbound_trunk_id="test-trunk",
                             allowed_numbers=("+821000000000",))
        with patch.object(launch, "load_env_file"), \
             patch.object(launch.VoiceConfig, "from_env", return_value=config), \
             patch.object(launch.importlib, "import_module") as importer:
            with self.assertRaises(ConfigurationError):
                launch.main()
            importer.assert_not_called()

    def test_enabled_deployment_worker_delegates_to_sdk_after_validation(self):
        from . import launch
        config = VoiceConfig(livekit_url="wss://example.invalid", api_key="test-key",
                             api_secret="test-secret", outbound_trunk_id="test-trunk",
                             allowed_numbers=("+821000000000",), enabled=True)
        worker = SimpleNamespace(server=object(), agents=SimpleNamespace(
            cli=SimpleNamespace(run_app=Mock())))
        with patch.object(launch, "load_env_file"), \
             patch.object(launch.VoiceConfig, "from_env", return_value=config), \
             patch.object(launch.importlib, "import_module", return_value=worker):
            launch.main()
            worker.agents.cli.run_app.assert_called_once_with(worker.server)

    def test_prewarmed_vad_is_reused_without_loading_again_during_call(self):
        from .worker import prewarm, create_session
        process = SimpleNamespace(userdata={})
        with patch("prototypes.phone_agent.voice.worker.silero.VAD.load") as load, \
             patch("prototypes.phone_agent.voice.worker.create_stt"), \
             patch("prototypes.phone_agent.voice.worker.inference.LLM"), \
             patch("prototypes.phone_agent.voice.worker.inference.TTS"), \
             patch("prototypes.phone_agent.voice.worker.AgentSession") as factory:
            prewarm(process)
            create_session(VoiceConfig(), {}, vad=process.userdata["vad"])
            load.assert_called_once_with(activation_threshold=0.5)
            self.assertIs(load.return_value, factory.call_args.kwargs["vad"])

    def test_default_only_uses_nc_and_preserves_overlap_audio(self):
        config = VoiceConfig()
        options = turn_options(config)
        self.assertEqual("adaptive", options["interruption"]["mode"])
        self.assertFalse(options["interruption"]["discard_audio_if_uninterruptible"])
        self.assertFalse(options["preemptive_generation"]["enabled"])
        with patch("prototypes.phone_agent.voice.resilience.noise_cancellation.NC") as nc:
            room_options(config)
            nc.assert_called_once()
        self.assertIsNone(room_options(replace(config, noise_suppression=False)).audio_input.noise_cancellation)

    def test_invalid_audio_configuration_is_rejected_and_vad_rollback_is_supported(self):
        for settings in [{"PHONE_INTERRUPTION_MODE": "unknown"}, {"PHONE_NOISE_SUPPRESSION": "maybe"}]:
            with self.assertRaises(ConfigurationError):
                VoiceConfig.from_env({"PHONE_SECRETS_DIR": "no-test-secrets", **settings})
        config = VoiceConfig.from_env({"PHONE_SECRETS_DIR": "no-test-secrets", "PHONE_INTERRUPTION_MODE": "vad"})
        self.assertEqual("vad", turn_options(config)["interruption"]["mode"])

    def test_normal_answers_and_negated_machine_mentions_are_not_gated(self):
        for text in ("네", "아니요", "잠시만요 일곱 시 말고 여덟 시요", "음성사서함이 아니고 직원입니다", "예약 가능합니다"):
            self.assertEqual("conversation", control_intent(text))

    def test_session_constructor_passes_supported_sdk_options_without_network(self):
        from .worker import create_session
        with patch("prototypes.phone_agent.voice.worker.create_stt") as stt, \
             patch("prototypes.phone_agent.voice.worker.inference.LLM"), \
             patch("prototypes.phone_agent.voice.worker.inference.TTS"), \
             patch("prototypes.phone_agent.voice.worker.silero.VAD.load"), \
             patch("prototypes.phone_agent.voice.worker.AgentSession") as factory:
            create_session(VoiceConfig(), {"reservationName": "가상이름"})
            self.assertEqual(0.5, factory.call_args.kwargs["turn_handling"]["endpointing"]["min_delay"])
            stt.assert_called_once()


if __name__ == "__main__":
    unittest.main()
