"""Integration boundaries for pairing, server-owned candidates and no-network dialing."""
import unittest
from datetime import timedelta
from unittest.mock import patch
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
