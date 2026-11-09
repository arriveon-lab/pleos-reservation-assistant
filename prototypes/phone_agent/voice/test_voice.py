"""No-network tests for real-call claims, result boundaries and SDK requests."""
import asyncio
import copy
import json
import tempfile
import unittest
from dataclasses import replace
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from ..service import DomainError, ReservationService, identifier, required_conditions
from .config import ConfigurationError, VoiceConfig, normalize_number
from .setup import setup_plan
from .workflow import LiveWorkflow

CONDITIONS = {"partySize": 4, "highChairCount": 1, "petAccompanied": "NO", "stepFree": True, "dietaryRequirements": ""}
DAY = (date.today() + timedelta(days=7)).isoformat()


def fake_config(**changes):
    config = VoiceConfig(livekit_url="wss://example.invalid", api_key="test-key", api_secret="test-secret",
                         sip_uri="sip:example.sip.livekit.cloud", telkit_number="+827000000000",
                         telkit_number_id="example-number", telkit_key="test-telkit-key",
                         sip_username="example-user", sip_password="example-password",
                         inbound_trunk_id="ST_in", outbound_trunk_id="ST_out", enabled=True,
                         allowed_numbers=("+821000000000",), restaurant_numbers={"demo-garden": "+821000000000"},
                         daily_calls=6)
    return replace(config, **changes)


class LiveWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.service = ReservationService()
        self.owner, _ = self.service.session()
        self.config = fake_config()
        self.workflow = LiveWorkflow(self.service, self.config)

    def tearDown(self):
        self.service.close()

    def command(self, action, payload):
        return self.service.command(self.owner, action, payload, identifier())

    def prepare(self):
        trip = self.command("create_trip", {"conditions": CONDITIONS})["id"]
        booking = self.command("create_booking", {"tripId": trip, "tripVersion": 1,
                               "date": DAY, "time": "19:00", "reservationName": "김예약"})["id"]
        self.command("approve_booking", {"bookingId": booking, "version": 1})
        return booking

    def reserve(self):
        booking = self.prepare()
        call, _ = self.workflow.reserve(self.owner, booking, 2, "live-request")
        self.workflow.mark_dispatching(call["id"])
        return booking, call

    def get_booking(self, booking):
        return self.service._get(self.owner, "booking", booking)

    def result(self, call, **changes):
        evidence = changes.get("evidence", "김예약 이름으로 예약 확정됐습니다. 요청하신 조건 모두 가능합니다.")
        self.workflow.transcript(call["id"], "restaurant", evidence)
        payload = {"accepted": True, "day": DAY, "time": "19:00", "reservation_name": "김예약", "people": 4,
                   "additional_cost": 0, "confirmed_requirements": required_conditions(CONDITIONS),
                   "evidence": evidence}
        payload.update(changes)
        return self.workflow.record_outcome(call["id"], **payload)

    def confirmed(self):
        booking, call = self.reserve()
        self.result(call)
        self.workflow.finish(call["id"])
        return booking

    def test_real_call_disabled_by_default(self):
        workflow = LiveWorkflow(self.service, replace(self.config, enabled=False))
        booking = self.prepare()
        with self.assertRaises(ConfigurationError):workflow.reserve(self.owner, booking, 2, "request")
        self.assertEqual("READY", self.get_booking(booking)["status"])

    def test_target_allowlist_is_required(self):
        workflow = LiveWorkflow(self.service, replace(self.config, allowed_numbers=()))
        with self.assertRaises(ConfigurationError):workflow.reserve(self.owner, self.prepare(), 2, "request")

    def test_reserve_is_idempotent_and_worker_nonce_required(self):
        booking, call = self.reserve()
        same, created = self.workflow.reserve(self.owner, booking, 2, "live-request")
        self.assertFalse(created)
        self.assertEqual(call["id"], same["id"])
        with self.assertRaises(DomainError):self.workflow.authorize_outbound_worker(call["id"], "wrong", call["room"])
        self.workflow.authorize_outbound_worker(call["id"], call["nonce"], call["room"])

    def test_same_request_cannot_target_different_booking(self):
        booking, _ = self.reserve()
        with self.assertRaises(DomainError):self.workflow.reserve(self.owner, "different", 2, "live-request")

    def test_pending_call_blocks_next_call_and_data_deletion(self):
        self.reserve()
        other = self.prepare()
        with self.assertRaises(DomainError):self.workflow.reserve(self.owner, other, 2, "second")
        with self.assertRaises(DomainError):self.command("delete_all", {"confirm": True})

    def test_ended_without_result_becomes_unknown_no_mock_override(self):
        booking, call = self.reserve()
        self.workflow.finish(call["id"], "disconnect")
        self.assertEqual("UNKNOWN", self.get_booking(booking)["status"])
        with self.assertRaises(DomainError):self.command("verify_booking", {
            "bookingId": booking, "version": self.get_booking(booking)["version"],
            "result": "EXISTS", "allConditionsConfirmed": True})

    def test_confirmation_needs_real_restaurant_evidence(self):
        _, call = self.reserve()
        with self.assertRaises(DomainError):self.workflow.record_outcome(call["id"], accepted=True,
            day=DAY,time="19:00",reservation_name="김예약",people=4,additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS),evidence="만든 발화")

    def test_exact_result_confirms_snapshot_and_live_mode(self):
        booking, call = self.reserve()
        self.assertEqual("CONFIRMED", self.result(call)["status"])
        self.assertEqual("LIVE", self.get_booking(booking)["mode"])
        self.assertEqual(1, self.service.state(self.owner)["externalCalls"])

    def test_recognized_name_mismatch_cannot_be_hidden_by_tool_argument(self):
        booking, call = self.reserve()
        self.assertEqual("UNKNOWN", self.result(call, evidence="다른이름으로 예약 확정됐습니다.")["status"])
        self.assertIsNone(self.get_booking(booking)["confirmedTime"])

    def test_time_cost_people_or_requirements_mismatch_stays_unknown(self):
        for changes in [{"time":"19:30"},{"reservation_name":"다른이름"},{"people":5},{"additional_cost":1000},{"confirmed_requirements":[]}]:
            with self.subTest(changes=changes):
                service = ReservationService()
                owner, _ = service.session()
                previous = self.service,self.owner,self.workflow
                self.service,self.owner,self.workflow = service,owner,LiveWorkflow(service,self.config)
                try:
                    booking, call = self.reserve()
                    self.assertEqual("UNKNOWN", self.result(call, **changes)["status"])
                    self.assertIsNone(self.get_booking(booking)["confirmedTime"])
                finally:
                    self.service,self.owner,self.workflow = previous
                    service.close()

    def test_negative_or_vague_evidence_never_confirms(self):
        booking, call = self.reserve()
        for evidence in ["예약 확정은 안 됩니다.","네", "잠시만요 예약 확정인지 확인해 볼게요."]:
            self.workflow.transcript(call["id"], "restaurant", evidence)
        self.assertEqual("UNKNOWN", self.result(call, evidence="예약 확정은 안 됩니다.")["status"])
        self.assertIsNone(self.get_booking(booking)["confirmedTime"])

    def test_inbound_rejects_unknown_caller_or_trunk(self):
        self.confirmed()
        with self.assertRaises(DomainError):self.workflow.begin_inbound("+821000000001", "ST_in", "room")
        with self.assertRaises(DomainError):self.workflow.begin_inbound("+821000000000", "wrong", "room")

    def test_callback_identification_and_change_then_real_reconfirmation(self):
        booking = self.confirmed()
        inbound = self.workflow.begin_inbound("+821000000000", "ST_in", "inbound-room")
        self.assertFalse(self.workflow.identify(inbound["id"], "다른이름", DAY, "19:00")["found"])
        self.assertTrue(self.workflow.identify(inbound["id"], "김예약", DAY, "19:00")["found"])
        self.workflow.request_change(inbound["id"], "19:30", "가상 변경 사유")
        self.workflow.finish(inbound["id"])
        current = self.get_booking(booking)
        self.command("decide_change", {"bookingId": booking, "version":current["version"], "approve":True})
        current = self.get_booking(booking)
        with self.assertRaises(DomainError):self.command("reconfirm_change", {
            "bookingId": booking, "version":current["version"], "result":"ACCEPT"})
        call, _ = self.workflow.reserve(self.owner, booking, current["version"], "change-request")
        self.assertEqual("CHANGE_CONFIRMATION", call["purpose"])
        self.workflow.mark_dispatching(call["id"])
        self.assertEqual("19:30", self.workflow.context(call["id"])["time"])
        # The previous booking's acceptance cannot confirm a later change call.
        with self.assertRaises(DomainError):
            self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:30", reservation_name="김예약", people=4,
                additional_cost=0, confirmed_requirements=required_conditions(CONDITIONS),
                evidence="김예약 이름으로 예약 확정됐습니다. 요청하신 조건 모두 가능합니다.")
        self.result(call,time="19:30")
        self.assertEqual("19:30", self.get_booking(booking)["confirmedTime"])
        self.assertEqual("APPLIED", self.get_booking(booking)["change"]["status"])

    def test_dispatch_timeout_never_redials_on_request_retry(self):
        from .transport import dial_booking
        client=SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=AsyncMock()),
            sip=SimpleNamespace(create_sip_participant=AsyncMock(side_effect=TimeoutError())),
            room=SimpleNamespace(delete_room=AsyncMock()))
        booking=self.prepare()
        asyncio.run(dial_booking(self.service,self.config,self.owner,booking,2,"timeout",client))
        asyncio.run(dial_booking(self.service,self.config,self.owner,booking,2,"timeout",client))
        self.assertEqual(1,client.sip.create_sip_participant.await_count)
        self.assertEqual("UNKNOWN",self.get_booking(booking)["status"])

    def test_daily_call_limit_counts_finished_calls(self):
        self.confirmed()
        workflow=LiveWorkflow(self.service,replace(self.config,daily_calls=1))
        with self.assertRaises(DomainError):workflow.reserve(self.owner,self.prepare(),2,"over-budget")


class ConfigurationAndSDKTest(unittest.TestCase):
    def test_stt_keyterm_is_limited_to_the_identified_call_name(self):
        from .worker import create_stt
        with patch("prototypes.phone_agent.voice.worker.inference.STT") as factory:
            create_stt(fake_config(), {"reservationName": "가상이름", "unrelated": "다른사람"})
            self.assertEqual({"keyterms_prompt": ["가상이름"]}, factory.call_args.kwargs["extra_kwargs"])
            create_stt(fake_config(), {"found": False})
            self.assertEqual({}, factory.call_args.kwargs["extra_kwargs"])
            create_stt(replace(fake_config(), stt_model="other/example"), {"reservationName": "가상이름"})
            self.assertEqual({}, factory.call_args.kwargs["extra_kwargs"])

    def test_hangup_waits_for_prior_speech_and_closing_before_deleting_room(self):
        from .worker import BookingVoiceAgent
        order = []
        async def prior():order.append("prior_speech_played")
        async def closing(*args, **kwargs):order.append("closing_played")
        async def close(reason):order.append("room_deleted")
        agent = BookingVoiceAgent(None, "test-call", {}, close)
        context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=closing))
        asyncio.run(agent.finish_call(context))
        self.assertEqual(["prior_speech_played", "closing_played", "room_deleted"], order)

    def test_audio_and_stt_diagnostics_distinguish_silence_without_saving_content(self):
        from livekit import rtc
        from livekit.agents import stt
        from .diagnostics import AudioDiagnostics
        import array
        diagnostics = AudioDiagnostics()
        diagnostics.audio(rtc.AudioFrame(bytes(320), 8000, 1, 160))
        self.assertEqual(0, diagnostics.snapshot()["peakAmplitude"])
        pcm = array.array("h", [4096] * 160).tobytes()
        diagnostics.audio(rtc.AudioFrame(pcm, 8000, 1, 160))
        diagnostics.speech_event(stt.SpeechEvent(type=stt.SpeechEventType.FINAL_TRANSCRIPT,
            alternatives=[stt.SpeechData(language="ko", text="가상 발화는 진단에 저장하지 않음")]))
        snapshot = diagnostics.snapshot()
        self.assertEqual(2, snapshot["audioFrames"])
        self.assertEqual(0.04, snapshot["audioSeconds"])
        self.assertEqual(0.125, snapshot["peakAmplitude"])
        self.assertEqual(1, snapshot["sttFinal"])
        self.assertNotIn("가상", json.dumps(snapshot, ensure_ascii=False))

    def test_audio_observation_preserves_frames_and_provider_events(self):
        from livekit import rtc
        from livekit.agents import Agent, stt
        from .worker import BookingVoiceAgent
        frame = rtc.AudioFrame(bytes(320), 8000, 1, 160)
        event = stt.SpeechEvent(type=stt.SpeechEventType.FINAL_TRANSCRIPT,
                                alternatives=[stt.SpeechData(language="ko", text="가상 응답")])
        async def source():yield frame
        async def provider(agent, audio, settings):
            async for actual in audio:self.assertIs(frame, actual)
            yield event
        async def run():
            agent = BookingVoiceAgent(None, "test-call", {}, AsyncMock())
            with patch.object(Agent.default, "stt_node", provider):
                output = [item async for item in agent.stt_node(source(), {})]
            self.assertIs(event, output[0])
            self.assertEqual(1, agent.diagnostics.snapshot()["audioFrames"])
            self.assertEqual(1, agent.diagnostics.snapshot()["sttFinal"])
        asyncio.run(run())

    def test_unheard_reminder_runs_once_and_never_after_reply_or_end(self):
        from .diagnostics import AudioDiagnostics, reprompt_if_unheard
        for final, ended, state, expected in [(0,False,"listening",1),(1,False,"listening",0),
                                             (0,True,"listening",0),(0,False,"speaking",0)]:
            with self.subTest(final=final, ended=ended, state=state):
                diagnostics = AudioDiagnostics();diagnostics.final = final
                session = SimpleNamespace(user_state=state, say=AsyncMock())
                sleep = AsyncMock()
                asyncio.run(reprompt_if_unheard(session, diagnostics, lambda: ended, sleep=sleep))
                self.assertEqual(expected, session.say.await_count)

    def test_handoff_event_is_not_speech_and_does_not_break_transcripts(self):
        from livekit.agents.llm import AgentHandoff, ChatMessage
        from .worker import store_conversation_item
        workflow = SimpleNamespace(transcript=Mock())
        self.assertFalse(store_conversation_item(workflow, "test-call", AgentHandoff(new_agent_id="booking")))
        workflow.transcript.assert_not_called()
        self.assertFalse(store_conversation_item(workflow, "test-call", ChatMessage(role="user", content=["예약 확정됐습니다."])))
        workflow.transcript.assert_called_once_with("test-call", "restaurant", "예약 확정됐습니다.")
        self.assertTrue(store_conversation_item(workflow, "test-call", ChatMessage(role="assistant", content=["감사합니다."])))
        self.assertFalse(store_conversation_item(workflow, "test-call", ChatMessage(role="assistant", content=[])))

    def test_secrets_not_in_repr_or_readiness_report(self):
        config=fake_config()
        value=repr(config)+json.dumps(config.report())+json.dumps(setup_plan(config))
        for secret in [config.api_key,config.api_secret,config.telkit_key,config.sip_password]:
            self.assertNotIn(secret,value)

    def test_configuration_normalizes_domestic_numbers(self):
        self.assertEqual("+821000000000",normalize_number("010-0000-0000"))
        for number in ["+12025550000","0600000000","not-a-number"]:
            with self.assertRaises(ConfigurationError):normalize_number(number)

    def test_setup_schemas_match_installed_livekit_protocol(self):
        from google.protobuf.json_format import ParseDict
        from livekit import api
        plan=setup_plan(fake_config())
        for key,cls in [("inbound",api.SIPInboundTrunkInfo),("outbound",api.SIPOutboundTrunkInfo),
                        ("dispatch",api.SIPDispatchRuleInfo)]:ParseDict(plan[key],cls())
        self.assertEqual(["+821000000000"],plan["inbound"]["allowed_numbers"])
        self.assertEqual("tls",plan["telkitConnection"]["transport"])

    def test_sdk_voice_worker_and_tools_import_without_network(self):
        from .worker import BookingVoiceAgent
        self.assertTrue(callable(BookingVoiceAgent))

    def test_real_dial_sdk_request_contains_duration_and_no_private_metadata(self):
        from .transport import dial_booking
        service=ReservationService();owner,_=service.session()
        def command(action,payload):return service.command(owner,action,payload,identifier())
        trip=command("create_trip",{"conditions":CONDITIONS})["id"]
        booking=command("create_booking",{"tripId":trip,"tripVersion":1,"reservationName":"김예약","date":DAY,"time":"19:00"})["id"]
        command("approve_booking",{"bookingId":booking,"version":1})
        client=SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=AsyncMock()),
                               sip=SimpleNamespace(create_sip_participant=AsyncMock()))
        try:
            asyncio.run(dial_booking(service,fake_config(),owner,booking,2,"request",client))
            request=client.sip.create_sip_participant.await_args.args[0]
            self.assertEqual(180,request.max_call_duration.seconds)
            metadata=client.agent_dispatch.create_dispatch.await_args.args[0].metadata
            self.assertNotIn("김예약",metadata)
            self.assertNotIn("partySize",metadata)
        finally:service.close()


if __name__ == "__main__":unittest.main()
