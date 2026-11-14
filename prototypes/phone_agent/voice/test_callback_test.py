import unittest
from unittest.mock import AsyncMock

from ..service import DomainError, ReservationService, timestamp
from . import callback_test
from .config import ConfigurationError, VoiceConfig
from .test_voice import fake_config
from .workflow import LiveWorkflow


class CallbackTestRegistrationTest(unittest.TestCase):
    def setUp(self):
        self.service = ReservationService()
        self.addCleanup(self.service.close)
        self.owner, _ = self.service.session()
        self.other, _ = self.service.session()
        self.config = fake_config(self_service_test=True, test_phone_input=True, daily_calls=0)
        self.update_inbound = AsyncMock()
        self.workflow = LiveWorkflow(self.service, self.config)
        self.phone = "+821000001234"

    def register(self, action="START", owner=None):
        return callback_test.configure(self.service, self.config, owner or self.owner,
                                       {"action": action, "phoneNumber": self.phone},
                                       update_inbound=self.update_inbound)

    def test_daily_limit_can_be_disabled_in_test_mode(self):
        with self.assertRaises(ConfigurationError):
            VoiceConfig.from_env({"PHONE_DAILY_CALLS": "0"})
        config = VoiceConfig.from_env({
            "PHONE_DAILY_CALLS": "0", "PHONE_CALLBACK_TEST_SELF_SERVICE": "true",
        })
        self.assertEqual(0, config.daily_calls)

    def test_self_service_call_duration_defaults_to_two_minutes(self):
        self.assertEqual(180, VoiceConfig.from_env({}).max_seconds)
        env = {"PHONE_CALLBACK_TEST_SELF_SERVICE": "true"}
        self.assertEqual(120, VoiceConfig.from_env(env).max_seconds)
        self.assertEqual(60, VoiceConfig.from_env({**env, "PHONE_MAX_SECONDS": "60"}).max_seconds)
        self.assertEqual(120, VoiceConfig.from_env({**env, "PHONE_MAX_SECONDS": "180"}).max_seconds)

    def test_registration_is_scoped_to_browser_and_restores_trunk_on_stop(self):
        self.assertEqual("1234", self.register()["phoneLast4"])
        self.update_inbound.assert_awaited_once_with(self.config, self.phone)
        self.assertEqual({"active": False}, callback_test.status(self.service, self.other))
        with self.assertRaises(DomainError) as error:
            self.register(owner=self.other)
        self.assertEqual("CALLBACK_TEST_ACTIVE", error.exception.code)
        self.assertEqual({"active": False}, self.register("STOP"))
        self.update_inbound.assert_awaited_with(self.config, None)

    def test_inbound_matches_latest_confirmed_outbound_in_registered_browser(self):
        self.register()
        for owner, booking_id in ((self.owner, "expected"), (self.other, "unrelated")):
            self.service._put(owner, "booking", {
                "id": booking_id, "version": 3, "restaurantId": "test-restaurant",
                "mode": "LIVE", "status": "CONFIRMED", "liveTest": {"name": "가상 식당"},
            })
            self.service._put(owner, "live_call", {
                "id": "call-" + booking_id, "bookingId": booking_id, "status": "ENDED",
                "direction": "outbound", "purpose": "BOOKING", "outcome": "CONFIRMED",
                "target": self.phone, "testPhoneEntered": True, "createdAt": timestamp(),
            })
        with self.assertRaises(DomainError) as error:
            self.workflow.begin_inbound("+821000001235", "ST_in", "room", self.config.telkit_number)
        self.assertEqual("CALLER_NOT_ALLOWED", error.exception.code)
        call = self.workflow.begin_inbound(self.phone, "ST_in", "room", self.config.telkit_number)
        self.assertEqual("expected", call["bookingId"])
        self.assertEqual(call, self.service._get(self.owner, "live_call", call["id"]))
        with self.assertRaises(DomainError) as error:
            self.workflow.begin_inbound(self.phone, "ST_in", "room", self.config.telkit_number)
        self.assertEqual("CALL_ACTIVE", error.exception.code)

    def test_provider_failure_does_not_activate_registration(self):
        self.update_inbound.side_effect = DomainError("CALLBACK_TEST_UNAVAILABLE", "test", 503)
        with self.assertRaises(DomainError):
            self.register()
        self.assertEqual({"active": False}, callback_test.status(self.service, self.owner))
