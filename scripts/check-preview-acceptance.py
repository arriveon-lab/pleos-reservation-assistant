#!/usr/bin/env python3
"""Exercise test-only MOCK fixtures with real issued tokens; no device or external service."""
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid
import zipfile

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = {"/api/v1": json.loads((ROOT / "api/openapi.json").read_text()),
             "/api/experimental": json.loads((ROOT / "api/place-preview-draft.json").read_text())}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def validate(path, method, body, result, status):
    prefix = "/api/v1" if path.startswith("/api/v1/") else "/api/experimental"
    contract = CONTRACTS[prefix]
    route = path.removeprefix(prefix)
    operations = [item[method.lower()] for pattern, item in contract["paths"].items()
                  if method.lower() in item and re.fullmatch(
                      re.escape(pattern).replace(r"\{id\}", "[^/]+"), route)]
    assert len(operations) == 1, "Contract route mismatch"
    operation = operations[0]
    schema = operation["responses"][str(status)]["content"]["application/json"]["schema"]
    def check(schema, value):
        Draft202012Validator({**schema, "components": contract["components"]},
                             format_checker=FormatChecker()).validate(value)
    check(schema, result)
    if status == 200 and body is not None:
        check(operation["requestBody"]["content"]["application/json"]["schema"], body)


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def exercise(fixture, expected_count, classpath, directory):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    opener = build_opener(ProxyHandler({}), NoRedirect())
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(("SPRING_", "APP_", "SERVER_", "LOGGING_"))
           and k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")}
    count = 0
    with (directory / "startup.log").open("w") as log:
        process = subprocess.Popen(
            ["java", "-cp", classpath, "ai.pleos.assistant.PreviewAcceptanceApplication",
             fixture, str(port), str(directory / "auth")], cwd=ROOT, env=env,
            stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(150):
                if process.poll() is not None:
                    raise RuntimeError("Acceptance server exited before readiness")
                try:
                    with opener.open(base + "/api/v1/status", timeout=1) as response:
                        assert json.loads(response.read(65536))["mode"] == "MOCK"
                        break
                except OSError:
                    time.sleep(0.2)
            else:
                raise RuntimeError("Acceptance readiness deadline elapsed")

            def api(path, body=None, token=None, method="POST", expected=200, key=None):
                nonlocal count
                headers = {"Content-Type": "application/json"}
                if token:
                    headers["Authorization"] = "Bearer " + token
                if key:
                    headers["Idempotency-Key"] = key
                request = Request(base + path, method=method, headers=headers,
                                  data=None if body is None else json.dumps(body).encode())
                try:
                    response = opener.open(request, timeout=10)
                except HTTPError as error:
                    response = error
                with response:
                    assert response.code == expected, "Unexpected acceptance HTTP status"
                    assert response.headers["Cache-Control"] == "no-store"
                    raw = response.read(262145)
                    assert len(raw) <= 262144, "Response exceeds acceptance bound"
                    result = json.loads(raw)
                validate(path, method, body, result, expected)
                count += 1
                return result

            api("/api/experimental/place-previews", {}, expected=401)
            auth = api("/api/v1/anonymous-sessions", {})
            token = auth["accessToken"]
            session = api("/api/experimental/place-previews", {}, token)
            path = "/api/experimental/place-previews/" + session["previewId"]
            query = json.loads((ROOT / "api/examples/place-preview-recommendation-query.json").read_text())
            query["requirements"]["requiredFeatures"] = ["childFriendly", "parking"]
            query["requirements"]["preferredCuisines"] = ["한식", "양식"]

            def check_result(result):
                assert result["state"] == fixture
                assert len(result["places"]) == expected_count
                assert all(result[k] == "MOCK" for k in ("mode", "placeSource", "reservationMode"))
                if fixture == "FAILED":
                    assert result["failure"] == "UPSTREAM_UNAVAILABLE"
                    assert result["recommendationPlan"] is None
                else:
                    plan = result["recommendationPlan"]
                    assert plan["state"] == fixture and len(plan["candidates"]) == expected_count
                    assert result["expiresAt"] == plan["displayValidUntil"]
                    for candidate in plan["candidates"]:
                        assert not candidate["bookable"] and not candidate["inquiryDispatchAllowed"]
                        assert all(q["certainty"] == "UNKNOWN" for q in candidate["questions"])
                return result

            check_result(api(path + "/queries", query, token))
            reordered = deepcopy(query)
            for field in ("requiredFeatures", "preferredCuisines"):
                reordered["requirements"][field].reverse()
            assert api(path + "/queries", reordered, token)["state"] == "ALREADY_FINISHED"
            changed = deepcopy(query)
            changed["requirements"]["maxWaitMinutes"] += 1
            assert api(path + "/queries", changed, token)["state"] == "REQUEST_CONFLICT"
            for field, value in (("adults", 21), ("children", 11)):
                invalid = deepcopy(query)
                invalid["requirements"]["party"][field] = value
                api(path + "/queries", invalid, token, expected=400)
            query["generation"] += 1
            query["requirements"]["party"] = {"adults": 20, "children": 10}
            check_result(api(path + "/queries", query, token))
            refreshed = api("/api/v1/anonymous-sessions/refresh", {"refreshToken": auth["refreshToken"]},
                            key=str(uuid.uuid4()))
            api(path + "/close", {}, refreshed["accessToken"])
            api("/api/v1/anonymous-sessions/current", token=refreshed["accessToken"], method="DELETE")
        finally:
            stop(process)
    return count


def main():
    classpath = (ROOT / "server/build/smoke-classpath.txt").read_text().strip()
    with zipfile.ZipFile(ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar") as archive:
        assert not any("PreviewAcceptanceApplication" in n for n in archive.namelist())
    total = 0
    (ROOT / "work").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="check-", dir=ROOT / "work") as temporary:
        for fixture, places in (("RESULTS", 3), ("EMPTY", 0), ("FILTERED", 0), ("FAILED", 0)):
            directory = Path(temporary) / fixture.lower()
            directory.mkdir()
            print(f"CHECK: MOCK {fixture}", flush=True)
            count = exercise(fixture, places, classpath, directory)
            total += count
            print(f"PASS: MOCK {fixture}, candidates={places}, authenticated HTTP={count}")
    print(f"PASS: {total} HTTP exchanges; schemas, party bounds, set equivalence, refresh ownership; no device/external calls")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # Validation errors can include entire credential responses; never print their bodies.
        print(f"FAIL: acceptance check ({type(failure).__name__}); response bodies omitted")
        raise SystemExit(1) from None
