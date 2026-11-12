"""Temporary caller registration for the authenticated phone test screen."""
import asyncio
import json
import re
from datetime import datetime, timedelta, timezone

from ..service import DomainError, require
from .config import ConfigurationError, normalize_number


def _window(service):
    row = service.db.execute(
        "SELECT owner,body FROM objects WHERE kind='callback_test_window' AND id='active'"
    ).fetchone()
    return (row[0], json.loads(row[1])) if row else (None, None)


def active_window(service):
    owner, window = _window(service)
    if window and window["expiresAt"] > datetime.now(timezone.utc).isoformat():
        return owner, window
    return None, None


def status(service, owner):
    with service.lock:
        registered_owner, window = active_window(service)
    if registered_owner != owner:
        return {"active": False}
    return {"active": True, "phoneLast4": window["phone"][-4:], "expiresAt": window["expiresAt"]}


async def _allow_inbound(config, phone):
    from livekit import api

    allowed = set(config.allowed_numbers)
    if phone is not None:
        allowed.add(phone)
    require(bool(allowed), "CALLBACK_TEST_UNAVAILABLE", "기존 수신 허용 설정을 확인해 주세요.", 503)
    try:
        async with asyncio.timeout(6):
            async with api.LiveKitAPI(config.livekit_url, config.api_key, config.api_secret) as client:
                trunk = await client.sip.update_inbound_trunk_fields(
                    config.inbound_trunk_id, allowed_numbers=sorted(allowed))
                if set(trunk.allowed_numbers) != allowed:
                    raise ValueError("Inbound allowlist did not match")
    except Exception:
        raise DomainError("CALLBACK_TEST_UNAVAILABLE", "수신 설정을 확인하지 못했어요. 다시 시도해 주세요.", 503) from None


def configure(service, config, owner, payload, *, update_inbound=_allow_inbound):
    action = payload.get("action")
    require(action in ("START", "STOP"), "INVALID_INPUT", "등록 또는 종료를 선택해 주세요.", 400)
    phone = None
    if action == "START":
        raw = payload.get("phoneNumber")
        require(isinstance(raw, str) and len(raw) <= 24,
                "INVALID_TEST_PHONE", "시험 휴대폰 번호를 확인해 주세요.", 400)
        try:
            phone = normalize_number(raw)
        except ConfigurationError:
            phone = ""
        require(re.fullmatch(r"\+8210\d{8}", phone), "INVALID_TEST_PHONE",
                "010으로 시작하는 국내 휴대폰 번호를 입력해 주세요.", 400)
    config.require_live()
    config.require_live("inbound")
    with service.lock:
        service.db.execute("BEGIN IMMEDIATE")
        try:
            current_owner, current = active_window(service)
            require(current_owner in (None, owner), "CALLBACK_TEST_ACTIVE",
                    "다른 브라우저에서 시험 중이에요. 기존 시험을 종료하거나 만료 뒤 시작해 주세요.")
            if action == "STOP":
                stored_owner, _ = _window(service)
                require(stored_owner == owner, "CALLBACK_TEST_NOT_FOUND",
                        "이 브라우저에서 등록한 시험 번호가 없어요.", 404)
            active_call = service.db.execute(
                "SELECT 1 FROM objects WHERE kind='live_call' "
                "AND json_extract(body,'$.status') IN ('RESERVED','DISPATCHING','ACTIVE','RESULT_SAVED') LIMIT 1"
            ).fetchone()
            require(not active_call, "CALL_ACTIVE", "진행 중인 통화를 마친 뒤 번호를 바꿔 주세요.")
            asyncio.run(update_inbound(config, phone))
            service.db.execute("DELETE FROM objects WHERE kind='callback_test_window' AND id='active'")
            if phone is not None:
                now = datetime.now(timezone.utc)
                started = current["startedAt"] if current and current["phone"] == phone else now.isoformat()
                service._put(owner, "callback_test_window", {
                    "id": "active", "phone": phone, "startedAt": started,
                    "expiresAt": (now + timedelta(hours=1)).isoformat(),
                })
            service.db.execute("COMMIT")
        except Exception:
            service.db.execute("ROLLBACK")
            raise
    return status(service, owner)


def match_booking(service, config, caller, called_number):
    owner, window = active_window(service)
    require(window and caller == window["phone"], "CALLER_NOT_ALLOWED",
            "등록된 시험 휴대폰에서 걸어 주세요.", 403)
    require(called_number and normalize_number(called_number) == normalize_number(config.telkit_number),
            "PHONE_BINDING_MISMATCH", "시험용 수신 번호가 일치하지 않아요.")
    calls = [call for call in service._list(owner, "live_call")
             if call["direction"] == "outbound" and call.get("purpose") == "BOOKING"
             and call.get("testPhoneEntered") and call["target"] == caller
             and call["createdAt"] >= window["startedAt"]]
    require(bool(calls), "CALLBACK_TEST_UNMATCHED", "이 브라우저에서 시험 발신으로 예약을 먼저 확정해 주세요.")
    call = max(calls, key=lambda item: item["createdAt"])
    booking = service._get(owner, "booking", call["bookingId"])
    require(call["status"] == "ENDED" and call.get("outcome") == "CONFIRMED"
            and booking.get("liveTest") and booking.get("mode") == "LIVE"
            and booking["status"] == "CONFIRMED",
            "CALLBACK_TEST_UNMATCHED", "가장 최근 시험전화에서 확정한 예약이 없어요.")
    return owner, booking
