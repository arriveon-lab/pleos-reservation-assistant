#!/usr/bin/env python3
"""Packaged JAR + temporary H2 + fictional web reports. No external model/search/phone calls."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec

ROOT = Path(__file__).resolve().parents[1]
SPEC = json.loads((ROOT / "api/web-recommendation-draft.json").read_text())
validate_spec(SPEC)
BASE = "/api/experimental/web-recommendations"
COUNT = 0


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def validate(value, schema):
    document = dict(SPEC)
    document.update({"$ref": "#/components/schemas/" + schema})
    Draft202012Validator(document, format_checker=FormatChecker()).validate(value)


def call(port, method, path, body=None, token=None, key=None, expected=200, schema=None):
    global COUNT
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request("http://127.0.0.1:" + str(port) + path,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers, method=method)
    COUNT += 1
    try:
        response = opener.open(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.status == expected, (method, path, response.status, expected)
        assert "no-store" in response.headers.get("Cache-Control", "")
        value = json.load(response)
    if schema:
        validate(value, schema)
    return value


def start(directory, port, enabled):
    jar = ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar"
    log = (directory / "server.log").open("ab")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    process = subprocess.Popen([java, "-jar", str(jar), "--server.address=127.0.0.1",
                                "--server.port=" + str(port), "--app.mode=MOCK",
                                "--app.web-recommendations.enabled=" + str(enabled).lower(),
                                "--spring.datasource.url=jdbc:h2:file:" + str(directory / "auth")],
                               cwd=directory, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(200):
            if process.poll() is not None:
                raise RuntimeError("MOCK JAR exited before readiness")
            try:
                with opener.open("http://127.0.0.1:" + str(port) + "/api/v1/status", timeout=1) as response:
                    status = json.load(response)
                    assert status["mode"] == "MOCK" and status["externalCallsEnabled"] is False
                    return process, log
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.1)
        raise RuntimeError("MOCK JAR readiness timeout")
    except BaseException:
        stop(process, log)
        raise


def stop(process, log):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    log.close()


def run():
    with socket.socket() as bound:
        bound.bind(("127.0.0.1", 0))
        port = bound.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="arriveon-web-needs-") as temporary:
        directory = Path(temporary)
        process, log = start(directory, port, True)
        request = json.loads((ROOT / "api/examples/web-recommendation-request.json").read_text())
        request["viewId"] = str(uuid.uuid4())
        key = str(uuid.uuid4())
        try:
            tokens = call(port, "POST", "/api/v1/anonymous-sessions", {}, key=str(uuid.uuid4()))
            token = tokens["accessToken"]
            call(port, "POST", BASE, request, key=key, expected=401, schema="Error")
            result = call(port, "POST", BASE, request, token, key, schema="Response")
            request_id = result["requestId"]
            assert result["result"]["state"] == "RESULTS"
            assert len(result["result"]["candidates"]) == 3
            assert result["result"]["excludedCandidates"] == 1
            assert all(not c["bookable"] and not c["inquiryDispatchAllowed"] for c in result["result"]["candidates"])
            assert "INDOOR_PET_FRIENDLY" in result["result"]["candidates"][1]["remainingQuestions"]
            repeat = call(port, "POST", BASE, request, token, key, schema="Response")
            assert repeat["result"]["state"] == "ALREADY_REQUESTED" and not repeat["result"]["candidates"]
            feedback = json.loads((ROOT / "api/examples/web-recommendation-feedback.json").read_text())
            saved = call(port, "PUT", BASE + "/" + request_id + "/feedback", feedback, token, schema="Saved")
            assert saved["feedback"]["fit"] == "MATCH"
            assert "merchant.example" not in json.dumps(saved)
            other = call(port, "POST", "/api/v1/anonymous-sessions", {}, key=str(uuid.uuid4()))["accessToken"]
            call(port, "GET", BASE + "/" + request_id, token=other, expected=404, schema="Error")
            invalid = dict(feedback, sourceUrl="https://unwanted.example")
            call(port, "PUT", BASE + "/" + request_id + "/feedback", invalid, token, expected=400, schema="Error")
            changed = dict(request, area="다른 가상 지역")
            call(port, "POST", BASE, changed, token, key, expected=409, schema="Error")
            newer = dict(request, generation=2)
            new_result = call(port, "POST", BASE, newer, token, str(uuid.uuid4()), schema="Response")
            call(port, "PUT", BASE + "/" + request_id + "/feedback", dict(feedback, selectedOrdinal=2), token, expected=409, schema="Error")
            call(port, "POST", BASE + "/" + new_result["requestId"] + "/close", token=token, schema="Closed")
            caller = subprocess.run([sys.executable, str(ROOT / "scripts/web-recommendation-demo.py"),
                                     "--base-url", "http://127.0.0.1:" + str(port), "--interactive"],
                                    input="1\nMATCH\n", capture_output=True, text=True, timeout=15)
            assert caller.returncode == 0, "Fictional CLI caller failed"
            assert '"fit": "MATCH"' in caller.stdout and "accessToken" not in caller.stdout
        finally:
            stop(process, log)
        process, log = start(directory, port, True)
        try:
            saved = call(port, "GET", BASE + "/" + request_id, token=token, schema="Saved")
            assert saved["feedback"]["fit"] == "MATCH" and "result" not in saved
            repeat = call(port, "POST", BASE, request, token, key, schema="Response")
            assert repeat["result"]["state"] == "ALREADY_REQUESTED"
            call(port, "PUT", BASE + "/" + request_id + "/feedback", dict(feedback, selectedOrdinal=2), token, expected=409, schema="Error")
            updated = call(port, "PUT", BASE + "/" + request_id + "/feedback", dict(feedback, fit="PARTIAL"), token, schema="Saved")
            assert updated["feedback"]["fit"] == "PARTIAL"
            deleted = call(port, "DELETE", BASE + "/" + request_id, token=token, schema="Saved")
            assert deleted["deleted"] and deleted["request"] is None and deleted["feedback"] is None
            call(port, "POST", BASE, request, token, key, expected=409, schema="Error")
            call(port, "DELETE", BASE + "/" + request_id, token=token, schema="Saved")
        finally:
            stop(process, log)
        process, log = start(directory, port, False)
        try:
            call(port, "GET", BASE + "/" + request_id, token=token, expected=404)
        finally:
            stop(process, log)
    print(json.dumps({"result": "PASS", "mode": "MOCK", "httpChecks": COUNT, "cliHttpChecks": 5,
                      "temporaryDatabaseRestarted": True, "externalCalls": 0}))


if __name__ == "__main__":
    run()
