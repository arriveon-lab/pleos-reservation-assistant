#!/usr/bin/env python3
"""Exercise the recommendation-to-phone HTTP APIs with two local processes and fictional data."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from prototypes.phone_agent.service import ReservationService

BASE = "/api/experimental/destination-recommendations"
FIXTURE = ROOT / "server/src/test/resources/places/seoul-catalog-fictional.json"
JAR = ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar"
SCENARIOS = ReservationService.SCENARIOS | {"SLOT_UNAVAILABLE", "SLOT_UNKNOWN"}
CONTRACT = json.loads((ROOT / "prototypes/phone_agent/openapi.json").read_text())
RECEIPT = Draft202012Validator(
    CONTRACT["components"]["schemas"]["MockPhoneReceipt"], format_checker=FormatChecker())


def request(port, method, path, body=None, token=None, *, key=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
    try:
        connection.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = connection.getresponse()
        value = json.load(response)
        if response.status != expected:
            raise RuntimeError(f"{method} {path}: expected HTTP {expected}, got {response.status}: {value}")
        if response.status == 200 and (
            path.endswith("/phone-reservations") or path.startswith("/api/internal/mock-recommendation-bookings/")
        ):
            RECEIPT.validate(value)
        return value
    finally:
        connection.close()


def shortlist(port, token):
    payload = json.loads((ROOT / "api/examples/destination-recommendation-request.json").read_text())
    payload["viewId"] = str(uuid.uuid4())
    job = request(port, "POST", BASE, payload, token)["taskId"]
    path = f"{BASE}/{job}"
    for _ in range(100):
        polled = request(port, "GET", path, token=token)
        if polled["state"] == "RESULTS":
            listing = polled["result"]["listing"]
            if listing["state"] == "RESULTS" and listing["provider"] == "SEOUL_OPEN_DATA":
                return job, listing
        if polled["state"] not in ("QUEUED", "RUNNING"):
            break
        time.sleep(0.05)
    raise RuntimeError(f"가상 추천 결과를 받을 수 없어요 ({polled['state']}).")


def approval(ordinal, scenario, previous=None):
    tomorrow = (datetime.now(ZoneInfo("Asia/Seoul")) + timedelta(days=1)).replace(
        hour=19, minute=0, second=0, microsecond=0)
    return {
        "candidateOrdinal": ordinal, "approved": True,
        "reservationAt": tomorrow.isoformat(), "reservationName": "가상 예약자",
        "previousAttemptId": previous,
        "conditionScenario": {"REQUIREMENT_UNKNOWN": "UNKNOWN", "REQUIREMENT_UNAVAILABLE": "UNMET"}.get(
            scenario, "MET"),
        "availabilityScenario": {"SLOT_UNAVAILABLE": "UNAVAILABLE", "SLOT_UNKNOWN": "UNKNOWN"}.get(
            scenario, "AVAILABLE"),
        "bookingScenario": scenario if scenario not in {
            "REQUIREMENT_UNKNOWN", "REQUIREMENT_UNAVAILABLE", "SLOT_UNAVAILABLE", "SLOT_UNKNOWN",
        } else "ACCEPT",
    }


def verify(port, phone_port, token):
    def start():
        job, listing = shortlist(port, token)
        assert 2 <= len(listing["candidates"]) <= 3
        return f"{BASE}/{job}/phone-reservations"

    def post(path, body, expected=200, key=None):
        return request(port, "POST", path, body, token, key=key or uuid.uuid4().hex, expected=expected)

    path = start()
    first_body = approval(2, "REQUIREMENT_UNAVAILABLE")
    request(port, "POST", path, first_body, key=uuid.uuid4().hex, expected=401)
    request(phone_port, "GET", f"/api/internal/mock-recommendation-bookings/{path.split('/')[-2]}",
            token=token, expected=401)
    post(path, {**first_body, "approved": False}, expected=400)
    post(path, {**first_body, "approved": "true"}, expected=400)
    post(path, {**first_body, "candidateOrdinal": 2.5}, expected=400)
    post(path, {**first_body, "candidateOrdinal": 99}, expected=400)
    request(port, "GET", path, token=token, expected=404)
    first_key = uuid.uuid4().hex
    first = post(path, first_body, key=first_key)
    assert first["status"] == "DECLINED" and first["bookingRequested"] is False
    assert first["nextCandidate"]["ordinal"] == 1 and first["externalCalls"] == 0
    assert request(port, "GET", path, token=token) == first
    assert post(path, first_body, key=first_key) == first
    assert post(path, {**first_body, "conditionScenario": "MET"}, expected=409,
                key=first_key)["code"] == "IDEMPOTENCY_CONFLICT"

    next_body = {**first_body, "candidateOrdinal": 1, "previousAttemptId": first["attemptId"],
                 "conditionScenario": "MET"}
    assert post(path, {**next_body, "previousAttemptId": None}, expected=409)["code"] == "FALLBACK_APPROVAL_REQUIRED"
    assert post(path, {**next_body, "candidateOrdinal": 2}, expected=409)["code"] == "FALLBACK_APPROVAL_REQUIRED"
    post(path, {**next_body, "approved": False}, expected=400)
    assert post(path, {**next_body, "reservationName": "바뀐 이름"}, expected=409)["code"] == "APPROVAL_SCOPE_CHANGED"
    assert request(port, "GET", path, token=token) == first
    second_key = uuid.uuid4().hex
    with ThreadPoolExecutor(max_workers=2) as pool:
        second, replay = list(pool.map(lambda _: post(path, next_body, key=second_key), range(2)))
    assert second == replay
    assert second["status"] == "CONFIRMED" and second["attemptId"] != first["attemptId"]
    assert second["bookingRequested"] is True and second["nextCandidate"] is None
    assert second["partySize"] == 3 and second["confirmedTime"] == "19:00"
    assert second["requiredConditions"] == [
        "아동 동반 이용", "주차 가능", "도착 시 대기 15분 이내", "유아의자 1개", "계단 없이 입장",
    ]
    assert "제공할 수 있습니다" in second["conversation"][1]["message"]
    assert "예약해 주세요" in second["conversation"][2]["message"]
    assert post(path, next_body, expected=409)["code"] == "NO_FALLBACK"
    assert request(port, "GET", path, token=token) == second
    assert post(path, first_body, key=first_key) == first

    other = request(port, "POST", "/api/v1/anonymous-sessions", {})["accessToken"]
    request(port, "GET", path, token=other, expected=404)
    request(port, "POST", path, first_body, other, key=uuid.uuid4().hex, expected=404)

    cases = (
        ("SLOT_UNAVAILABLE", "DECLINED", False, True),
        ("DECLINE", "DECLINED", True, True),
        ("REQUIREMENT_UNKNOWN", "UNKNOWN", False, False),
        ("SLOT_UNKNOWN", "UNKNOWN", False, False),
        ("AMBIGUOUS", "UNKNOWN", True, False),
        ("DISCONNECT", "UNKNOWN", True, False),
        ("BUSY", "FAILED", False, False),
        ("ARS", "UNKNOWN", False, False),
    )
    for scenario, status, booked, fallback in cases:
        path = start()
        result = post(path, approval(1, scenario))
        assert result["status"] == status and result["bookingRequested"] is booked
        assert (result["nextCandidate"] is not None) is fallback and result["externalCalls"] == 0
        if not fallback:
            error = post(path, approval(2, "ACCEPT", result["attemptId"]), expected=409)
            assert error["code"] == "NO_FALLBACK" and request(port, "GET", path, token=token) == result

    job, listing = shortlist(port, token)
    path = f"{BASE}/{job}/phone-reservations"
    body = approval(1, "SLOT_UNAVAILABLE")
    for candidate in listing["candidates"]:
        assert body["candidateOrdinal"] == candidate["ordinal"]
        result = post(path, body)
        assert result["status"] == "DECLINED" and result["bookingRequested"] is False
        body["previousAttemptId"] = result["attemptId"]
        if result["nextCandidate"]:
            body["candidateOrdinal"] = result["nextCandidate"]["ordinal"]
    assert result["nextCandidate"] is None
    assert post(path, body, expected=409)["code"] == "NO_FALLBACK"

    # Invalidated results may be absent or returned in a terminal state.
    verify_view = json.loads((ROOT / "api/examples/destination-recommendation-request.json").read_text())
    verify_view["viewId"] = str(uuid.uuid4())
    job = request(port, "POST", BASE, verify_view, token)["taskId"]
    request(port, "POST", f"{BASE}/views/{verify_view['viewId']}/close", {}, token)
    post(f"{BASE}/{job}/phone-reservations", approval(1, "ACCEPT"), expected=409)
    return {"mode": "MOCK", "verification": "passed", "externalCalls": 0, "firstAttempt": first,
            "reapprovedAttempt": second, "additionalScenarios": [case[0] for case in cases]}


def demo(port, token, args):
    job, listing = shortlist(port, token)
    candidates = {candidate["ordinal"]: candidate for candidate in listing["candidates"]}
    ordinal = args.candidate
    if ordinal is None:
        for number, candidate in candidates.items():
            print(f"{number}. {candidate['name']} · {candidate['address']}")
        try:
            ordinal = int(input("가상 예약에 넘길 후보 번호 "))
        except (EOFError, ValueError) as error:
            raise RuntimeError("표시된 후보 번호를 입력해 주세요.") from error
    if ordinal not in candidates:
        raise RuntimeError("표시된 후보 번호를 입력해 주세요.")
    result = {"mode": "MOCK", "recommendationId": job, "selectedCandidate": candidates[ordinal],
              "candidates": list(candidates.values()), "externalCalls": 0, "bookingStatus": "AWAITING_APPROVAL"}
    if not args.scenario:
        return result
    path = f"{BASE}/{job}/phone-reservations"
    body = approval(ordinal, args.scenario)
    party = listing["requirements"]["party"]
    if args.prompt_approval and not confirm(candidates[ordinal], body, party):
        return result
    first = request(port, "POST", path, body, token, key=uuid.uuid4().hex)
    result.update(firstAttempt=first, bookingStatus=first["status"])
    if args.next_scenario:
        candidate = first["nextCandidate"]
        if not candidate:
            raise RuntimeError(f"다음 후보를 승인할 수 없어요. 첫 결과는 {first['status']}입니다.")
        assert request(port, "GET", path, token=token) == first
        next_body = approval(candidate["ordinal"], args.next_scenario, first["attemptId"])
        next_body["reservationAt"] = body["reservationAt"]
        if args.prompt_approval:
            print(first["notice"])
            if not confirm(candidates[candidate["ordinal"]], next_body, party):
                return result
        second = request(port, "POST", path, next_body, token, key=uuid.uuid4().hex)
        result.update(reapprovedAttempt=second, bookingStatus=second["status"])
    return result


def confirm(candidate, body, party):
    print(f"{candidate['name']} · {candidate['address']} · {body['reservationAt']}")
    print(f"성인 {party['adults']}명 · 어린이 {party['children']}명 · 예약자 {body['reservationName']}")
    for question in candidate["questions"]:
        print(f"- {question['prompt']}")
    try:
        return input("이 후보의 가상 예약을 승인할까요? [예/아니요] ").strip() == "예"
    except EOFError:
        return False


@contextmanager
def process(args, directory, env, log_path):
    with log_path.open("wb") as log:
        child = subprocess.Popen(args, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            yield child
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()


def wait_for(child, log_path, probe):
    for _ in range(150):
        if child.poll() is not None:
            break
        try:
            result = probe()
            if result is not None:
                return result
        except OSError:
            pass
        time.sleep(0.2)
    raise RuntimeError("서비스를 시작하지 못했어요.\n" + log_path.read_text()[-2200:])


def run(args):
    if not JAR.is_file():
        raise RuntimeError("먼저 ./gradlew -PincludeAndroid=false :server:bootJar 를 실행해 주세요.")
    with tempfile.TemporaryDirectory(prefix="arriveon-agent-demo-") as temp:
        directory = Path(temp)
        catalog = json.loads(FIXTURE.read_text())
        now = datetime.now(timezone.utc).isoformat()
        catalog.update(acquiredAt=now, sourceSnapshotAt=now)
        catalog_path = directory / "fictional-catalog.json"
        catalog_path.write_text(json.dumps(catalog, ensure_ascii=False))
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin/java") if java_home else "java"
        env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
        env["PHONE_RECOMMENDATION_TOKEN"] = secrets.token_urlsafe(32)
        phone_env = {**env, "PYTHONPATH": str(ROOT), "PHONE_ENV_FILE": str(directory / "no-live-secrets")}
        phone_log = directory / "phone.log"
        recommendation_log = directory / "recommendation.log"
        with process([
            sys.executable, "-m", "prototypes.phone_agent", "--port", "0",
            "--database", str(directory / "mock-phone.sqlite3"),
            "--mock-recommendation-catalog", str(catalog_path),
        ], directory, phone_env, phone_log) as phone:
            def phone_listening():
                match = re.search(r"Phone agent test UI: http://127\.0\.0\.1:(\d+)", phone_log.read_text())
                return int(match[1]) if match else None

            phone_port = wait_for(phone, phone_log, phone_listening)
            with process([
                java, "-jar", str(JAR), "--app.mode=MOCK", "--server.address=127.0.0.1",
                f"--server.port={port}", "--spring.datasource.url=jdbc:h2:mem:agent-demo",
                "--app.destination-recommendations.enabled=true",
                "--app.destination-recommendations.source=PUBLIC_CATALOG",
                f"--app.destination-recommendations.catalog-path={catalog_path}",
                "--app.recommendation-phone.enabled=true", f"--app.recommendation-phone.port={phone_port}",
            ], directory, env, recommendation_log) as recommendation:
                status = wait_for(recommendation, recommendation_log, lambda: request(port, "GET", "/api/v1/status"))
                if status["mode"] != "MOCK" or status["externalCallsEnabled"] is not False:
                    raise RuntimeError("추천 서버를 MOCK 모드로 실행해야 해요.")
                token = request(port, "POST", "/api/v1/anonymous-sessions", {})["accessToken"]
                return verify(port, phone_port, token) if args.verify else demo(port, token, args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=int, choices=range(1, 4), help="가상 후보 번호")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), help="첫 후보 승인과 가상 응답")
    parser.add_argument("--next-scenario", choices=sorted(SCENARIOS), help="제시된 다음 후보를 별도 승인하고 가상 응답")
    parser.add_argument("--prompt-approval", action="store_true", help="각 후보를 예약하기 전에 터미널에서 승인 입력")
    parser.add_argument("--verify", action="store_true", help="HTTP 승인, 실패 분기, 중복 요청과 소유자 격리 검증")
    args = parser.parse_args()
    if args.next_scenario and not args.scenario:
        parser.error("--next-scenario requires --scenario")
    try:
        print(json.dumps(run(args), ensure_ascii=False, indent=2))
    except (RuntimeError, OSError, http.client.HTTPException) as error:
        parser.exit(1, f"{error}\n")
