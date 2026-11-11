"""Offline wire-protocol and real SDK playback-sync contract tests."""
import asyncio
import contextlib
import json
import time
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock

from livekit.agents import APIConnectOptions
from livekit.agents.voice import io
from livekit.agents.voice.transcription import TranscriptSynchronizer
from livekit.plugins import soniox

from .agent_text import AgentTextOutput, AgentTextSTT, AgentTextStream
from .config import VoiceConfig
from .diagnostics import AudioDiagnostics
from .echo import pcm_frame
from .resilience import room_options, speak_trial_opening


class Playback(io.AudioOutput):
    def __init__(self):
        super().__init__(label="test", sample_rate=16000,
                         capabilities=io.AudioOutputCapabilities(pause=False))

    async def capture_frame(self, frame):
        await super().capture_frame(frame)

    def flush(self):
        super().flush()

    def clear_buffer(self):
        self.on_playback_finished(playback_position=0.1, interrupted=True)


class AgentTextTest(unittest.IsolatedAsyncioTestCase):
    async def test_opening_primes_audio_then_plays_the_complete_buffered_identity(self):
        from livekit.rtc._proto import stats_pb2
        row = stats_pb2.RtcStats()
        row.outbound_rtp.stream.kind = "audio"
        row.outbound_rtp.sent.packets_sent = 30
        row.outbound_rtp.sent.bytes_sent = 1200
        room = SimpleNamespace(get_rtc_stats=AsyncMock(return_value=SimpleNamespace(publisher_stats=[row])))
        calls = []
        greeting = "안녕하세요, 김예시 님의 AI 비서입니다. 예약 통화 시험입니다."
        greeting_frames = [pcm_frame(b"\x01\x00" * 320), pcm_frame(b"\x02\x00" * 320)]

        @contextlib.asynccontextmanager
        async def synthesize(text, **kwargs):
            self.assertEqual(greeting, text)
            async def chunks():
                for frame in greeting_frames:
                    yield SimpleNamespace(frame=frame)
                calls.append("buffered")
            yield chunks()

        async def say(text, **kwargs):
            frames = [frame async for frame in kwargs["audio"]]
            if not text:
                self.assertAlmostEqual(0.6, sum(frame.duration for frame in frames))
                self.assertTrue(all(not any(frame.data) for frame in frames))
                self.assertFalse(kwargs["add_to_chat_ctx"])
            else:
                self.assertEqual(["", "buffered"], calls)
                self.assertEqual(greeting_frames, frames)
            calls.append(text)

        diagnostics = AudioDiagnostics()
        session = SimpleNamespace(say=say, tts=SimpleNamespace(synthesize=synthesize))
        await speak_trial_opening(session, room, {"reservationName": "김예시", "testCall": True}, diagnostics)
        self.assertEqual(["", "buffered", greeting], calls)
        self.assertEqual(40, diagnostics.opening_audio["bufferedAudioMs"])
        self.assertEqual(30, diagnostics.snapshot()["openingAudio"]["afterGreeting"]["packetsSent"])
        self.assertEqual("played_on_server", diagnostics.opening_audio["state"])
        self.assertFalse(room_options(VoiceConfig()).audio_output.track_publish_options.HasField("dtx"))
        trial = VoiceConfig(stt_model="soniox/stt-rt-v6")
        self.assertTrue(room_options(trial).audio_output.track_publish_options.HasField("dtx"))
        self.assertFalse(room_options(trial).audio_output.track_publish_options.dtx)

    async def test_unavailable_rtp_stats_do_not_suppress_greeting(self):
        room = SimpleNamespace(get_rtc_stats=AsyncMock(side_effect=RuntimeError("fixture")))
        @contextlib.asynccontextmanager
        async def synthesize(text, **kwargs):
            async def chunks():
                yield SimpleNamespace(frame=pcm_frame(b"\x01\x00" * 320))
            yield chunks()
        session = SimpleNamespace(say=AsyncMock(), tts=SimpleNamespace(synthesize=synthesize))
        diagnostics = AudioDiagnostics()
        await speak_trial_opening(session, room, {}, diagnostics)
        self.assertEqual(2, session.say.await_count)
        self.assertFalse(diagnostics.opening_audio["afterGreeting"]["available"])

    def client(self):
        return AgentTextSTT(api_key="fixture-secret", params=soniox.STTOptions(
            model="stt-rt-v6", language_hints=["ko"], enable_language_identification=False))

    async def connect(self, client):
        ws = SimpleNamespace(send_str=AsyncMock(), send_bytes=AsyncMock(),
                             close=AsyncMock(), closed=False)
        stream = SimpleNamespace(_stt=client, _conn_options=APIConnectOptions(),
                                 _ws=ws, _ensure_session=lambda: SimpleNamespace(ws_connect=AsyncMock(return_value=ws)))
        await AgentTextStream._connect_ws(stream)
        return stream, ws

    async def test_start_config_and_audio_text_share_one_ordered_socket(self):
        client = self.client()
        stream, ws = await self.connect(client)
        config = json.loads(ws.send_str.await_args.args[0])
        self.assertEqual("stt-rt-v6", config["model"])
        self.assertTrue(config["enable_agent_text_input"])
        self.assertEqual(16000, config["sample_rate"])
        self.assertEqual(["ko"], config["language_hints"])
        self.assertNotIn("context", config)
        self.assertTrue(client.connected.is_set())
        stream.audio_queue.put_nowait(b"pcm")
        client.send_agent_text("예약 ")
        client.send_agent_text("", end=True)
        task = asyncio.create_task(AgentTextStream._send_audio_task(stream))
        await asyncio.sleep(0)
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        ws.send_bytes.assert_awaited_once_with(b"pcm")
        self.assertEqual({"type": "agent_text", "text": "예약 ", "end_of_segment": False},
                         json.loads(ws.send_str.await_args_list[1].args[0]))
        self.assertEqual(1, client.snapshot()["sentChunks"])
        self.assertEqual(1, client.snapshot()["endedSegments"])
        self.assertNotIn("fixture-secret", json.dumps(client.snapshot()))
        await client.aclose()

    async def test_disconnect_and_reconnect_never_replay_old_text(self):
        client = self.client()
        client.send_agent_text("연결 전")
        stream, ws = await self.connect(client)
        client.send_agent_text("이전 연결")
        old_queue = stream.audio_queue
        ws.closed = True
        client.send_agent_text("연결 종료 후")
        await AgentTextStream._connect_ws(stream)
        self.assertIsNot(old_queue, stream.audio_queue)
        self.assertTrue(stream.audio_queue.empty())
        self.assertEqual(2, client.snapshot()["droppedChunks"])
        await client.aclose()

    async def test_strip_split_ssml_preserve_spaces_and_end_once(self):
        client = self.client()
        stream, _ = await self.connect(client)
        output = AgentTextOutput(client)
        await output.capture_text('가 <break ti')
        await output.capture_text('me="0.12s" /> 나 **다** ')
        output.flush()
        output.flush()
        messages = [json.loads(stream.audio_queue.get_nowait()) for _ in range(stream.audio_queue.qsize())]
        self.assertEqual("가  나 다 ", "".join(message["text"] for message in messages))
        self.assertEqual(1, sum(message["end_of_segment"] for message in messages))
        await client.aclose()

    async def test_sdk_waits_for_playback_and_discards_interrupted_tail(self):
        client = self.client()
        stream, _ = await self.connect(client)
        output = AgentTextOutput(client)
        options = room_options(VoiceConfig(), output)
        self.assertTrue(options.text_output.sync_transcription)
        self.assertIs(output, options.text_output.next_in_chain)
        audio = Playback()
        sync = TranscriptSynchronizer(next_in_chain_audio=audio, next_in_chain_text=output)
        try:
            await sync.text_output.capture_text("네 " + "아직 말하지 않은 내용 " * 20)
            sync.text_output.flush()
            await sync.audio_output.capture_frame(pcm_frame(bytes(32000 * 30)))
            sync.audio_output.flush()
            await asyncio.sleep(0.05)
            self.assertTrue(stream.audio_queue.empty(), "No text before playback")
            audio.on_playback_started(created_at=time.time())
            await asyncio.sleep(0.3)
            sync.audio_output.clear_buffer()
            await sync.audio_output.wait_for_playout()
            await sync.barrier()
            messages = [json.loads(stream.audio_queue.get_nowait()) for _ in range(stream.audio_queue.qsize())]
            text = "".join(message["text"] for message in messages)
            self.assertTrue(text.startswith("네"))
            self.assertLess(len(text), 30, "Do not forward the unplayed reply")
            self.assertEqual(1, sum(message["end_of_segment"] for message in messages))
        finally:
            await sync.aclose()
            await client.aclose()
