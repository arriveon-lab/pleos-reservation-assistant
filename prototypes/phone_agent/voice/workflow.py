"""Persist real-call claims and observations separately from deterministic MOCK results."""
import copy
import hashlib
import hmac
import json
import re
from datetime import datetime, timezone

from ..service import DomainError, identifier, require, required_conditions, timestamp
from .config import normalize_number


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
    if (not quote or re.search(r"다만|하지만|그런데|근데|대신|말고|됐지만|되었지만|"
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
        prompt = request["message"]
        named_request = re.search(
            rf"(?:예약자\s*)?이름은\s*{re.escape(name)}입니다(?:[.,!\s]|$)|"
            rf"(?<!\w){re.escape(name)}\s*(?:님\s*)?(?:이름|성함)?으로\s*예약", prompt)
        booking_request = re.search(
            r"예약\s*(?:을\s*)?(?:부탁(?:드립니다|드려요|드리겠습니다|드릴게요)|"
            r"(?:해|잡아)\s*주(?:세요|시겠어요|시겠습니까|실\s*수\s*있을까요))[.!?。！？\s]*$", prompt)
        # Availability, an unrelated question after the request, or a negative
        # question must not inherit the meaning of a positive booking request.
        if (not named_request or not booking_request
                or re.search(r"아니|말고|다른|변경|취소|대신|가능|안\s*(?:되|돼|될)|못", prompt)):
            continue
        later_replies = [e["message"] for e in events[index + 1:] if e["speaker"] == "restaurant"]
        later = " ".join(later_replies)
        pending = bool(later_replies and re.search(
            r"잠시|잠깐|아직|확인해\s*볼", later_replies[-1]))
        return not re.search(r"다만|하지만|그런데|근데|대신|말고|취소|불가|안\s*(?:돼|되|됩)|못|"
                             r"다른\s*(?:이름|시간|날짜)|이름.{0,10}아니", later) and not pending
    return False


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
        today = datetime.now(timezone.utc).date().isoformat()
        require(sum(c["createdAt"].startswith(today) for c in calls) < self.config.daily_calls,
                "CALL_LIMIT", "실전화 테스트의 일일 통화 상한에 도달했어요.")
        require(not any(c["status"] in ("RESERVED", "DISPATCHING", "ACTIVE", "RESULT_SAVED") for c in calls),
                "CALL_ACTIVE", "진행 중인 실제 통화가 있어요.")

    def _find(self, call_id):
        rows = self.service.db.execute(
            "SELECT owner,body FROM objects WHERE kind='live_call' AND id=?", (call_id,)).fetchall()
        require(len(rows) == 1, "NOT_FOUND", "실전화 작업을 찾을 수 없어요.", 404)
        return rows[0][0], json.loads(rows[0][1])

    def reserve(self, owner, booking_id, version, request_id):
        self.config.require_live()
        require(isinstance(request_id, str) and 1 <= len(request_id) <= 100,
                "INVALID_INPUT", "실전화 요청 키를 확인해 주세요.", 400)
        call_id = hashlib.sha256((owner + ":" + request_id).encode()).hexdigest()[:32]
        def operation():
            existing = self.service.db.execute(
                "SELECT body FROM objects WHERE owner=? AND kind='live_call' AND id=?", (owner, call_id)).fetchone()
            if existing:
                call = json.loads(existing[0])
                require(call["bookingId"] == booking_id and call["requestedVersion"] == version,
                        "IDEMPOTENCY_CONFLICT", "같은 요청 키의 예약·버전이 달라요.")
                return call, False
            booking = self.service._get(owner, "booking", booking_id)
            change = booking.get("change")
            changing = (booking["status"] == "CONFIRMED" and booking.get("mode") == "LIVE"
                        and change and change["status"] == "RECONFIRMING" and change["approved"])
            require(booking["version"] == version and (booking["status"] == "READY" or changing),
                    "INVALID_STATE", "승인된 최신 예약·변경에서만 실제 발신할 수 있어요.")
            trip = self.service._get(owner, "trip", booking["tripId"])
            require(changing or trip["version"] == booking["tripVersion"], "VERSION_CONFLICT", "승인 후 조건이 바뀌었어요.")
            target = self.config.restaurant_numbers.get(booking["restaurantId"])
            require(target in self.config.allowed_numbers, "TARGET_NOT_ALLOWED", "허용한 식당 테스트 번호를 설정해 주세요.")
            self._capacity()
            call = {"id": call_id, "bookingId": booking_id, "requestedVersion": version,
                    "direction": "outbound", "purpose": "CHANGE_CONFIRMATION" if changing else "BOOKING",
                    "status": "RESERVED", "target": target,
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

    def begin_inbound(self, caller, trunk_id, room):
        self.config.require_live()
        caller = normalize_number(caller)
        require(caller in self.config.allowed_numbers and trunk_id == self.config.inbound_trunk_id,
                "CALLER_NOT_ALLOWED", "허용한 수신 테스트 통화가 아니에요.", 403)
        restaurants = [key for key, number in self.config.restaurant_numbers.items() if number == caller]
        require(len(restaurants) == 1, "CALLER_UNMATCHED", "식당을 특정하지 못했어요.")
        def operation():
            self._capacity()
            call = {"id": identifier(), "direction": "inbound", "status": "ACTIVE",
                    "room": room, "restaurantId": restaurants[0], "bookingId": None,
                    "nonce": identifier(), "createdAt": timestamp(), "inviteRequested": False}
            self.service._put("inbound", "live_call", call)
            return call
        return self._atomic(operation)

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
                return self._context(booking)
            matches = []
            for row in self.service.db.execute("SELECT owner,body FROM objects WHERE kind='booking'"):
                booking = json.loads(row[1])
                if (booking["restaurantId"] == call["restaurantId"] and booking["status"] == "CONFIRMED" and booking.get("mode") == "LIVE"
                        and booking["reservationName"] == name and booking["date"] == day
                        and booking["confirmedTime"] == current_time):
                    matches.append((row[0], booking))
            if len(matches) != 1:
                return {"found": False, "message": "예약자 이름·날짜·시간을 다시 확인해 주세요."}
            new_owner, booking = matches[0]
            self.service.db.execute("DELETE FROM objects WHERE owner=? AND kind='live_call' AND id=?", (owner, call_id))
            call.update(bookingId=booking["id"], bookingVersion=booking["version"])
            self.service._put(new_owner, "live_call", call)
            return self._context(booking)
        return self._atomic(operation)

    @staticmethod
    def _context(booking):
        return {"found": True, "reservationName": booking["reservationName"], "date": booking["date"],
                "time": booking["confirmedTime"] or booking["requestedTime"],
                "conditions": copy.deepcopy(booking["approvedSnapshot"]),
                "requiredConditions": required_conditions(booking["approvedSnapshot"])}

    def context(self, call_id):
        with self.service.lock:
            owner, call = self._find(call_id)
            if not call.get("bookingId"):
                return {"found": False}
            context = self._context(self.service._get(owner, "booking", call["bookingId"]))
            context["purpose"] = call.get("purpose", "CALLBACK")
            if call.get("purpose") == "CHANGE_CONFIRMATION":
                booking = self.service._get(owner, "booking", call["bookingId"])
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
            events = [e for e in self.service._list(owner, "event")
                      if e["bookingId"] == booking["id"] and e.get("callId") == call_id]
            recent = [e["message"] for e in events if e["speaker"] == "restaurant"][-3:]
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
                      "noAdditionalCost": type(additional_cost) is int and additional_cost == 0,
                      "requiredConditionsConfirmed": required <= set(confirmed_requirements)}
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

    def request_change(self, call_id, proposed_time, reason):
        def operation():
            owner, call = self._find(call_id)
            require(call["direction"] == "inbound" and call.get("bookingId") and call["status"] == "ACTIVE",
                    "IDENTIFICATION_REQUIRED", "예약을 먼저 확인해야 해요.")
            if call.get("changeSaved"):
                return {"saved": True, "status": "AWAITING_USER"}
            booking = self.service._get(owner, "booking", call["bookingId"])
            require(booking["version"] == call["bookingVersion"], "VERSION_CONFLICT", "예약 상태가 바뀌었어요.")
            result = self.service._receive_callback(owner, {
                "restaurantId": call["restaurantId"], "reservationName": booking["reservationName"],
                "date": booking["date"], "currentTime": booking["confirmedTime"],
                "proposedTime": proposed_time, "reason": reason}, live=True)
            call["changeSaved"] = result.get("matched", False)
            self.service._put(owner, "live_call", call)
            return {"saved": call["changeSaved"], "status": "AWAITING_USER"}
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
                    booking.update(status="UNKNOWN", version=booking["version"] + 1,
                                   notice="실제 통화 결과가 확정되지 않았어요. 신규 예약 재발신을 막고 대조를 기다려요.")
                    self.service._put(owner, "booking", booking)
            call.update(status="ENDED", endedAt=timestamp(), endReason=reason)
            self.service._put(owner, "live_call", call)
        self._atomic(operation)
