#!/usr/bin/env python3
"""Isolated Compose smoke: four MOCK fixtures, real auth, persisted DB, host-only publishing."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler
import uuid

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true", help="Use the locally built preview image")
    args = parser.parse_args()
    engine = subprocess.check_output(["docker", "version", "--format", "{{.Server.Version}}"],
                                     text=True).strip()
    assert int(engine.split(".")[0]) >= 28
    acceptance = module("acceptance", ROOT / "scripts/check-preview-acceptance.py")
    auth = module("auth_smoke", ROOT / "scripts/auth-smoke.py")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    opener = build_opener(ProxyHandler({}), acceptance.NoRedirect())
    project = "rec05-check-" + uuid.uuid4().hex[:12]
    (ROOT / "work").mkdir(exist_ok=True)
    requests = 0
    auth_requests = 0
    with tempfile.TemporaryDirectory(prefix="compose-", dir=ROOT / "work") as temporary:
        directory = Path(temporary)
        empty_env = directory / "empty.env"
        empty_env.touch()
        env = {**os.environ, "PREVIEW_PORT": str(port), "PREVIEW_FIXTURE": "RESULTS"}
        command = ["docker", "compose", "--env-file", str(empty_env),
                   "--project-name", project, "--file", str(ROOT / "compose.yaml")]

        def compose(*arguments, output=False):
            result = subprocess.run(command + list(arguments), cwd=ROOT, env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=600, check=True, text=True)
            return result.stdout if output else None

        def api(path, body=None, token=None, method="POST", expected=200):
            nonlocal requests
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            request = Request(base + path, method=method, headers=headers,
                              data=None if body is None else json.dumps(body).encode())
            try:
                response = opener.open(request, timeout=10)
            except HTTPError as error:
                response = error
            with response:
                assert response.code == expected
                assert response.headers["Cache-Control"] == "no-store"
                raw = response.read(262145)
                assert len(raw) <= 262144
                result = json.loads(raw)
            acceptance.validate(path, method, body, result, expected)
            requests += 1
            return result

        def validate_auth(path, body, result, status, method=None):
            nonlocal auth_requests
            acceptance.validate("/api/v1" + path,
                                (method or ("post" if body is not None else "get")).upper(),
                                body, result, status)
            auth_requests += 1

        try:
            config = json.loads(compose("config", "--format", "json", output=True))
            service = config["services"]["preview"]
            assert service["ports"][0]["host_ip"] == "127.0.0.1"
            assert config["networks"]["preview"]["driver"] == "bridge"
            if not args.no_build:
                print("CHECK: building local MOCK image", flush=True)
                compose("build")
            token = None
            after_restart = None
            for fixture, count in (("RESULTS", 3), ("EMPTY", 0), ("FILTERED", 0), ("FAILED", 0)):
                env["PREVIEW_FIXTURE"] = fixture
                print(f"CHECK: Compose MOCK {fixture}", flush=True)
                compose("up", "--detach", "--force-recreate", "--wait", "--wait-timeout", "90")
                container = compose("ps", "--quiet", "preview", output=True).strip()
                inspected = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
                assert inspected["Config"]["User"] == "10001:10001"
                assert inspected["HostConfig"]["ReadonlyRootfs"] is True
                assert inspected["State"]["Health"]["Status"] == "healthy"
                assert inspected["NetworkSettings"]["Ports"]["18082/tcp"] == [
                    {"HostIp": "127.0.0.1", "HostPort": str(port)}]
                status = api("/api/v1/status", method="GET")
                assert status["mode"] == "MOCK" and status["externalCallsEnabled"] is False
                api("/api/experimental/place-previews", {}, expected=401)
                if token is None:
                    token = api("/api/v1/anonymous-sessions", {})["accessToken"]
                if after_restart is not None:
                    after_restart()
                    after_restart = None
                session = api("/api/experimental/place-previews", {}, token)
                path = "/api/experimental/place-previews/" + session["previewId"]
                query = json.loads((ROOT / "api/examples/place-preview-recommendation-query.json").read_text())
                result = api(path + "/queries", query, token)
                assert result["state"] == fixture and len(result["places"]) == count
                if fixture == "FAILED":
                    assert result["recommendationPlan"] is None
                    assert result["failure"] == "UPSTREAM_UNAVAILABLE"
                else:
                    plan = result["recommendationPlan"]
                    assert plan["state"] == fixture and len(plan["candidates"]) == count
                    assert all(not c["bookable"] and not c["inquiryDispatchAllowed"]
                               for c in plan["candidates"])
                api(path + "/close", {}, token)
                if fixture == "RESULTS":
                    after_restart = auth.exercise(base, validate_auth)
                print(f"PASS: Compose {fixture}, candidates={count}, persisted token accepted", flush=True)
            api("/api/v1/anonymous-sessions/current", token=token, method="DELETE")
            print(f"PASS: Docker MOCK4, preview HTTP={requests}, auth HTTP={auth_requests}; loopback/non-root/read-only/health/DB restart/role403")
        finally:
            # Only this newly generated project/volume is removed, never the user's dev instance.
            compose("down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"FAIL: Compose check ({type(failure).__name__}); logs and response bodies omitted")
        raise SystemExit(1) from None
