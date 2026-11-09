#!/usr/bin/env python3
"""Boot the packaged JAR in a temporary directory, demo, restart, verify persistence."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error
from urllib.error import HTTPError
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("demo", ROOT / "scripts/mock-demo.py")
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)
jar = ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar"
if not jar.exists():
    raise SystemExit("Run ./gradlew verify first")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check-contract", action="store_true",
                    help="Also validate HTTP requests/responses; requires requirements-dev.txt")
args = parser.parse_args()
validate_exchange = None
if args.check_contract:
    contract_spec = importlib.util.spec_from_file_location("contract", ROOT / "scripts/check-contract.py")
    contract_module = importlib.util.module_from_spec(contract_spec)
    contract_spec.loader.exec_module(contract_module)
    validate_exchange = contract_module.validate_exchange
    contract_module.validate_preview_exchanges()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


with tempfile.TemporaryDirectory(prefix="pleos-smoke-") as tmp:
    port = free_port()
    base = f"http://127.0.0.1:{port}"
    env = {k: v for k, v in os.environ.items() if not k.startswith(("SPRING_", "APP_", "SERVER_"))}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with open(Path(tmp) / "server.log", "w+") as log:
        def start(*extra, authenticated=False):
            if authenticated:
                classpath_file = ROOT / "server/build/smoke-classpath.txt"
                if not classpath_file.exists():
                    raise RuntimeError("Run ./gradlew -PincludeAndroid=false verify for test-only HTTP fixtures")
                command = ["java", "-cp", classpath_file.read_text().strip(),
                           "ai.pleos.assistant.OwnershipSmokeApplication"]
            else:
                command = ["java", "-jar", str(jar)]
            process = subprocess.Popen(command + [f"--server.port={port}",
                                        "--server.address=127.0.0.1", "--app.mode=MOCK", *extra],
                                       cwd=tmp, env=env, stdout=log, stderr=subprocess.STDOUT)
            for _ in range(150):
                try:
                    with opener.open(base + "/api/v1/status", timeout=1) as response:
                        assert json.load(response)["mode"] == "MOCK"
                        return process
                except OSError:
                    if process.poll() is not None:
                        break
                    time.sleep(0.2)
            stop(process)
            log.flush()
            log.seek(0)
            raise RuntimeError(log.read())

        # The real artifact has no synthetic identity or development authentication bypass.
        with zipfile.ZipFile(jar) as archive:
            assert not any("OwnershipSmokeApplication" in name for name in archive.namelist())
        process = start()
        try:
            for path in ["/searches/missing", "/reservations/missing", "/calls/missing"]:
                request = urllib.request.Request(base + "/api/v1" + path,
                    headers={"Authorization": "Bearer forged", "X-User-Id": "smoke-owner"})
                try:
                    opener.open(request, timeout=10)
                    raise AssertionError("Packaged server accepted an unverified caller")
                except urllib.error.HTTPError as error:
                    result = json.load(error)
                    assert error.code == 401 and result["code"] == "AUTHENTICATION_REQUIRED"
                    if validate_exchange:
                        validate_exchange(path, None, result, error.code)
            print("PASS: packaged JAR rejects unverified identity; test authentication absent")
            auth_spec = importlib.util.spec_from_file_location("auth_smoke", ROOT / "scripts/auth-smoke.py")
            auth_smoke = importlib.util.module_from_spec(auth_spec)
            auth_spec.loader.exec_module(auth_smoke)
            check_after_restart = auth_smoke.exercise(base, validate_exchange)
            subprocess.run([sys.executable, str(ROOT / "scripts/preview-contract-probe.py"),
                            "--base-url", base], check=True)
            request = urllib.request.Request(base + "/api/experimental/place-previews", data=b"{}",
                                             headers={"Content-Type": "application/json"})
            try:
                opener.open(request, timeout=5)
                raise AssertionError("Preview must be disabled by default")
            except HTTPError as error:
                assert error.code == 404
        finally:
            stop(process)
        process = start()
        try:
            check_after_restart()
        finally:
            stop(process)
        process = start(authenticated=True)
        try:
            report = demo.run(base, validate=validate_exchange)
        finally:
            stop(process)
        process = start(authenticated=True)
        try:
            with opener.open(base + "/api/v1/reservations/" + report["reservationId"], timeout=10) as response:
                stored = json.load(response)
                assert stored["status"] == "CONFIRMED" and stored["pendingChangeCallId"]
            print("PASS: test-only Principal, Flyway migrations, HTTP workflow, owned H2 restart persistence")
            if args.check_contract:
                print("PASS: HTTP requests and responses match OpenAPI 3.1")
        finally:
            stop(process)
        process = start("--app.place-preview.enabled=true")
        try:
            subprocess.run([sys.executable, str(ROOT / "scripts/preview-contract-probe.py"),
                            "--base-url", base, "--enabled"], check=True)
            request = urllib.request.Request(base + "/api/experimental/place-previews", data=b"{}",
                                             headers={"Content-Type": "application/json",
                                                      "X-Owner": "fictional-owner",
                                                      "Authorization": "Bearer fictional-token"})
            try:
                opener.open(request, timeout=5)
                raise AssertionError("Opt-in preview must not invent authentication")
            except HTTPError as error:
                assert error.code == 401
                assert error.headers["Cache-Control"] == "no-store"
                assert json.load(error)["code"] == "PREVIEW_AUTHENTICATION_REQUIRED"
            print("PASS: packaged preview default-off 404 and opt-in unauthenticated/spoofed 401")
            if args.check_contract:
                token_spec = importlib.util.spec_from_file_location(
                    "preview_token_smoke", ROOT / "scripts/preview-token-smoke.py")
                token_smoke = importlib.util.module_from_spec(token_spec)
                token_spec.loader.exec_module(token_smoke)
                token_smoke.exercise(base)
            subprocess.run([sys.executable, str(ROOT / "scripts/preview-session-demo.py"),
                            "--base-url", base], check=True)
        finally:
            stop(process)
        live = subprocess.run(["java", "-jar", str(jar), "--app.mode=LIVE", "--server.port=0"],
                              cwd=tmp, env=env, capture_output=True, text=True, timeout=30)
        assert live.returncode != 0 and "LIVE_DISABLED" in live.stdout + live.stderr
        print("PASS: LIVE startup rejected; no outbound provider exists")
