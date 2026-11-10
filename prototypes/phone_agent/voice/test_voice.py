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
        for method, args, operation in (
            ("save_change_request", ("19:30", "변경", "실제 발화"), "request_change"),
            ("save_callback_note", ("CANCELLATION", "취소 통보", "실제 발화"), "request_note")):
            with self.subTest(method=method):
                order = []
                async def prior(): order.append("prior")
                async def say(text, **kwargs): order.append(text)
                async def close(reason): order.append(reason)
                workflow = SimpleNamespace(**{operation: Mock(return_value={"saved": True})})
                agent = BookingVoiceAgent(workflow, "call", {}, close)
                context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=say))
                with self.assertRaises(StopResponse):
                    asyncio.run(getattr(agent, method)(context, *args))
                self.assertEqual("prior", order[0])
                self.assertIn("접수", order[1])
                self.assertNotIn("완료", order[1])
                self.assertEqual("callback_received", order[-1])

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
        for status in ("CONFIRMED", "DECLINED", "UNKNOWN"):
            with self.subTest(status=status):
                order = []
                workflow = SimpleNamespace(record_outcome=Mock(return_value={"saved":True, "status":status}))
                async def prior():order.append("prior")
                async def say(text, **kwargs):order.append(text)
                async def close(reason):order.append("closed")
                agent = BookingVoiceAgent(workflow, "call", {}, close)
                context = SimpleNamespace(wait_for_playout=prior, session=SimpleNamespace(say=say))
                with self.assertRaises(StopResponse):
                    asyncio.run(agent.save_reservation_result(context, True, DAY, "19:00", "김예약", 4, 0, [], "가상 근거"))
                self.assertEqual("prior", order[0]);self.assertEqual("closed", order[-1])
                if status == "UNKNOWN":self.assertIn("확인하지 못했습니다", order[1])
                if status == "CONFIRMED":self.assertIn("예약 확인 감사합니다", order[1])
                if status == "DECLINED":self.assertIn("예약이 어렵다", order[1])

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
