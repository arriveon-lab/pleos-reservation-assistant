#!/usr/bin/env python3
"""Exercise actual server-issued tokens using fictional data; never print or persist tokens."""
import json
import copy
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timedelta, timezone


def exercise(base, validate=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(path, body=None, token=None, key=None, expected=200, method=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(base + "/api/v1" + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with opener.open(request, timeout=10) as response:
                status, result = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status, result = error.code, json.load(error)
        assert status == expected, (path, status, expected, result.get("code"))
        if validate:
            validate(path, body, result, status, method=method.lower() if method else None)
        return result

    first = api("/anonymous-sessions", {})
    other = api("/anonymous-sessions", {})
    now = datetime.now(timezone.utc)
    body = {"navigation": {"tripId": str(uuid.uuid4()), "destination": {
        "name": "가상 목적지", "latitude": 37.544, "longitude": 127.055},
        "eta": (now + timedelta(hours=1)).isoformat(), "observedAt": now.isoformat(), "source": "MOCK"},
        "party": {"adults": 2, "children": 1}, "maxWaitMinutes": 15,
        "requiredFeatures": ["parking"], "preferredCuisines": ["한식"], "learningConsent": False}
    key = str(uuid.uuid4())
    search = api("/searches", body, first["accessToken"], key)
    closed_body = copy.deepcopy(body)
    closed_body["navigation"]["eta"] = (now - timedelta(seconds=1)).isoformat()
    closed_key = str(uuid.uuid4())
    assert api("/searches", closed_body, first["accessToken"], closed_key, expected=400)["code"] == "INVALID_TIME"
    api("/searches/reconcile", closed_body, key=closed_key, expected=401)
    closed = api("/searches/reconcile", closed_body, first["accessToken"], closed_key)
    assert closed["state"] == "REJECTED_CLOSED" and closed["searchId"] is None
    assert api("/searches/reconcile", closed_body, first["accessToken"], closed_key) == closed
    assert api("/searches", closed_body, first["accessToken"], closed_key, expected=409)["code"] == "SEARCH_COMMAND_CLOSED"
    assert api("/searches/reconcile", body, first["accessToken"], closed_key, expected=409)["code"] == "KEY_REUSED"
    completed = api("/searches/reconcile", body, first["accessToken"], key)
    assert completed["state"] == "COMPLETED" and completed["searchId"] == search["id"]
    assert api("/searches/reconcile", body, other["accessToken"], key, expected=409)["code"] == "REQUEST_NOT_CLOSABLE"
    assert api("/searches", body, first["accessToken"], key)["id"] == search["id"]
    separate = api("/searches", body, other["accessToken"], key)
    assert separate["id"] != search["id"]
    api("/searches/" + search["id"], token=other["accessToken"], expected=404)
    api("/searches/" + search["id"] + "/inquiries", {"candidateId": "mock-1"},
        other["accessToken"], key, expected=404)
    call = api("/searches/" + search["id"] + "/inquiries", {"candidateId": "mock-1"},
        first["accessToken"], str(uuid.uuid4()))
    event = {"eventId": str(uuid.uuid4()), "outcome": "UNKNOWN", "explicitAcceptance": False,
             "observedAt": datetime.now(timezone.utc).isoformat(), "acceptedTime": None,
             "additionalCostKrw": 0, "facts": None}
    api("/mock/calls/" + call["id"] + "/outcomes", event, first["accessToken"], expected=403)
    refresh_key = str(uuid.uuid4())
    refreshed = api("/anonymous-sessions/refresh", {"refreshToken": first["refreshToken"]}, key=refresh_key)
    assert api("/anonymous-sessions/refresh", {"refreshToken": first["refreshToken"]}, key=refresh_key) == refreshed
    api("/anonymous-sessions/refresh", {"refreshToken": first["refreshToken"]}, key=str(uuid.uuid4()), expected=409)
    api("/searches/" + search["id"], token=refreshed["accessToken"])
    api("/anonymous-sessions/current", token=other["accessToken"], method="DELETE")
    api("/searches/" + separate["id"], token=other["accessToken"], expected=401)
    print("PASS: issued Bearer tokens, ownership, role rejection, refresh/retry, revocation")

    def after_restart():
        assert api("/searches/reconcile", closed_body, refreshed["accessToken"], closed_key) == closed
        assert api("/searches", closed_body, refreshed["accessToken"], closed_key, expected=409)["code"] == "SEARCH_COMMAND_CLOSED"
        assert api("/searches/reconcile", body, refreshed["accessToken"], key) == completed
        api("/searches", body, refreshed["accessToken"], str(uuid.uuid4()))
        print("PASS: old search closure, completed response reconciliation and explicit new search survive H2 restart")
        api("/searches/" + search["id"], token=refreshed["accessToken"])
        assert api("/anonymous-sessions/refresh", {"refreshToken": first["refreshToken"]}, key=refresh_key) == refreshed
        next_pair = api("/anonymous-sessions/refresh", {"refreshToken": refreshed["refreshToken"]}, key=str(uuid.uuid4()))
        api("/searches/" + search["id"], token=next_pair["accessToken"])
        api("/searches/" + separate["id"], token=other["accessToken"], expected=401)
        api("/anonymous-sessions/current", token=next_pair["accessToken"], method="DELETE")
        api("/searches/" + search["id"], token=first["accessToken"], expected=401)
        api("/anonymous-sessions/refresh", {"refreshToken": next_pair["refreshToken"]}, key=str(uuid.uuid4()), expected=401)
        print("PASS: token identity, lost-response retry and revocation survive H2 restart")

    return after_restart
