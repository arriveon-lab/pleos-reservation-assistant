"""Integration boundaries for pairing, server-owned candidates and no-network dialing."""
import unittest
from dataclasses import replace
from datetime import timedelta
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from . import paired_phone as paired
from .service import DomainError, ReservationService
from .voice.test_voice import fake_config
from .voice.workflow import LiveWorkflow


class PairedPhoneTest(unittest.TestCase):
    def setUp(self):
        self.service = ReservationService(web_test_enabled=True)
        self.addCleanup(self.service.close)
        self.owner = "pleos-paired:test-owner"
        self.other = "pleos-paired:other"
        self.config = fake_config(self_service_test=True, test_phone_input=True)
        self.phone = "01000001234"  # Synthetic only; no transport connection.
        self.sync_inbound = self.enterContext(patch(
            "prototypes.phone_agent.voice.callback_test.sync_inbound_numbers", new_callable=AsyncMock))
        self.start = {"viewId": str(uuid4()), "generation": 1, "destination": {"name": "가상"},
                      "party": {"adults": 2, "children": 1}, "maxWaitMinutes": 15,
                      "requiredFeatures": ["parking", "stepFreeAccess"]}

    def payload(self, job):
        return {"taskId": job, "candidateOrdinal": 1, "date": (paired.now() + timedelta(days=2)).date().isoformat(),
                "time": "18:30", "reservationName": "가상시험"}

    def live_config(self):
        return self.config

    def bind(self, mode="MOCK"):
        code = paired.issue(self.service, self.owner)["pairingCode"]
        request = {"pairingCode": code, "testPhoneNumber": self.phone, "mode": mode}
        status = paired.claim(self.service, request, self.live_config)
        return request, status

    def test_one_time_code_does_not_reveal_number_or_cross_owners(self):
        request, status = self.bind()
        self.assertEqual("1234", status["phoneLast4"])
        self.assertNotIn("phone", status)
        self.assertFalse(paired.status(self.service, self.other)["active"])
        with self.assertRaisesRegex(DomainError, "만료"):
            paired.claim(self.service, request, self.live_config)
        paired.disconnect(self.service, self.owner)
        self.assertFalse(paired.status(self.service, self.owner)["active"])

    def test_mock_uses_server_selected_candidate_and_survives_repeat(self):
        self.bind()
        key = str(uuid4())
        with patch.object(paired, "upstream", side_effect=AssertionError("No external traffic in MOCK")):
            _, search = paired.start(self.service, 1, self.owner, self.start, None)
            data = self.payload(search["taskId"])
            with self.assertRaises(DomainError):
                paired.dial(self.service, 1, self.other, data, key, None, self.live_config)
            with self.assertRaises(DomainError):
                paired.dial(self.service, 1, self.owner, {**data, "phoneNumber": self.phone},
                            key, None, self.live_config)
            result = paired.dial(self.service, 1, self.owner, data, key, None, self.live_config)
            self.assertEqual("CONFIRMED", result["status"])
            self.assertEqual("가상 시험 식당 1", result["restaurantName"])
            self.assertEqual("CONFIRMED", result["requirements"]["계단 없이 입장"])
            self.assertEqual("CONFIRMED", result["requirements"]["아동 동반 이용"])
            self.assertEqual(result, paired.dial(self.service, 1, self.owner, data, key, None, self.live_config))
            self.assertEqual(result, paired.status(self.service, self.owner)["latest"])
            self.assertFalse(self.service.state(self.owner)["externalCalls"])
            with self.assertRaises(DomainError) as duplicate:
                paired.dial(self.service, 1, self.owner, data, str(uuid4()), None, self.live_config)
            self.assertEqual("DUPLICATE_CANDIDATE", duplicate.exception.code)
            with self.assertRaises(DomainError):
                paired.attempt_result(self.service, self.other, key)

    def test_live_reserve_uses_only_paired_recipient_and_uncertain_is_not_retried(self):
        self.bind("LIVE")
        job, key = str(uuid4()), str(uuid4())
        expiry = (paired.now() + timedelta(minutes=4)).isoformat()
        candidate = {"ordinal": 1, "name": "가상 시험 매장", "address": "가상 주소",
                     "identitySourceUrl": "https://example.com/", "venueCategory": "RESTAURANT",
                     "phoneNumber": "0212345678"}
        provider = (200, {"id": job, "state": "RESULTS", "validUntil": expiry,
                          "result": {"webState": "RESULTS", "webRecommendations": [candidate]}})
        workflow = LiveWorkflow(self.service, self.config)

        async def reserve_only(service, config, owner, booking, version, request_key, *, test_phone_number):
            self.assertEqual("+821000001234", test_phone_number)
            with self.assertRaises(DomainError) as rejected:
                workflow.reserve(owner, booking, version, request_key, test_phone_number="01000005678")
            self.assertEqual("PAIRING_REQUIRED", rejected.exception.code)
            call, created = workflow.reserve(owner, booking, version, request_key,
                                             test_phone_number=test_phone_number)
            self.assertTrue(created)
            self.assertEqual(test_phone_number, call["target"])
            workflow.mark_dispatching(call["id"])
            workflow.finish(call["id"], "dispatch_uncertain")

        with patch.object(paired, "upstream", side_effect=[(200, {"taskId": job}), provider]), \
                patch("prototypes.phone_agent.voice.transport.dial_booking", side_effect=reserve_only) as dial:
            paired.start(self.service, 1, self.owner, self.start, "test-bearer")
            data = self.payload(job)
            result = paired.dial(self.service, 1, self.owner, data, key, "test-bearer", self.live_config)
            self.assertEqual("UNKNOWN", result["status"])
            self.assertEqual(result, paired.dial(self.service, 1, self.owner, data, key, "test-bearer", self.live_config))
            dial.assert_awaited_once()

    def confirmed_attempt(self, owner, phone):
        code = paired.issue(self.service, owner)["pairingCode"]
        paired.claim(self.service, {"pairingCode": code, "testPhoneNumber": phone, "mode": "LIVE"},
                     self.live_config)
        booking_id, attempt_id, call_id, trip_id = (str(uuid4()) for _ in range(4))
        day = self.payload(str(uuid4()))["date"]
        self.service._put(owner, "trip", {"id": trip_id, "version": 1})
        self.service._put(owner, "booking", {
            "id": booking_id, "mode": "LIVE", "status": "CONFIRMED", "version": 1,
            "pleosPairedTest": paired._active(self.service, owner)["generation"],
            "liveTest": True, "webTest": True, "liveCallId": call_id,
            "tripId": trip_id, "tripVersion": 1, "restaurantId": "test-restaurant",
            "restaurantName": "가상 식당", "reservationName": "가상시험", "date": day,
            "snapshot": {"partySize": 3}, "approvedSnapshot": {"partySize": 3, "petAccompanied": "NO",
                                                                "stepFree": False, "highChairCount": 0,
                                                                "dietaryRequirements": ""},
            "requestedTime": "18:30", "confirmedTime": "18:30", "notice": "",
        })
        self.service._put(owner, "pleos_phone_attempt", {"id": attempt_id, "mode": "LIVE", "bookingId": booking_id})
        self.service._put(owner, "live_call", {
            "id": call_id, "direction": "outbound", "purpose": "BOOKING", "status": "ENDED",
            "outcome": "CONFIRMED", "bookingId": booking_id,
            "target": paired.normalize_number(phone), "createdAt": paired.now().isoformat(),
        })
        return attempt_id, day

    def callback(self, day):
        config = replace(self.config, allowed_numbers=(paired.normalize_number(self.phone),))
        workflow = LiveWorkflow(self.service, config)
        with self.assertRaisesRegex(DomainError, "허용"):
            workflow.begin_inbound("01000009999", config.inbound_trunk_id, "wrong", config.telkit_number)
        call = workflow.begin_inbound(self.phone, config.inbound_trunk_id, "test", config.telkit_number)
        call_id = call["id"]
        context = workflow.context(call_id)
        self.assertTrue(context["found"])
        self.assertTrue(context["pairedCallback"])
        self.assertEqual("18:30", context["time"])
        proposal = "같은 날 19시로 변경할 수 있을까요?"
        workflow.transcript(call_id, "restaurant", proposal)
        with self.assertRaisesRegex(DomainError, "추가 비용"):
            workflow.request_change(call_id, "19:00", "가상 지연", proposal, 1000, day)
        for reply in ("네, 맞아요.", "그렇습니다.", "네."):
            workflow.transcript(call_id, "restaurant", reply)
        with self.assertRaisesRegex(DomainError, "날짜"):
            workflow.request_change(call_id, "19:00", "가상 지연", proposal, 0, "2099-01-01")
        workflow.request_change(call_id, "19:00", "가상 지연", proposal, proposed_date=day)
        return workflow, call_id

    def test_callback_prefers_unique_reservation_from_latest_test_pairing(self):
        older_id, _ = self.confirmed_attempt(self.other, self.phone)
        current_id, _ = self.confirmed_attempt(self.owner, self.phone)
        old_booking = self.service._get(self.other, "pleos_phone_attempt", older_id)["bookingId"]
        new_booking = self.service._get(self.owner, "pleos_phone_attempt", current_id)["bookingId"]
        config = replace(self.config, allowed_numbers=(paired.normalize_number(self.phone),))
        workflow = LiveWorkflow(self.service, config)
        call = workflow.begin_inbound(self.phone, config.inbound_trunk_id, "current", config.telkit_number)
        self.assertTrue(call["ownerBound"])
        self.assertEqual(new_booking, call["bookingId"])
        self.assertNotEqual(old_booking, call["bookingId"])
        self.assertEqual(old_booking, self.service._get(self.other, "booking", old_booking)["id"])
        workflow.finish(call["id"])

        caller = config.allowed_numbers[0]
        matches = paired.callback_bookings(self.service, caller)
        current = self.service._get(self.owner, "booking", new_booking)
        self.assertIsNone(paired.current_callback_booking(
            self.service, caller, matches + [(self.owner, {**current, "id": str(uuid4())})]))
        paired.disconnect(self.service, self.owner)
        paired.disconnect(self.service, self.other)
        call = workflow.begin_inbound(self.phone, config.inbound_trunk_id, "ambiguous", config.telkit_number)
        self.assertFalse(call["ownerBound"])
        self.assertIsNone(call["bookingId"])

    def test_registered_team_numbers_can_return_calls_without_static_allowlist(self):
        first, _ = self.confirmed_attempt(self.owner, self.phone)
        other_phone = "01000005678"
        second, _ = self.confirmed_attempt(self.other, other_phone)
        caller, other_caller = map(paired.normalize_number, (self.phone, other_phone))
        self.sync_inbound.assert_awaited_with(self.config, {caller, other_caller})
        self.assertNotIn(caller, self.config.allowed_numbers)
        self.assertNotIn(other_caller, self.config.allowed_numbers)
        workflow = LiveWorkflow(self.service, self.config)
        for owner, attempt, number in ((self.owner, first, caller), (self.other, second, other_caller)):
            booking = self.service._get(owner, "pleos_phone_attempt", attempt)["bookingId"]
            call = workflow.begin_inbound(number, self.config.inbound_trunk_id, "team", self.config.telkit_number)
            self.assertEqual(booking, call["bookingId"])
            workflow.finish(call["id"])
        binding = paired._active(self.service, self.owner)
        binding["expiresAt"] = (paired.now() - timedelta(seconds=1)).isoformat()
        self.service._put(self.owner, "pleos_phone_binding", binding)
        with self.assertRaises(DomainError) as denied:
            workflow.begin_inbound(caller, self.config.inbound_trunk_id, "expired", self.config.telkit_number)
        self.assertEqual("CALLER_NOT_ALLOWED", denied.exception.code)
        self.bind("LIVE")
        with self.assertRaises(DomainError) as unmatched:
            workflow.begin_inbound(caller, self.config.inbound_trunk_id, "new", self.config.telkit_number)
        self.assertEqual("CALLBACK_TEST_UNMATCHED", unmatched.exception.code)

    def test_inbound_sync_failure_leaves_pairing_code_available_for_retry(self):
        code = paired.issue(self.service, self.owner)["pairingCode"]
        body = {"pairingCode": code, "testPhoneNumber": self.phone, "mode": "LIVE"}
        self.sync_inbound.side_effect = DomainError("CALLBACK_TEST_UNAVAILABLE", "test", 503)
        with self.assertRaises(DomainError):
            paired.claim(self.service, body, self.live_config)
        self.assertFalse(paired.status(self.service, self.owner)["active"])
        self.sync_inbound.side_effect = None
        self.assertTrue(paired.claim(self.service, body, self.live_config)["active"])

    def test_driver_delay_dials_same_booking_and_requires_call_evidence(self):
        attempt_id, day = self.confirmed_attempt(self.owner, self.phone)
        attempt = self.service._get(self.owner, "pleos_phone_attempt", attempt_id)
        booking_id = attempt["bookingId"]
        workflow = LiveWorkflow(self.service, self.config)
        key = str(uuid4())
        body = {"version": 1, "proposedTime": "19:00"}

        async def reserve_only(service, config, owner, booking, version, request_key, **kwargs):
            call, created = workflow.reserve(owner, booking, version, request_key, **kwargs)
            self.assertEqual("CHANGE_CONFIRMATION", call["purpose"])
            self.assertEqual("+821000001234", call["target"])
            if created:
                workflow.mark_dispatching(call["id"])

        with patch("prototypes.phone_agent.voice.transport.dial_booking", side_effect=reserve_only) as dial:
            with self.assertRaises(DomainError):
                paired.request_delay_change(self.service, self.other, attempt_id, body, key, self.live_config)
            with self.assertRaises(DomainError):
                paired.request_delay_change(self.service, self.owner, attempt_id,
                                            {**body, "proposedTime": "18:00"}, key, self.live_config)
            running = paired.request_delay_change(self.service, self.owner, attempt_id, body, key, self.live_config)
            self.assertEqual("18:30", running["latest"]["confirmedTime"])
            self.assertEqual("CALLING", running["latest"]["delayChange"]["status"])
            self.assertEqual(running, paired.request_delay_change(
                self.service, self.owner, attempt_id, body, key, self.live_config))
            self.assertEqual(3, dial.await_count)  # Earlier-time request is rejected in reserve.
        self.assertEqual(1, paired._active(self.service, self.owner)["calls"])
        booking = self.service._get(self.owner, "booking", booking_id)
        call_id = booking["liveCallId"]
        with self.assertRaises(DomainError):
            workflow.confirm_driver_delay(call_id, "네.")
        question = workflow.context(call_id)["driverDelayQuestion"]
        self.assertIn("저녁 여섯 시 삼십 분", question)
        self.assertIn("저녁 일곱 시", question)
        workflow.transcript(call_id, "agent", question)
        workflow.transcript(call_id, "restaurant", "네, 변경 가능합니다.")
        with self.assertRaises(DomainError):
            workflow.confirm_driver_delay(call_id, "네, 변경 가능합니다.")
        followup = workflow.context(call_id)["driverDelayFollowup"]
        self.assertEqual("저녁 일곱 시로 변경을 확정해 주셨나요?", followup)
        workflow.transcript(call_id, "agent", followup)
        workflow.transcript(call_id, "restaurant", "네, 19:30으로 변경 확정됐습니다.")
        with self.assertRaises(DomainError):
            workflow.confirm_driver_delay(call_id, "네, 19:30으로 변경 확정됐습니다.")
        workflow.transcript(call_id, "agent", followup)
        workflow.transcript(call_id, "restaurant", "네, 저녁 7시로 변경 확정됐습니다.")
        self.assertEqual("APPLIED", workflow.confirm_driver_delay(call_id, "네, 저녁 7시로 변경 확정됐습니다."))
        workflow.finish(call_id)
        final = paired.status(self.service, self.owner)
        self.assertEqual("19:00", final["latest"]["confirmedTime"])
        self.assertEqual("APPLIED", final["latest"]["delayChange"]["status"])
        self.assertIsNone(final["callback"])
        self.assertEqual(booking_id, paired.callback_bookings(
            self.service, "+821000001234")[0][1]["id"])

    def test_unconfirmed_driver_delay_only_rechecks_the_same_time(self):
        attempt_id, _ = self.confirmed_attempt(self.owner, self.phone)
        attempt = self.service._get(self.owner, "pleos_phone_attempt", attempt_id)
        booking_id = attempt["bookingId"]
        workflow = LiveWorkflow(self.service, self.config)
        kwargs = {"test_phone_number": self.phone, "delay_time": "19:30"}
        first_key = str(uuid4())
        call, _ = workflow.reserve(self.owner, booking_id, 1, first_key, **kwargs)
        workflow.finish(call["id"], "delay_change_unconfirmed")
        latest = paired.status(self.service, self.owner)["latest"]
        self.assertTrue(latest["delayChange"]["canRetry"])
        self.assertEqual("18:30", latest["confirmedTime"])
        with self.assertRaises(DomainError) as raised:
            workflow.reserve(self.owner, booking_id, latest["version"], str(uuid4()),
                             **{**kwargs, "delay_time": "19:40"})
        self.assertEqual("CHANGE_PENDING", raised.exception.code)
        original, created = workflow.reserve(self.owner, booking_id, 1, first_key, **kwargs)
        self.assertFalse(created)
        self.assertEqual("ENDED", original["status"])
        retry, created = workflow.reserve(self.owner, booking_id, latest["version"], str(uuid4()), **kwargs)
        self.assertTrue(created)
        self.assertNotEqual(call["id"], retry["id"])
        self.assertIn("아까 문의드린 예약 시간 변경", retry["delayQuestion"])
        workflow.finish(call["id"], "late_cleanup")
        running = paired.status(self.service, self.owner)["latest"]
        self.assertEqual("CALLING", running["delayChange"]["status"])
        self.assertEqual("18:30", running["confirmedTime"])

    def test_voice_day_matches_real_booking_date(self):
        from datetime import date
        from .voice.workflow import spoken_booking_day

        today = date(2026, 11, 14)
        self.assertEqual("오늘", spoken_booking_day("2026-11-14", today=today))
        self.assertEqual("내일", spoken_booking_day("2026-11-15", today=today))
        self.assertEqual("11월 16일", spoken_booking_day("2026-11-16", today=today))

    def test_callback_prompt_reads_korean_twenty_four_hour_time(self):
        from .voice.workflow import callback_confirmation_question

        self.assertEqual("예약자가 동의했습니다. 십구 시 사십 분으로 변경 확정해 주시겠어요?",
                         callback_confirmation_question({"proposedTime": "19:40"}))
        self.assertEqual("예약자가 동의했습니다. 추가 비용 없이 영 시로 변경 확정해 주시겠어요?",
                         callback_confirmation_question({"proposedTime": "00:00", "additionalCostKrw": 0}))

    def test_callback_change_is_owned_idempotent_and_requires_restaurant_confirmation(self):
        attempt_id, day = self.confirmed_attempt(self.owner, self.phone)
        self.confirmed_attempt(self.other, "01000005678")
        paired.disconnect(self.service, self.owner)
        workflow, call_id = self.callback(day)
        with patch.object(paired, "upstream", return_value=(200, {"subject": "other"})):
            response = paired.route(self.service, 1, "GET", "/api/v1/paired-phone",
                                    {"Authorization": "Bearer " + "o" * 32}, None, self.live_config)
            self.assertIsNone(response[1]["callback"])

        path = f"/api/v1/paired-phone/calls/{attempt_id}/change"
        headers = {"Authorization": "Bearer " + "o" * 32, "Idempotency-Key": str(uuid4())}
        pending = paired.status(self.service, self.owner)["callback"]["change"]
        self.assertTrue(pending["canDecide"])
        self.assertIsNone(pending["additionalCostKrw"])
        body = {"changeId": pending["id"], "version": pending["version"], "approve": True}
        with patch.object(paired, "upstream", return_value=(200, {"subject": "other"})):
            with self.assertRaises(DomainError) as denied:
                paired.route(self.service, 1, "POST", path, headers, body, self.live_config)
            self.assertEqual(404, denied.exception.status)

        with patch.object(paired, "upstream", return_value=(200, {"subject": "test-owner"})):
            result = paired.route(self.service, 1, "POST", path, headers, body, self.live_config)[1]["callback"]
            self.assertEqual("18:30", result["confirmedTime"])
            self.assertEqual("RECONFIRMING", result["change"]["status"])
            self.assertEqual(result, paired.route(
                self.service, 1, "POST", path, headers, body, self.live_config)[1]["callback"])
            with self.assertRaises(DomainError) as different:
                paired.route(self.service, 1, "POST", path, headers, {**body, "approve": False}, self.live_config)
            self.assertEqual("IDEMPOTENCY_CONFLICT", different.exception.code)

        prompt = workflow.callback_confirmation_prompt(call_id)
        self.assertEqual("예약자가 동의했습니다. 십구 시로 변경 확정해 주시겠어요?", prompt)
        self.assertNotIn("추가 비용", prompt)
        workflow.transcript(call_id, "agent", prompt)
        workflow.transcript(call_id, "restaurant", "네, 19시 반으로 변경했습니다.")
        with self.assertRaises(DomainError):
            workflow.confirm_callback_change(call_id, "네, 19시 반으로 변경했습니다.")
        self.assertEqual("18:30", paired.attempt_result(self.service, self.owner, attempt_id)["confirmedTime"])
        workflow.transcript(call_id, "agent", prompt)
        workflow.transcript(call_id, "restaurant", "네.")
        workflow.confirm_callback_change(call_id, "네.")
        workflow.finish(call_id)
        result = paired.attempt_result(self.service, self.owner, attempt_id)
        self.assertEqual(("19:00", "APPLIED"), (result["confirmedTime"], result["change"]["status"]))

    def test_rejected_expired_and_interrupted_callbacks_preserve_original_time(self):
        attempt_id, day = self.confirmed_attempt(self.owner, self.phone)
        for outcome in ("REJECTED", "EXPIRED", "UNKNOWN"):
            with self.subTest(outcome=outcome):
                workflow, call_id = self.callback(day)
                pending = paired.status(self.service, self.owner)["callback"]["change"]
                if outcome != "EXPIRED":
                    paired.decide_change(self.service, self.owner, attempt_id, {
                        "changeId": pending["id"], "version": pending["version"],
                        "approve": outcome == "UNKNOWN",
                    }, str(uuid4()))
                workflow.finish(call_id)
                result = paired.attempt_result(self.service, self.owner, attempt_id)
                self.assertEqual(("18:30", outcome), (result["confirmedTime"], result["change"]["status"]))
                if outcome == "EXPIRED":
                    with self.assertRaises(DomainError):
                        paired.decide_change(self.service, self.owner, attempt_id, {
                            "changeId": pending["id"], "version": pending["version"], "approve": True,
                        }, str(uuid4()))

    def test_ambiguous_caller_details_do_not_link_a_reservation(self):
        _, day = self.confirmed_attempt(self.owner, self.phone)
        self.confirmed_attempt(self.other, self.phone)
        paired.disconnect(self.service, self.owner)
        paired.disconnect(self.service, self.other)
        config = replace(self.config, allowed_numbers=(paired.normalize_number(self.phone),))
        workflow = LiveWorkflow(self.service, config)
        call = workflow.begin_inbound(self.phone, config.inbound_trunk_id, "ambiguous", config.telkit_number)
        self.assertFalse(workflow.context(call["id"])["found"])
        self.assertNotIn("reservationName", workflow.context(call["id"]))
        self.assertFalse(workflow.identify(call["id"], "가상시험", day, "18:30")["found"])
        self.assertIsNone(workflow._find(call["id"])[1]["bookingId"])
