"""Cuisine forwarding and immutable trial booking context; no live providers."""
import copy
import unittest
from datetime import date, datetime, timedelta, timezone
from unittest.mock import patch

from .service import ReservationService, identifier
from .test_service import CONDITIONS
from .web_recommendation import search_web


class FoodPreferenceTest(unittest.TestCase):
    def test_preference_phrase_and_required_needs_are_forwarded_together(self):
        result = {"preferredCuisines": ["양식"], "webState": "RESULTS", "webRecommendations": []}
        with patch("prototypes.phone_agent.web_recommendation.request", side_effect=[
            {"accessToken": "fixture-token"},
            {"taskId": "11111111-1111-4111-8111-111111111111"},
            {"state": "RESULTS", "source": {"provider": "WEB_SEARCH"}, "result": result, "validUntil": None},
        ]) as upstream:
            response = search_web(9999, {"destination": "가상 성수", "foodPreferenceText": "양식 먹고 싶어",
                                       "venueCategory": "RESTAURANT", "requiredNeeds": ["PARKING", "HIGH_CHAIR"]})
        query = upstream.call_args_list[1].args[3]
        self.assertEqual(query["foodPreferenceText"], "양식 먹고 싶어")
        self.assertEqual(query["requiredNeeds"], ["PARKING", "HIGH_CHAIR"])
        self.assertEqual(response["result"]["preferredCuisines"], ["양식"])

    def test_selection_keeps_cuisine_and_companion_snapshot_until_approval(self):
        service = ReservationService(web_test_enabled=True)
        self.addCleanup(service.close)
        owner, _ = service.session()
        snapshot = copy.deepcopy(CONDITIONS)
        response = {"validUntil": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
                    "result": {"preferredCuisines": ["양식"], "webRecommendations": [{
                        "ordinal": 1, "name": "가상 양식집", "address": "가상 시험주소 1",
                        "identitySourceUrl": "https://merchant.example/fictional", "venueCategory": "RESTAURANT"}]}}
        search = service.remember_web_search(owner, response, snapshot)
        snapshot["highChairCount"] = 0
        response["result"]["preferredCuisines"].clear()
        created = service.command(owner, "create_web_test_booking", {
            "searchId": search, "candidateOrdinal": 1, "reservationName": "가상예약자",
            "date": (date.today() + timedelta(days=7)).isoformat(), "time": "19:00"}, identifier())
        booking = service.state(owner)["bookings"][0]
        self.assertEqual(booking["id"], created["id"])
        self.assertEqual(booking["snapshot"]["preferredCuisines"], ["양식"])
        self.assertEqual(booking["snapshot"]["highChairCount"], 1)
        self.assertTrue(booking["snapshot"]["stepFree"])
        service.command(owner, "approve_booking", {"bookingId": booking["id"], "version": booking["version"]}, identifier())
        self.assertEqual(service.state(owner)["bookings"][0]["approvedSnapshot"]["preferredCuisines"], ["양식"])


if __name__ == "__main__":
    unittest.main()
