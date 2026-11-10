#!/usr/bin/env python3
"""Run synthetic requests against a loopback MOCK test harness with trusted Principal injection.

Ordinary issued tokens cannot inject mock outcomes. Use smoke-test.py for the operator harness.
"""
import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone


def stamp(value):
    return value.isoformat().replace("+00:00", "Z")


def now():
    return datetime.now(timezone.utc)


def run(base, emit=print, validate=None):
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.username or parsed.password:
        raise ValueError("This demo accepts loopback HTTP URLs only")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(path, body=None, key=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if body is not None:
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        req = urllib.request.Request(base.rstrip("/") + "/api/v1" + path,
                                     data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with opener.open(req, timeout=10) as response:
                status, result = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status, result = error.code, json.load(error)
        if status == 401 and expected != 401:
            raise RuntimeError("A trusted operator Principal is required for mock outcome injection; "
                               "use scripts/smoke-test.py for the isolated test-only harness.")
        assert status == expected, (status, result)
        if validate is not None:
            validate(path, body, result, status)
        if isinstance(result, dict):
            assert result.get("mode") == "MOCK", result
        return result

    status = api("/status")
    assert not status["externalCallsEnabled"]
    eta = now().replace(microsecond=0) + timedelta(hours=1)
    request = {
        "navigation": {"tripId": "mock-" + str(uuid.uuid4()), "destination": {
            "name": "가상 성수 목적지", "latitude": 37.544, "longitude": 127.055},
            "eta": stamp(eta), "observedAt": stamp(now()), "source": "MOCK"},
        "party": {"adults": 2, "children": 1}, "maxWaitMinutes": 15,
        "requiredFeatures": ["parking"], "preferredCuisines": ["한식"], "learningConsent": False,
    }
    search = api("/searches", request)
    sid = search["id"]
    assert len(search["candidates"]) == 3
    call = api(f"/searches/{sid}/inquiries", {"candidateId": "mock-1"})
    api(f'/mock/calls/{call["id"]}/outcomes', {
        "eventId": str(uuid.uuid4()), "outcome": "ANSWERED", "observedAt": stamp(now()),
        "explicitAcceptance": False, "additionalCostKrw": 0,
        "facts": {"childFriendly": True, "parking": True, "seatsAvailable": True,
                  "reservationAvailable": True, "nowWaitMinutes": 25, "arrivalWaitMinutes": 10,
                  "arrivalAppliesAt": stamp(eta)},
    })
    refreshed = api(f"/searches/{sid}")
    assert next(c for c in refreshed["candidates"] if c["id"] == "mock-1")["bookable"]
    book = {"candidateId": "mock-1", "requestedTime": stamp(eta), "allowBooking": True,
            "changePolicy": {"enabled": True, "latestTime": stamp(eta + timedelta(hours=1)), "maxAdditionalCostKrw": 0}}
    key = str(uuid.uuid4())
    reservation = api(f"/searches/{sid}/reservations", book, key)
    assert api(f"/searches/{sid}/reservations", book, key)["id"] == reservation["id"]
    assert reservation["confirmedTime"] is None
    api(f'/mock/calls/{reservation["bookingCallId"]}/outcomes', {
        "eventId": str(uuid.uuid4()), "outcome": "ACCEPTED", "explicitAcceptance": True,
        "observedAt": stamp(now()), "acceptedTime": stamp(eta), "additionalCostKrw": 0,
    })
    rid = reservation["id"]
    confirmed = api(f"/reservations/{rid}")
    assert confirmed["status"] == "CONFIRMED"
    changed = api(f"/reservations/{rid}/changes", {"requestedTime": stamp(eta + timedelta(minutes=15)), "additionalCostKrw": 0, "approveAdditionalCost": False})
    assert changed["confirmedTime"] == confirmed["confirmedTime"]
    api(f'/mock/calls/{changed["pendingChangeCallId"]}/outcomes', {
        "eventId": str(uuid.uuid4()), "outcome": "UNKNOWN", "observedAt": stamp(now()),
        "explicitAcceptance": False, "additionalCostKrw": 0,
    })
    final = api(f"/reservations/{rid}")
    assert final["confirmedTime"] == confirmed["confirmedTime"] and final["pendingChangeCallId"]
    blocked = api(f"/reservations/{rid}/changes", {"requestedTime": stamp(eta + timedelta(minutes=20)), "additionalCostKrw": 0, "approveAdditionalCost": False}, expected=409)
    assert blocked["code"] == "CHANGE_BLOCKED"
    report = {"mode": "MOCK", "searchId": sid, "reservationId": rid,
              "status": final["status"], "unresolvedChange": True,
              "externalCalls": 0, "result": "PASS"}
    emit(json.dumps(report, ensure_ascii=False, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    run(parser.parse_args().base_url)
