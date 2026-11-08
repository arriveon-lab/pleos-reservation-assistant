"""Fictional loopback protocol tests. No real tokens, places, phone calls or outside network."""
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import threading
import unittest

MODULE = Path(__file__).resolve().parents[1] / "preview-session-demo.py"
SPEC = importlib.util.spec_from_file_location("preview_session_demo", MODULE)
demo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(demo)
ACCESS = "pa_" + "a" * 43
NEXT_ACCESS = "pa_" + "b" * 43
REFRESH = "pr_" + "c" * 43
NEXT_REFRESH = "pr_" + "d" * 43
PREVIEW = "00000000-0000-0000-0000-000000000016"
BASE_PATH = "/api/experimental/place-previews/" + PREVIEW


class Sandbox:
    def __init__(self, behavior="normal"):
        self.behavior = behavior
        self.calls = []
        sandbox = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.handle_api()

            def do_POST(self):
                self.handle_api()

            def do_DELETE(self):
                self.handle_api()

            def handle_api(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                sandbox.calls.append((self.command, self.path,
                                      self.headers.get("Authorization"),
                                      self.headers.get("Idempotency-Key"),
                                      json.loads(body) if body else None))
                status = 200
                response = {"mode": "MOCK"}
                if self.path == "/api/v1/status":
                    response.update(contractVersion="1.0.0" if sandbox.behavior == "old" else "1.1.0",
                                    dataSource="FICTIONAL_FIXTURES", externalCallsEnabled=False)
                    if sandbox.behavior == "live":
                        response["mode"] = "LIVE"
                    if sandbox.behavior == "external":
                        response["externalCallsEnabled"] = True
                elif self.path == "/api/v1/anonymous-sessions":
                    response.update(tokenType="Bearer", accessToken=ACCESS, refreshToken=REFRESH)
                elif self.path == "/api/v1/anonymous-sessions/refresh":
                    response.update(tokenType="Bearer", accessToken=NEXT_ACCESS, refreshToken=NEXT_REFRESH)
                    if sandbox.behavior == "refresh-failure":
                        status = 503
                        response = {"message": REFRESH}
                elif self.path == "/api/experimental/place-previews":
                    response["previewId"] = PREVIEW
                    if sandbox.behavior == "disabled":
                        status = 404
                elif self.path == BASE_PATH + "/queries":
                    place = {"name": "가상 식당", "bookable": False, "parking": "UNKNOWN",
                             "childFriendly": "UNKNOWN", "reservationAvailability": "UNKNOWN"}
                    response.update(previewId=PREVIEW, generation=1, state="RESULTS", placeSource="MOCK",
                                    reservationMode="MOCK", places=[place])
                    if sandbox.behavior == "query-failure":
                        status = 500
                        response = {"message": ACCESS}
                    if sandbox.behavior == "query-source":
                        response["placeSource"] = "KAKAO"
                    if sandbox.behavior == "bookable":
                        place["bookable"] = True
                    if sandbox.behavior == "redirect":
                        self.send_response(302)
                        self.send_header("Location", sandbox.base + "/trap")
                        self.end_headers()
                        return
                elif self.path == BASE_PATH + "/cancel":
                    response.update(previewId=PREVIEW, generation=2, state="CANCEL_REQUESTED")
                elif self.path == BASE_PATH + "/close":
                    response.update(previewId=PREVIEW, state="CLOSED")
                    if sandbox.behavior == "close-failure":
                        status = 503
                elif self.path == "/api/v1/anonymous-sessions/current":
                    response["state"] = "REVOKED"
                    if sandbox.behavior == "revoke-failure":
                        status = 503
                else:
                    status = 404
                raw = json.dumps(response).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class PreviewSessionDemoTest(unittest.TestCase):
    def run_cli(self, sandbox):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            status = demo.main(["--base-url", sandbox.base])
        output = stdout.getvalue()
        for secret in (ACCESS, NEXT_ACCESS, REFRESH, NEXT_REFRESH, PREVIEW):
            self.assertNotIn(secret, output)
        return status, json.loads(output)

    def test_success_uses_one_query_refreshes_same_preview_then_closes_and_revokes(self):
        with Sandbox() as sandbox:
            status, result = self.run_cli(sandbox)
            self.assertEqual(status, 0)
            self.assertEqual(result["httpRequests"], 8)
            self.assertEqual(result["placeQueries"], 1)
            self.assertTrue(result["closed"] and result["revoked"] and result["refreshed"])
            refresh = next(c for c in sandbox.calls if c[1].endswith("/refresh"))
            self.assertEqual(refresh[4], {"refreshToken": REFRESH})
            self.assertIsNotNone(refresh[3])
            for call in sandbox.calls:
                if call[1].endswith(("/cancel", "/close", "/current")):
                    self.assertEqual(call[2], "Bearer " + NEXT_ACCESS)

    def test_wrong_server_mode_or_missing_auth_contract_stops_before_identity_issuance(self):
        for behavior in ("old", "live", "external"):
            with self.subTest(behavior=behavior), Sandbox(behavior) as sandbox:
                status, _ = self.run_cli(sandbox)
                self.assertEqual(status, 1)
                self.assertEqual(len(sandbox.calls), 1)

    def test_disabled_preview_revokes_identity_without_retrying_creation(self):
        with Sandbox("disabled") as sandbox:
            status, result = self.run_cli(sandbox)
            self.assertEqual(status, 1)
            self.assertEqual(result["code"], "HTTP_404")
            self.assertEqual([c[1] for c in sandbox.calls].count("/api/v1/anonymous-sessions"), 1)
            self.assertEqual(sandbox.calls[-1][1], "/api/v1/anonymous-sessions/current")

    def test_query_or_refresh_failure_cleans_up_without_requery_or_secret_output(self):
        for behavior in ("query-failure", "refresh-failure", "query-source", "bookable"):
            with self.subTest(behavior=behavior), Sandbox(behavior) as sandbox:
                status, _ = self.run_cli(sandbox)
                self.assertEqual(status, 1)
                self.assertEqual([c[1] for c in sandbox.calls].count(BASE_PATH + "/queries"), 1)
                self.assertEqual(sandbox.calls[-2][1], BASE_PATH + "/close")
                self.assertEqual(sandbox.calls[-1][1], "/api/v1/anonymous-sessions/current")

    def test_redirect_is_not_followed_with_credentials(self):
        with Sandbox("redirect") as sandbox:
            status, result = self.run_cli(sandbox)
            self.assertEqual(status, 1)
            self.assertEqual(result["code"], "HTTP_302")
            self.assertNotIn("/trap", [c[1] for c in sandbox.calls])

    def test_cleanup_failure_is_not_reported_as_success(self):
        for behavior in ("close-failure", "revoke-failure"):
            with self.subTest(behavior=behavior), Sandbox(behavior) as sandbox:
                status, result = self.run_cli(sandbox)
                self.assertEqual(status, 1)
                self.assertEqual(result["code"], "CLEANUP_UNCONFIRMED")
                self.assertEqual([c[1] for c in sandbox.calls].count(BASE_PATH + "/close"), 1)

    def test_non_loopback_and_ambiguous_base_urls_are_rejected_before_network(self):
        for base in ("https://example.com", "http://localhost:8080", "http://127.0.0.1:99999",
                     "http://user:secret@127.0.0.1", "http://127.0.0.1/other",
                     "http://127.0.0.1?token=secret", "http://127.0.0.1#fragment"):
            with self.subTest(base=base), self.assertRaises(demo.DemoError):
                demo.PreviewDemo(base)


if __name__ == "__main__":
    unittest.main()
