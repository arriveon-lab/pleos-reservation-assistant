"""Transactional, owner-scoped reservation simulator using only the standard library."""
import copy
import hashlib
import json
import re
import sqlite3
import threading
import uuid
from datetime import date, datetime, timezone


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def identifier():
    return uuid.uuid4().hex


class DomainError(Exception):
    def __init__(self, code, message, status=409):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


def require(condition, code, message, status=409):
    if not condition:
        raise DomainError(code, message, status)


def text(value, maximum=100):
    require(isinstance(value, str) and 0 < len(value.strip()) <= maximum,
            "INVALID_INPUT", "입력한 문구의 길이를 확인해 주세요.", 400)
    return value.strip()


def integer(value, low, high):
    require(type(value) is int and low <= value <= high,
            "INVALID_INPUT", "인원과 개수의 범위를 확인해 주세요.", 400)
    return value


def conditions(raw):
    require(isinstance(raw, dict), "INVALID_INPUT", "동승자 조건을 확인해 주세요.", 400)
    size = integer(raw.get("partySize"), 1, 30)
    chairs = integer(raw.get("highChairCount", 0), 0, size)
    pets = raw.get("petAccompanied", "UNKNOWN")
    require(pets in ("YES", "NO", "UNKNOWN"), "INVALID_INPUT", "반려동물 동반을 확인해 주세요.", 400)
    step_free = raw.get("stepFree", False)
    require(type(step_free) is bool, "INVALID_INPUT", "접근성 조건을 확인해 주세요.", 400)
    dietary = raw.get("dietaryRequirements", "")
    require(isinstance(dietary, str) and len(dietary) <= 160,
            "INVALID_INPUT", "식사 조건은 160자 이내로 입력해 주세요.", 400)
    result = {"partySize": size, "highChairCount": chairs, "petAccompanied": pets,
              "stepFree": step_free, "dietaryRequirements": dietary.strip()}
    if "preferredCuisines" in raw:
        cuisines = raw["preferredCuisines"]
        require(isinstance(cuisines, list) and len(cuisines) <= 6
                and all(isinstance(item, str) and item in {"한식", "양식", "일식", "중식", "분식", "아시아음식"} for item in cuisines),
                "INVALID_INPUT", "음식 취향을 확인해 주세요.", 400)
        result["preferredCuisines"] = sorted(set(cuisines))
    for key in ("childFriendly", "parkingRequired"):
        if key in raw:
            require(type(raw[key]) is bool, "INVALID_INPUT", "필수 조건을 확인해 주세요.", 400)
            result[key] = raw[key]
    if "maxArrivalWaitMinutes" in raw:
        result["maxArrivalWaitMinutes"] = integer(raw["maxArrivalWaitMinutes"], 0, 120)
    return result


def required_conditions(value):
    result = []
    if value.get("childFriendly"):
        result.append("아동 동반 이용")
    if value.get("parkingRequired"):
        result.append("주차 가능")
    if "maxArrivalWaitMinutes" in value:
        result.append(f"도착 시 대기 {value['maxArrivalWaitMinutes']}분 이내")
    if value["highChairCount"]:
        result.append(f"유아의자 {value['highChairCount']}개")
    if value["petAccompanied"] == "YES":
        result.append("반려동물 동반")
    if value["stepFree"]:
        result.append("계단 없이 입장")
    if value["dietaryRequirements"]:
        result.append(f"식사 조건: {value['dietaryRequirements']}")
    return result


def valid_time(value):
    require(isinstance(value, str) and re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value),
            "INVALID_INPUT", "시간은 HH:MM으로 입력해 주세요.", 400)
    return value


class ReservationService:
    """Single-process prototype. SQLite transactions also serialize multiple connections."""

    ACTIONS = {"save_profile", "delete_profile", "create_trip", "update_trip", "create_booking",
               "create_web_test_booking",
               "approve_booking", "run_call", "receive_callback", "decide_change",
               "reconfirm_change", "verify_booking", "delete_all"}
    SCENARIOS = {"ACCEPT", "DECLINE", "AMBIGUOUS", "DISCONNECT", "REQUIREMENT_UNKNOWN",
                 "REQUIREMENT_UNAVAILABLE", "BUSY", "TIME_MISMATCH", "COST_REQUIRED", "ARS"}

    DEMO_RESTAURANTS = {"demo-garden": "가상 정원식당", "demo-table": "가상 식탁"}

    def __init__(self, database=":memory:", *, mock_restaurants=None, test_restaurants=None,
                 web_test_enabled=False):
        self.mock_restaurants = dict(mock_restaurants or {})
        self.test_restaurants = dict(test_restaurants or {})
        self.web_test_enabled = web_test_enabled
        self.test_mode = web_test_enabled or bool(self.test_restaurants)
        self.web_searches = {}
        restaurants = {key: item["name"] for key, item in self.test_restaurants.items()}
        self.restaurants = self.DEMO_RESTAURANTS | self.mock_restaurants | restaurants
        if len(self.restaurants) != len(self.DEMO_RESTAURANTS) + len(self.mock_restaurants) + len(restaurants):
            raise ValueError("식당 ID는 서로 겹칠 수 없어요.")
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(database), check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS objects (
                owner TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL,
                PRIMARY KEY(owner, kind, id));
            CREATE TABLE IF NOT EXISTS commands (
                owner TEXT NOT NULL, request_id TEXT NOT NULL, digest TEXT NOT NULL, result TEXT NOT NULL,
                PRIMARY KEY(owner, request_id));
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY, owner TEXT NOT NULL, created_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS deleted_live_call_usage (
                utc_day TEXT PRIMARY KEY, calls INTEGER NOT NULL);
        """)

    def close(self):
        self.db.close()

    def session(self, token=None):
        with self.lock:
            digest = hashlib.sha256((token or "").encode()).hexdigest()
            row = self.db.execute("SELECT owner FROM sessions WHERE token_hash=?", (digest,)).fetchone()
            if row:
                return row[0], token
            token = identifier() + identifier()
            owner = identifier()
            self.db.execute("INSERT INTO sessions VALUES(?,?,?)",
                            (hashlib.sha256(token.encode()).hexdigest(), owner, timestamp()))
            return owner, token

    def _put(self, owner, kind, value):
        self.db.execute("INSERT OR REPLACE INTO objects VALUES(?,?,?,?)",
                        (owner, kind, value["id"], json.dumps(value, ensure_ascii=False)))
        return value

    def _get(self, owner, kind, object_id):
        require(isinstance(object_id, str), "NOT_FOUND", "항목을 찾을 수 없어요.", 404)
        row = self.db.execute("SELECT body FROM objects WHERE owner=? AND kind=? AND id=?",
                              (owner, kind, object_id)).fetchone()
        require(row is not None, "NOT_FOUND", "현재 사용자에게 해당 항목이 없어요.", 404)
        return json.loads(row[0])

    def _list(self, owner, kind):
        return [json.loads(row[0]) for row in self.db.execute(
            "SELECT body FROM objects WHERE owner=? AND kind=? ORDER BY rowid", (owner, kind))]

    def _event(self, owner, booking_id, speaker, message, mode="MOCK", call_id=None):
        self._put(owner, "event", {"id": identifier(), "bookingId": booking_id,
                                  "speaker": speaker, "message": message, "mode": mode,
                                  "callId": call_id, "at": timestamp()})

    def state(self, owner):
        with self.lock:
            profiles = self._list(owner, "profile")
            calls = self._list(owner, "live_call")
            active_callbacks = {call["id"] for call in calls if call["direction"] == "inbound"
                                and call["status"] == "ACTIVE" and call.get("changeSaved")}
            bookings = self._list(owner, "booking")
            for booking in bookings:
                change = booking.get("change")
                if change and change.get("callId") in active_callbacks:
                    change["waitingOnPhone"] = change["status"] == "AWAITING_USER"
            return {"mode": "LIVE" if calls else "MOCK", "externalCalls": sum(bool(c.get("inviteRequested")) for c in calls),
                    "inboundCalls": sum(c.get("direction") == "inbound" for c in calls),
                    "personalPhoneLinked": bool(self._list(owner, "phone_binding")),
                    "profile": profiles[0] if profiles else None,
                    "trips": self._list(owner, "trip"), "bookings": bookings,
                    "callbacks": self._list(owner, "callback"), "testRestaurants": list(self.test_restaurants.values()),
                    "events": self._list(owner, "event")[-100:]}

    def command(self, owner, action, payload, request_id):
        require(isinstance(action, str) and action in self.ACTIONS, "INVALID_ACTION", "지원하지 않는 동작이에요.", 400)
        return self._command(owner, action, payload, request_id)

    def _command(self, owner, action, payload, request_id):
        require(isinstance(payload, dict), "INVALID_INPUT", "JSON 객체를 입력해 주세요.", 400)
        text(request_id, 100)
        digest = hashlib.sha256(json.dumps([action, payload], sort_keys=True).encode()).hexdigest()
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                old = self.db.execute("SELECT digest,result FROM commands WHERE owner=? AND request_id=?",
                                      (owner, request_id)).fetchone()
                if old:
                    require(old[0] == digest, "IDEMPOTENCY_CONFLICT", "같은 요청 키에 다른 내용이 있어요.")
                    self.db.execute("COMMIT")
                    return json.loads(old[1])
                result = getattr(self, "_" + action)(owner, payload)
                # Store result identifiers only, never duplicate profile/condition data in the ledger.
                self.db.execute("INSERT INTO commands VALUES(?,?,?,?)",
                                (owner, request_id, digest, json.dumps(result)))
                self.db.execute("COMMIT")
                return result
            except Exception:
                self.db.execute("ROLLBACK")
                raise

    def recommendation_attempt(self, owner, recommendation_id, payload=None, request_id=None):
        if payload is None:
            with self.lock:
                flow = self._get(owner, "recommendation_flow", recommendation_id)
                return self._get(owner, "recommendation_receipt", flow["latestAttemptId"])
        result = self._command(owner, "approve_recommendation", {
            **payload, "recommendationId": recommendation_id,
        }, request_id)
        with self.lock:
            return self._get(owner, "recommendation_receipt", result["id"])

    def _approve_recommendation(self, owner, payload):
        require(payload.get("approved") is True, "APPROVAL_REQUIRED", "이 후보의 예약을 승인해 주세요.", 400)
        check = payload.get("conditionScenario")
        availability = payload.get("availabilityScenario")
        scenario = payload.get("bookingScenario")
        require(check in ("MET", "UNMET", "UNKNOWN")
                and availability in ("AVAILABLE", "UNAVAILABLE", "UNKNOWN")
                and isinstance(scenario, str) and scenario in self.SCENARIOS - {
                    "REQUIREMENT_UNKNOWN", "REQUIREMENT_UNAVAILABLE",
                }, "INVALID_SCENARIO", "가상 식당의 응답을 선택해 주세요.", 400)
        roster = payload["candidates"]
        ordinal = payload.get("candidateOrdinal")
        selected = next((item for item in roster if item["ordinal"] == ordinal), None)
        require(type(ordinal) is int and selected is not None,
                "INVALID_CANDIDATE", "추천 목록의 후보를 선택해 주세요.", 400)
        scope = {key: payload[key] for key in ("date", "time", "reservationName", "conditions", "candidates")}
        recommendation_id = payload["recommendationId"]
        previous_id = payload.get("previousAttemptId")
        row = self.db.execute("SELECT body FROM objects WHERE owner=? AND kind=? AND id=?",
                              (owner, "recommendation_flow", recommendation_id)).fetchone()
        if row:
            flow = json.loads(row[0])
            previous = self._get(owner, "recommendation_receipt", flow["latestAttemptId"])
            require(previous["nextCandidate"] is not None,
                    "NO_FALLBACK", "다음 후보를 예약할 수 있는 상태가 아니에요.")
            require(previous_id == previous["attemptId"]
                    and ordinal == previous["nextCandidate"]["ordinal"],
                    "FALLBACK_APPROVAL_REQUIRED", "제시된 다음 후보를 새로 승인해 주세요.")
            require(flow["scope"] == scope, "APPROVAL_SCOPE_CHANGED", "추천할 때 확정한 예약 조건이 바뀌었어요.")
        else:
            require(previous_id is None, "FALLBACK_APPROVAL_REQUIRED", "첫 후보를 승인해 주세요.")
            trip = self._create_trip(owner, {"conditions": scope["conditions"]})
            flow = {"id": recommendation_id, "tripId": trip["id"], "scope": scope, "attemptedOrdinals": []}

        created = self._create_booking(owner, {
            **scope, "tripId": flow["tripId"], "tripVersion": 1, "restaurantId": selected["restaurantId"],
        })
        booking_id = created["id"]
        self._approve_booking(owner, {"bookingId": booking_id, "version": 1})
        booking = self._get(owner, "booking", booking_id)
        required = required_conditions(booking["approvedSnapshot"])
        self._event(owner, booking_id, "agent",
                    f"{booking['date']} {booking['requestedTime']}에 {booking['approvedSnapshot']['partySize']}명 예약이 가능한가요? "
                    + ", ".join(required))
        answers = {"MET": "필수 조건을 제공할 수 있습니다.", "UNMET": "필수 조건을 제공할 수 없습니다.",
                   "UNKNOWN": "필수 조건을 확인할 수 없습니다."}
        slots = {"AVAILABLE": "요청하신 시간에 예약이 가능합니다.",
                 "UNAVAILABLE": "요청하신 시간에 예약이 불가능합니다.", "UNKNOWN": "해당 시간은 확인이 필요합니다."}
        if scenario in ("BUSY", "ARS"):
            check = availability = "UNKNOWN"
            response = "[통화 중으로 연결되지 않음]" if scenario == "BUSY" else "[자동 안내만 응답함]"
        else:
            response = answers[check] + " " + slots[availability]
        self._event(owner, booking_id, "restaurant", response)
        booking_requested = check == "MET" and availability == "AVAILABLE"
        if booking_requested:
            self._run_call(owner, {"bookingId": booking_id, "version": booking["version"], "scenario": scenario},
                           prechecked=True)
            booking = self._get(owner, "booking", booking_id)
        else:
            declined = check == "UNMET" or availability == "UNAVAILABLE"
            status = "FAILED" if scenario == "BUSY" else "DECLINED" if declined else "UNKNOWN"
            checks = {"MET": "CONFIRMED", "UNMET": "UNMET", "UNKNOWN": "UNKNOWN"}
            booking.update(status=status, attempts=1, requirementChecks=dict.fromkeys(required, checks[check]),
                           version=booking["version"] + 1,
                           notice={"FAILED": "식당이 통화 중이에요.", "DECLINED": "예약 가능 조건이 충족되지 않았어요.",
                                   "UNKNOWN": "필수 조건이나 시간을 확인해야 해요."}[status])
            self._put(owner, "booking", booking)
        flow["attemptedOrdinals"].append(ordinal)
        next_candidate = None
        if booking["status"] == "DECLINED":
            next_candidate = next(({key: item[key] for key in ("ordinal", "name", "address")}
                                   for item in roster if item["ordinal"] not in flow["attemptedOrdinals"]), None)
        receipt = {
            "id": booking_id, "attemptId": booking_id, "mode": "MOCK", "candidateOrdinal": ordinal,
            "restaurantName": booking["restaurantName"], "status": booking["status"],
            "conditionStatus": check, "availabilityStatus": availability,
            "bookingRequested": booking_requested, "requestedTime": booking["requestedTime"],
            "confirmedTime": booking["confirmedTime"], "date": booking["date"],
            "partySize": booking["snapshot"]["partySize"], "requiredConditions": required,
            "requirementChecks": booking["requirementChecks"],
            "nextCandidate": next_candidate, "externalCalls": 0, "notice": booking["notice"],
            "conversation": [{"speaker": event["speaker"], "message": event["message"]}
                             for event in self._list(owner, "event") if event["bookingId"] == booking_id],
        }
        self._put(owner, "recommendation_receipt", receipt)
        self._put(owner, "recommendation_flow", {**flow, "latestAttemptId": booking_id})
        return {"id": booking_id}

    def _save_profile(self, owner, payload):
        require(payload.get("remember") is True, "STORAGE_CHOICE_REQUIRED",
                "재사용 저장을 선택해야 프로필을 저장할 수 있어요.", 400)
        value = {"id": "profile", "reservationName": text(payload.get("reservationName"), 40),
                 "template": conditions(payload.get("conditions")), "purpose": "NEXT_TRIP_INPUT",
                 "remember": True, "learningConsent": False, "updatedAt": timestamp()}
        # Health-related text is never copied into the reusable template.
        value["template"]["dietaryRequirements"] = ""
        self._put(owner, "profile", value)
        return {"id": "profile"}

    def _delete_profile(self, owner, payload):
        self.db.execute("DELETE FROM objects WHERE owner=? AND kind='profile'", (owner,))
        return {"deleted": "profile"}

    def _create_trip(self, owner, payload):
        trip = {"id": identifier(), "conditions": conditions(payload.get("conditions")),
                "version": 1, "confirmedAt": timestamp()}
        self._put(owner, "trip", trip)
        return {"id": trip["id"]}

    def _update_trip(self, owner, payload):
        trip = self._get(owner, "trip", payload.get("tripId"))
        require(trip["version"] == payload.get("version"), "VERSION_CONFLICT", "조건이 바뀌었어요. 다시 확인해 주세요.")
        trip.update(conditions=conditions(payload.get("conditions")), version=trip["version"] + 1,
                    confirmedAt=timestamp())
        self._put(owner, "trip", trip)
        return {"id": trip["id"]}

    def remember_web_search(self, owner, response, snapshot):
        snapshot = conditions({**snapshot, "preferredCuisines": response["result"].get("preferredCuisines", [])})
        expires_at = datetime.fromisoformat(response["validUntil"].replace("Z", "+00:00"))
        now = datetime.now(timezone.utc)
        search_id = identifier()
        with self.lock:
            self.web_searches = {key: value for key, value in self.web_searches.items() if value[1] > now}
            self.web_searches[owner] = (search_id, expires_at, snapshot, response["result"]["webRecommendations"])
        return search_id

    def _create_web_test_booking(self, owner, payload):
        require(self.web_test_enabled, "WEB_TEST_DISABLED", "웹검색 시험 예약을 사용할 수 없어요.", 403)
        search = self.web_searches.get(owner)
        require(search is not None and payload.get("searchId") == search[0]
                and datetime.now(timezone.utc) < search[1],
                "WEB_SEARCH_EXPIRED", "최신 웹검색에서 후보를 다시 선택해 주세요.")
        search_id, _, snapshot, candidates = search
        ordinal = integer(payload.get("candidateOrdinal"), 1, 3)
        candidate = next((item for item in candidates if item["ordinal"] == ordinal), None)
        require(candidate is not None, "INVALID_CANDIDATE", "이 검색 결과의 후보를 선택해 주세요.", 400)
        restaurant = {"id": f"web-{search_id}-{ordinal}", "name": candidate["name"],
                      "address": candidate["address"], "sourceUrl": candidate["identitySourceUrl"],
                      "venueCategory": candidate.get("venueCategory", "UNKNOWN")}
        if not self.db.execute("SELECT 1 FROM objects WHERE owner=? AND kind='trip' AND id=?",
                               (owner, search_id)).fetchone():
            self._put(owner, "trip", {"id": search_id, "version": 1, "conditions": snapshot,
                                      "confirmedAt": timestamp()})
        return self._create_booking(owner, {**payload, "tripId": search_id, "tripVersion": 1,
                                           "restaurantId": restaurant["id"]}, web_restaurant=restaurant)

    def _create_booking(self, owner, payload, *, web_restaurant=None):
        trip = self._get(owner, "trip", payload.get("tripId"))
        require(payload.get("tripVersion") == trip["version"], "VERSION_CONFLICT", "최신 동승자 조건을 확인해 주세요.")
        try:
            day = date.fromisoformat(payload.get("date", ""))
        except (ValueError, TypeError):
            raise DomainError("INVALID_INPUT", "예약 날짜를 확인해 주세요.", 400) from None
        require(day >= date.today(), "INVALID_INPUT", "지난 날짜는 예약할 수 없어요.", 400)
        name = text(payload.get("reservationName"), 40)
        restaurant = payload.get("restaurantId", "demo-garden")
        require(isinstance(restaurant, str) and (web_restaurant is not None or restaurant in self.restaurants),
                "INVALID_INPUT", "가상 식당을 선택해 주세요.", 400)
        for old in self._list(owner, "booking"):
            require(not (old["tripId"] == trip["id"] and old["status"] not in ("DECLINED", "FAILED")),
                    "ACTIVE_BOOKING_EXISTS", "이 여정에는 처리 중이거나 확정된 예약이 있어요.")
        booking = {"id": identifier(), "tripId": trip["id"], "tripVersion": trip["version"],
                   "restaurantId": restaurant,
                   "restaurantName": web_restaurant["name"] if web_restaurant else self.restaurants[restaurant],
                   "reservationName": name, "date": day.isoformat(), "requestedTime": valid_time(payload.get("time")),
                   "confirmedTime": None, "status": "AWAITING_APPROVAL", "snapshot": copy.deepcopy(trip["conditions"]),
                   "approvedSnapshot": None, "version": 1, "attempts": 0, "change": None,
                   "notice": "조건을 확인한 뒤 발신을 승인해 주세요.", "createdAt": timestamp()}
        if restaurant in self.mock_restaurants:
            booking["mockOnly"] = True
        if web_restaurant is not None:
            booking.update(liveTest=web_restaurant, webTest=True)
        elif restaurant in self.test_restaurants:
            booking["liveTest"] = self.test_restaurants[restaurant]
        self._put(owner, "booking", booking)
        return {"id": booking["id"]}

    def _approve_booking(self, owner, payload):
        booking = self._get(owner, "booking", payload.get("bookingId"))
        trip = self._get(owner, "trip", booking["tripId"])
        self._version(booking, payload)
        require(booking["status"] == "AWAITING_APPROVAL", "INVALID_STATE", "발신 승인 대기 상태가 아니에요.")
        require(trip["version"] == booking["tripVersion"], "VERSION_CONFLICT", "동승자 조건이 바뀌었어요. 요청을 다시 만드세요.")
        require(booking["snapshot"]["petAccompanied"] != "UNKNOWN", "CONDITIONS_UNCONFIRMED", "반려동물 동반 여부를 확인해 주세요.")
        booking.update(status="READY", approvedSnapshot=copy.deepcopy(booking["snapshot"]),
                       approvedAt=timestamp(), version=booking["version"] + 1,
                       notice="승인 완료. 가상 통화 또는 별도로 준비한 실제 테스트 발신을 선택하세요.")
        self._put(owner, "booking", booking)
        return {"id": booking["id"]}

    @staticmethod
    def _version(booking, payload):
        require(booking["version"] == payload.get("version"), "VERSION_CONFLICT", "예약이 바뀌었어요. 새로고침 후 확인해 주세요.")

    def _run_call(self, owner, payload, *, prechecked=False):
        booking = self._get(owner, "booking", payload.get("bookingId"))
        self._version(booking, payload)
        require(not booking.get("liveTest"), "LIVE_TEST_ONLY", "이 시험 예약은 등록 휴대폰으로 테스트해 주세요.")
        require(booking["status"] == "READY", "INVALID_STATE", "승인된 새 예약에서만 발신할 수 있어요. 미확인 예약은 대조가 필요해요.")
        trip = self._get(owner, "trip", booking["tripId"])
        require(trip["version"] == booking["tripVersion"], "VERSION_CONFLICT", "승인 후 동승자 조건이 바뀌었어요. 새 조건을 재확인해야 해요.")
        scenario = payload.get("scenario")
        require(isinstance(scenario, str) and scenario in self.SCENARIOS, "INVALID_INPUT", "식당 응답 시나리오를 선택해 주세요.", 400)
        snapshot = booking["approvedSnapshot"]
        required = required_conditions(snapshot)
        booking["attempts"] += 1
        if prechecked:
            self._event(owner, booking["id"], "agent", f"그럼 {booking['requestedTime']}, {snapshot['partySize']}명, {booking['reservationName']} 이름으로 예약해 주세요.")
        else:
            self._event(owner, booking["id"], "agent", f"안녕하세요. AI 예약 비서입니다. {booking['date']} {booking['requestedTime']}, {snapshot['partySize']}명, {booking['reservationName']} 이름으로 예약 가능할까요?")
        if required and not prechecked:
            self._event(owner, booking["id"], "agent", "필수 조건도 확인 부탁드립니다. " + ", ".join(required))
        outcomes = {
            "ACCEPT": ("CONFIRMED", "네, 요청하신 시간과 인원으로 예약 확정했고 필수 조건도 가능합니다.", "예약이 확정됐어요."),
            "DECLINE": ("DECLINED", "죄송하지만 예약이 불가능합니다.", "식당이 예약을 거절했어요."),
            "AMBIGUOUS": ("UNKNOWN", "잠시만요, 확인해 볼게요.", "명확한 수락이 없어 확인이 필요해요."),
            "DISCONNECT": ("UNKNOWN", "[예약 수락 여부를 저장하기 전에 통화 연결이 끊김]", "연결이 끊겼어요. 신규 예약 재발신이 아닌 기존 예약 대조가 필요해요."),
            "REQUIREMENT_UNKNOWN": ("UNKNOWN", "시간은 되는데 요청 조건은 확인해 봐야 해요.", "필수 조건을 확인하지 못했어요."),
            "REQUIREMENT_UNAVAILABLE": ("DECLINED", "시간은 되지만 필요한 조건을 제공할 수 없어요.", "필수 조건이 충족되지 않아 확정하지 않았어요."),
            "BUSY": ("FAILED", "[통화 중으로 연결되지 않음]", "연결 실패. 자동 재발신하지 않았어요."),
            "TIME_MISMATCH": ("UNKNOWN", "요청한 시간 말고 다른 시간만 가능합니다.", "승인한 시간과 달라 확정하지 않았어요."),
            "COST_REQUIRED": ("UNKNOWN", "예약하려면 추가 비용이 필요해요.", "추가 비용 승인이 없어 확정하지 않았어요."),
            "ARS": ("UNKNOWN", "[자동 안내] 영업시간은 오전 열한 시부터입니다.", "자동 안내를 예약 수락으로 처리하지 않았어요."),
        }
        status, response, notice = outcomes[scenario]
        self._event(owner, booking["id"], "restaurant", response)
        booking.update(status=status, notice=notice, version=booking["version"] + 1,
                       requirementChecks={key: "CONFIRMED" if prechecked or status == "CONFIRMED" else "UNKNOWN" for key in required})
        if status == "CONFIRMED":
            booking["confirmedTime"] = booking["requestedTime"]
            self._event(owner, booking["id"], "agent", "예약 내용을 확인했습니다. 변경 사항이 있으면 이 번호로 연락 부탁드립니다. AI 예약 비서가 받아 전달하겠습니다.")
        else:
            self._event(owner, booking["id"], "system", notice)
        self._put(owner, "booking", booking)
        return {"id": booking["id"]}

    def _receive_callback(self, owner, payload, live=False, call_id=None, evidence=None,
                          booking_id=None):
        restaurant = payload.get("restaurantId")
        require(isinstance(restaurant, str) and (live or restaurant in self.restaurants),
                "INVALID_INPUT", "가상 식당을 선택해 주세요.", 400)
        # These are the facts supplied by the simulated caller, not a list read to them.
        name, day = payload.get("reservationName"), payload.get("date")
        matches = [b for b in self._list(owner, "booking") if b["restaurantId"] == restaurant
                   and b["reservationName"] == name and b["date"] == day
                   and b["confirmedTime"] == payload.get("currentTime")
                   and b["status"] == "CONFIRMED"
                   and (b.get("mode") == "LIVE" and b["id"] == booking_id
                        if live else b.get("mode") != "LIVE")]
        callback = {"id": identifier(), "status": "UNMATCHED", "bookingId": None, "at": timestamp(),
                    "mode": "LIVE" if live else "MOCK", "callId": call_id, "category": "TIME_CHANGE"}
        if len(matches) != 1:
            self._put(owner, "callback", callback)
            return {"id": callback["id"], "matched": False}
        booking = matches[0]
        proposed = valid_time(payload.get("proposedTime"))
        require(proposed != booking["confirmedTime"], "INVALID_INPUT", "변경할 시간을 다르게 입력해 주세요.", 400)
        change = booking.get("change")
        same_pending_request = (live and change and change["status"] == "AWAITING_USER"
                                and change["proposedTime"] == proposed)
        if not same_pending_request:
            require(not change or change["status"] in ("APPLIED", "REJECTED", "EXPIRED"),
                    "CHANGE_PENDING", "이미 확인 중인 변경 요청이 있어요.")
            change = {"id": identifier(), "status": "AWAITING_USER", "originalTime": booking["confirmedTime"],
                      "proposedTime": proposed, "reason": text(payload.get("reason", "식당 사정"), 160),
                      "approved": False, "createdAt": timestamp()}
        if live:
            change["callId"] = call_id
        booking.update(change=change, version=booking["version"] + 1, notice="식당에서 시간 변경을 요청했어요. 기존 예약은 유지하고 사용자 확인을 기다려요.")
        callback.update(status="MATCHED", bookingId=booking["id"])
        if live:
            callback.update(proposedTime=proposed, summary=change["reason"], evidence=evidence)
        self._put(owner, "callback", callback)
        self._put(owner, "booking", booking)
        if not live:
            self._event(owner, booking["id"], "restaurant", f"{proposed}로 변경할 수 있을까요?")
            self._event(owner, booking["id"], "agent", "예약자에게 확인 후 다시 연락드리겠습니다.")
        return {"id": callback["id"], "matched": True}

    def _decide_change(self, owner, payload):
        booking = self._get(owner, "booking", payload.get("bookingId"))
        self._version(booking, payload)
        change = booking.get("change")
        require(not booking.get("reviewRequired"), "CALLBACK_REVIEW_REQUIRED",
                "식당의 취소·예약 불가 통보를 먼저 확인해야 해요.")
        require(change is not None and change["status"] == "AWAITING_USER", "INVALID_STATE", "승인 대기인 변경 요청이 없어요.")
        require(type(payload.get("approve")) is bool, "INVALID_INPUT", "승인 또는 거절을 선택해 주세요.", 400)
        change.update(approved=payload["approve"], status="RECONFIRMING" if payload["approve"] else "REJECTED")
        booking.update(version=booking["version"] + 1, notice="사용자 승인 완료. 식당 재확인이 필요해요." if payload["approve"] else "변경을 거절했어요. 기존 확정 시간은 유지해요. 식당의 유지 가능 여부는 별도 확인 대상이에요.")
        self._put(owner, "booking", booking)
        return {"id": booking["id"]}

    def _reconfirm_change(self, owner, payload):
        booking = self._get(owner, "booking", payload.get("bookingId"))
        require(booking.get("mode") != "LIVE", "LIVE_RESULT_REQUIRED", "실제 변경은 음성 에이전트의 재확인 결과로만 반영해요.")
        self._version(booking, payload)
        change = booking.get("change")
        require(change is not None and change["status"] == "RECONFIRMING" and change["approved"],
                "INVALID_STATE", "사용자가 승인한 변경만 식당에 재확인할 수 있어요.")
        result = payload.get("result")
        require(result in ("ACCEPT", "DECLINE", "DISCONNECT"), "INVALID_INPUT", "재확인 응답을 선택해 주세요.", 400)
        if result == "ACCEPT":
            booking["confirmedTime"] = change["proposedTime"]
            change["status"] = "APPLIED"
            notice = "식당 재확인 완료. 변경된 시간으로 확정했어요."
        elif result == "DECLINE":
            change["status"] = "REJECTED"
            notice = "식당이 변경을 수락하지 않았어요. 기존 시간은 보존하고 유지 가능 여부를 확인해 주세요."
        else:
            change["status"] = "UNKNOWN"
            notice = "재확인 통화가 끊겼어요. 기존 시간 보존, 변경 결과 대조가 필요해요."
        booking.update(version=booking["version"] + 1, notice=notice)
        self._put(owner, "booking", booking)
        self._event(owner, booking["id"], "system", notice)
        return {"id": booking["id"]}

    def _verify_booking(self, owner, payload):
        booking = self._get(owner, "booking", payload.get("bookingId"))
        require(booking.get("mode") != "LIVE", "LIVE_RESULT_REQUIRED", "실전화 결과는 별도 대조가 필요해요.")
        self._version(booking, payload)
        require(booking["status"] == "UNKNOWN", "INVALID_STATE", "미확인 신규 예약만 대조할 수 있어요.")
        result = payload.get("result")
        require(result in ("EXISTS", "NOT_EXISTS"), "INVALID_INPUT", "대조 결과를 선택해 주세요.", 400)
        require(result != "EXISTS" or payload.get("allConditionsConfirmed") is True,
                "CONDITIONS_UNCONFIRMED", "기존 예약과 필수 조건이 모두 확인되어야 해요.")
        booking.update(status="CONFIRMED" if result == "EXISTS" else "DECLINED",
                       confirmedTime=booking["requestedTime"] if result == "EXISTS" else None,
                       version=booking["version"] + 1, notice="가상 대조 결과를 반영했어요. 신규 예약을 재발신하지 않았어요.")
        if result == "EXISTS":
            booking["requirementChecks"] = {key: "CONFIRMED" for key in required_conditions(booking["approvedSnapshot"])}
        self._put(owner, "booking", booking)
        self._event(owner, booking["id"], "system", booking["notice"])
        return {"id": booking["id"]}

    def _delete_all(self, owner, payload):
        require(payload.get("confirm") is True, "CONFIRM_REQUIRED", "전체 삭제를 확인해 주세요.", 400)
        calls = self._list(owner, "live_call")
        require(not any(c["status"] != "ENDED" for c in calls),
                "LIVE_CALL_ACTIVE", "진행 중인 실제 통화를 종료한 뒤 전체 삭제할 수 있어요.")
        today = timestamp()[:10]
        used = sum(call["createdAt"].startswith(today) for call in calls)
        if used:
            self.db.execute("""
                INSERT INTO deleted_live_call_usage VALUES (?, ?)
                ON CONFLICT(utc_day) DO UPDATE SET calls = calls + excluded.calls
            """, (today, used))
        self.db.execute("DELETE FROM objects WHERE owner=?", (owner,))
        self.db.execute("DELETE FROM commands WHERE owner=?", (owner,))
        self.db.execute("DELETE FROM sessions WHERE owner=?", (owner,))
        self.web_searches.pop(owner, None)
        return {"deleted": "all-local-data"}
