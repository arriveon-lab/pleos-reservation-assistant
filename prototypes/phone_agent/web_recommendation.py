"""Fetch a transient WEB_SEARCH result from the loopback recommendation server."""
from datetime import datetime
import http.client
import json
import re
import time
import uuid

from .service import DomainError, require


BASE = "/api/experimental/destination-recommendations"
NEEDS = {"PARKING", "HIGH_CHAIR", "STEP_FREE_ACCESS", "CHILD_FRIENDLY",
         "PET_FRIENDLY", "INDOOR_PET_FRIENDLY"}


def booking_web_needs(snapshot):
    needs = ["PARKING"]
    if snapshot["highChairCount"]:
        needs.append("HIGH_CHAIR")
    if snapshot["stepFree"]:
        needs.append("STEP_FREE_ACCESS")
    if snapshot.get("childFriendly"):
        needs.append("CHILD_FRIENDLY")
    if snapshot["petAccompanied"] == "YES":
        needs.append("PET_FRIENDLY")
    return needs


def search_web(port, payload):
    require(port is not None, "WEB_NOT_CONFIGURED", "웹검색 서버를 실행한 뒤 다시 시도해 주세요.", 503)
    destination = payload.get("destination")
    needs = payload.get("requiredNeeds")
    category = payload.get("venueCategory")
    preference = payload.get("foodPreferenceText", "")
    require(isinstance(preference, str) and len(preference) <= 160
            and not re.search(r"[\x00-\x1f\x7f-\x9f]", preference),
            "INVALID_FOOD_PREFERENCE", "먹고 싶은 음식은 160자 이내로 알려 주세요.", 400)
    require(not (category == "CAFE" and preference.strip()),
            "INVALID_FOOD_PREFERENCE", "음식 종류로 찾으려면 장소 종류를 식당 또는 전체로 선택해 주세요.", 400)
    require(isinstance(destination, str) and 1 <= len(destination.strip()) <= 80
            and not re.search(r"[\x00-\x1f\x7f-\x9f]", destination)
            and isinstance(needs, list) and len(needs) <= len(NEEDS)
            and all(isinstance(need, str) and need in NEEDS for need in needs),
            "INVALID_WEB_REQUEST", "목적지와 필수 조건을 확인해 주세요.", 400)
    require(category is None or category in ("RESTAURANT", "CAFE"),
            "INVALID_VENUE_CATEGORY", "검색할 장소 종류를 확인해 주세요.", 400)
    query = {
        "viewId": str(uuid.uuid4()), "generation": 1, "destination": destination.strip(),
        "requiredNeeds": needs,
        "foodPreferenceText": preference.strip(),
    }
    if category is not None:
        query["venueCategory"] = category
    if payload.get("arrivalAt") is not None:
        arrival = payload["arrivalAt"]
        require(isinstance(arrival, str) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}", arrival),
                "INVALID_ARRIVAL", "방문 날짜와 시간을 확인해 주세요.", 400)
        try:
            datetime.fromisoformat(arrival)
        except ValueError:
            raise DomainError("INVALID_ARRIVAL", "방문 날짜와 시간을 확인해 주세요.", 400) from None
        query["arrivalAt"] = arrival
    token = request(port, "POST", "/api/v1/anonymous-sessions", {}).get("accessToken")
    require(isinstance(token, str) and token, "INVALID_WEB_RESPONSE", "추천 서버 인증에 실패했어요.", 502)
    started = request(port, "POST", BASE + "/web", query, token)
    task = started.get("taskId")
    require(isinstance(task, str) and re.fullmatch(r"[0-9a-f-]{36}", task),
            "INVALID_WEB_RESPONSE", "추천 서버 응답을 확인할 수 없어요.", 502)
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        snapshot = request(port, "GET", BASE + "/" + task, token=token)
        if snapshot.get("state") not in ("QUEUED", "RUNNING"):
            require((snapshot.get("source") or {}).get("provider") == "WEB_SEARCH",
                    "INVALID_WEB_RESPONSE", "웹검색 추천 서버에 연결해 주세요.", 502)
            return {"state": snapshot["state"], "result": snapshot.get("result"),
                    "validUntil": snapshot.get("validUntil")}
        time.sleep(0.4)
    raise DomainError("WEB_TIMEOUT", "웹검색 응답 시간이 지났어요. 잠시 후 다시 시도해 주세요.", 504)


def request(port, method, path, body=None, token=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        connection.request(method, path, None if body is None else json.dumps(body), headers)
        response = connection.getresponse()
        if response.status == 429:
            raise DomainError("WEB_BUSY", "웹검색 요청이 많아요. 잠시 후 다시 시도해 주세요.", 429)
        if response.status == 404:
            raise DomainError("WEB_NOT_CONFIGURED", "웹검색 추천 서버 설정을 확인해 주세요.", 503)
        if response.status == 400:
            error = json.loads(response.read(16384))
            if isinstance(error, dict) and error.get("code") == "FOOD_PREFERENCE_UNCLEAR":
                raise DomainError("FOOD_PREFERENCE_UNCLEAR", "먹고 싶은 음식 종류를 한식·양식·일식·중식·분식·아시아음식 중에서 알려 주세요. 예: 양식 먹고 싶어, 양식 말고 한식", 400)
            raise DomainError("INVALID_WEB_REQUEST", "목적지·음식 취향·필수 조건을 확인해 주세요.", 400)
        require(response.status == 200, "WEB_UNAVAILABLE", "추천 서버가 응답하지 못했어요.", 502)
        raw = response.read(1024 * 1024 + 1)
        require(len(raw) <= 1024 * 1024, "INVALID_WEB_RESPONSE", "추천 서버 응답이 너무 커요.", 502)
        data = json.loads(raw)
        require(isinstance(data, dict), "INVALID_WEB_RESPONSE", "추천 서버 응답을 확인할 수 없어요.", 502)
        return data
    except (OSError, http.client.HTTPException, ValueError):
        raise DomainError("WEB_UNAVAILABLE", "웹검색 추천 서버에 연결할 수 없어요.", 503) from None
    finally:
        connection.close()
