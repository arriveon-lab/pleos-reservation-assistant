"""No-network tests for real-call claims, result boundaries and SDK requests."""
import asyncio
import copy
import json
import tempfile
import unittest
from dataclasses import replace
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, PropertyMock, patch

from ..service import DomainError, ReservationService, identifier, required_conditions
from .config import SONIOX_TRIAL_MODEL, ConfigurationError, VoiceConfig, normalize_number
from .setup import setup_plan
from .workflow import LiveWorkflow

CONDITIONS = {"partySize": 4, "highChairCount": 1, "petAccompanied": "NO", "stepFree": True, "dietaryRequirements": ""}
DAY = (date.today() + timedelta(days=7)).isoformat()


def fake_config(**changes):
    config = VoiceConfig(livekit_url="wss://example.invalid", api_key="test-key", api_secret="test-secret",
                         sip_uri="sip:example.sip.livekit.cloud", telkit_number="+827000000000",
                         telkit_number_id="example-number", telkit_key="test-telkit-key",
                         sip_username="example-user", sip_password="example-password",
                         inbound_trunk_id="ST_in", outbound_trunk_id="ST_out",
                         enabled=True, inbound_enabled=True,
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

    def test_misheard_cost_reply_gets_one_clarification_before_the_model_can_close(self):
        from livekit.agents import StopResponse
        from livekit.agents.llm import ChatMessage
        from .worker import BookingVoiceAgent

        booking, call = self.reserve()
        call_id = call["id"]
        close, say = AsyncMock(), AsyncMock()
        agent = BookingVoiceAgent(self.workflow, call_id, self.workflow.context(call_id), close)
        self.workflow.transcript(call_id, "agent", "추가 비용은 따로 없을까요?")
        misheard = ChatMessage(role="user", content=["네, 추가 배움 없습니다."])

        async def exchange():
            with self.assertRaises(StopResponse):
                await agent.on_user_turn_completed(None, misheard)
            self.assertEqual("CALLING", self.get_booking(booking)["status"])
            say.assert_awaited_once_with("추가 비용이 드는지 한 번 더 말씀해 주시겠어요?", allow_interruptions=False)
            self.workflow.transcript(call_id, "agent", say.await_args.args[0])
            with self.assertRaises(StopResponse):
                await agent.on_user_turn_completed(None, misheard)
            self.assertEqual(2, say.await_count)
            say.assert_awaited_with("더 생각해보고 말씀드리겠습니다. 감사합니다.", allow_interruptions=False)
            close.assert_awaited_once_with("additional_fee_unconfirmed")

        with patch.object(BookingVoiceAgent, "session", new_callable=PropertyMock,
                          return_value=SimpleNamespace(say=say)):
            asyncio.run(exchange())

    def test_clear_cost_replies_and_unrelated_answers_do_not_trigger_clarification(self):
        _, call = self.reserve()
        for question, reply in (
            ("추가 비용은 따로 없을까요?", "아니요, 추가 비용은 없어요."),
            ("추가 비용이 있나요?", "없습니다."),
            ("추가 비용이 있나요?", "예약금이 이만 원이에요."),
            ("추가 비용이 있나요?", "예약이 어렵습니다."),
            ("예약 가능할까요?", "잠깐 봐야 할 것 같아요."),
        ):
            with self.subTest(reply=reply):
                self.workflow.transcript(call["id"], "agent", question)
                self.workflow.transcript(call["id"], "restaurant", reply)
                self.assertFalse(self.workflow.cost_reply_needs_clarification(call["id"]))

    def test_reported_fee_ends_booking_before_model_can_request_reservation(self):
        from livekit.agents import StopResponse
        from livekit.agents.llm import ChatMessage
        from .worker import BookingVoiceAgent

        booking, call = self.reserve()
        call_id = call["id"]
        self.workflow.transcript(call_id, "agent", "저녁 일곱 시 반에 두 명 예약 가능할까요?")
        reply = "네, 가상 예약자 저녁 7시 반 추가 요금은 인당 3,500원."
        close = AsyncMock(side_effect=lambda reason: self.workflow.finish(call_id, reason))
        say = AsyncMock()
        agent = BookingVoiceAgent(self.workflow, call_id, self.workflow.context(call_id), close)
        with patch.object(BookingVoiceAgent, "session", new_callable=PropertyMock,
                          return_value=SimpleNamespace(say=say)):
            with self.assertRaises(StopResponse):
                asyncio.run(agent.on_user_turn_completed(None, ChatMessage(role="user", content=[reply])))
        say.assert_awaited_once_with("더 생각해보고 말씀드리겠습니다. 감사합니다.", allow_interruptions=False)
        close.assert_awaited_once_with("additional_fee_reported")
        self.assertEqual("UNKNOWN", self.get_booking(booking)["status"])
        self.assertIn("추가 요금", self.get_booking(booking)["notice"])
        self.assertEqual(reply, self.service._list(self.owner, "event")[-1]["message"])

    def test_only_explicit_nonzero_surcharges_trigger_early_deferral(self):
        from .workflow import reports_required_fee

        self.assertTrue(reports_required_fee("별도 비용은 한 명당 삼천 오백 원이에요."))
        self.assertTrue(reports_required_fee("네, 추가 비용이 있어요."))
        for reply in (
            "추가 요금은 0원입니다.",
            "추가 요금은 없어요. 메뉴는 3,500원입니다.",
            "음료를 선택하시면 추가 요금은 3,500원입니다.",
            "별실 이용 시 추가 요금은 3,500원입니다.",
            "추가 요금은 3,500원?",
            "추가 요금은 원입니다.",
        ):
            with self.subTest(reply=reply):
                self.assertFalse(reports_required_fee(reply))

    def test_previous_fee_vetoes_confirmation_and_preserves_request_context(self):
        from livekit.agents import StopResponse
        from .worker import BookingVoiceAgent

        booking, call = self.reserve()
        call_id = call["id"]
        self.workflow.transcript(call_id, "restaurant", "네, 저녁 7시 반 추가 요금은 인당 3,500원.")
        self.assertFalse(self.workflow.booking_requested(call_id))
        self.workflow.transcript(call_id, "agent", "김 예 약 이름으로 예약 부탁드립니다.")
        self.workflow.transcript(call_id, "restaurant", "네.")
        self.assertTrue(self.workflow.booking_requested(call_id))
        close, say = AsyncMock(), AsyncMock()
        agent = BookingVoiceAgent(self.workflow, call_id, self.workflow.context(call_id), close)
        context = SimpleNamespace(wait_for_playout=AsyncMock(), session=SimpleNamespace(say=say))
        with self.assertRaises(StopResponse):
            asyncio.run(agent.save_reservation_result(
                context, True, DAY, "19:00", "김예약", 4, 0, required_conditions(CONDITIONS), "네."))
        self.assertEqual("UNKNOWN", self.get_booking(booking)["status"])
        self.assertFalse(self.workflow._find(call_id)[1]["confirmationChecks"]["noAdditionalCost"])
        say.assert_awaited_once_with("예약이 확정됐는지 확인이 필요합니다. 안내 감사합니다.",
                                     allow_interruptions=False)

    def test_target_allowlist_is_required(self):
        workflow = LiveWorkflow(self.service, replace(self.config, allowed_numbers=()))
        with self.assertRaises(ConfigurationError):workflow.reserve(self.owner, self.prepare(), 2, "request")

    def test_mock_recommendation_blocks_live_dial_even_with_an_allowlisted_target(self):
        restaurant_id = "mock-test-restaurant"
        service = ReservationService(mock_restaurants={restaurant_id: "가상 추천 식당"})
        self.addCleanup(service.close)
        owner, _ = service.session()
        trip = service.command(owner, "create_trip", {"conditions": CONDITIONS}, identifier())
        booking = service.command(owner, "create_booking", {
            "tripId": trip["id"], "tripVersion": 1, "restaurantId": restaurant_id,
            "date": DAY, "time": "19:00", "reservationName": "가상 예약자",
        }, identifier())
        service.command(owner, "approve_booking", {
            "bookingId": booking["id"], "version": 1,
        }, identifier())
        config = replace(self.config, restaurant_numbers={restaurant_id: self.config.allowed_numbers[0]})
        workflow = LiveWorkflow(service, config)
        with self.assertRaises(DomainError) as error:
            workflow.reserve(owner, booking["id"], 2, "mock-recommendation")
        self.assertEqual("MOCK_RECOMMENDATION_ONLY", error.exception.code)
        self.assertEqual([], workflow._calls())
        self.assertEqual("READY", service.state(owner)["bookings"][0]["status"])

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

    def test_session_deletion_keeps_server_daily_call_usage(self):
        _, call = self.reserve()
        self.workflow.finish(call["id"])
        self.command("delete_all", {"confirm": True})
        self.owner, _ = self.service.session()
        booking = self.prepare()
        workflow = LiveWorkflow(self.service, replace(self.config, daily_calls=1))
        with self.assertRaises(DomainError) as error:
            workflow.reserve(self.owner, booking, 2, "new-session")
        self.assertEqual("CALL_LIMIT", error.exception.code)
        self.assertEqual([], workflow._calls())

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

    def test_named_request_acknowledgement_confirms_without_repeating_every_field(self):
        booking, call = self.reserve()
        self.workflow.transcript(call["id"], "agent", "예약자 이름은 김예약입니다. 이 이름으로 예약 부탁드립니다.")
        evidence = "네, 알겠습니다. 좀 이따 뵐게요."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        self.workflow.transcript(call["id"], "agent", "추가 비용이나 필수 조건에 문제가 있을까요?")
        self.workflow.transcript(call["id"], "restaurant", "추가 비용 없고 요청하신 조건 모두 가능합니다.")
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence=evidence)
        self.assertEqual("CONFIRMED", result["status"])
        self.assertEqual("19:00", self.get_booking(booking)["confirmedTime"])
        self.assertEqual("contextual_acknowledgement", self.workflow._find(call["id"])[1]["confirmationBasis"])

    def test_short_acceptance_is_bound_to_the_named_booking_request(self):
        from .workflow import acknowledged_booking_name
        for reply in ["네", "네.", "네네", "예", "넵", "아, 네.", "네, 좋습니다.", "네, 맞아요.",
                      "오케이, 가능합니다.", "접수해 두겠습니다.",
                      "알겠습니다.", "그렇게 해드릴게요.", "네, 예약되었습니다.",
                      "예약 확정됐습니다.", "네, 알겠습니다. 이따 뵐게요."]:
            with self.subTest(reply=reply):
                events = [{"speaker": "agent", "message": "김예약 이름으로 예약 부탁드립니다."},
                          {"speaker": "restaurant", "message": reply}]
                self.assertTrue(acknowledged_booking_name(events, reply, "김예약"))

    def test_bare_yes_is_valid_evidence_even_when_cost_answer_is_also_yes(self):
        booking, call = self.reserve()
        for speaker, message in [
            ("agent", "김예약 이름으로 예약 부탁드립니다."), ("restaurant", "네."),
            ("agent", "추가 비용은 없고 필수 조건도 모두 가능할까요?"), ("restaurant", "네."),
        ]:
            self.workflow.transcript(call["id"], speaker, message)
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence="네")
        self.assertEqual("CONFIRMED", result["status"])
        self.assertEqual("19:00", self.get_booking(booking)["confirmedTime"])

    def test_short_acceptance_uses_this_bookings_approved_name(self):
        name = "박테스트"
        trip = self.command("create_trip", {"conditions": CONDITIONS})["id"]
        booking = self.command("create_booking", {"tripId": trip, "tripVersion": 1,
                               "date": DAY, "time": "19:00", "reservationName": name})["id"]
        self.command("approve_booking", {"bookingId": booking, "version": 1})
        call, _ = self.workflow.reserve(self.owner, booking, 2, "different-approved-name")
        self.workflow.mark_dispatching(call["id"])
        self.assertEqual(name, self.workflow.context(call["id"])["reservationName"])
        self.workflow.transcript(call["id"], "agent", name + " 이름으로 예약 부탁드립니다.")
        self.workflow.transcript(call["id"], "restaurant", "네")
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name=name, people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence="네")
        self.assertEqual("CONFIRMED", result["status"])

    def test_natural_named_request_and_model_acceptance_can_confirm(self):
        _, call = self.reserve()
        self.workflow.transcript(call["id"], "agent", "김예약 님으로 예약 부탁드립니다.")
        evidence = "오케이, 가능합니다."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence=evidence)
        self.assertEqual("CONFIRMED", result["status"])

    def test_request_context_cannot_override_the_models_negative_acceptance(self):
        _, call = self.reserve()
        self.workflow.transcript(call["id"], "agent", "김예약 님으로 예약 부탁드립니다.")
        self.workflow.transcript(call["id"], "restaurant", "좋아요")
        result = self.workflow.record_outcome(call["id"], accepted=False, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence="좋아요")
        self.assertEqual("UNKNOWN", result["status"])

    def test_booking_completion_reply_does_not_require_repeating_the_name(self):
        booking, call = self.reserve()
        for speaker, message in [
            ("agent", "김예약 이름으로 예약 부탁드립니다."),
            ("restaurant", "네, 예약되었습니다."),
            ("agent", "추가 비용이나 필수 조건에 문제가 있을까요?"),
            ("restaurant", "아니요, 비용은 없고 요청하신 조건 모두 가능합니다."),
        ]:
            self.workflow.transcript(call["id"], speaker, message)
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence="네, 예약되었습니다.")
        self.assertEqual("CONFIRMED", result["status"])
        self.assertEqual("contextual_acknowledgement", self.workflow._find(call["id"])[1]["confirmationBasis"])

    def test_hold_after_acceptance_can_resolve_without_repeating_the_name(self):
        _, call = self.reserve()
        for speaker, message in [
            ("agent", "김예약 이름으로 예약 부탁드립니다."), ("restaurant", "네"),
            ("agent", "추가 비용이나 필수 조건에 문제가 있을까요?"),
            ("restaurant", "잠시만요, 확인해 볼게요."), ("agent", "네, 기다리겠습니다."),
            ("restaurant", "추가 비용 없고 요청하신 조건 모두 가능합니다."),
        ]:
            self.workflow.transcript(call["id"], speaker, message)
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence="네")
        self.assertEqual("CONFIRMED", result["status"])

    def test_yes_to_availability_or_cost_alone_stays_unknown(self):
        booking, call = self.reserve()
        self.workflow.transcript(call["id"], "agent", "김예약 이름으로 예약 가능할까요?")
        self.workflow.transcript(call["id"], "restaurant", "네")
        result = self.workflow.record_outcome(call["id"], accepted=True, day=DAY, time="19:00",
            reservation_name="김예약", people=4, additional_cost=0,
            confirmed_requirements=required_conditions(CONDITIONS), evidence="네")
        self.assertEqual("UNKNOWN", result["status"])
        self.assertIsNone(self.get_booking(booking)["confirmedTime"])

    def test_short_acceptance_rejects_qualified_partial_and_stale_evidence(self):
        from .workflow import acknowledged_booking_name
        named = "김예약 이름으로 예약 부탁드립니다."
        for prompt, reply, quote in [
            (named, "네, 다만 시간이 달라요.", "네"),
            (named, "네, 따로 없습니다.", "네, 따로 없습니다."),
            (named, "네, 확인해 볼게요.", "네"),
            (named, "네, 추가 비용이 있습니다.", "네, 추가 비용이 있습니다."),
            (named, "네, 예약됐지만 다른 시간입니다.", "네, 예약됐지만 다른 시간입니다."),
            ("김예약 이름으로 예약 부탁드립니다. 이름은 들리셨나요?", "네", "네"),
            ("예약할까요?", "네", "네"),
            ("박김예약 이름으로 예약 부탁드립니다.", "네", "네"),
            ("김예약 이름으로 예약 부탁드리면 안 될까요?", "네", "네"),
        ]:
            with self.subTest(prompt=prompt, reply=reply):
                events = [{"speaker": "agent", "message": prompt},
                          {"speaker": "restaurant", "message": reply}]
                self.assertFalse(acknowledged_booking_name(events, quote, "김예약"))
        events = [{"speaker": "agent", "message": named},
                  {"speaker": "restaurant", "message": "네"}]
        for _ in range(3):
            events += [{"speaker": "agent", "message": "추가 사항이 있나요?"},
                       {"speaker": "restaurant", "message": "네, 따로 없습니다."}]
        self.assertFalse(acknowledged_booking_name(events, "네", "김예약"))

    def test_later_retraction_overrides_short_acceptance(self):
        from .workflow import acknowledged_booking_name
        for correction in ["잠시만요, 아직 확인이 필요해요.", "예약은 안 돼요.",
                           "일곱 시 말고 여덟 시요.", "이름은 다른이름입니다."]:
            with self.subTest(correction=correction):
                events = [{"speaker": "agent", "message": "김예약 이름으로 예약 부탁드립니다."},
                          {"speaker": "restaurant", "message": "네"},
                          {"speaker": "restaurant", "message": correction}]
                self.assertFalse(acknowledged_booking_name(events, "네", "김예약"))

    def test_completion_with_a_qualification_cannot_bypass_context_checks(self):
        booking, call = self.reserve()
        self.workflow.transcript(call["id"], "agent", "김예약 이름으로 예약 부탁드립니다.")
        evidence = "김예약 이름으로 예약됐지만 다른 시간으로만 가능합니다."
        result = self.result(call, evidence=evidence)
        self.assertEqual("UNKNOWN", result["status"])
        self.assertIsNone(self.get_booking(booking)["confirmedTime"])

    def test_acknowledgement_cannot_confirm_an_unrelated_or_conditional_request(self):
        from .workflow import acknowledged_booking_name
        named = "김예약 이름으로 예약 부탁드립니다."
        for request, reply, later in [
            ("김예약 이름으로 예약 가능할까요?", "네, 알겠습니다.", ""),
            ("다른이름으로 예약 부탁드립니다.", "네, 알겠습니다.", ""),
            ("김예약 이름 말고 다른이름으로 예약 부탁드립니다.", "네, 알겠습니다.", ""),
            ("예약자 이름은 김예", "네, 알겠습니다.", ""),
            (named, "네, 알겠습니다. 다만 다른 시간으로 가능합니다.", ""),
            (named, "네, 알겠습니다.", "그런데 예약은 취소해야 합니다."),
            (named, "네, 알겠습니다.", "이름은 김예약이 아니에요."),
        ]:
            with self.subTest(request=request, reply=reply, later=later):
                events = [{"speaker":"agent", "message":request}, {"speaker":"restaurant", "message":reply}]
                if later:events.append({"speaker":"restaurant", "message":later})
                self.assertFalse(acknowledged_booking_name(events, reply, "김예약"))

    def test_acknowledged_name_cannot_be_reused_from_a_previous_call(self):
        _, previous = self.reserve()
        self.workflow.transcript(previous["id"], "agent", "김예약 이름으로 예약 부탁드립니다.")
        self.workflow.finish(previous["id"])
        booking = self.prepare()
        call, _ = self.workflow.reserve(self.owner, booking, 2, "new-call")
        self.workflow.mark_dispatching(call["id"])
        self.assertEqual("UNKNOWN", self.result(call, evidence="네, 알겠습니다.")["status"])

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

    def personal_call(self):
        booking = self.confirmed()
        self.workflow.bind_inbound_number(self.owner, self.config.telkit_number, "ST_in")
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "personal-room",
                                           self.config.telkit_number)
        return booking, call

    def test_personal_number_loads_only_its_owners_existing_reservation(self):
        from .resilience import initial_reply_instructions, opening_text
        booking = self.confirmed()
        other, _ = self.service.session()
        self.service._put(other, "booking", {**self.get_booking(booking), "id": identifier()})
        self.workflow.bind_inbound_number(self.owner, self.config.telkit_number, "ST_in")
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "personal-room",
                                           self.config.telkit_number)
        context = self.workflow.context(call["id"])
        self.assertEqual(self.owner, self.workflow._find(call["id"])[0])
        self.assertEqual(booking, call["bookingId"])
        self.assertTrue(context["personalAssistant"])
        self.assertEqual(("김예약", DAY, "19:00", 4),
                         (context["reservationName"], context["date"], context["time"],
                          context["conditions"]["partySize"]))
        self.assertIn("김예약", opening_text(context))
        self.assertIn("다시 묻거나 새 예약을 요청하지 않는다", initial_reply_instructions(context))
        self.assertTrue(self.service.state(self.owner)["personalPhoneLinked"])
        self.assertFalse(self.service.state(other)["personalPhoneLinked"])
        self.assertEqual([], self.service.state(other)["events"])

    def test_personal_number_with_multiple_reservations_never_selects_another_owner(self):
        booking = self.confirmed()
        second = {**self.get_booking(booking), "id": identifier(), "confirmedTime": "20:00"}
        self.service._put(self.owner, "booking", second)
        other, _ = self.service.session()
        self.service._put(other, "booking", {**self.get_booking(booking), "id": identifier()})
        self.workflow.bind_inbound_number(self.owner, self.config.telkit_number, "ST_in")
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "ambiguous-personal",
                                           self.config.telkit_number)
        self.assertFalse(self.workflow.context(call["id"])["found"])
        self.assertNotIn("reservationName", self.workflow.context(call["id"]))
        self.assertTrue(self.workflow.identify(call["id"], "김예약", DAY, "19:00")["found"])
        self.assertEqual(booking, self.workflow._find(call["id"])[1]["bookingId"])

    def test_personal_number_never_falls_back_to_foreign_or_mock_reservations(self):
        booking = self.confirmed()
        other, _ = self.service.session()
        original = self.get_booking(booking)
        self.service._put(other, "booking", {**original, "id": identifier()})
        self.service._put(self.owner, "booking", {**original, "mode": "MOCK"})
        self.workflow.bind_inbound_number(self.owner, self.config.telkit_number, "ST_in")
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "no-personal-match",
                                           self.config.telkit_number)
        self.assertFalse(self.workflow.context(call["id"])["found"])
        self.assertFalse(self.workflow.identify(call["id"], "김예약", DAY, "19:00")["found"])
        self.assertEqual([], self.service.state(other)["callbacks"])

    def test_personal_binding_rejects_wrong_called_number_caller_and_trunk(self):
        self.confirmed()
        self.workflow.bind_inbound_number(self.owner, self.config.telkit_number, "ST_in")
        for caller, trunk, called in (("+821000000000", "ST_in", None),
                                       ("+821000000000", "ST_in", "+827000000001"),
                                       ("+821000000001", "ST_in", self.config.telkit_number),
                                       ("+821000000000", "wrong", self.config.telkit_number)):
            with self.subTest(caller=caller, trunk=trunk, called=called):
                with self.assertRaises(DomainError):
                    self.workflow.begin_inbound(caller, trunk, "rejected", called)
        self.assertEqual(1, len(self.workflow._calls()))

    def test_binding_is_idempotent_and_cannot_transfer_ownership(self):
        self.confirmed()
        self.assertTrue(self.workflow.bind_inbound_number(
            self.owner, self.config.telkit_number, "ST_in")["created"])
        self.assertFalse(self.workflow.bind_inbound_number(
            self.owner, self.config.telkit_number, "ST_in")["created"])
        other, _ = self.service.session()
        with self.assertRaises(DomainError):
            self.workflow.bind_inbound_number(other, self.config.telkit_number, "ST_in")
        self.assertEqual(self.owner, self.workflow._inbound_owner("ST_in", self.config.telkit_number))

    def test_binding_during_an_active_call_is_rejected(self):
        self.reserve()
        with self.assertRaises(DomainError):
            self.workflow.bind_inbound_number(self.owner, self.config.telkit_number, "ST_in")
        self.assertFalse(self.service.state(self.owner)["personalPhoneLinked"])

    def test_personal_callback_saves_change_without_asking_to_identify_again(self):
        booking, call = self.personal_call()
        snapshot = self.get_booking(booking)["approvedSnapshot"]
        evidence = "일곱 시 반으로 변경 부탁드립니다."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        self.assertTrue(self.workflow.request_change(call["id"], "19:30", "식당 사정", evidence)["saved"])
        context = self.workflow.context(call["id"])
        self.assertEqual("19:00", context["time"])
        self.assertEqual("19:30", context["pendingChange"]["proposedTime"])
        self.assertEqual("AWAITING_USER", context["pendingChange"]["status"])
        self.assertFalse(context["pendingChange"]["approved"])
        self.assertEqual(snapshot, context["conditions"])

    def test_personal_context_preserves_critical_review_state(self):
        _, call = self.personal_call()
        evidence = "예약을 받을 수 없게 됐습니다."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        self.workflow.request_note(call["id"], "UNAVAILABLE", "예약 불가", evidence)
        self.assertTrue(self.workflow.context(call["id"])["reviewRequired"])
        self.assertEqual("19:00", self.workflow.context(call["id"])["time"])

    def test_callback_identification_and_change_then_real_reconfirmation(self):
        booking = self.confirmed()
        inbound = self.workflow.begin_inbound("+821000000000", "ST_in", "inbound-room")
        self.assertFalse(self.workflow.identify(inbound["id"], "다른이름", DAY, "19:00")["found"])
        self.assertTrue(self.workflow.identify(inbound["id"], "김예약", DAY, "19:00")["found"])
        evidence = "일곱 시 반으로 변경할 수 있을까요?"
        self.workflow.transcript(inbound["id"], "restaurant", evidence)
        self.workflow.request_change(inbound["id"], "19:30", "가상 변경 사유", evidence)
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

    def inbound(self, identify=True):
        booking = self.confirmed()
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "inbound-room")
        if identify:
            self.workflow.identify(call["id"], "김예약", DAY, "19:00")
        return booking, call

    def test_receive_only_worker_accepts_inbound_but_never_dials(self):
        booking = self.confirmed()
        config = replace(self.config, enabled=False, outbound_trunk_id="")
        config.require_worker()
        workflow = LiveWorkflow(self.service, config)
        with self.assertRaises(ConfigurationError):
            workflow.reserve(self.owner, booking, self.get_booking(booking)["version"], "no-dial")
        call = workflow.begin_inbound("+821000000000", "ST_in", "receive-only")
        self.assertEqual("CALLBACK", workflow.context(call["id"])["purpose"])
        with self.assertRaises(ConfigurationError):
            LiveWorkflow(self.service, replace(config, inbound_enabled=False)).begin_inbound(
                "+821000000000", "ST_in", "disabled")

    def test_unidentified_callback_starts_without_exposing_booking(self):
        from .resilience import initial_reply_instructions, opening_text
        _, call = self.inbound(identify=False)
        context = self.workflow.context(call["id"])
        self.assertFalse(context["found"])
        self.assertNotIn("reservationName", context)
        self.assertNotIn("김예약", opening_text(context))
        self.assertIn("성함", initial_reply_instructions(context))
        self.assertIn("새 예약을 요청하지 않는다", initial_reply_instructions(context))
        self.assertEqual("CALLBACK", context["purpose"])

    def test_identification_moves_only_this_calls_private_journal(self):
        booking, call = self.inbound(identify=False)
        self.workflow.transcript(call["id"], "restaurant", "김예약 예약으로 연락했어요.")
        pending = self.service._list("inbound", "unidentified_call_event")[0]
        self.service._put("inbound", "unidentified_call_event", {
            **pending, "id": identifier(), "callId": "another-call", "message": "별도 통화"})
        self.assertFalse(any(e.get("callId") == call["id"] for e in self.service.state(self.owner)["events"]))
        self.assertFalse(self.workflow.identify(call["id"], "김예약", DAY, "20:00")["found"])
        self.workflow.identify(call["id"], "김예약", DAY, "19:00")
        moved = next(e for e in self.service.state(self.owner)["events"] if e["id"] == pending["id"])
        self.assertEqual((booking, pending["at"], "LIVE"),
                         (moved["bookingId"], moved["at"], moved["mode"]))
        self.assertEqual(["another-call"], [e["callId"] for e in self.service._list("inbound", "unidentified_call_event")])
        with self.assertRaises(DomainError):
            self.workflow.identify(call["id"], "다른이름", DAY, "19:00")

    def test_ambiguous_reservations_never_reveal_candidates_or_mutate_them(self):
        booking, call = self.inbound(identify=False)
        other_owner, _ = self.service.session()
        duplicate = {**self.get_booking(booking), "id": identifier()}
        self.service._put(other_owner, "booking", duplicate)
        result = self.workflow.identify(call["id"], "김예약", DAY, "19:00")
        self.assertFalse(result["found"])
        self.assertNotIn("conditions", result)
        self.assertEqual([], self.service.state(other_owner)["callbacks"])
        with self.assertRaises(DomainError):
            self.workflow.request_note(call["id"], "OTHER", "문의", "김예약입니다")

    def test_callback_requires_actual_whole_recent_same_call_evidence(self):
        _, call = self.inbound()
        evidence = "일곱 시 반으로 바꿔 주세요. 어려우면 다시 연락 주세요."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        for quote in (None, "만든 발화", "일곱 시 반으로 바꿔 주세요.",
                      "김예약 이름으로 예약 확정됐습니다. 요청하신 조건 모두 가능합니다."):
            with self.subTest(quote=quote), self.assertRaises(DomainError):
                self.workflow.request_change(call["id"], "19:30", "변경 요청", quote)
        self.assertEqual([], self.service.state(self.owner)["callbacks"])

    def test_callback_is_idempotent_preserves_original_and_exposes_only_live_receipt(self):
        booking, call = self.inbound()
        original = copy.deepcopy(self.get_booking(booking))
        evidence = "일곱 시 반으로 바꿔 주세요."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        events_before = len(self.service.state(self.owner)["events"])
        result = self.workflow.request_change(call["id"], "19:30", "영업 사정", evidence)
        again = self.workflow.request_change(call["id"], "19:30", "영업 사정", evidence)
        self.assertEqual(result, again)
        with self.assertRaises(DomainError):
            self.workflow.request_change(call["id"], "20:00", "영업 사정", evidence)
        current = self.get_booking(booking)
        self.assertEqual("19:00", current["confirmedTime"])
        self.assertEqual(original["approvedSnapshot"], current["approvedSnapshot"])
        self.assertEqual("AWAITING_USER", current["change"]["status"])
        state = self.service.state(self.owner)
        self.assertEqual((1, 1), (len(state["callbacks"]), state["inboundCalls"]))
        self.assertEqual(events_before, len(state["events"]))  # No synthetic speech.
        self.assertEqual(("LIVE", call["id"], evidence),
                         tuple(state["callbacks"][0][k] for k in ("mode", "callId", "evidence")))
        other_owner, _ = self.service.session()
        self.assertEqual([], self.service.state(other_owner)["callbacks"])

    def test_callback_version_conflict_never_overwrites_changed_booking(self):
        booking, call = self.inbound()
        current = self.get_booking(booking)
        current["version"] += 1
        self.service._put(self.owner, "booking", current)
        self.workflow.transcript(call["id"], "restaurant", "예약 취소 부탁드립니다.")
        with self.assertRaises(DomainError):
            self.workflow.request_note(call["id"], "CANCELLATION", "취소 통보", "예약 취소 부탁드립니다.")
        self.assertEqual([], self.service.state(self.owner)["callbacks"])
        self.assertNotIn("reviewRequired", self.get_booking(booking))

    def test_live_callback_cannot_attach_to_identical_mock_booking(self):
        booking, call = self.inbound()
        duplicate = {**self.get_booking(booking), "id": identifier(), "mode": "MOCK"}
        self.service._put(self.owner, "booking", duplicate)
        evidence = "일곱 시 반으로 바꿔 주세요."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        self.workflow.request_change(call["id"], "19:30", "변경", evidence)
        self.assertEqual(booking, self.service.state(self.owner)["callbacks"][0]["bookingId"])
        self.assertIsNone(self.get_booking(duplicate["id"])["change"])

    def test_cancellation_receipt_blocks_approved_change_without_cancelling_reservation(self):
        booking, call = self.inbound()
        self.workflow.transcript(call["id"], "restaurant", "일곱 시 반으로 변경 부탁드려요.")
        self.workflow.request_change(call["id"], "19:30", "변경", "일곱 시 반으로 변경 부탁드려요.")
        self.workflow.finish(call["id"])
        current = self.get_booking(booking)
        self.command("decide_change", {"bookingId": booking, "version": current["version"], "approve": True})
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "second-inbound")
        self.workflow.identify(call["id"], "김예약", DAY, "19:00")
        evidence = "영업이 어려워 예약 취소 부탁드립니다."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        result = self.workflow.request_note(call["id"], "CANCELLATION", "식당 취소 통보", evidence)
        self.assertEqual(result, self.workflow.request_note(call["id"], "CANCELLATION", "식당 취소 통보", evidence))
        self.workflow.finish(call["id"])
        current = self.get_booking(booking)
        self.assertEqual(("CONFIRMED", "19:00", True),
                         (current["status"], current["confirmedTime"], current["reviewRequired"]))
        with self.assertRaises(DomainError):
            self.workflow.reserve(self.owner, booking, current["version"], "blocked-reconfirmation")
        self.assertEqual("NEEDS_REVIEW", self.service.state(self.owner)["callbacks"][-1]["status"])

    def test_unavailable_note_needs_review_and_other_note_preserves_booking(self):
        booking, call = self.inbound()
        evidence = "주차 위치를 안내드리려고 전화했어요."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        self.workflow.request_note(call["id"], "OTHER", "주차 안내", evidence)
        self.assertNotIn("reviewRequired", self.get_booking(booking))
        self.workflow.finish(call["id"])
        call = self.workflow.begin_inbound("+821000000000", "ST_in", "unavailable")
        self.workflow.identify(call["id"], "김예약", DAY, "19:00")
        evidence = "예약을 받을 수 없게 됐어요."
        self.workflow.transcript(call["id"], "restaurant", evidence)
        self.workflow.request_note(call["id"], "UNAVAILABLE", "예약 불가", evidence)
        self.assertTrue(self.get_booking(booking)["reviewRequired"])
        self.assertEqual("19:00", self.get_booking(booking)["confirmedTime"])

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

    def test_sip_failure_categories_are_saved_without_provider_message_or_redial(self):
        from livekit import api
        from .transport import dial_booking
        for code, reason in [(486, "sip_busy"), (408, "sip_no_answer"), (488, "sip_media_rejected")]:
            client = SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=AsyncMock()),
                sip=SimpleNamespace(create_sip_participant=AsyncMock(side_effect=api.TwirpError(
                    "unavailable", "private provider content", status=503,
                    metadata={"sip_status_code": str(code), "extra": "private"}))),
                room=SimpleNamespace(delete_room=AsyncMock()))
            booking = self.prepare()
            result = asyncio.run(dial_booking(self.service, self.config, self.owner, booking, 2, str(code), client))
            again = asyncio.run(dial_booking(self.service, self.config, self.owner, booking, 2, str(code), client))
            self.assertTrue(again["reused"])
            self.assertEqual(1, client.sip.create_sip_participant.await_count)
            self.assertEqual("UNKNOWN", self.get_booking(booking)["status"])
            self.assertEqual(reason, self.workflow._find(result["id"])[1]["endReason"])


class ConfigurationAndSDKTest(unittest.TestCase):
    def test_callback_receipt_is_spoken_before_close_without_claiming_applied(self):
        from livekit.agents import StopResponse
        from .worker import BookingVoiceAgent
        order = []

        async def prior():
            order.append("prior")

        async def say(text, **kwargs):
            order.append(text)

        async def close(reason):
            order.append(reason)

        workflow = SimpleNamespace(request_note=Mock(return_value={"saved": True}))
        agent = BookingVoiceAgent(workflow, "call", {}, close)
        context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=say))
        with self.assertRaises(StopResponse):
            asyncio.run(agent.save_callback_note(context, "CANCELLATION", "취소 통보", "실제 발화"))
        self.assertEqual("prior", order[0])
        self.assertIn("접수", order[1])
        self.assertNotIn("완료", order[1])
        self.assertEqual("callback_received", order[-1])

    def test_change_call_speaks_user_decision_and_only_waits_for_confirmation_after_approval(self):
        from livekit.agents import StopResponse
        from .worker import BookingVoiceAgent
        from .workflow import CALLBACK_CONFIRM_QUESTION

        for decision, expected, reason in (
            ("RECONFIRMING", CALLBACK_CONFIRM_QUESTION, None),
            ("REJECTED", "예약자가 시간 변경을 원하지 않습니다. 기존 시간으로 부탁드립니다. 감사합니다.", "callback_change_rejected"),
            ("AWAITING_USER", "예약자 확인이 필요한 요청으로 남겨두겠습니다. 기다려 주셔서 감사합니다.", "callback_approval_pending"),
        ):
            with self.subTest(decision=decision), patch("prototypes.phone_agent.voice.worker.CHANGE_APPROVAL_SECONDS", 0):
                workflow = SimpleNamespace(request_change=Mock(return_value={"saved": True}),
                                           callback_change_decision=Mock(return_value=decision),
                                           callback_confirmation_prompt=Mock(return_value=CALLBACK_CONFIRM_QUESTION))
                close, say = AsyncMock(), AsyncMock()
                agent = BookingVoiceAgent(workflow, "callback", {"purpose": "CALLBACK"}, close)
                context = SimpleNamespace(wait_for_playout=AsyncMock(), session=SimpleNamespace(say=say))
                with self.assertRaises(StopResponse):
                    asyncio.run(agent.save_change_request(context, "19:30", "식당 사정", "실제 발화"))
                self.assertEqual("예약자에게 확인할게요. 잠시만 기다려 주세요.", say.await_args_list[0].args[0])
                self.assertEqual(expected, say.await_args_list[1].args[0])
                self.assertEqual(decision == "RECONFIRMING", agent.awaiting_callback_confirmation)
                if reason:
                    close.assert_awaited_once_with(reason)
                else:
                    close.assert_not_awaited()

    def test_invalid_callback_receipt_does_not_announce_or_close(self):
        from .worker import BookingVoiceAgent
        workflow = SimpleNamespace(request_note=Mock(side_effect=DomainError("EVIDENCE_REQUIRED", "근거 필요")))
        close, say = AsyncMock(), AsyncMock()
        agent = BookingVoiceAgent(workflow, "call", {}, close)
        context = SimpleNamespace(wait_for_playout=AsyncMock(), session=SimpleNamespace(say=say))
        result = asyncio.run(agent.save_callback_note(context, "OTHER", "문의", "없는 발화"))
        self.assertFalse(result["saved"])
        close.assert_not_awaited()
        say.assert_not_awaited()

    def test_saved_outcome_controls_closing_and_prevents_model_success_rewrite(self):
        from livekit.agents import StopResponse
        from .worker import BookingVoiceAgent
        defer = "더 생각해보고 말씀드리겠습니다. 감사합니다."
        cases = (
            ("BOOKING", "CONFIRMED", "예약 확정 감사합니다. 통화를 마치겠습니다."),
            ("BOOKING", "DECLINED", defer),
            ("BOOKING", "UNKNOWN", defer),
            ("CHANGE_CONFIRMATION", "DECLINED", "예약이 어렵다는 말씀 확인했습니다. 안내 감사합니다."),
            ("CHANGE_CONFIRMATION", "UNKNOWN", "예약 확정 여부는 아직 확인하지 못했습니다. 안내 감사합니다."),
        )
        for purpose, status, expected in cases:
            with self.subTest(purpose=purpose, status=status):
                order = []
                workflow = SimpleNamespace(record_outcome=Mock(return_value={"saved": True, "status": status}))

                async def prior():
                    order.append("prior")

                async def say(text, *, allow_interruptions):
                    self.assertFalse(allow_interruptions)
                    order.append(text)

                async def close(reason):
                    order.append(reason)

                agent = BookingVoiceAgent(workflow, "call", {"purpose": purpose}, close)
                context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=say))
                with self.assertRaises(StopResponse):
                    asyncio.run(agent.save_reservation_result(context, status == "CONFIRMED", DAY, "19:00",
                                                              "김예약", 4, 0, [], "가상 근거"))
                self.assertEqual(["prior", expected, "agent_finished"], order)

    def test_test_call_continues_after_conditions_until_named_reservation_is_accepted(self):
        from livekit.agents import StopResponse
        from .worker import BookingVoiceAgent

        service = ReservationService(web_test_enabled=True)
        self.addCleanup(service.close)
        owner, _ = service.session()
        snapshot = CONDITIONS | {"partySize": 2, "highChairCount": 0}
        search_id = service.remember_web_search(owner, {
            "validUntil": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
            "result": {"webRecommendations": [{
                "ordinal": 1, "name": "가상 양식당", "address": "가상 주소",
                "identitySourceUrl": "https://example.invalid/restaurant",
            }]},
        }, snapshot)
        booking_id = service.command(owner, "create_web_test_booking", {
            "searchId": search_id, "candidateOrdinal": 1, "reservationName": "김예약",
            "date": DAY, "time": "19:00",
        }, "draft")["id"]
        service.command(owner, "approve_booking", {"bookingId": booking_id, "version": 1}, "approve")
        workflow = LiveWorkflow(service, fake_config(test_phone_input=True))
        call, _ = workflow.reserve(owner, booking_id, 2, "test-call",
                                   test_phone_number="010-0000-1234")
        call_id = call["id"]
        workflow.mark_dispatching(call_id)
        close, say = AsyncMock(), AsyncMock()
        context = SimpleNamespace(wait_for_playout=AsyncMock(), session=SimpleNamespace(say=say))
        agent = BookingVoiceAgent(workflow, call_id, workflow.context(call_id), close)

        for speaker, message in (
            ("agent", "저녁 일곱 시에 두 명 예약 가능할까요?"),
            ("restaurant", "네, 가능합니다."),
            ("agent", "계단 없이 입장이 가능할까요?"),
            ("restaurant", "네, 엘리베이터로 입장 가능합니다."),
            ("agent", "추가 비용이 있을까요?"),
            ("restaurant", "네, 따로 없습니다."),
        ):
            workflow.transcript(call_id, speaker, message)

        approved_fields = (True, DAY, "19:00", "김예약", 2, 0, ["계단 없이 입장"])
        fee_reply = "네, 따로 없습니다."
        result = asyncio.run(agent.finish_call(context))
        self.assertFalse(result["ended"])
        result = asyncio.run(agent.save_reservation_result(
            context, False, *approved_fields[1:-1], [], fee_reply))
        self.assertEqual("BOOKING_ACCEPTANCE_REQUIRED", result["code"])
        say.assert_not_awaited()

        with self.assertRaises(StopResponse):
            asyncio.run(agent.save_reservation_result(context, False, *approved_fields[1:], fee_reply))
        self.assertEqual("CALLING", service._get(owner, "booking", booking_id)["status"])
        close.assert_not_awaited()
        say.assert_awaited_once_with("김 예 약 이름으로 예약 부탁드립니다.", allow_interruptions=False)

        self.assertEqual("김 예 약", workflow.context(call_id)["spokenReservationName"])
        workflow.transcript(call_id, "agent", say.await_args.args[0])
        accepted = "네."
        workflow.transcript(call_id, "restaurant", accepted)
        with self.assertRaises(StopResponse):
            asyncio.run(agent.save_reservation_result(context, *approved_fields, accepted))
        booking = service._get(owner, "booking", booking_id)
        self.assertEqual("CONFIRMED", booking["status"])
        self.assertEqual({"계단 없이 입장": "CONFIRMED"}, booking["requirementChecks"])
        close.assert_awaited_once_with("agent_finished")

        workflow.finish(call_id, "agent_finished")
        caller = "+821000001234"
        callback_config = fake_config(allowed_numbers=(caller,),
                                      restaurant_numbers={booking["restaurantId"]: caller})
        callback = LiveWorkflow(service, replace(callback_config, callback_test_booking_id=booking_id))
        with self.assertRaises(DomainError):
            callback.begin_inbound(caller, "ST_in", "wrong-called-number", "+827000000001")
        wrong_caller = "+821000001235"
        wrong_config = replace(callback.config, allowed_numbers=(wrong_caller,),
                               restaurant_numbers={booking["restaurantId"]: wrong_caller})
        with self.assertRaises(DomainError) as error:
            LiveWorkflow(service, wrong_config).begin_inbound(
                wrong_caller, "ST_in", "wrong-caller", callback_config.telkit_number)
        self.assertEqual("CALLBACK_TEST_UNMATCHED", error.exception.code)
        incoming = callback.begin_inbound(caller, "ST_in", "callback", callback_config.telkit_number)
        inbound_context = callback.context(incoming["id"])
        self.assertEqual(("CALLBACK", True, "19:00"),
                         (inbound_context["purpose"], inbound_context["found"], inbound_context["time"]))
        self.assertNotIn("testCall", inbound_context)
        request = "일곱 시 예약을 저녁 일곱 시 삼십 분으로 미룰 수 있을까요?"
        callback.transcript(incoming["id"], "restaurant", request)
        receipt = callback.request_change(incoming["id"], "19:30", "식당 사정", request)
        booking = service._get(owner, "booking", booking_id)
        self.assertTrue(receipt["saved"])
        self.assertEqual("19:00", booking["confirmedTime"])
        self.assertEqual(("AWAITING_USER", "19:30"),
                         (booking["change"]["status"], booking["change"]["proposedTime"]))
        self.assertTrue(service.state(owner)["bookings"][0]["change"]["waitingOnPhone"])
        original_change_id = booking["change"]["id"]
        callback.finish(incoming["id"], "callback_approval_pending")
        self.assertFalse(service.state(owner)["bookings"][0]["change"].get("waitingOnPhone"))
        incoming = callback.begin_inbound(caller, "ST_in", "repeat-callback", callback_config.telkit_number)
        callback.transcript(incoming["id"], "restaurant", request)
        with self.assertRaises(DomainError) as error:
            callback.request_change(incoming["id"], "20:00", "식당 사정", request)
        self.assertEqual("CHANGE_PENDING", error.exception.code)
        callback.request_change(incoming["id"], "19:30", "식당 사정", request)
        booking = service._get(owner, "booking", booking_id)
        self.assertEqual(original_change_id, booking["change"]["id"])
        self.assertTrue(service.state(owner)["bookings"][0]["change"]["waitingOnPhone"])
        service.command(owner, "decide_change", {"bookingId": booking_id, "version": booking["version"],
                                                 "approve": True}, "approve-time-change")
        self.assertEqual("RECONFIRMING", callback.callback_change_decision(incoming["id"]))
        self.assertEqual("19:00", service._get(owner, "booking", booking_id)["confirmedTime"])
        with self.assertRaises(DomainError):
            callback.confirm_callback_change(incoming["id"], request)
        from .workflow import CALLBACK_CONFIRM_QUESTION
        callback.transcript(incoming["id"], "agent", CALLBACK_CONFIRM_QUESTION)
        callback.transcript(incoming["id"], "restaurant", "네, 변경됐습니다.")
        self.assertTrue(callback.confirm_callback_change(incoming["id"], "네, 변경됐습니다.")["saved"])
        self.assertEqual("19:30", service._get(owner, "booking", booking_id)["confirmedTime"])

    def test_invalid_result_does_not_announce_success_or_close_before_clarification(self):
        from .worker import BookingVoiceAgent
        workflow = SimpleNamespace(record_outcome=Mock(side_effect=DomainError("EVIDENCE_REQUIRED", "실제 발화 필요")))
        close = AsyncMock();say = AsyncMock();prior = AsyncMock()
        agent = BookingVoiceAgent(workflow, "call", {}, close)
        context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=say))
        result = asyncio.run(agent.save_reservation_result(context, True, DAY, "19:00", "김예약", 4, 0, [], "없는 근거"))
        self.assertFalse(result["saved"])
        say.assert_not_awaited();close.assert_not_awaited();prior.assert_not_awaited()

    def test_stt_keyterm_is_limited_to_the_identified_call_name(self):
        from .worker import create_stt
        with patch("prototypes.phone_agent.voice.worker.inference.STT") as factory:
            create_stt(fake_config(), {"reservationName": "가상이름", "unrelated": "다른사람"})
            self.assertEqual({"keyterms_prompt": ["가상이름"]}, factory.call_args.kwargs["extra_kwargs"])
            create_stt(fake_config(), {"purpose": "BOOKING", "reservationName": "가상이름",
                                       "requiredConditions": ["계단 없이 입장"]})
            self.assertEqual({"keyterms_prompt": ["가상이름", "추가 비용", "추가 요금", "계단 없이 입장", "엘리베이터"]},
                             factory.call_args.kwargs["extra_kwargs"])
            create_stt(fake_config(), {"found": False})
            self.assertEqual({}, factory.call_args.kwargs["extra_kwargs"])
            create_stt(replace(fake_config(), stt_model="other/example"), {"reservationName": "가상이름"})
            self.assertEqual({}, factory.call_args.kwargs["extra_kwargs"])

    def test_cartesia_receives_continuous_streamed_name_in_soniox_trial(self):
        from .worker import create_session

        async def chunks():
            for part in ("안녕하세요, 김", "민", "준 님의 비서입니다. 김 민", " 준 이름으로 예약 부탁드립니다."):
                yield part

        async def transformed(transform):
            return "".join([part async for part in transform(chunks())])

        with patch("prototypes.phone_agent.voice.worker.AgentSession") as session, \
                patch("prototypes.phone_agent.voice.worker.inference"), \
                patch("prototypes.phone_agent.voice.worker.create_stt"):
            create_session(fake_config(), {"reservationName": "김민준"}, vad=object())
            transform = session.call_args.kwargs["tts_text_transforms"][-1]
            name = '김 <break time="0.12s" /> 민 <break time="0.12s" /> 준'
            self.assertEqual(f"안녕하세요, {name} 님의 비서입니다. {name} 이름으로 예약 부탁드립니다.",
                             asyncio.run(transformed(transform)))
            create_session(fake_config(stt_model=SONIOX_TRIAL_MODEL),
                           {"reservationName": "김민준", "testCall": True}, vad=object())
            transform = session.call_args.kwargs["tts_text_transforms"][-1]
            self.assertEqual("안녕하세요, 김민준 님의 비서입니다. 김민준 이름으로 예약 부탁드립니다.",
                             asyncio.run(transformed(transform)))
            create_session(fake_config(tts_model="other/example"), {"reservationName": "김민준"}, vad=object())
            self.assertEqual(["filter_markdown", "filter_emoji"], session.call_args.kwargs["tts_text_transforms"])

    def test_hangup_waits_for_prior_speech_and_closing_before_deleting_room(self):
        from .worker import BookingVoiceAgent
        order = []

        async def prior():
            order.append("prior_speech_played")

        async def closing(text, *, allow_interruptions):
            self.assertFalse(allow_interruptions)
            order.append(text)

        async def close(reason):
            order.append("room_deleted")

        context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=closing))
        for purpose, expected in (
            ("BOOKING", "더 생각해보고 말씀드리겠습니다. 감사합니다."),
            ("CALLBACK", "안내 감사합니다. 통화를 마치겠습니다."),
        ):
            with self.subTest(purpose=purpose):
                order.clear()
                agent = BookingVoiceAgent(None, "test-call", {"purpose": purpose}, close)
                asyncio.run(agent.finish_call(context))
                self.assertEqual(["prior_speech_played", expected, "room_deleted"], order)

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
        config=fake_config(soniox_key="test-soniox-secret")
        value=repr(config)+json.dumps(config.report())+json.dumps(setup_plan(config))
        for secret in [config.api_key,config.api_secret,config.telkit_key,config.sip_password,config.soniox_key]:
            self.assertNotIn(secret,value)

    def test_soniox_trial_requires_key_and_bounds_before_startup(self):
        config = fake_config(stt_model=SONIOX_TRIAL_MODEL, inbound_enabled=False, max_seconds=60, daily_calls=1)
        with self.assertRaisesRegex(ConfigurationError, "SONIOX_API_KEY"):
            config.require_worker()
        config = replace(config, soniox_key="fixture-key")
        config.require_worker()
        for changes in ({"inbound_enabled": True}, {"test_phone_input": True}, {"max_seconds": 61},
                        {"daily_calls": 2}, {"allowed_numbers": ("+8220000000",)},
                        {"stt_model": "soniox/stt-rt-v5"}):
            with self.subTest(changes=changes), self.assertRaises(ConfigurationError):
                replace(config, **changes).require_worker()

    def test_soniox_trial_blocks_regular_bookings_before_sip(self):
        from .transport import dial_booking
        service = ReservationService()
        self.addCleanup(service.close)
        owner, _ = service.session()
        trip = service.command(owner, "create_trip", {"conditions": CONDITIONS}, "trip")["id"]
        booking = service.command(owner, "create_booking", {"tripId": trip, "tripVersion": 1,
                                  "reservationName": "가상이름", "date": DAY, "time": "19:00"}, "book")["id"]
        service.command(owner, "approve_booking", {"bookingId": booking, "version": 1}, "approve")
        config = fake_config(stt_model=SONIOX_TRIAL_MODEL, soniox_key="fixture-key",
                             inbound_enabled=False, max_seconds=60, daily_calls=1)
        dispatch, sip = AsyncMock(), AsyncMock()
        client = SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=dispatch),
                                 sip=SimpleNamespace(create_sip_participant=sip))
        with self.assertRaises(DomainError) as error:
            asyncio.run(dial_booking(service, config, owner, booking, 2, "dial", client))
        self.assertEqual("SONIOX_TEST_ONLY", error.exception.code)
        dispatch.assert_not_awaited()
        sip.assert_not_awaited()

    def test_soniox_trial_uses_real_v6_adapter_only_for_test_context(self):
        from .worker import create_stt
        config = fake_config(stt_model=SONIOX_TRIAL_MODEL, soniox_key="fixture-key",
                             inbound_enabled=False, max_seconds=60, daily_calls=1)
        with self.assertRaises(DomainError):
            create_stt(config, {"purpose": "BOOKING"})
        client = create_stt(config, {"purpose": "BOOKING", "testCall": True, "reservationName": "가상이름"})
        self.assertEqual("stt-rt-v6", client.model)
        asyncio.run(client.aclose())

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

    def test_web_candidate_is_session_owned_and_dials_only_registered_mobile(self):
        from .transport import dial_booking
        service = ReservationService(web_test_enabled=True)
        self.addCleanup(service.close)
        owner, _ = service.session()
        other, _ = service.session()
        snapshot = {**CONDITIONS, "parkingRequired": True}
        search_id = service.remember_web_search(owner, {
            "validUntil": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
            "result": {"webRecommendations": [{
                "ordinal": 1, "name": "가상 검색 후보", "address": "가상 주소",
                "identitySourceUrl": "https://example.invalid/restaurant",
            }]},
        }, snapshot)
        payload = {"searchId": search_id, "candidateOrdinal": 1, "reservationName": "김예약",
                   "date": DAY, "time": "19:00", "target": "+8220000000"}
        with self.assertRaisesRegex(DomainError, "최신 웹검색"):
            service.command(other, "create_web_test_booking", payload, identifier())
        booking = service.command(owner, "create_web_test_booking", payload, "draft")["id"]
        self.assertEqual(booking, service.command(owner, "create_web_test_booking", payload, "draft")["id"])
        draft = service.state(owner)["bookings"][0]
        self.assertEqual(("가상 검색 후보", "AWAITING_APPROVAL", snapshot | {"preferredCuisines": []}),
                         (draft["restaurantName"], draft["status"], draft["snapshot"]))
        config = fake_config(allowed_numbers=("+821000000000", "+8220000000"),
                             restaurant_numbers={draft["restaurantId"]: "+8220000000"})
        client = SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=AsyncMock()),
                                 sip=SimpleNamespace(create_sip_participant=AsyncMock()))
        with self.assertRaisesRegex(DomainError, "승인된"):
            asyncio.run(dial_booking(service, config, owner, booking, 1, "unapproved", client))
        client.sip.create_sip_participant.assert_not_awaited()
        service.command(owner, "approve_booking", {"bookingId": booking, "version": 1}, identifier())
        asyncio.run(dial_booking(service, config, owner, booking, 2, "approved", client))
        self.assertEqual(config.test_mobile, client.sip.create_sip_participant.await_args.args[0].sip_call_to)

    def test_entered_mobile_reaches_sip_only_for_the_approved_owned_test(self):
        from .transport import dial_booking

        service = ReservationService(web_test_enabled=True)
        self.addCleanup(service.close)
        owner, _ = service.session()
        other, _ = service.session()
        search_id = service.remember_web_search(owner, {
            "validUntil": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
            "result": {"webRecommendations": [{
                "ordinal": 1, "name": "가상 검색 후보", "address": "가상 주소",
                "identitySourceUrl": "https://example.invalid/restaurant",
            }]},
        }, CONDITIONS)
        booking = service.command(owner, "create_web_test_booking", {
            "searchId": search_id, "candidateOrdinal": 1, "reservationName": "가상예약자",
            "date": DAY, "time": "19:00",
        }, identifier())["id"]
        config = fake_config(test_phone_input=True)
        client = SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=AsyncMock()),
                                 sip=SimpleNamespace(create_sip_participant=AsyncMock()))
        number = "010-0000-1234"

        def dial(dial_owner, version, phone, voice_config=config):
            return asyncio.run(dial_booking(service, voice_config, dial_owner, booking, version, "dial",
                                            client, test_phone_number=phone))

        with self.assertRaisesRegex(DomainError, "승인된"):
            dial(owner, 1, number)
        service.command(owner, "approve_booking", {"bookingId": booking, "version": 1}, identifier())
        for dial_owner, phone, voice_config, code in (
            (other, number, config, "NOT_FOUND"),
            (owner, None, config, "TEST_PHONE_REQUIRED"),
            (owner, "070-0000-0000", config, "INVALID_TEST_PHONE"),
            (owner, number, replace(config, test_phone_input=False), "TEST_PHONE_INPUT_DISABLED"),
        ):
            with self.subTest(code=code), self.assertRaises(DomainError) as error:
                dial(dial_owner, 2, phone, voice_config)
            self.assertEqual(code, error.exception.code)
        client.sip.create_sip_participant.assert_not_awaited()

        result = dial(owner, 2, number)
        canonical = "+821000001234"
        self.assertEqual(canonical, client.sip.create_sip_participant.await_args.args[0].sip_call_to)
        self.assertNotIn(canonical, client.agent_dispatch.create_dispatch.await_args.args[0].metadata)
        self.assertNotIn(canonical, json.dumps(service.state(owner)))
        self.assertEqual(result["id"], dial(owner, 2, canonical)["id"])
        with self.assertRaises(DomainError) as error:
            dial(owner, 2, "010-0000-5678")
        self.assertEqual("IDEMPOTENCY_CONFLICT", error.exception.code)
        client.sip.create_sip_participant.assert_awaited_once()

    def test_public_contact_cannot_replace_registered_sip_recipient(self):
        from .transport import dial_booking
        place = {"id": "test-cafe", "name": "가상 카페", "address": "가상 주소",
                 "phone": "02-000-0000", "sourceUrl": "https://example.invalid"}
        service = ReservationService(test_restaurants={place["id"]: place})
        self.addCleanup(service.close)
        owner, _ = service.session()

        def command(action, payload):
            return service.command(owner, action, payload, identifier())

        trip = command("create_trip", {"conditions": CONDITIONS})["id"]
        booking = command("create_booking", {"tripId": trip, "tripVersion": 1, "reservationName": "김예약",
                          "date": DAY, "time": "19:00", "restaurantId": place["id"],
                          "target": "+8220000000"})["id"]
        config = fake_config(allowed_numbers=("+821000000000", "+8220000000"),
                             restaurant_numbers={place["id"]: "+8220000000"})
        client = SimpleNamespace(agent_dispatch=SimpleNamespace(create_dispatch=AsyncMock()),
                                 sip=SimpleNamespace(create_sip_participant=AsyncMock()))
        with self.assertRaisesRegex(DomainError, "승인된"):
            asyncio.run(dial_booking(service, config, owner, booking, 1, "unapproved", client))
        client.sip.create_sip_participant.assert_not_awaited()
        command("approve_booking", {"bookingId": booking, "version": 1})
        with self.assertRaisesRegex(DomainError, "휴대폰 한 대"):
            asyncio.run(dial_booking(service, replace(config, allowed_numbers=("+8220000000",)),
                                     owner, booking, 2, "missing-mobile", client))
        result = asyncio.run(dial_booking(service, config, owner, booking, 2, "request", client))
        request = client.sip.create_sip_participant.await_args.args[0]
        self.assertEqual("+821000000000", request.sip_call_to)
        self.assertEqual(place, service.state(owner)["bookings"][0]["liveTest"])
        self.assertTrue(LiveWorkflow(service, config).context(result["id"])["testCall"])
        self.assertTrue(asyncio.run(dial_booking(service, config, owner, booking, 2, "request", client))["reused"])
        client.sip.create_sip_participant.assert_awaited_once()


if __name__ == "__main__":unittest.main()
