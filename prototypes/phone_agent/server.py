"""Loopback test UI: default MOCK, with explicitly enabled optional live telephony."""
import argparse
import asyncio
import importlib.util
import os
import hashlib
import json
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .service import DomainError, ReservationService

STATIC = Path(__file__).with_name("static")


def make_server(service, port=8765):
    def current_state(owner):
        value = service.state(owner)
        try:
            from .voice.config import VoiceConfig, load_env_file
            load_env_file(os.environ.get("PHONE_ENV_FILE", "work/phone-live.env"))
            config = VoiceConfig.from_env()
            value["voice"] = config.report()
            value["voice"]["sdkInstalled"] = importlib.util.find_spec("livekit") is not None
            value["voice"]["ready"] = not config.missing() and config.enabled and value["voice"]["sdkInstalled"]
        except ValueError:
            value["voice"] = {"ready": False, "configurationError": True}
        return value

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # Do not copy user conditions or session cookies into access logs.

        def _headers(self, status, content_type, length, cookie=None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(length))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
            if cookie:
                self.send_header("Set-Cookie", f"phone_session={cookie}; HttpOnly; SameSite=Strict; Path=/")
            self.end_headers()

        def _json(self, status, body, cookie=None):
            raw = json.dumps(body, ensure_ascii=False).encode()
            self._headers(status, "application/json; charset=utf-8", len(raw), cookie)
            self.wfile.write(raw)

        def _host(self):
            expected = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
            if self.headers.get("Host") not in expected:
                raise DomainError("INVALID_HOST", "로컬 주소로 접속해 주세요.", 403)
            return "http://" + self.headers["Host"]

        def _owner(self, create=False):
            jar = cookies.SimpleCookie()
            try:
                jar.load(self.headers.get("Cookie", ""))
            except cookies.CookieError:
                pass
            token = jar["phone_session"].value if "phone_session" in jar else None
            if not create:
                require_header = self.headers.get("X-Prototype-Request")
                if not token or require_header != "1":
                    raise DomainError("SESSION_REQUIRED", "먼저 테스트 화면을 열어 주세요.", 403)
                digest = hashlib.sha256(token.encode()).hexdigest()
                with service.lock:
                    row = service.db.execute("SELECT owner FROM sessions WHERE token_hash=?", (digest,)).fetchone()
                if not row:
                    raise DomainError("SESSION_REQUIRED", "세션을 다시 열어 주세요.", 403)
                return row[0], None
            owner, actual = service.session(token)
            return owner, actual if actual != token else None

        def do_GET(self):
            try:
                self._host()
                path = urlsplit(self.path).path
                if path == "/api/state":
                    owner, cookie = self._owner(create=True)
                    self._json(200, current_state(owner), cookie)
                    return
                files = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
                         "/style.css": ("style.css", "text/css")}
                if path not in files:
                    raise DomainError("NOT_FOUND", "페이지를 찾을 수 없어요.", 404)
                filename, content_type = files[path]
                raw = (STATIC / filename).read_bytes()
                self._headers(200, content_type + "; charset=utf-8", len(raw))
                self.wfile.write(raw)
            except DomainError as error:
                self._json(error.status, {"error": {"code": error.code, "message": error.message}})

        def do_POST(self):
            try:
                origin = self._host()
                if self.headers.get("Origin") not in (None, origin):
                    raise DomainError("ORIGIN_DENIED", "다른 사이트에서 보낸 요청은 받지 않아요.", 403)
                if self.path not in ("/api/action", "/api/live/dial"):
                    raise DomainError("NOT_FOUND", "경로를 찾을 수 없어요.", 404)
                if self.headers.get_content_type() != "application/json":
                    raise DomainError("INVALID_CONTENT_TYPE", "JSON 요청이 필요해요.", 415)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    length = 0
                if not 0 < length <= 24576:
                    raise DomainError("BODY_SIZE", "요청 크기를 확인해 주세요.", 413)
                owner, _ = self._owner()
                try:
                    body = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeDecodeError):
                    raise DomainError("INVALID_JSON", "JSON을 확인해 주세요.", 400) from None
                if not isinstance(body, dict):
                    raise DomainError("INVALID_INPUT", "요청은 JSON 객체여야 해요.", 400)
                if self.path == "/api/live/dial":
                    from .voice.config import ConfigurationError, VoiceConfig, load_env_file
                    try:
                        load_env_file(os.environ.get("PHONE_ENV_FILE", "work/phone-live.env"))
                        config = VoiceConfig.from_env()
                        config.require_live()
                    except ConfigurationError as error:
                        raise DomainError("LIVE_NOT_READY", str(error), 409) from None
                    if importlib.util.find_spec("livekit") is None:
                        raise DomainError("LIVE_NOT_READY", "음성 SDK가 설치된 Python으로 실행해 주세요.", 409)
                    from .voice.transport import dial_booking
                    result = asyncio.run(dial_booking(service, config, owner, body.get("bookingId"),
                                                      body.get("version"), body.get("requestId")))
                else:
                    result = service.command(owner, body.get("action"), body.get("payload", {}), body.get("requestId"))
                cookie = None
                if body.get("action") == "delete_all":
                    owner, cookie = service.session()
                self._json(200, {"result": result, "state": current_state(owner)}, cookie)
            except DomainError as error:
                self._json(error.status, {"error": {"code": error.code, "message": error.message}})
            except Exception:
                self._json(500, {"error": {"code": "INTERNAL_ERROR", "message": "처리하지 못했어요. 상태를 새로 조회해 주세요."}})

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--database", type=Path, default=Path("data/phone-agent-prototype.sqlite3"))
    args = parser.parse_args()
    args.database.parent.mkdir(parents=True, exist_ok=True)
    service = ReservationService(args.database)
    server = make_server(service, args.port)
    print(f"Phone agent test UI: http://127.0.0.1:{server.server_port}", flush=True)
    print("MOCK by default. Optional LIVE requires explicit configuration and approval. Use fictional booking data.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        service.close()


if __name__ == "__main__":
    main()
