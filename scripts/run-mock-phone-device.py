#!/usr/bin/env python3
"""Local-only marked gateway for the fictional catalog/phone simulator device demo.

Keeps its own DB and token under work/ux56-runtime across restarts. Never edits an
existing app journal or auth DB. Ctrl-C stops only this command's child processes.
"""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]

def run(args):
    state = ROOT / "work/ux56-runtime"
    state.mkdir(parents=True, exist_ok=True)
    os.chmod(state, 0o700)
    token_file = state / "service-token"
    if not token_file.exists():
        fd = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(secrets.token_urlsafe(32))
    catalog = json.loads((ROOT / "server/src/test/resources/places/seoul-catalog-fictional.json").read_text())
    # This file contains fictional observations only, never refresh actual provider data.
    now = datetime.now(timezone.utc).isoformat()
    catalog.update(acquiredAt=now, sourceSnapshotAt=now)
    catalog_file = state / "fictional-catalog.json"
    catalog_file.write_text(json.dumps(catalog, ensure_ascii=False))
    env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
    env.update(PYTHONPATH=str(ROOT), PHONE_ENV_FILE=str(state / "no-live-env"),
               PHONE_RECOMMENDATION_TOKEN=token_file.read_text())
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
    processes, logs = [], []
    gateway = None
    try:
        commands = [
            [sys.executable, "-m", "prototypes.phone_agent", "--port", str(args.phone_port),
             "--database", str(state / "phone.sqlite3"), "--mock-recommendation-catalog", str(catalog_file)],
            [java, "-jar", str(ROOT / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar"),
             "--server.address=127.0.0.1", f"--server.port={args.server_port}", "--app.mode=MOCK",
             f"--spring.datasource.url=jdbc:h2:file:{state / 'server'}",
             "--app.destination-recommendations.enabled=true", "--app.destination-recommendations.source=PUBLIC_CATALOG",
             f"--app.destination-recommendations.catalog-path={catalog_file}",
             "--app.destination-recommendations.web.enabled=false", "--app.recommendation-phone.enabled=true",
             f"--app.recommendation-phone.port={args.phone_port}", "--logging.level.root=WARN"]]
        for name, command in zip(("phone", "server"), commands):
            log = (state / f"{name}.log").open("ab")
            logs.append(log)
            processes.append(subprocess.Popen(command, cwd=state, env=env, stdout=log, stderr=subprocess.STDOUT))
        for _ in range(150):
            if any(p.poll() is not None for p in processes):
                raise RuntimeError("Child exited; inspect local server/phone logs")
            try:
                connection = http.client.HTTPConnection("127.0.0.1", args.server_port, timeout=1)
                connection.request("GET", "/api/v1/status")
                response = connection.getresponse()
                status = json.load(response)
                connection.close()
                if status.get("mode") == "MOCK" and status.get("externalCallsEnabled") is False:
                    break
            except OSError:
                pass
            time.sleep(0.2)
        else:
            raise RuntimeError("MOCK startup timed out")

        class Gateway(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # Never log tokens, bodies, names or coordinates.
            def do_GET(self):
                self.forward()
            def do_POST(self):
                self.forward()
            def forward(self):
                if self.path == "/mock-capabilities" and self.command == "GET":
                    body = json.dumps({"destinationRecommendationsMode": "MOCK", "externalCallsEnabled": False,
                                       "phoneReservationsMode": "MOCK"}).encode()
                    self.send_response(200); self.send_header("Content-Length", str(len(body)))
                    self.end_headers(); self.wfile.write(body); return
                if not self.path.startswith("/api/"):
                    self.send_error(404); return
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > 262144:
                    self.send_error(413); return
                body = self.rfile.read(length) if length else None
                headers = {k: self.headers[k] for k in ("Authorization", "Idempotency-Key", "Content-Type") if k in self.headers}
                connection = http.client.HTTPConnection("127.0.0.1", args.server_port, timeout=12)
                try:
                    connection.request(self.command, self.path, body, headers)
                    response = connection.getresponse()
                    data = response.read(262145)
                    self.send_response(response.status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers(); self.wfile.write(data)
                except OSError:
                    self.send_error(502)
                finally:
                    connection.close()
        gateway = ThreadingHTTPServer(("127.0.0.1", args.port), Gateway)
        print(f"READY MOCK phone demo at 127.0.0.1:{args.port}; external calls disabled", flush=True)
        gateway.serve_forever()
    finally:
        if gateway:
            gateway.server_close()
        for p in reversed(processes):
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    p.kill(); p.wait()
        for log in logs:
            log.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--server-port", type=int, default=18089)
    parser.add_argument("--phone-port", type=int, default=18090)
    args = parser.parse_args()
    if len({args.port, args.server_port, args.phone_port}) != 3 or any(p < 1024 or p > 65535 for p in (args.port, args.server_port, args.phone_port)):
        parser.error("Use three distinct unprivileged ports")
    try:
        run(args)
    except KeyboardInterrupt:
        pass
