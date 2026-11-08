#!/usr/bin/env python3
"""One MOCK query with server-issued tokens. Requires the #20/#21 authentication integration."""
import argparse
import json
import re
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class DemoError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class PreviewDemo:
    def __init__(self, base):
        try:
            url = urlsplit(base)
            valid = (url.scheme == "http" and url.hostname == "127.0.0.1"
                     and not url.username and not url.password and url.path in ("", "/")
                     and not url.query and not url.fragment and (url.port or 80) > 0)
        except ValueError:
            valid = False
        if not valid:
            raise DemoError("LOOPBACK_URL_REQUIRED")
        self.base = base.rstrip("/")
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.requests = 0

    def request(self, path, body=None, token=None, key=None, method="POST"):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = Request(self.base + path, method=method, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        self.requests += 1
        try:
            with self.opener.open(request, timeout=10) as response:
                raw = response.read(262145)
                if len(raw) > 262144:
                    raise DemoError("INVALID_RESPONSE")
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise DemoError("INVALID_RESPONSE")
                return result
        except HTTPError as error:
            # Do not read, print or attach upstream payloads that can contain a secret.
            error.close()
            raise DemoError("HTTP_" + str(error.code)) from None
        except (URLError, OSError, ValueError):
            raise DemoError("REQUEST_FAILED") from None

    @staticmethod
    def tokens(result):
        access, refresh = result.get("accessToken"), result.get("refreshToken")
        if (result.get("tokenType") != "Bearer" or result.get("mode") != "MOCK"
                or not isinstance(access, str) or not re.fullmatch(r"pa_[A-Za-z0-9_-]{43}", access)
                or not isinstance(refresh, str) or not re.fullmatch(r"pr_[A-Za-z0-9_-]{43}", refresh)):
            raise DemoError("INVALID_TOKEN_RESPONSE")
        return access, refresh

    def run(self):
        status = self.request("/api/v1/status", method="GET")
        if (status.get("mode") != "MOCK" or status.get("externalCallsEnabled") is not False
                or status.get("dataSource") != "FICTIONAL_FIXTURES"):
            raise DemoError("MOCK_SERVER_REQUIRED")
        if status.get("contractVersion") != "1.1.0":
            raise DemoError("AUTH_INTEGRATION_REQUIRED")
        access = None
        preview = None
        refreshed = closed = revoked = False
        cleanup_ok = True
        result = None
        try:
            access, refresh = self.tokens(self.request("/api/v1/anonymous-sessions", {}))
            session = self.request("/api/experimental/place-previews", {}, access)
            try:
                preview = str(uuid.UUID(session["previewId"]))
            except (KeyError, TypeError, ValueError):
                raise DemoError("INVALID_PREVIEW_RESPONSE") from None
            path = "/api/experimental/place-previews/" + preview
            result = self.request(path + "/queries", {
                "generation": 1,
                "query": {"latitude": 37.544, "longitude": 127.055,
                          "radiusMeters": 1000, "limit": 3}}, access)
            if (result.get("previewId") != preview or result.get("generation") != 1
                    or result.get("state") not in ("RESULTS", "EMPTY")
                    or result.get("mode") != "MOCK" or result.get("placeSource") != "MOCK"
                    or result.get("reservationMode") != "MOCK"
                    or not isinstance(result.get("places"), list)
                    or len(result["places"]) > 3
                    or bool(result["places"]) != (result["state"] == "RESULTS")
                    or any(not isinstance(p, dict) or p.get("bookable") is not False
                           or any(p.get(k) != "UNKNOWN" for k in
                                  ("parking", "childFriendly", "reservationAvailability"))
                           for p in result["places"])):
                raise DemoError("INVALID_MOCK_RESULT")
            # A user-requested protocol check, not an automatic response to a failed request.
            # Never re-issue anonymous identity or retry/refresh-and-repeat a place query.
            key = str(uuid.uuid4())
            access, _ = self.tokens(self.request("/api/v1/anonymous-sessions/refresh",
                                                 {"refreshToken": refresh}, key=key))
            refreshed = True
            cancelled = self.request(path + "/cancel", {"generation": 2}, access)
            if (cancelled.get("previewId") != preview or cancelled.get("generation") != 2
                    or cancelled.get("state") != "CANCEL_REQUESTED"):
                raise DemoError("INVALID_PREVIEW_RESPONSE")
        finally:
            # Best effort, one attempt each, on success as well as partial failures.
            # If the network fails these actions can remain unconfirmed; never claim success.
            if preview and access:
                try:
                    response = self.request("/api/experimental/place-previews/" + preview + "/close",
                                            {}, access)
                    closed = response.get("previewId") == preview and response.get("state") == "CLOSED"
                    cleanup_ok = closed
                except DemoError:
                    cleanup_ok = False
            if access:
                try:
                    revoked = self.request("/api/v1/anonymous-sessions/current", token=access,
                                           method="DELETE").get("state") == "REVOKED"
                    cleanup_ok = cleanup_ok and revoked
                except DemoError:
                    cleanup_ok = False
        if not cleanup_ok:
            raise DemoError("CLEANUP_UNCONFIRMED")
        return {"mode": "MOCK", "placeSource": "MOCK", "state": result["state"],
                "displayed": len(result["places"]), "refreshed": refreshed,
                "closed": closed, "revoked": revoked, "httpRequests": self.requests,
                "placeQueries": 1, "externalCalls": 0}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(PreviewDemo(args.base_url).run(), ensure_ascii=False))
        return 0
    except Exception as error:
        code = error.code if isinstance(error, DemoError) else "CLIENT_FAILED"
        print(json.dumps({"status": "FAILED", "code": code}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
