"""Loopback test UI: default MOCK, with explicitly enabled optional live telephony."""
import argparse
import asyncio
import importlib.util
import os
import hashlib
import hmac
import http.client
import json
import re
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .service import DomainError, ReservationService, conditions, require
from .web_recommendation import booking_web_needs, search_web

STATIC = Path(__file__).with_name("static")
RECOMMENDATION_PATH = re.compile(
    r"/api/internal/mock-recommendation-bookings/([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})")


def load_test_restaurants(path):
    entries = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(entries, list) or not 1 <= len(entries) <= 20:
        raise ValueError("표시할 시험 식당 목록을 확인해 주세요.")
    result = {}
    for entry in entries:
        if not isinstance(entry, dict) or not all(
            isinstance(entry.get(key), str) and entry[key].strip()
            for key in ("id", "name", "address", "phone", "sourceUrl")
        ):
            raise ValueError("시험 식당의 지점, 주소, 공개 번호와 출처가 필요해요.")
        source = urlsplit(entry["sourceUrl"])
        if (not re.fullmatch(r"test-[a-z0-9-]{1,60}", entry["id"]) or entry["id"] in result
                or not re.fullmatch(r"(?:02|0[3-7]\d|050[4-8])-\d{3,4}-\d{4}", entry["phone"])
                or source.scheme != "https" or not source.hostname or source.username or source.password):
            raise ValueError("시험 식당의 식별자, 공개 사업장 번호와 HTTPS 출처를 확인해 주세요.")
        result[entry["id"]] = {key: entry[key] for key in ("id", "name", "address", "phone", "sourceUrl")}
    return result


def make_server(service, port=8765, *, recommendation_token=None, recommendation_catalog=None,
                worker_ready=None, web_search=False, web_search_port=None):
    def current_state(owner):
        value = service.state(owner)
        if web_search:
            value["webSearch"] = {"configured": web_search_port is not None}
        try:
            from .voice.config import VoiceConfig, load_env_file
            load_env_file(os.environ.get("PHONE_ENV_FILE", "work/phone-live.env"))
            config = VoiceConfig.from_env()
            value["voice"] = config.report()
            value["voice"]["sdkInstalled"] = importlib.util.find_spec("livekit") is not None
            value["voice"]["ready"] = not config.missing() and config.enabled and value["voice"]["sdkInstalled"]
            if service.test_mode:
                value["voice"]["testPhoneInput"] = config.test_phone_input
                registered = config.test_mobile if not config.test_phone_input else None
                value["voice"]["testRecipientLast4"] = registered[-4:] if registered else None
                if value["voice"]["ready"] and worker_ready is not None:
                    value["voice"]["workerReady"] = worker_ready()
                    value["voice"]["ready"] = value["voice"]["workerReady"]
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
                self.send_header("Set-Cookie", f"{self._cookie_name()}={cookie}; HttpOnly; SameSite=Strict; Path=/")
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

        def _cookie_name(self):
            if service.test_mode:
                return f"phone_test_session_{self.server.server_port}"
            return "phone_session"

        def _owner(self, create=False):
            jar = cookies.SimpleCookie()
            try:
                jar.load(self.headers.get("Cookie", ""))
            except cookies.CookieError:
                pass
            cookie = jar.get(self._cookie_name())
            token = cookie.value if cookie else None
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

        def _recommendation_owner(self):
            require(recommendation_token is not None and recommendation_catalog is not None,
                    "NOT_FOUND", "경로를 찾을 수 없어요.", 404)
            provided = self.headers.get("Authorization", "").encode()
            require(hmac.compare_digest(provided, ("Bearer " + recommendation_token).encode()),
                    "SERVICE_AUTH_REQUIRED", "서비스 인증이 필요해요.", 401)
            subject = self.headers.get("X-Recommendation-Owner", "")
            require(re.fullmatch(r"[A-Za-z0-9._:-]{1,100}", subject),
                    "INVALID_OWNER", "사용자 식별자를 확인해 주세요.", 400)
            return "recommendation:" + subject

        def do_GET(self):
            try:
                self._host()
                path = urlsplit(self.path).path
                recommendation = RECOMMENDATION_PATH.fullmatch(path)
                if recommendation:
                    self._json(200, service.recommendation_attempt(self._recommendation_owner(), recommendation[1]))
                    return
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
                recommendation = RECOMMENDATION_PATH.fullmatch(self.path)
                browser_paths = ("/api/action", "/api/live/dial", "/api/web-recommendations") if web_search else (
                    "/api/action", "/api/live/dial")
                if not recommendation and self.path not in browser_paths:
                    raise DomainError("NOT_FOUND", "경로를 찾을 수 없어요.", 404)
                if self.headers.get_content_type() != "application/json":
                    raise DomainError("INVALID_CONTENT_TYPE", "JSON 요청이 필요해요.", 415)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    length = 0
                if not 0 < length <= 24576:
                    raise DomainError("BODY_SIZE", "요청 크기를 확인해 주세요.", 413)
                owner = self._recommendation_owner() if recommendation else self._owner()[0]
                try:
                    body = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeDecodeError):
                    raise DomainError("INVALID_JSON", "JSON을 확인해 주세요.", 400) from None
                if not isinstance(body, dict):
                    raise DomainError("INVALID_INPUT", "요청은 JSON 객체여야 해요.", 400)
                if self.path == "/api/web-recommendations":
                    snapshot = None
                    if service.web_test_enabled and "conditions" in body:
                        snapshot = conditions(body["conditions"])
                        body["requiredNeeds"] = booking_web_needs(snapshot)
                    result = search_web(web_search_port, body)
                    if snapshot is not None and (result.get("result") or {}).get("webState") == "RESULTS":
                        result["searchId"] = service.remember_web_search(owner, result, snapshot)
                    self._json(200, result)
                    return
                if recommendation:
                    candidates = body.get("candidates")
                    require(isinstance(candidates, list) and 1 <= len(candidates) <= 3
                            and all(isinstance(item, dict) and type(item.get("ordinal")) is int and item["ordinal"] == index
                                    for index, item in enumerate(candidates, 1)),
                            "INVALID_CANDIDATE", "추천 후보를 확인해 주세요.", 400)
                    roster = []
                    for item in candidates:
                        name, address = item.get("name"), item.get("address")
                        restaurant = recommendation_catalog.get((name, address)) if (
                            isinstance(name, str) and isinstance(address, str)) else None
                        require(restaurant, "UNVERIFIED_MOCK_CANDIDATE", "가상 식당 목록에 없는 지점이에요.", 400)
                        roster.append({"ordinal": item["ordinal"], "name": name, "address": address,
                                       "restaurantId": restaurant})
                    body["candidates"] = roster
                    require(all(key in body for key in ("date", "time", "reservationName", "conditions")),
                            "INVALID_INPUT", "승인할 예약 조건을 확인해 주세요.", 400)
                    self._json(200, service.recommendation_attempt(
                        owner, recommendation[1], body, self.headers.get("Idempotency-Key")))
                    return
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
                    if worker_ready is not None and not worker_ready():
                        raise DomainError("LIVE_NOT_READY", "음성 에이전트 연결이 준비되면 다시 시도해 주세요.", 409)
                    from .voice.transport import dial_booking
                    result = asyncio.run(dial_booking(service, config, owner, body.get("bookingId"),
                                                      body.get("version"), body.get("requestId"),
                                                      test_phone_number=body.get("testPhoneNumber")))
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
    parser.add_argument("--mock-recommendation-catalog", type=Path)
    parser.add_argument("--test-restaurant-directory", type=Path)
    parser.add_argument("--recommendation-port", type=int, metavar="PORT")
    parser.add_argument("--worker-health-port", type=int, metavar="PORT")
    args = parser.parse_args()
    for port in (args.recommendation_port, args.worker_health_port):
        if port is not None and not 1 <= port <= 65535:
            parser.error("Check the loopback service port.")
    token = None
    registry = None
    restaurants = {}
    if args.mock_recommendation_catalog:
        token = os.environ.get("PHONE_RECOMMENDATION_TOKEN", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
            parser.error("PHONE_RECOMMENDATION_TOKEN must contain 32–128 URL-safe characters.")
        registry = {}
        for entry in json.loads(args.mock_recommendation_catalog.read_text())["entries"]:
            source = re.fullmatch(r"fictional-catalog-(\d+)", entry["sourceRecordId"])
            if (not source or entry["name"] != f"가상 공공데이터 식당 {source[1]}"
                    or entry["address"] != f"가상 성수 시험주소 {source[1]}"):
                parser.error("Use the fictional recommendation catalog fixture.")
            key = (entry["name"], entry["address"])
            restaurant = "mock-" + entry["catalogPlaceId"]
            if key in registry or restaurant in restaurants:
                parser.error("The fictional catalog contains duplicate restaurant identities.")
            registry[key] = restaurant
            restaurants[restaurant] = entry["name"]
    args.database.parent.mkdir(parents=True, exist_ok=True)
    test_restaurants = load_test_restaurants(args.test_restaurant_directory) if args.test_restaurant_directory else None
    web_test_enabled = args.recommendation_port is not None
    service = ReservationService(args.database, mock_restaurants=restaurants, test_restaurants=test_restaurants,
                                 web_test_enabled=web_test_enabled)

    def worker_ready():
        connection = http.client.HTTPConnection("127.0.0.1", args.worker_health_port, timeout=0.5)
        try:
            connection.request("GET", "/")
            return connection.getresponse().status == 200
        except (OSError, http.client.HTTPException):
            return False
        finally:
            connection.close()

    server = make_server(service, args.port, recommendation_token=token, recommendation_catalog=registry,
                         web_search=web_test_enabled, web_search_port=args.recommendation_port,
                         worker_ready=worker_ready if args.worker_health_port is not None else None)
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
