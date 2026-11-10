"""Behavior tests for offline reservations, callbacks, ownership and transport boundaries."""
import concurrent.futures
import http.client
import json
import tempfile
import threading
import unittest
from datetime import date, timedelta
from pathlib import Path

from .server import make_server
from .service import DomainError, ReservationService, identifier


CONDITIONS = {"partySize": 4, "highChairCount": 1, "petAccompanied": "NO",
              "stepFree": True, "dietaryRequirements": ""}
DAY = (date.today() + timedelta(days=7)).isoformat()


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.service = ReservationService()
        self.owner, self.token = self.service.session()

    def tearDown(self):
        self.service.close()

    def command(self, action, payload=None, key=None, owner=None):
        return self.service.command(owner or self.owner, action, payload or {}, key or identifier())

    def booking(self, scenario=None, name="김예약", values=None):
        trip_id = self.command("create_trip", {"conditions": values or CONDITIONS})["id"]
        booking_id = self.command("create_booking", {"tripId": trip_id, "tripVersion": 1,
                                  "reservationName": name, "date": DAY, "time": "19:00"})["id"]
        if scenario:
            self.command("approve_booking", self.payload(booking_id))
            self.command("run_call", {**self.payload(booking_id), "scenario": scenario})
        return self.get(booking_id)

    def get(self, booking_id):
        return next(b for b in self.service.state(self.owner)["bookings"] if b["id"] == booking_id)

    def payload(self, booking_id):
        booking = self.get(booking_id)
        return {"bookingId": booking_id, "version": booking["version"]}

    def callback(self, booking, **extra):
        return self.command("receive_callback", {"restaurantId": booking["restaurantId"],
                            "reservationName": booking["reservationName"], "date": DAY,
                            "currentTime": booking["confirmedTime"], "proposedTime": "19:30", **extra})

    def assert_error(self, code, fn):
        with self.assertRaises(DomainError) as error:
            fn()
        self.assertEqual(code, error.exception.code)

    def test_storage_opt_out_still_allows_booking(self):
        booking = self.booking("ACCEPT")
        self.assertEqual("CONFIRMED", booking["status"])
        self.assertIsNone(self.service.state(self.owner)["profile"])
        self.assertEqual(0, self.service.state(self.owner)["externalCalls"])

    def test_profile_storage_requires_choice(self):
        self.assert_error("STORAGE_CHOICE_REQUIRED", lambda: self.command("save_profile", {
            "reservationName": "김예약", "conditions": CONDITIONS, "remember": False}))

    def test_health_text_not_copied_to_reusable_profile(self):
        self.command("save_profile", {"reservationName": "김예약", "remember": True,
                     "conditions": {**CONDITIONS, "dietaryRequirements": "가상 식사 조건"},
                     "learningConsent": True})
        profile = self.service.state(self.owner)["profile"]
        self.assertEqual("", profile["template"]["dietaryRequirements"])
        self.assertFalse(profile["learningConsent"])
        self.assertEqual([], self.service.state(self.owner)["trips"])

    def test_profile_update_does_not_modify_reservation(self):
        booking = self.booking("ACCEPT")
        self.command("save_profile", {"reservationName": "가상다른이름", "remember": True,
                     "conditions": {**CONDITIONS, "partySize": 8}})
        self.assertEqual(4, self.get(booking["id"])["approvedSnapshot"]["partySize"])
        self.assertEqual("김예약", self.get(booking["id"])["reservationName"])

    def test_profile_delete_preserves_booking_snapshot(self):
        booking = self.booking("ACCEPT")
        self.command("save_profile", {"reservationName": "김예약", "remember": True, "conditions": CONDITIONS})
        self.command("delete_profile")
        self.assertIsNone(self.service.state(self.owner)["profile"])
        self.assertEqual(CONDITIONS, self.get(booking["id"])["approvedSnapshot"])

    def test_all_data_delete_erases_history_and_pending_work(self):
        booking = self.booking("ACCEPT")
        self.callback(booking)
        self.command("delete_all", {"confirm": True})
        state = self.service.state(self.owner)
        for kind in ("trips", "bookings", "events", "callbacks"):
            self.assertEqual([], state[kind])
        self.assert_error("NOT_FOUND", lambda: self.command("decide_change", {
            "bookingId": booking["id"], "version": 3, "approve": True}))

    def test_full_delete_invalidates_old_session(self):
        self.command("delete_all", {"confirm": True})
        new_owner, _ = self.service.session(self.token)
        self.assertNotEqual(self.owner, new_owner)

    def test_condition_update_before_approval_rejected(self):
        booking = self.booking()
        self.command("update_trip", {"tripId": booking["tripId"], "version": 1,
                     "conditions": {**CONDITIONS, "partySize": 5}})
        self.assert_error("VERSION_CONFLICT", lambda: self.command("approve_booking", self.payload(booking["id"])))

    def test_condition_update_after_approval_blocks_dispatch(self):
        booking = self.booking()
        self.command("approve_booking", self.payload(booking["id"]))
        self.command("update_trip", {"tripId": booking["tripId"], "version": 1,
                     "conditions": {**CONDITIONS, "partySize": 5}})
        self.assert_error("VERSION_CONFLICT", lambda: self.command("run_call", {
            **self.payload(booking["id"]), "scenario": "ACCEPT"}))
        self.assertEqual(0, self.get(booking["id"])["attempts"])

    def test_unknown_pet_condition_prevents_approval(self):
        booking = self.booking(values={**CONDITIONS, "petAccompanied": "UNKNOWN"})
        self.assert_error("CONDITIONS_UNCONFIRMED", lambda: self.command("approve_booking", self.payload(booking["id"])))

    def test_calls_require_explicit_approval(self):
        booking = self.booking()
        self.assert_error("INVALID_STATE", lambda: self.command("run_call", {
            **self.payload(booking["id"]), "scenario": "ACCEPT"}))

    def test_accept_records_exact_time_and_required_conditions(self):
        booking = self.booking("ACCEPT")
        self.assertEqual("19:00", booking["confirmedTime"])
        self.assertTrue(booking["requirementChecks"])
        self.assertEqual({"CONFIRMED"}, set(booking["requirementChecks"].values()))

    def test_decline_does_not_confirm(self):
        booking = self.booking("DECLINE")
        self.assertEqual("DECLINED", booking["status"])
        self.assertIsNone(booking["confirmedTime"])

    def test_ambiguous_disconnect_cost_time_and_ars_remain_unknown(self):
        for scenario in ("AMBIGUOUS", "DISCONNECT", "COST_REQUIRED", "TIME_MISMATCH", "ARS"):
            with self.subTest(scenario=scenario):
                booking = self.booking(scenario)
                self.assertEqual("UNKNOWN", booking["status"])
                self.assertIsNone(booking["confirmedTime"])

    def test_unknown_or_unavailable_requirement_never_confirms(self):
        for scenario in ("REQUIREMENT_UNKNOWN", "REQUIREMENT_UNAVAILABLE"):
            booking = self.booking(scenario)
            self.assertNotEqual("CONFIRMED", booking["status"])

    def test_unknown_not_automatically_redialed(self):
        booking = self.booking("DISCONNECT")
        self.assert_error("INVALID_STATE", lambda: self.command("run_call", {
            **self.payload(booking["id"]), "scenario": "ACCEPT"}))
        self.assertEqual(1, self.get(booking["id"])["attempts"])

    def test_unknown_resolution_requires_full_condition_confirmation(self):
        booking = self.booking("AMBIGUOUS")
        self.assert_error("CONDITIONS_UNCONFIRMED", lambda: self.command("verify_booking", {
            **self.payload(booking["id"]), "result": "EXISTS"}))
        self.command("verify_booking", {**self.payload(booking["id"]), "result": "EXISTS",
                     "allConditionsConfirmed": True})
        self.assertEqual("CONFIRMED", self.get(booking["id"])["status"])
        self.assertEqual(1, self.get(booking["id"])["attempts"])
        self.assertEqual({"CONFIRMED"}, set(self.get(booking["id"])["requirementChecks"].values()))

    def test_duplicate_dispatch_applies_once(self):
        booking = self.booking()
        self.command("approve_booking", self.payload(booking["id"]))
        payload = {**self.payload(booking["id"]), "scenario": "ACCEPT"}
        first = self.command("run_call", payload, key="same-dispatch")
        self.assertEqual(first, self.command("run_call", payload, key="same-dispatch"))
        self.assertEqual(1, self.get(booking["id"])["attempts"])

    def test_reusing_key_with_changed_payload_conflicts(self):
        self.command("create_trip", {"conditions": CONDITIONS}, key="same-key")
        self.assert_error("IDEMPOTENCY_CONFLICT", lambda: self.command("create_trip", {
            "conditions": {**CONDITIONS, "partySize": 5}}, key="same-key"))

    def test_concurrent_same_request_creates_one_trip(self):
        def submit(_):
            return self.command("create_trip", {"conditions": CONDITIONS}, key="parallel-key")
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(submit, range(8)))
        self.assertEqual(1, len({r["id"] for r in results}))
        self.assertEqual(1, len(self.service.state(self.owner)["trips"]))

    def test_second_active_booking_for_same_trip_rejected(self):
        booking = self.booking("ACCEPT")
        self.assert_error("ACTIVE_BOOKING_EXISTS", lambda: self.command("create_booking", {
            "tripId": booking["tripId"], "tripVersion": 1, "reservationName": "김예약",
            "date": DAY, "time": "19:00"}))

    def test_other_owner_cannot_read_or_change_booking(self):
        booking = self.booking("ACCEPT")
        other, _ = self.service.session()
        self.assertEqual([], self.service.state(other)["bookings"])
        self.assert_error("NOT_FOUND", lambda: self.command("approve_booking", self.payload(booking["id"]), owner=other))

    def test_unmatched_callback_changes_no_booking(self):
        booking = self.booking("ACCEPT")
        response = self.callback(booking, reservationName="모르는이름")
        self.assertFalse(response["matched"])
        self.assertIsNone(self.get(booking["id"])["change"])

    def test_ambiguous_multiple_matches_not_selected(self):
        first = self.booking("ACCEPT")
        self.booking("ACCEPT")
        self.assertFalse(self.callback(first)["matched"])
        self.assertTrue(all(b["change"] is None for b in self.service.state(self.owner)["bookings"]))

    def test_callback_facts_select_correct_booking(self):
        first = self.booking("ACCEPT", name="가상하나")
        second = self.booking("ACCEPT", name="가상둘")
        self.assertTrue(self.callback(second)["matched"])
        self.assertIsNone(self.get(first["id"])["change"])
        self.assertEqual("AWAITING_USER", self.get(second["id"])["change"]["status"])

    def test_user_approval_alone_does_not_change_time(self):
        booking = self.booking("ACCEPT")
        self.callback(booking)
        self.command("decide_change", {**self.payload(booking["id"]), "approve": True})
        value = self.get(booking["id"])
        self.assertEqual("19:00", value["confirmedTime"])
        self.assertEqual("RECONFIRMING", value["change"]["status"])

    def test_only_restaurant_reconfirmation_applies_change(self):
        booking = self.booking("ACCEPT")
        self.callback(booking)
        self.assert_error("INVALID_STATE", lambda: self.command("reconfirm_change", {
            **self.payload(booking["id"]), "result": "ACCEPT"}))
        self.command("decide_change", {**self.payload(booking["id"]), "approve": True})
        self.command("reconfirm_change", {**self.payload(booking["id"]), "result": "ACCEPT"})
        value = self.get(booking["id"])
        self.assertEqual("19:30", value["confirmedTime"])
        self.assertEqual("19:00", value["change"]["originalTime"])

    def test_change_rejection_preserves_original(self):
        booking = self.booking("ACCEPT")
        self.callback(booking)
        self.command("decide_change", {**self.payload(booking["id"]), "approve": False})
        self.assertEqual("19:00", self.get(booking["id"])["confirmedTime"])
        self.assertEqual("REJECTED", self.get(booking["id"])["change"]["status"])

    def test_reconfirmation_disconnect_preserves_original_and_unknown(self):
        booking = self.booking("ACCEPT")
        self.callback(booking)
        self.command("decide_change", {**self.payload(booking["id"]), "approve": True})
        self.command("reconfirm_change", {**self.payload(booking["id"]), "result": "DISCONNECT"})
        self.assertEqual("19:00", self.get(booking["id"])["confirmedTime"])
        self.assertEqual("UNKNOWN", self.get(booking["id"])["change"]["status"])
        self.assert_error("CHANGE_PENDING", lambda: self.callback(self.get(booking["id"])))

    def test_invalid_conditions_and_time_rejected(self):
        self.assert_error("INVALID_INPUT", lambda: self.command("create_trip", {
            "conditions": {**CONDITIONS, "highChairCount": 8}}))
        trip = self.command("create_trip", {"conditions": CONDITIONS})["id"]
        self.assert_error("INVALID_INPUT", lambda: self.command("create_booking", {
            "tripId": trip, "tripVersion": 1, "reservationName": "김예약", "date": DAY, "time": "29:00"}))

    def test_trip_and_session_survive_database_reopen(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.sqlite3"
            service = ReservationService(path)
            owner, token = service.session()
            service.command(owner, "create_trip", {"conditions": CONDITIONS}, "persist")
            service.close()
            service = ReservationService(path)
            try:
                self.assertEqual(owner, service.session(token)[0])
                self.assertEqual(1, len(service.state(owner)["trips"]))
            finally:
                service.close()


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.service = ReservationService()
        self.server = make_server(self.service, 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)

    def tearDown(self):
        self.connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.service.close()

    def request(self, method, path, body=None, headers=None):
        self.connection.request(method, path, body=body, headers=headers or {})
        response = self.connection.getresponse()
        raw = response.read()
        return response, raw

    def test_session_cookie_and_json_action(self):
        response, raw = self.request("GET", "/api/state")
        self.assertEqual(200, response.status)
        cookie = response.getheader("Set-Cookie")
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=Strict", cookie)
        response, raw = self.request("POST", "/api/action", json.dumps({
            "action": "create_trip", "payload": {"conditions": CONDITIONS}, "requestId": "http-test"}),
            {"Cookie": cookie.split(";")[0], "Content-Type": "application/json", "X-Prototype-Request": "1"})
        self.assertEqual(200, response.status)
        self.assertEqual(1, len(json.loads(raw)["state"]["trips"]))

    def test_cross_site_origin_blocked(self):
        response, raw = self.request("POST", "/api/action", "{}", {
            "Content-Type": "application/json", "Origin": "https://external.invalid"})
        self.assertEqual(403, response.status)
        self.assertEqual("ORIGIN_DENIED", json.loads(raw)["error"]["code"])

    def test_missing_session_cannot_mutate(self):
        response, _ = self.request("POST", "/api/action", "{}", {"Content-Type": "application/json"})
        self.assertEqual(403, response.status)

    def test_dns_rebinding_host_blocked(self):
        response, _ = self.request("GET", "/api/state", headers={"Host": "external.invalid"})
        self.assertEqual(403, response.status)

    def test_static_path_allowlist(self):
        response, _ = self.request("GET", "/../service.py")
        self.assertEqual(404, response.status)
        response, raw = self.request("GET", "/")
        self.assertEqual(200, response.status)
        self.assertIn("MOCK".encode(), raw)
        self.assertIn("script-src 'self'", response.getheader("Content-Security-Policy"))


if __name__ == "__main__":
    unittest.main()
