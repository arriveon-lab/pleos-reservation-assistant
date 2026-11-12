"""Persist real-call claims and observations separately from deterministic MOCK results."""
import copy
import hashlib
import hmac
import json
import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from ..service import DomainError, identifier, require, required_conditions, timestamp
from . import callback_test
from .config import ConfigurationError, normalize_number


NO_EXTRA_COST_REPLY = re.compile(
    r"(?:네|예|아니요)?\s*[,，]?\s*(?:추가\s*(?:비용|요금|금)|별도\s*(?:비용|요금))"
    r"(?:은|는|이|가)?\s*(?:따로\s*)?(?:없(?:습니다|어요|네요)|안\s*(?:들어요|듭니다))[.!…。！\s]*"
)
COST_QUESTION = re.compile(
    r"(?:추가\s*(?:비용|요금|금)|별도\s*(?:비용|요금)|예약금|보증금)"
    r"[^.!?。！？]{0,36}(?:까요|나요|습니까|인가요|세요|시겠어요)\s*[.?!。！？]?$"
)
SHORT_NO_EXTRA_COST_REPLY = re.compile(
    r"(?:(?:네|예|아니요)\s*[,，]?\s*)?(?:(?:비용|요금)(?:은|는|이|가)?\s*)?(?:따로\s*)?"
    r"(?:없습니다|없어요|없네요|안\s*(?:들어요|듭니다))[.!…。！\s]*"
)
SHORT_COST_REPLY = re.compile(
    r"(?:(?:네|예|아니요)\s*[,，]?\s*)?(?:(?:비용|요금)(?:은|는|이|가)?\s*)?(?:따로\s*)?"
    r"(?:없습니다|없어요|없네요|있습니다|있어요|들어요|안\s*(?:들어요|듭니다))[.!。！\s]*"
)
FEE_OR_REFUSAL = re.compile(
    r"(?:\d\s*(?:만\s*|천\s*)?|[일이삼사오육칠팔구십백천만억]+\s*)원|"
    r"(?:비용|요금|금액|추가금|예약금|보증금)(?:은|는|이|가)?\s*(?:따로\s*)?"
    r"(?:있습니다|있어요|붙습니다|붙어요|듭니다|들어요|발생합니다|발생해요|필요합니다|필요해요)|"
    r"불가|어렵습니다|어려워요|안\s*(?:돼요|됩니다)"
)
FEE_AMOUNT = (
    r"(?:(?:(?:1|일)\s*인당|인당|한\s*명당)\s*)?"
    r"(?:[1-9]\d*(?:,\d{3})*(?:\s*[만천백])?|[일이삼사오육칠팔구십백천만억]+(?:\s+[일이삼사오육칠팔구십백천만억]+)*)\s*원"
)
FEE_AFFIRMATION = r"있습니다|있어요|들어요|듭니다|붙어요|붙습니다|발생합니다|발생해요|필요합니다|필요해요"
REQUIRED_FEE_REPLY = re.compile(
    r"(?:추가\s*(?:비용|요금|금)|별도\s*(?:비용|요금))(?:은|는|이|가)?\s*(?:따로\s*)?"
    rf"(?:{FEE_AMOUNT}(?:\s*(?:입니다|이에요|예요|{FEE_AFFIRMATION}))?|{FEE_AFFIRMATION})[.!。！\s]*$"
)
CALLBACK_CONFIRM_QUESTION = "예약자가 동의했습니다. 말씀하신 시간으로 변경 확정해 주시겠어요?"


def reports_required_fee(reply):
    # Ordinary menu prices and conditional surcharges are not grounds to end a call.
    return bool(REQUIRED_FEE_REPLY.search(reply) and not re.search(
        r"선택|원하시면|원하면|시키면|주문하면|추가하면|이용하면|이용\s*시|경우|또는|혹시|인가요|말고|없(?:어요|습니다)",
        reply,
    ))


def spoken_reservation_name(name, *, split_syllables=True):
    compact = "".join(name.split())
    if not re.fullmatch(r"[가-힣]+", compact):
        return name
    return " ".join(compact) if split_syllables else compact


def named_booking_request(prompt, name):
    name_pattern = r"\s*".join(map(re.escape, spoken_reservation_name(name).split()))
    named = re.search(
        rf"(?:예약자\s*)?이름은\s*{name_pattern}\s*입니다(?:[.,!\s]|$)|"
        rf"(?<!\w){name_pattern}\s*(?:님\s*)?(?:이름|성함)?으로\s*예약", prompt)
    request = re.search(
        r"예약\s*(?:을\s*)?(?:부탁(?:드립니다|드려요|드리겠습니다|드릴게요)|"
        r"(?:해|잡아)\s*주(?:세요|시겠어요|시겠습니까|실\s*수\s*있을까요))[.!?。！？\s]*$", prompt)
    return bool(named and request and not re.search(
        r"아니|말고|다른|변경|취소|대신|가능|안\s*(?:되|돼|될)|못", prompt))


def acknowledged_booking_name(events, evidence, name):
    """Bind the model's acceptance evidence to a named booking request.

    This verifies context, not arbitrary Korean semantics. record_outcome also
    requires the model's accepted=True and the approved booking fields to match.
    """
    def compact(text):
        return re.sub(r"[\s,.!?~…。！？]+", "", text)

    quote = compact(evidence)
    # Acceptance intent comes from the model, not a fixed list of replies.
    # Keep a conservative veto for explicit qualifications, refusal, and pending checks.
    if (not quote or SHORT_NO_EXTRA_COST_REPLY.fullmatch(evidence.strip())
            or re.search(r"다만|하지만|그런데|근데|대신|말고|됐지만|되었지만|"
                              r"아니|불가|취소|안\s*(?:돼|되|됩)|못|잠시|잠깐|아직|"
                              r"확인해\s*볼|확인\s*중|다른\s*(?:이름|시간|날짜)|"
                              r"추가\s*비용.{0,8}(?:있|발생|필요)", evidence)):
        return False
    # Keep the existing recent-evidence window. Match the whole reply, so quoting
    # only '네' from '네, 다만...' cannot remove a qualification or correction.
    recent = [i for i, event in enumerate(events) if event["speaker"] == "restaurant"][-3:]
    for index in reversed(recent):
        if index == 0:
            continue
        reply, request = events[index], events[index - 1]
        if compact(reply["message"]) != quote or request["speaker"] != "agent":
            continue
        # Availability, an unrelated question after the request, or a negative
        # question must not inherit the meaning of a positive booking request.
        if not named_booking_request(request["message"], name):
            continue
        later_replies = [e["message"] for e in events[index + 1:] if e["speaker"] == "restaurant"]
        later = " ".join(later_replies)
        pending = bool(later_replies and re.search(
            r"잠시|잠깐|아직|확인해\s*볼", later_replies[-1]))
        return not re.search(r"다만|하지만|그런데|근데|대신|말고|취소|불가|안\s*(?:돼|되|됩)|못|"
                             r"다른\s*(?:이름|시간|날짜)|이름.{0,10}아니", later) and not pending
    return False


def latest_no_extra_cost_reply(events):
    """Short fee answers depend on the question they answer."""
    if not events or events[-1]["speaker"] != "restaurant":
        return False
    reply = events[-1]["message"].strip()
    if NO_EXTRA_COST_REPLY.fullmatch(reply):
        return True
    previous_agent = next((event["message"] for event in reversed(events[:-1])
                           if event["speaker"] == "agent"), "")
    return bool(COST_QUESTION.search(previous_agent.strip()) and SHORT_NO_EXTRA_COST_REPLY.fullmatch(reply))


class LiveWorkflow:
    def __init__(self, service, config):
        self.service, self.config = service, config

    def _atomic(self, operation):
        with self.service.lock:
            self.service.db.execute("BEGIN IMMEDIATE")
            try:
                result = operation()
                self.service.db.execute("COMMIT")
                return result
            except Exception:
                self.service.db.execute("ROLLBACK")
                raise

    def _calls(self):
        return [json.loads(row[0]) for row in self.service.db.execute(
            "SELECT body FROM objects WHERE kind='live_call'")]

    def _capacity(self):
        calls = self._calls()
        if self.config.daily_calls:
            today = datetime.now(timezone.utc).date().isoformat()
            deleted = self.service.db.execute(
                "SELECT calls FROM deleted_live_call_usage WHERE utc_day=?", (today,)).fetchone()
            used = sum(c["createdAt"].startswith(today) for c in calls) + (deleted[0] if deleted else 0)
            require(used < self.config.daily_calls,
                    "CALL_LIMIT", "실전화 테스트의 일일 통화 상한에 도달했어요.")
        require(not any(c["status"] in ("RESERVED", "DISPATCHING", "ACTIVE", "RESULT_SAVED") for c in calls),
                "CALL_ACTIVE", "진행 중인 실제 통화가 있어요.")

    def _find(self, call_id):
        rows = self.service.db.execute(
            "SELECT owner,body FROM objects WHERE kind='live_call' AND id=?", (call_id,)).fetchall()
        require(len(rows) == 1, "NOT_FOUND", "실전화 작업을 찾을 수 없어요.", 404)
        return rows[0][0], json.loads(rows[0][1])

    def reserve(self, owner, booking_id, version, request_id, *, test_phone_number=None):
        self.config.require_live()
        require(isinstance(request_id, str) and 1 <= len(request_id) <= 100,
                "INVALID_INPUT", "실전화 요청 키를 확인해 주세요.", 400)
        entered = None
        if test_phone_number is not None:
            require(self.service.test_mode and self.config.test_phone_input, "TEST_PHONE_INPUT_DISABLED",
                    "이 화면에서는 시험전화 번호를 입력할 수 없어요.", 403)
            require(isinstance(test_phone_number, str) and len(test_phone_number) <= 24,
                    "INVALID_TEST_PHONE", "010으로 시작하는 국내 휴대폰 번호를 확인해 주세요.", 400)
            try:
                entered = normalize_number(test_phone_number)
            except ConfigurationError:
                entered = ""
            require(re.fullmatch(r"\+8210[0-9]{8}", entered), "INVALID_TEST_PHONE",
                    "010으로 시작하는 국내 휴대폰 번호를 확인해 주세요.", 400)
        call_id = hashlib.sha256((owner + ":" + request_id).encode()).hexdigest()[:32]
        def operation():
            existing = self.service.db.execute(
                "SELECT body FROM objects WHERE owner=? AND kind='live_call' AND id=?", (owner, call_id)).fetchone()
            if existing:
                call = json.loads(existing[0])
                previous_entered = call["target"] if call.get("testPhoneEntered") else None
                require(call["bookingId"] == booking_id and call["requestedVersion"] == version
                        and previous_entered == entered,
                        "IDEMPOTENCY_CONFLICT", "같은 요청 키의 예약·버전·시험전화 번호가 달라요.")
                return call, False
            booking = self.service._get(owner, "booking", booking_id)
            require(not booking.get("mockOnly"), "MOCK_RECOMMENDATION_ONLY",
                    "가상 추천으로 만든 예약은 모의 통화에서만 실행할 수 있어요.", 403)
            change = booking.get("change")
            changing = (booking["status"] == "CONFIRMED" and booking.get("mode") == "LIVE"
                        and not booking.get("reviewRequired") and change
                        and change["status"] == "RECONFIRMING" and change["approved"])
            require(booking["version"] == version and (booking["status"] == "READY" or changing),
                    "INVALID_STATE", "승인된 최신 예약·변경에서만 실제 발신할 수 있어요.")
            trip = self.service._get(owner, "trip", booking["tripId"])
            require(changing or trip["version"] == booking["tripVersion"], "VERSION_CONFLICT", "승인 후 조건이 바뀌었어요.")
            if booking.get("liveTest") or self.service.test_mode:
                web_test = booking.get("webTest") is True and self.service.web_test_enabled
                require(booking.get("liveTest") and not changing
                        and (web_test or booking["liveTest"] == self.service.test_restaurants.get(booking["restaurantId"])),
                        "TEST_RESTAURANT_REQUIRED", "이 실행에서는 현재 시험 목록의 새 예약만 발신할 수 있어요.", 403)
                if self.config.test_phone_input:
                    require(entered is not None, "TEST_PHONE_REQUIRED", "시험전화를 받을 휴대폰 번호를 입력해 주세요.", 400)
                    target = entered
                else:
                    target = self.config.test_mobile
                    require(target is not None, "TEST_MOBILE_REQUIRED", "시험용 등록 휴대폰 한 대를 확인해 주세요.", 403)
            else:
                target = self.config.restaurant_numbers.get(booking["restaurantId"])
            if self.config.soniox_trial:
                require(booking.get("liveTest") and not changing and target == self.config.test_mobile,
                        "SONIOX_TEST_ONLY", "Soniox는 등록한 휴대폰으로 거는 시험전화에서만 사용할 수 있어요.", 403)
            if entered is None:
                require(target in self.config.allowed_numbers, "TARGET_NOT_ALLOWED", "허용한 식당 테스트 번호를 설정해 주세요.")
            if booking.get("pleosPairedTest"):
                from ..paired_phone import registered_target
                require(self.config.self_service_test and self.config.test_phone_input
                        and entered and entered == registered_target(self.service, owner, booking["pleosPairedTest"]),
                        "PAIRING_REQUIRED", "현재 앱에 연결된 시험 번호만 사용할 수 있어요.", 403)
            elif self.config.self_service_test:
                registered_owner, window = callback_test.active_window(self.service)
                require(window and registered_owner == owner and entered == window["phone"],
                        "CALLBACK_TEST_REGISTRATION_REQUIRED", "같은 브라우저에서 등록한 시험 휴대폰으로 발신해 주세요.", 403)
            self._capacity()
            call = {"id": call_id, "bookingId": booking_id, "requestedVersion": version,
                    "direction": "outbound", "purpose": "CHANGE_CONFIRMATION" if changing else "BOOKING",
                    "status": "RESERVED", "target": target, "testPhoneEntered": entered is not None,
                    "restaurantId": booking["restaurantId"], "nonce": identifier(),
                    "room": "arriveon-out-" + call_id, "createdAt": timestamp(), "inviteRequested": False}
            booking.update(status="CONFIRMED" if changing else "CALLING", version=version + 1,
                           mode="LIVE", liveCallId=call_id, notice="실제 전화 연결을 준비하고 있어요.")
            if changing:
                booking["change"]["status"] = "CALLING"
            call["bookingVersion"] = booking["version"]
            self.service._put(owner, "booking", booking)
            self.service._put(owner, "live_call", call)
            return call, True
        return self._atomic(operation)

    def mark_dispatching(self, call_id):
        def operation():
            owner, call = self._find(call_id)
            require(call["status"] == "RESERVED", "ALREADY_DISPATCHED", "이미 요청한 통화를 다시 발신하지 않아요.")
            call.update(status="DISPATCHING", inviteRequested=True)
            self.service._put(owner, "live_call", call)
            return call
        return self._atomic(operation)

    def authorize_outbound_worker(self, call_id, nonce, room):
        with self.service.lock:
            owner, call = self._find(call_id)
            require(isinstance(nonce, str) and hmac.compare_digest(call["nonce"], nonce)
                    and call["room"] == room and call["status"] in ("DISPATCHING", "ACTIVE"),
                    "WORKER_CONTEXT_DENIED", "통화 작업의 문맥을 확인할 수 없어요.", 403)
            return owner, call

    def bind_inbound_number(self, owner, number, trunk_id):
        """Operator-only enrollment after number ownership has been verified.

        No HTTP/voice tool exposes this method. Never move a number between owners.
        """
        number = normalize_number(number)
        require(number.startswith("+8270") and trunk_id == self.config.inbound_trunk_id,
                "INVALID_PHONE_BINDING", "전용 수신 번호와 trunk 설정을 확인해 주세요.")
        def operation():
            rows = self.service.db.execute(
                "SELECT owner,body FROM objects WHERE kind='phone_binding' AND id=?",
                (number,)).fetchall()
            require(not rows or len(rows) == 1 and rows[0][0] == owner
                    and json.loads(rows[0][1])["trunkId"] == trunk_id,
                    "PHONE_ALREADY_BOUND", "이미 연결된 번호를 다른 사용자나 trunk로 바꿀 수 없어요.")
            if rows:
                return {"bound": True, "created": False}
            require(bool(self.service._list(owner, "booking")),
                    "OWNER_NOT_FOUND", "기존 예약을 가진 소유 세션을 확인해 주세요.")
            require(all(c["status"] == "ENDED" for c in self._calls()),
                    "CALL_ACTIVE", "전용 번호 연결은 진행 중인 통화가 없을 때 설정해 주세요.")
            self.service._put(owner, "phone_binding", {
                "id": number, "trunkId": trunk_id, "createdAt": timestamp()})
            return {"bound": True, "created": True}
        return self._atomic(operation)

    def _inbound_owner(self, trunk_id, called_number):
        bindings = [(row[0], json.loads(row[1])) for row in self.service.db.execute(
            "SELECT owner,body FROM objects WHERE kind='phone_binding'")]
        on_trunk = [item for item in bindings if item[1]["trunkId"] == trunk_id]
        if not called_number:
            require(not on_trunk, "CALLED_NUMBER_REQUIRED", "수신된 서비스 번호를 확인할 수 없어요.")
            return None
        number = normalize_number(called_number)
        matching = [item for item in bindings if item[1]["id"] == number]
        if not matching:
            require(not on_trunk, "PHONE_BINDING_MISMATCH", "전용 수신 번호가 일치하지 않아요.")
            return None
        require(len(matching) == 1 and matching[0][1]["trunkId"] == trunk_id,
                "PHONE_BINDING_MISMATCH", "전용 수신 번호 연결을 확인할 수 없어요.")
        return matching[0][0]

    def _callback_test_booking(self, caller, called_number, restaurant_id, bound_owner):
        require(called_number and self.config.telkit_number
                and normalize_number(called_number) == normalize_number(self.config.telkit_number),
                "PHONE_BINDING_MISMATCH", "시험용 수신 번호가 일치하지 않아요.")
        rows = self.service.db.execute("SELECT owner,body FROM objects WHERE kind='booking' AND id=?",
                                       (self.config.callback_test_booking_id,)).fetchall()
        require(len(rows) == 1, "CALLBACK_TEST_UNMATCHED", "시험 예약을 찾을 수 없어요.")
        owner, body = rows[0]
        booking = json.loads(body)
        require((bound_owner is None or bound_owner == owner) and booking.get("liveTest")
                and booking["restaurantId"] == restaurant_id and booking["status"] == "CONFIRMED"
                and booking.get("mode") == "LIVE" and booking.get("liveCallId"),
                "CALLBACK_TEST_UNMATCHED", "회신 시험의 확정 예약이 일치하지 않아요.")
        outbound = self.service._get(owner, "live_call", booking["liveCallId"])
        require(outbound["direction"] == "outbound" and outbound["status"] == "ENDED"
                and outbound.get("outcome") == "CONFIRMED" and outbound["bookingId"] == booking["id"]
                and outbound["target"] == caller,
                "CALLBACK_TEST_UNMATCHED", "확정 시험전화의 수신 번호와 회신 번호가 일치하지 않아요.")
        return owner, booking

    def begin_inbound(self, caller, trunk_id, room, called_number=None):
        self.config.require_live("inbound")
        caller = normalize_number(caller)
        if self.config.self_service_test:
            require(trunk_id == self.config.inbound_trunk_id, "CALLER_NOT_ALLOWED", "시험 수신 연결을 확인해 주세요.", 403)

            def test_callback():
                self._capacity()
                owner, booking = callback_test.match_booking(self.service, self.config, caller, called_number)
                return self._save_inbound(room, booking["restaurantId"], owner, booking)

            return self._atomic(test_callback)
        require(caller in self.config.allowed_numbers and trunk_id == self.config.inbound_trunk_id,
                "CALLER_NOT_ALLOWED", "허용한 수신 테스트 통화가 아니에요.", 403)
        restaurants = [key for key, number in self.config.restaurant_numbers.items() if number == caller]
        require(len(restaurants) == 1, "CALLER_UNMATCHED", "식당을 특정하지 못했어요.")
        def operation():
            bound_owner = self._inbound_owner(trunk_id, called_number)
            self._capacity()
            matches = []
            if self.config.callback_test_booking_id:
                bound_owner, booking = self._callback_test_booking(caller, called_number, restaurants[0], bound_owner)
                matches = [booking]
            elif bound_owner:
                matches = [b for b in self.service._list(bound_owner, "booking")
                           if b["restaurantId"] == restaurants[0] and b["status"] == "CONFIRMED"
                           and b.get("mode") == "LIVE" and not b.get("liveTest")]
            booking = matches[0] if len(matches) == 1 else None
            return self._save_inbound(room, restaurants[0], bound_owner, booking)
        return self._atomic(operation)

    def _save_inbound(self, room, restaurant_id, owner, booking):
        call = {"id": identifier(), "direction": "inbound", "status": "ACTIVE",
                "purpose": "CALLBACK", "ownerBound": owner is not None,
                "room": room, "restaurantId": restaurant_id, "bookingId": None,
                "nonce": identifier(), "createdAt": timestamp(), "inviteRequested": False}
        if booking:
            call.update(bookingId=booking["id"], bookingVersion=booking["version"])
        self.service._put(owner or "inbound", "live_call", call)
        return call

    def identify(self, call_id, name, day, current_time):
        def operation():
            owner, call = self._find(call_id)
            require(call["direction"] == "inbound", "INVALID_STATE", "회신 통화에서만 예약을 찾을 수 있어요.")
            require(call["status"] == "ACTIVE", "INVALID_STATE", "종료된 통화예요.")
            if call.get("bookingId"):
                booking = self.service._get(owner, "booking", call["bookingId"])
                require(booking["reservationName"] == name and booking["date"] == day
                        and booking["confirmedTime"] == current_time,
                        "BOOKING_LOCKED", "확인한 예약을 다른 예약으로 바꿀 수 없어요.")
                return {**self._context(booking), "purpose": "CALLBACK",
                        "personalAssistant": bool(call.get("ownerBound"))}
            matches = []
            for row in self.service.db.execute("SELECT owner,body FROM objects WHERE kind='booking'"):
                booking = json.loads(row[1])
                if ((not call.get("ownerBound") or row[0] == owner)
                        and booking["restaurantId"] == call["restaurantId"] and booking["status"] == "CONFIRMED"
                        and booking.get("mode") == "LIVE" and not booking.get("liveTest")
                        and booking["reservationName"] == name and booking["date"] == day
                        and booking["confirmedTime"] == current_time):
                    matches.append((row[0], booking))
            if len(matches) != 1:
                return {"found": False, "message": "예약자 이름·날짜·시간을 다시 확인해 주세요."}
            new_owner, booking = matches[0]
            self.service.db.execute("DELETE FROM objects WHERE owner=? AND kind='live_call' AND id=?", (owner, call_id))
            call.update(bookingId=booking["id"], bookingVersion=booking["version"])
            self.service._put(new_owner, "live_call", call)
            for event in self.service._list(owner, "unidentified_call_event"):
                if event["callId"] == call_id:
                    self.service.db.execute("DELETE FROM objects WHERE owner=? AND kind=? AND id=?",
                                            (owner, "unidentified_call_event", event["id"]))
                    event["bookingId"] = booking["id"]
                    self.service._put(new_owner, "event", event)
            return {**self._context(booking), "purpose": "CALLBACK",
                    "personalAssistant": bool(call.get("ownerBound"))}
        return self._atomic(operation)

    @staticmethod
    def _context(booking):
        change = booking.get("change")
        context = {"found": True, "reservationName": booking["reservationName"], "date": booking["date"],
                "time": booking["confirmedTime"] or booking["requestedTime"],
                "conditions": copy.deepcopy(booking["approvedSnapshot"]),
                "reviewRequired": bool(booking.get("reviewRequired")),
                "pendingChange": ({key: change[key] for key in
                                   ("status", "originalTime", "proposedTime", "approved")}
                                  if change else None),
                "requiredConditions": required_conditions(booking["approvedSnapshot"])}
        return context

    def context(self, call_id):
        with self.service.lock:
            owner, call = self._find(call_id)
            if not call.get("bookingId"):
                return {"found": False, "purpose": "CALLBACK",
                        "personalAssistant": bool(call.get("ownerBound")),
                        "today": datetime.now(ZoneInfo("Asia/Seoul")).date().isoformat()}
            booking = self.service._get(owner, "booking", call["bookingId"])
            context = self._context(booking)
            context["purpose"] = call.get("purpose", "CALLBACK")
            context["personalAssistant"] = bool(call.get("ownerBound"))
            if context["purpose"] == "BOOKING" and booking.get("liveTest"):
                context["testCall"] = True
            if context["purpose"] == "BOOKING":
                context["spokenReservationName"] = spoken_reservation_name(
                    booking["reservationName"], split_syllables=not self.config.soniox_trial)
            if call.get("purpose") == "CHANGE_CONFIRMATION":
                context["time"] = booking["change"]["proposedTime"]
                context["originalTime"] = booking["confirmedTime"]
            return context

    def transcript(self, call_id, speaker, message):
        if not isinstance(message, str) or not message.strip():
            return
        with self.service.lock:
            owner, call = self._find(call_id)
            if call.get("bookingId"):
                self.service._event(owner, call["bookingId"], speaker, message[:2000], mode="LIVE", call_id=call_id)
            elif call["direction"] == "inbound":
                pending = [e for e in self.service._list(owner, "unidentified_call_event")
                           if e["callId"] == call_id]
                if len(pending) >= 20:
                    self.service.db.execute("DELETE FROM objects WHERE owner=? AND kind=? AND id=?",
                                            (owner, "unidentified_call_event", pending[0]["id"]))
                self.service._put(owner, "unidentified_call_event", {
                    "id": identifier(), "callId": call_id, "speaker": speaker,
                    "message": message[:2000], "mode": "LIVE", "at": timestamp()})

    def _call_events(self, owner, call_id):
        rows = self.service.db.execute(
            "SELECT body FROM objects WHERE owner=? AND kind='event' "
            "AND json_extract(body, '$.callId')=? ORDER BY rowid", (owner, call_id))
        return [json.loads(body) for (body,) in rows]

    def booking_requested(self, call_id):
        with self.service.lock:
            owner, call = self._find(call_id)
            booking = self.service._get(owner, "booking", call["bookingId"])
            return any(event["speaker"] == "agent" and
                       named_booking_request(event["message"], booking["reservationName"])
                       for event in self._call_events(owner, call_id))

    def booking_waiting_for_request(self, call_id):
        with self.service.lock:
            owner, call = self._find(call_id)
            booking = self.service._get(owner, "booking", call["bookingId"])
            return self._waiting_for_request(booking, self._call_events(owner, call_id))

    @staticmethod
    def _waiting_for_request(booking, events):
        return (booking.get("liveTest") and booking["status"] == "CALLING" and latest_no_extra_cost_reply(events)
                and not any(event["speaker"] == "agent"
                            and named_booking_request(event["message"], booking["reservationName"])
                            for event in events))

    def cost_reply_needs_clarification(self, call_id):
        with self.service.lock:
            owner, _ = self._find(call_id)
            events = self._call_events(owner, call_id)
        if len(events) < 2 or events[-1]["speaker"] != "restaurant":
            return False
        question = next((event["message"] for event in reversed(events[:-1])
                         if event["speaker"] == "agent"), "")
        if not COST_QUESTION.search(question.strip()):
            return False
        reply = events[-1]["message"].strip()
        return not (NO_EXTRA_COST_REPLY.fullmatch(reply) or SHORT_COST_REPLY.fullmatch(reply)
                    or FEE_OR_REFUSAL.search(reply))

    def record_diagnostics(self, call_id, diagnostics):
        def operation():
            owner, call = self._find(call_id)
            call["audioDiagnostics"] = dict(diagnostics)
            self.service._put(owner, "live_call", call)
        self._atomic(operation)

    def record_outcome(self, call_id, *, accepted, day, time, reservation_name, people, additional_cost,
                       confirmed_requirements, evidence):
        def operation():
            owner, call = self._find(call_id)
            require(call["direction"] == "outbound" and call["status"] in ("DISPATCHING", "ACTIVE"),
                    "INVALID_STATE", "새 예약 발신에서만 결과를 기록할 수 있어요.")
            booking = self.service._get(owner, "booking", call["bookingId"])
            changing = call.get("purpose") == "CHANGE_CONFIRMATION"
            require(booking["version"] == call["bookingVersion"]
                    and (booking["status"] == "CALLING" or changing and booking["change"]["status"] == "CALLING"),
                    "VERSION_CONFLICT", "예약 상태가 달라 결과를 자동 반영하지 않았어요.")
            events = self._call_events(owner, call_id)
            restaurant_replies = [e["message"] for e in events if e["speaker"] == "restaurant"]
            recent = restaurant_replies[-3:]
            require(isinstance(evidence, str) and bool(evidence.strip())
                    and any(evidence in item for item in recent),
                    "EVIDENCE_REQUIRED", "식당의 실제 발화에서 확인 근거를 인용해야 해요.")
            snapshot = booking["approvedSnapshot"]
            required = set(required_conditions(snapshot))
            require(isinstance(confirmed_requirements, list) and all(isinstance(x, str) for x in confirmed_requirements),
                    "INVALID_INPUT", "필수 조건 확인 목록을 확인해 주세요.", 400)
            negative = bool(re.search(r"안\s*(돼|되|됩|됐|될)|불가|아니|아직|미확정|못\s*(해|하|받)|확인해\s*볼|잠시만", evidence))
            qualified = bool(re.search(
                r"다만|하지만|됐지만|되었지만|그런데|근데|대신|말고|다른\s*(?:시간|날짜|이름)", evidence))
            explicit = bool(re.search(r"예약.{0,20}(확정|완료|됐|되었|해.{0,3}드)|잡아.{0,5}(드|둘)", evidence))
            contextual = (accepted is True and not changing
                          and acknowledged_booking_name(events, evidence, booking["reservationName"]))
            checks = {"modelAccepted": accepted is True,
                      "acceptanceEvidence": explicit or contextual,
                      "noNegativeOrQualification": not negative and not qualified,
                      "dateMatches": day == booking["date"],
                      "reservationNameMatches": reservation_name == booking["reservationName"],
                      "nameGroundedInDialogue": reservation_name in " ".join(recent) or contextual,
                      "timeMatches": time == (booking["change"]["proposedTime"]
                                               if changing else booking["requestedTime"]),
                      "partySizeMatches": type(people) is int and people == snapshot["partySize"],
                      "noAdditionalCost": (type(additional_cost) is int and additional_cost == 0
                                           and not (not changing and any(map(reports_required_fee, restaurant_replies)))),
                      "requiredConditionsConfirmed": required <= set(confirmed_requirements)}
            if booking.get("liveTest") and not changing:
                # Advance only when the approved details and required conditions match.
                if self._waiting_for_request(booking, events):
                    ready = all(checks[key] for key in ("dateMatches", "reservationNameMatches", "timeMatches",
                                                       "partySizeMatches", "noAdditionalCost",
                                                       "requiredConditionsConfirmed"))
                    raise DomainError(
                        "BOOKING_REQUEST_READY" if ready else "BOOKING_ACCEPTANCE_REQUIRED",
                        "추가 비용이 없다는 답변입니다. 아직 남은 필수 조건을 확인하고, 모두 맞으면 "
                        "승인된 예약자 이름으로 예약을 부탁한 뒤 상대의 답을 기다리세요.")
                if evidence in recent[-1] and latest_no_extra_cost_reply(events):
                    raise DomainError(
                        "BOOKING_ACCEPTANCE_REQUIRED",
                        "추가 비용이 없다는 답변입니다. 아직 남은 필수 조건을 확인하고, 모두 맞으면 "
                        "승인된 예약자 이름으로 예약을 부탁한 뒤 상대의 답을 기다리세요.")
                if accepted is True and (not checks["acceptanceEvidence"] or not checks["nameGroundedInDialogue"]):
                    raise DomainError(
                        "BOOKING_ACCEPTANCE_REQUIRED",
                        "예약 수락을 아직 확인하지 못했습니다. 필수 조건과 비용 확인을 마쳤다면 "
                        "승인된 예약자 이름으로 예약을 부탁한 뒤, 상대가 수락할 때까지 기다리세요.")
            verified = all(checks.values())
            status = "CONFIRMED" if verified else "DECLINED" if accepted is False and negative else "UNKNOWN"
            if changing:
                if verified:
                    booking["confirmedTime"] = booking["change"]["proposedTime"]
                booking["change"]["status"] = "APPLIED" if verified else "REJECTED" if accepted is False and negative else "UNKNOWN"
                booking.update(version=booking["version"] + 1, notice="실제 변경 재확인 결과를 저장했어요.")
            else:
                booking.update(status=status, mode="LIVE", version=booking["version"] + 1,
                               confirmedTime=booking["requestedTime"] if verified else None,
                               notice="실제 통화에서 예약 조건을 확인했어요." if verified else "실제 통화 결과를 저장했어요. 확정되지 않은 조건은 확인이 필요해요.",
                               requirementChecks={key: "CONFIRMED" if verified else "UNKNOWN" for key in required})
            call.update(status="RESULT_SAVED", outcome=status)
            call["confirmationChecks"] = checks  # No raw tool arguments or personal data.
            call["confirmationBasis"] = ("contextual_acknowledgement" if contextual else "explicit_statement") if verified else None
            self.service._put(owner, "booking", booking)
            self.service._put(owner, "live_call", call)
            return {"saved": True, "status": status}
        return self._atomic(operation)

    def _callback_receipt(self, call_id, evidence, payload):
        owner, call = self._find(call_id)
        require(call["direction"] == "inbound" and call.get("bookingId") and call["status"] == "ACTIVE",
                "IDENTIFICATION_REQUIRED", "예약을 먼저 확인해야 해요.")
        recent = [e for e in self.service._list(owner, "event")
                  if e.get("callId") == call_id and e["speaker"] == "restaurant"][-3:]
        compact = lambda value: re.sub(r"[\s,.!?~…。！？]+", "", value)
        matching = [e for e in recent if isinstance(evidence, str) and compact(evidence)
                    and compact(e["message"]) == compact(evidence)]
        require(bool(matching), "EVIDENCE_REQUIRED", "식당이 실제로 말한 최근 전체 발화를 인용해 주세요.")
        actual = matching[-1]["message"]
        digest = hashlib.sha256(json.dumps([payload, actual], sort_keys=True).encode()).hexdigest()
        if call.get("receiptDigest"):
            require(call["receiptDigest"] == digest, "IDEMPOTENCY_CONFLICT",
                    "같은 회신에서 이미 접수한 내용과 달라 자동 덮어쓰지 않았어요.")
            return owner, call, None, actual, digest
        booking = self.service._get(owner, "booking", call["bookingId"])
        require(booking["version"] == call["bookingVersion"], "VERSION_CONFLICT", "예약 상태가 바뀌었어요.")
        return owner, call, booking, actual, digest

    def request_change(self, call_id, proposed_time, reason, evidence=None):
        def operation():
            owner, call, booking, actual, digest = self._callback_receipt(
                call_id, evidence, ["TIME_CHANGE", proposed_time, reason])
            if booking is None:
                return {"saved": True, "status": "AWAITING_USER", "id": call["callbackId"]}
            result = self.service._receive_callback(owner, {
                "restaurantId": call["restaurantId"], "reservationName": booking["reservationName"],
                "date": booking["date"], "currentTime": booking["confirmedTime"],
                "proposedTime": proposed_time, "reason": reason}, live=True,
                call_id=call_id, evidence=actual, booking_id=booking["id"])
            call["changeSaved"] = result.get("matched", False)
            call.update(receiptDigest=digest, callbackId=result["id"], outcome="CHANGE_REQUESTED")
            self.service._put(owner, "live_call", call)
            return {"saved": call["changeSaved"], "status": "AWAITING_USER", "id": result["id"]}
        return self._atomic(operation)

    def callback_change_decision(self, call_id):
        with self.service.lock:
            owner, call = self._find(call_id)
            if call["direction"] != "inbound" or call["status"] != "ACTIVE" or not call.get("changeSaved"):
                return None
            booking = self.service._get(owner, "booking", call["bookingId"])
            change = booking.get("change") or {}
            return change["status"] if change.get("callId") == call_id and not booking.get("reviewRequired") else None

    def confirm_callback_change(self, call_id, evidence):
        def operation():
            require(self.callback_change_decision(call_id) == "RECONFIRMING", "INVALID_STATE",
                    "승인된 통화 중 변경 요청을 확인할 수 없어요.")
            owner, call = self._find(call_id)
            booking = self.service._get(owner, "booking", call["bookingId"])
            change = booking["change"]
            require(change.get("approved") is True, "INVALID_STATE", "사용자 승인이 필요해요.")
            events = [event for event in self.service._list(owner, "event") if event.get("callId") == call_id]
            normalize = lambda value: re.sub(r"[\s,.!?~…。！？]+", "", value)
            answer = normalize(evidence)
            require(len(events) >= 2 and events[-1]["speaker"] == "restaurant"
                    and normalize(events[-1]["message"]) == answer
                    and events[-2]["speaker"] == "agent"
                    and normalize(events[-2]["message"]) == normalize(CALLBACK_CONFIRM_QUESTION)
                    and re.fullmatch(r"(?:네|예|(?:네|예)?(?:알겠습니다|(?:예약|변경)?(?:확정)?(?:됐습니다|되었습니다|했습니다)))", answer),
                    "EVIDENCE_REQUIRED", "식당이 실제 변경을 확정했는지 확인해 주세요.")
            booking.update(confirmedTime=change["proposedTime"], version=booking["version"] + 1,
                           notice="식당이 통화 중 변경을 확인했어요.")
            change["status"] = "APPLIED"
            call["outcome"] = "CHANGE_CONFIRMED"
            self.service._put(owner, "booking", booking)
            self.service._put(owner, "live_call", call)
            return {"saved": True, "status": "APPLIED"}
        return self._atomic(operation)

    def request_note(self, call_id, category, summary, evidence):
        require(category in ("CANCELLATION", "UNAVAILABLE", "OTHER"),
                "INVALID_INPUT", "지원하는 회신 분류를 확인해 주세요.", 400)
        require(isinstance(summary, str) and 1 <= len(summary.strip()) <= 320,
                "INVALID_INPUT", "회신 요약을 확인해 주세요.", 400)
        def operation():
            owner, call, booking, actual, digest = self._callback_receipt(
                call_id, evidence, [category, summary])
            if booking is None:
                return {"saved": True, "status": "NEEDS_REVIEW", "id": call["callbackId"]}
            callback = {"id": identifier(), "bookingId": booking["id"], "callId": call_id,
                        "status": "NEEDS_REVIEW", "category": category, "summary": summary.strip(),
                        "evidence": actual, "mode": "LIVE", "at": timestamp()}
            booking.update(version=booking["version"] + 1,
                           notice="식당 회신을 접수했어요. 변경·취소를 완료한 것은 아니며 확인이 필요해요.")
            if category in ("CANCELLATION", "UNAVAILABLE"):
                booking["reviewRequired"] = True
            self.service._put(owner, "callback", callback)
            self.service._put(owner, "booking", booking)
            call.update(receiptDigest=digest, callbackId=callback["id"], outcome="CALLBACK_RECORDED")
            self.service._put(owner, "live_call", call)
            return {"saved": True, "status": "NEEDS_REVIEW", "id": callback["id"]}
        return self._atomic(operation)

    def finish(self, call_id, reason="ended"):
        def operation():
            owner, call = self._find(call_id)
            if call.get("bookingId") and call["direction"] == "outbound":
                booking = self.service._get(owner, "booking", call["bookingId"])
                if call.get("purpose") == "CHANGE_CONFIRMATION" and booking["change"]["status"] == "CALLING":
                    booking["change"]["status"] = "UNKNOWN"
                    booking.update(version=booking["version"] + 1, notice="변경 통화 결과가 미확인이에요. 원 시간을 보존해요.")
                    self.service._put(owner, "booking", booking)
                elif booking["status"] == "CALLING":
                    notice = {
                        "additional_fee_reported": "식당에서 추가 요금을 안내해 예약을 확정하지 않았어요.",
                        "additional_fee_unconfirmed": "추가 요금 여부를 확인하지 못해 예약을 확정하지 않았어요.",
                    }.get(reason, "실제 통화 결과가 확정되지 않았어요. 신규 예약 재발신을 막고 대조를 기다려요.")
                    booking.update(status="UNKNOWN", version=booking["version"] + 1, notice=notice)
                    self.service._put(owner, "booking", booking)
            call.update(status="ENDED", endedAt=timestamp(), endReason=reason)
            self.service._put(owner, "live_call", call)
        self._atomic(operation)
