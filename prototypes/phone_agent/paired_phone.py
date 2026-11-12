"""Stationary Pleos trial: operator claims a one-time code; only the app owner can dial."""
import asyncio
import hashlib
import http.client
import json
import re
import secrets
from uuid import uuid4
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

from .service import DomainError, conditions, identifier, integer, require, text, valid_time
from .voice.config import ConfigurationError, normalize_number

PREFIX = "/pleos-phone"
OPERATOR_PATH = "/api/live/pleos-pairing"
BASE = "/api/experimental/destination-recommendations"
UUID = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
CODE = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
ACTIVE = {"RESERVED", "CALLING"}
MAX_SEARCHES = 3
MAX_CALLS = 3


def now():
    return datetime.now(timezone.utc)


@contextmanager
def transaction(service):
    with service.lock:
        service.db.execute("BEGIN IMMEDIATE")
        try:
            yield
            service.db.execute("COMMIT")
        except BaseException:
            service.db.execute("ROLLBACK")
            raise


def _one(service, owner, kind, key="active"):
    try:
        return service._get(owner, kind, key)
    except DomainError as error:
        if error.status != 404:
            raise
        return None


def _active(service, owner):
    value = _one(service, owner, "pleos_phone_binding")
    return value if value and value["expiresAt"] > now().isoformat() else None


def registered_target(service, owner, generation):
    binding = _active(service, owner)
    return binding["phone"] if binding and binding["mode"] == "LIVE" and binding["generation"] == generation else None


def _public(binding):
    if not binding:
        return {"active": False}
    return {"active": True, "phoneLast4": binding["phone"][-4:], "mode": binding["mode"],
            "expiresAt": binding["expiresAt"]}


def status(service, owner):
    with service.lock:
        result = _public(_active(service, owner))
        attempts = service._list(owner, "pleos_phone_attempt")
        return {**result, "latest": _attempt_view(service, owner, attempts[-1]) if attempts else None}


def _purge(service):
    service.db.execute("DELETE FROM objects WHERE kind IN ('pleos_phone_code','pleos_phone_binding')"
                       " AND json_extract(body,'$.expiresAt')<=?", (now().isoformat(),))


def issue(service, owner):
    with transaction(service):
        _purge(service)
        require(not _active(service, owner), "PAIRING_ACTIVE", "이미 번호가 연결되어 있어요.", 409)
        previous = _one(service, owner, "pleos_phone_code")
        instant = now()
        require(not previous or previous["retryAfter"] <= instant.isoformat(),
                "PAIRING_RATE_LIMIT", "잠시 후 연결 코드를 다시 요청해 주세요.", 429)
        code = "".join(secrets.choice(CODE) for _ in range(10))
        expires = (instant + timedelta(minutes=5)).isoformat()
        service._put(owner, "pleos_phone_code", {"id": "active", "digest": _digest(code),
                     "expiresAt": expires, "retryAfter": (instant + timedelta(seconds=5)).isoformat()})
        return {"pairingCode": code, "expiresAt": expires}


def _digest(code):
    return hashlib.sha256(code.encode("ascii")).hexdigest()


def claim(service, body, live_config):
    code, raw, mode = body.get("pairingCode"), body.get("testPhoneNumber"), body.get("mode", "MOCK")
    require(isinstance(code, str) and re.fullmatch(f"[{CODE}]{{10}}", code),
            "INVALID_PAIRING_CODE", "앱 화면의 연결 코드 10자리를 입력해 주세요.", 400)
    require(mode in ("MOCK", "LIVE"), "INVALID_MODE", "MOCK 또는 LIVE를 선택해 주세요.", 400)
    require(isinstance(raw, str) and len(raw) <= 24, "INVALID_TEST_PHONE", "시험 휴대폰 번호를 확인해 주세요.", 400)
    try:
        phone = normalize_number(raw)
    except ConfigurationError:
        phone = ""
    require(re.fullmatch(r"\+8210\d{8}", phone), "INVALID_TEST_PHONE", "010 휴대폰 번호를 입력해 주세요.", 400)
    if mode == "LIVE":
        config = live_config()
        require(config.self_service_test and config.test_phone_input,
                "LIVE_NOT_READY", "시험 번호 등록 기능이 준비되지 않았어요.", 409)
    with transaction(service):
        _purge(service)
        matches = service.db.execute(
            "SELECT owner FROM objects WHERE kind='pleos_phone_code' AND json_extract(body,'$.digest')=?"
            " AND json_extract(body,'$.expiresAt')>?", (_digest(code), now().isoformat())).fetchall()
        require(len(matches) == 1, "PAIRING_CODE_EXPIRED", "연결 코드가 만료됐거나 이미 사용됐어요.", 404)
        owner = matches[0][0]
        require(not _active(service, owner), "PAIRING_ACTIVE", "이미 번호가 연결되어 있어요.", 409)
        service.db.execute("DELETE FROM objects WHERE owner=? AND kind='pleos_phone_code'", (owner,))
        result = service._put(owner, "pleos_phone_binding",
                              {"id": "active", "generation": identifier(), "phone": phone, "mode": mode,
                               "expiresAt": (now() + timedelta(hours=1)).isoformat(), "searches": 0, "calls": 0})
    return _public(result)


def disconnect(service, owner):
    with transaction(service):
        service.db.execute("DELETE FROM objects WHERE owner=? AND kind IN ('pleos_phone_code','pleos_phone_binding')", (owner,))
    return {"active": False}


def upstream(port, method, path, bearer=None, body=None, key=None):
    headers = {"Accept": "application/json"}
    if bearer is not None:
        headers["Authorization"] = bearer
    if key:
        headers["Idempotency-Key"] = key
    if body is not None:
        headers["Content-Type"] = "application/json"
    con = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        con.request(method, path, body=json.dumps(body).encode() if body is not None else None, headers=headers)
        response = con.getresponse()
        data = response.read(262145)
        require(len(data) <= 262144, "UPSTREAM_UNAVAILABLE", "추천 서버 응답이 너무 커요.", 502)
        return response.status, json.loads(data)
    except (OSError, ValueError, http.client.HTTPException):
        raise DomainError("UPSTREAM_UNAVAILABLE", "추천 서버에 연결하지 못했어요.", 502) from None
    finally:
        con.close()


def _mock_job(job, view):
    conditions = view["snapshot"]
    needs = ["PARKING"]
    for condition, need in (("highChairCount", "HIGH_CHAIR"), ("stepFree", "STEP_FREE_ACCESS"),
                            ("childFriendly", "CHILD_FRIENDLY")):
        if conditions.get(condition):
            needs.append(need)
    return {"id": job, "state": "RESULTS", "validUntil": view["expiresAt"],
            "source": {"provider": "WEB_SEARCH", "attribution": "가상 시험 데이터"},
            "result": {"webState": "RESULTS", "webRecommendations": [
                {"ordinal": i, "name": f"가상 시험 식당 {i}", "address": f"가상 시험주소 {i}",
                 "identitySourceUrl": "https://example.com/", "identityVerified": False,
                 "inquiryDispatchAllowed": False, "bookable": False, "venueCategory": "RESTAURANT",
                 "fit": "NEEDS_CONFIRMATION", "assessments": [
                    {"need": need, "verdict": "UNKNOWN", "evidence": []} for need in needs],
                 "remainingQuestions": needs, "reviews": []}
                for i in range(1, 4)]}}


def _snapshot(body):
    party = body.get("party")
    require(isinstance(party, dict), "INVALID_INPUT", "인원을 확인해 주세요.", 400)
    children = integer(party.get("children"), 0, 10)
    size = integer(party.get("adults"), 1, 20) + children
    features = body.get("requiredFeatures")
    require(isinstance(features, list) and all(item in ("parking", "highChair", "stepFreeAccess",
            "childFriendly", "petFriendly") for item in features), "INVALID_INPUT", "필수 조건을 확인해 주세요.", 400)
    return conditions({"partySize": size, "highChairCount": 1 if "highChair" in features else 0,
                       "stepFree": "stepFreeAccess" in features, "parkingRequired": True,
                       "childFriendly": children > 0 or "childFriendly" in features,
                       "petAccompanied": "YES" if "petFriendly" in features else "NO",
                       "maxArrivalWaitMinutes": body.get("maxWaitMinutes")})


def start(service, port, owner, body, bearer):
    snapshot = _snapshot(body)
    view = body.get("viewId")
    require(isinstance(view, str) and re.fullmatch(UUID, view), "INVALID_INPUT", "검색 화면을 확인해 주세요.", 400)
    with transaction(service):
        binding = _active(service, owner)
        require(binding, "PAIRING_REQUIRED", "Swagger에서 시험 번호를 먼저 연결해 주세요.", 409)
        require(binding["searches"] < MAX_SEARCHES, "PAIRING_SEARCH_LIMIT", "이 연결의 검색 한도에 도달했어요.", 429)
        binding["searches"] += 1
        service._put(owner, "pleos_phone_binding", binding)
    if binding["mode"] == "LIVE":
        request = dict(body)
        if snapshot["childFriendly"] and "childFriendly" not in request["requiredFeatures"]:
            request["requiredFeatures"] = [*request["requiredFeatures"], "childFriendly"]
        code, result = upstream(port, "POST", BASE, bearer, request)
        if code != 200:
            return code, result
        job = result["taskId"]
    else:
        job = str(uuid4())
        result = {"taskId": job, "state": "QUEUED"}
    with transaction(service):
        service._put(owner, "pleos_phone_search", {"id": job, "viewId": view,
                     "mode": binding["mode"], "generation": binding["generation"], "snapshot": snapshot,
                     "expiresAt": (now() + timedelta(minutes=5)).isoformat(), "closed": False})
    return 200, result


def poll(service, port, owner, job, bearer):
    with service.lock:
        view = service._get(owner, "pleos_phone_search", job)
    require(not view["closed"], "SEARCH_CLOSED", "닫힌 추천 화면이에요.", 409)
    if view["mode"] == "MOCK":
        return 200, _mock_job(job, view)
    return upstream(port, "GET", f"{BASE}/{job}", bearer)


def close_search(service, port, owner, view_id, bearer):
    live = False
    with transaction(service):
        for item in service._list(owner, "pleos_phone_search"):
            if item["viewId"] == view_id and not item["closed"]:
                item["closed"] = True
                live |= item["mode"] == "LIVE"
                service._put(owner, "pleos_phone_search", item)
    if live:
        return upstream(port, "POST", f"{BASE}/views/{view_id}/close", bearer, {})
    return 200, {"state": "CLOSED"}


def _attempt_view(service, owner, attempt):
    booking = service._get(owner, "booking", attempt["bookingId"])
    call = _one(service, owner, "live_call", booking.get("liveCallId")) if booking.get("liveCallId") else None
    state = booking["status"]
    if state == "READY":
        started = datetime.fromisoformat(booking["approvedAt"].replace("Z", "+00:00"))
        state = "RESERVED" if started + timedelta(seconds=45) > now() else "UNKNOWN"
    return {"id": attempt["id"], "mode": attempt["mode"], "status": state,
            "transportStatus": call["status"] if call else None,
            "restaurantName": booking["restaurantName"], "date": booking["date"],
            "requestedTime": booking["requestedTime"], "confirmedTime": booking["confirmedTime"],
            "partySize": booking["snapshot"]["partySize"],
            "notice": ("발신 상태를 확인하지 못했어요. 다시 걸지 않았어요." if state == "UNKNOWN" and not call
                       else booking["notice"] if state != "RESERVED" else ""),
            "requirements": booking.get("requirementChecks", {})}


def attempt_result(service, owner, key):
    with service.lock:
        return _attempt_view(service, owner, service._get(owner, "pleos_phone_attempt", key))


def dial(service, port, owner, body, key, bearer, live_config):
    require(isinstance(key, str) and re.fullmatch(UUID, key), "IDEMPOTENCY_KEY_REQUIRED", "요청 키를 확인해 주세요.", 400)
    require(set(body) == {"taskId", "candidateOrdinal", "date", "time", "reservationName"},
            "INVALID_INPUT", "추천 후보와 시험 예약 정보를 확인해 주세요.", 400)
    digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
    job = body["taskId"]
    ordinal = integer(body["candidateOrdinal"], 1, 3)
    with service.lock:
        old = _one(service, owner, "pleos_phone_attempt", key)
        if old:
            require(old["digest"] == digest, "IDEMPOTENCY_CONFLICT", "같은 키에 다른 시험 요청이에요.", 409)
            return _attempt_view(service, owner, old)
        binding = _active(service, owner)
        view = service._get(owner, "pleos_phone_search", job)
    require(binding and binding["generation"] == view["generation"], "PAIRING_REQUIRED",
            "현재 연결에서 추천을 다시 검색해 주세요.", 409)
    config = live_config() if binding["mode"] == "LIVE" else None
    code, result = poll(service, port, owner, job, bearer)
    require(code == 200 and result.get("state") == "RESULTS" and
            (result.get("result") or {}).get("webState") == "RESULTS",
            "SEARCH_EXPIRED", "유효한 추천 결과에서 다시 선택해 주세요.", 409)
    try:
        expires = datetime.fromisoformat(result["validUntil"].replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError):
        raise DomainError("SEARCH_EXPIRED", "추천 결과의 유효기간을 확인하지 못했어요.", 409) from None
    candidates = result["result"]["webRecommendations"]
    candidate = next((item for item in candidates if item.get("ordinal") == ordinal), None)
    require(candidate and candidate.get("venueCategory") == "RESTAURANT",
            "INVALID_CANDIDATE", "이 검색 결과의 식당을 선택해 주세요.", 400)
    restaurant = {"id": f"pleos-{job}-{ordinal}", "name": text(candidate.get("name"), 200),
                  "address": text(candidate.get("address"), 300),
                  "sourceUrl": text(candidate.get("identitySourceUrl"), 2048), "venueCategory": "RESTAURANT"}
    with transaction(service):
        old = _one(service, owner, "pleos_phone_attempt", key)
        if old:
            require(old["digest"] == digest, "IDEMPOTENCY_CONFLICT", "같은 키에 다른 시험 요청이에요.", 409)
            return _attempt_view(service, owner, old)
        current = _active(service, owner)
        view = service._get(owner, "pleos_phone_search", job)
        require(current and current["generation"] == binding["generation"] and not view["closed"] and expires > now(),
                "SEARCH_EXPIRED", "연결 상태나 추천 결과가 바뀌었어요.", 409)
        require(current["calls"] < MAX_CALLS, "PAIRING_CALL_LIMIT", "이 연결의 시험전화 한도에 도달했어요.", 429)
        attempts = service._list(owner, "pleos_phone_attempt")
        require(not any(_attempt_view(service, owner, row)["status"] in ACTIVE for row in attempts),
                "CALL_ACTIVE", "이미 진행 중인 시험전화가 있어요.", 409)
        require(not any(row["jobId"] == job and row["ordinal"] == ordinal for row in attempts),
                "DUPLICATE_CANDIDATE", "이 후보의 시험 결과를 먼저 확인해 주세요.", 409)
        if config:
            from .voice.workflow import LiveWorkflow
            LiveWorkflow(service, config)._capacity()
        if not _one(service, owner, "trip", job):
            service._put(owner, "trip", {"id": job, "version": 1, "conditions": view["snapshot"],
                                       "confirmedAt": now().isoformat()})
        valid_time(body["time"])
        booking_id = service._create_booking(owner, {**body, "tripId": job, "tripVersion": 1,
                                                    "restaurantId": restaurant["id"]},
                                             web_restaurant=restaurant)["id"]
        service._approve_booking(owner, {"bookingId": booking_id, "version": 1})
        booking = service._get(owner, "booking", booking_id)
        booking["pleosPairedTest"] = current["generation"]
        if not config:
            booking.pop("liveTest", None)
            booking.pop("webTest", None)
        service._put(owner, "booking", booking)
        attempt = service._put(owner, "pleos_phone_attempt", {"id": key, "digest": digest, "bookingId": booking_id,
                               "mode": current["mode"], "jobId": job, "ordinal": ordinal})
        current["calls"] += 1
        service._put(owner, "pleos_phone_binding", current)
        if not config:
            service._run_call(owner, {"bookingId": booking_id, "version": 2, "scenario": "ACCEPT"})
            return _attempt_view(service, owner, attempt)
    from .voice.transport import dial_booking
    try:
        asyncio.run(dial_booking(service, config, owner, booking_id, 2, key, test_phone_number=binding["phone"]))
    except Exception:
        # A durable reservation with uncertain dispatch is never silently retried.
        with transaction(service):
            booking = service._get(owner, "booking", booking_id)
            if booking["status"] == "READY":
                booking.update(status="FAILED", notice="시험 발신을 시작하지 못했어요.", version=booking["version"] + 1)
                service._put(owner, "booking", booking)
    return attempt_result(service, owner, key)


def route(service, port, method, path, headers, body, live_config):
    bearer, key = headers.get("Authorization"), headers.get("Idempotency-Key")
    if path == "/api/v1/status" and method == "GET":
        return upstream(port, method, path)
    if path in ("/api/v1/anonymous-sessions", "/api/v1/anonymous-sessions/refresh") and method == "POST":
        return upstream(port, method, path, body=body, key=key)
    require(bearer and re.fullmatch(r"Bearer [A-Za-z0-9._~-]{16,256}", bearer),
            "AUTHENTICATION_REQUIRED", "앱 인증이 필요해요.", 401)
    status_code, actor = upstream(port, "GET", "/api/v1/internal/paired-phone-subject", bearer)
    if status_code != 200:
        return status_code, actor
    subject = actor.get("subject")
    require(isinstance(subject, str) and re.fullmatch(r"[A-Za-z0-9._:-]{1,100}", subject),
            "UPSTREAM_UNAVAILABLE", "앱 계정을 확인할 수 없어요.", 502)
    owner = "pleos-paired:" + subject
    if path == "/api/v1/paired-phone":
        if method == "GET":
            return 200, status(service, owner)
        return 200, issue(service, owner)
    if path == "/api/v1/paired-phone/disconnect" and method == "POST":
        return 200, disconnect(service, owner)
    if path == "/api/v1/paired-phone/calls" and method == "POST":
        return 200, dial(service, port, owner, body, key, bearer, live_config)
    if method == "GET" and (match := re.fullmatch(rf"/api/v1/paired-phone/calls/({UUID})", path)):
        return 200, attempt_result(service, owner, match[1])
    if path == BASE and method == "POST":
        return start(service, port, owner, body, bearer)
    if method == "GET" and (match := re.fullmatch(rf"{BASE}/({UUID})", path)):
        return poll(service, port, owner, match[1], bearer)
    if method == "POST" and (match := re.fullmatch(rf"{BASE}/views/({UUID})/close", path)):
        return close_search(service, port, owner, match[1], bearer)
    raise DomainError("NOT_FOUND", "이 시험 API 경로를 찾을 수 없어요.", 404)
