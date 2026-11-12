#!/usr/bin/env python3
"""Check the place lookup, recommendation API and test booking with fictional data."""
from contextlib import ExitStack, contextmanager
from datetime import date, datetime, timedelta, timezone
import http.cookiejar
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
from threading import Thread
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar"
AREA = "가상 가온시 누리로 302"
WESTERN_AREA = "가상 성수"
NEW_AREA = "가상 은하역"
NEEDS = {"PARKING", "HIGH_CHAIR", "STEP_FREE_ACCESS"}


def report(area, observed_at):
    rows = [
        ("1", "가상 한식당", "한식", "RESTAURANT", "YES", "UNKNOWN"),
        ("2", "가상 양식당", "양식", "RESTAURANT", "YES", "YES"),
        ("3", "가상 테이블", "양식", "RESTAURANT", "YES", "UNKNOWN"),
        ("4", "가상 주차 불가 식당", "양식", "RESTAURANT", "NO", "YES"),
        ("5", "가상 카페", "", "CAFE", "YES", "YES"),
        ("6", "가상 파스타", "양식", "RESTAURANT", "UNKNOWN", "UNKNOWN"),
    ]
    sources, candidates = [], []
    for number, name, cuisine, category, parking, chair in rows:
        url = f"https://merchant.example/{number}"
        source = {"sourceUrl": url, "sourceType": "MERCHANT_PAGE", "sourceUpdatedAt": None}
        sources.append({"url": url, "title": f"{name} 가상 안내"})
        candidates.append({
            "name": name, "address": f"{area} {number}호", "identitySourceUrl": url,
            "venueCategory": category, "cuisine": cuisine,
            "businessDetails": [
                {**source, "kind": "CUISINE", "condition": cuisine or "커피"},
                {**source, "kind": "OPENING_HOURS", "condition": "매일 11:00–21:00"},
                {**source, "kind": "BREAK_TIME", "condition": "매일 15:00–17:00"},
            ],
            "reports": [
                {**source, "need": "PARKING", "answer": parking, "condition": "가상 주차 안내"},
                {**source, "need": "HIGH_CHAIR", "answer": chair, "condition": "가상 유아의자 안내"},
            ],
        })
    return {"observedAt": observed_at.isoformat(), "sources": sources, "candidates": candidates}


@contextmanager
def app():
    token = secrets.token_urlsafe(32)
    observed_at = datetime.now(timezone.utc) - timedelta(seconds=1)

    class Lookup(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            assert self.path == "/places"
            assert self.headers["Authorization"] == f"Bearer {token}"
            query = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            assert set(query) == {"destination", "venueCategory", "preferredCuisines", "requiredNeeds"}
            assert query["venueCategory"] == "RESTAURANT" and set(query["requiredNeeds"]) == NEEDS
            assert query["preferredCuisines"] == (["양식"] if query["destination"] == WESTERN_AREA else [])
            if query["destination"] == "가상 조회 장애":
                self.send_error(503)
                return
            stamp = observed_at - timedelta(minutes=6) if query["destination"] == "가상 만료 자료" else observed_at
            body = json.dumps(report(query["destination"], stamp), ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    with tempfile.TemporaryDirectory(prefix="arriveon-lookup-check-") as directory, ExitStack() as stack:
        lookup = stack.enter_context(HTTPServer(("127.0.0.1", 0), Lookup))
        thread = Thread(target=lookup.serve_forever, daemon=True)
        thread.start()
        stack.callback(lookup.shutdown)
        with socket.socket() as bound:
            bound.bind(("127.0.0.1", 0))
            recommendation_port = bound.getsockname()[1]
        env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin/java") if java_home else "java"
        recommendation_log = Path(directory) / "recommendation.log"
        with child(stack, [java, "-Xmx144m", "-Djdk.httpclient.disableRetryConnect=true",
                          "-Djdk.httpclient.redirects.retrylimit=1", "-jar", str(JAR),
                          "--server.address=127.0.0.1", f"--server.port={recommendation_port}",
                          "--app.mode=MOCK", "--spring.datasource.url=jdbc:h2:mem:lookup-check",
                          "--app.destination-recommendations.enabled=true",
                          "--app.destination-recommendations.source=WEB_SEARCH",
                          "--app.destination-recommendations.web.enabled=true",
                          "--app.destination-recommendations.web.runtime-enabled=true",
                          f"--app.destination-recommendations.web.place-lookup-port={lookup.server_port}",
                          "--app.destination-recommendations.web.place-lookup-fallback=false"],
                   directory, {**env, "PLACE_LOOKUP_TOKEN": token}, recommendation_log) as process:
            wait(process, recommendation_log, lambda: call(
                urllib.request.build_opener(urllib.request.ProxyHandler({})), recommendation_port, "/api/v1/status"))
            phone_log = Path(directory) / "phone.log"
            with child(stack, [sys.executable, "-m", "prototypes.phone_agent", "--port", "0",
                              "--database", str(Path(directory) / "phone.sqlite3"),
                              "--recommendation-port", str(recommendation_port)], directory,
                       {**env, "PYTHONPATH": str(ROOT), "PHONE_ENV_FILE": str(Path(directory) / "no-secrets")},
                       phone_log) as phone:
                port = wait(phone, phone_log, lambda: re.search(
                    r"Phone agent test UI: http://127\.0\.0\.1:(\d+)", phone_log.read_text()))
                yield int(port[1]), observed_at


@contextmanager
def child(stack, args, directory, env, log_path):
    log = stack.enter_context(log_path.open("wb"))
    process = subprocess.Popen(args, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        yield process
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def wait(process, log, ready):
    deadline = time.monotonic() + 30
    while process.poll() is None and time.monotonic() < deadline:
        try:
            result = ready()
            if result:
                return result
        except OSError:
            pass
        time.sleep(0.15)
    raise RuntimeError(log.read_text()[-2200:])


def call(opener, port, path, body=None):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-Prototype-Request": "1"})
    with opener.open(request, timeout=36) as response:
        return json.load(response)


def verify(port, observed_at):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    call(opener, port, "/api/state")
    conditions = {"partySize": 4, "highChairCount": 1, "stepFree": True,
                  "petAccompanied": "NO", "parkingRequired": True}

    def search(area, food="", arrival=None):
        return call(opener, port, "/api/web-recommendations", {
            "destination": area, "venueCategory": "RESTAURANT", "conditions": conditions,
            "foodPreferenceText": food,
            "arrivalAt": arrival,
        })

    results = []
    for area, food in ((AREA, ""), (WESTERN_AREA, "양식 먹고 싶어"), (NEW_AREA, "")):
        body = search(area, food)
        result = body["result"]
        places = result["webRecommendations"]
        assert result["webState"] == "RESULTS" and len(places) == 3, result
        assert places[0]["name"] == "가상 양식당"
        assert all(p["address"].startswith(area) and not p["bookable"] for p in places)
        assert {p["name"] for p in places} == {"가상 양식당", "가상 테이블", "가상 파스타" if food else "가상 한식당"}
        details = places[0]["businessDetails"]
        assert details["OPENING_HOURS"]["condition"] == "매일 11:00–21:00"
        assert details["BREAK_TIME"]["condition"] == "매일 15:00–17:00"
        assert datetime.fromisoformat(details["BREAK_TIME"]["observedAt"]) == observed_at
        assert details["BREAK_TIME"]["url"] == places[0]["identitySourceUrl"]
        assert {item["need"]: item["verdict"] for item in places[0]["assessments"]} == {
            "PARKING": "YES", "HIGH_CHAIR": "YES", "STEP_FREE_ACCESS": "UNKNOWN",
        }
        if food:
            booking = call(opener, port, "/api/action", {
                "action": "create_web_test_booking", "requestId": str(uuid.uuid4()),
                "payload": {"searchId": body["searchId"], "candidateOrdinal": 1,
                            "date": (date.today() + timedelta(days=1)).isoformat(),
                            "time": "19:00", "reservationName": "가상 예약자"},
            })["state"]["bookings"][0]
            assert booking["restaurantName"] == "가상 양식당" and booking["status"] == "AWAITING_APPROVAL"
            assert booking["snapshot"]["preferredCuisines"] == ["양식"]
            assert all(booking["snapshot"][key] == value for key, value in conditions.items())
        results.append(f"{area}: {len(places)}곳")
    during_break = search(AREA, arrival="2026-11-12T16:00")
    assert during_break["result"]["webState"] == "FILTERED" and "searchId" not in during_break
    after_break = search(AREA, arrival="2026-11-12T19:00")
    assert len(after_break["result"]["webRecommendations"]) == 3
    for area, reason in (("가상 조회 장애", "HTTP_REJECTED"), ("가상 만료 자료", "INVALID_SOURCE_METADATA")):
        body = search(area)
        assert body["result"]["webState"] == "FAILED" and body["result"]["webFailureReason"] == reason
        assert "searchId" not in body
    assert call(opener, port, "/api/state")["externalCalls"] == 0
    print("가상 조회 → 시험 예약 검증 통과 / " + ", ".join(results) + " / 외부 발신 0회")


if __name__ == "__main__":
    with app() as (port, stamp):
        verify(port, stamp)
