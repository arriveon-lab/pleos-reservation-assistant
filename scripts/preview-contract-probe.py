#!/usr/bin/env python3
"""Probe the local MOCK preview guard; does not authenticate or issue a place query."""
import argparse
import json
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--base-url", default="http://127.0.0.1:8080")
parser.add_argument("--enabled", action="store_true", help="Expect an enabled sandbox to require authentication")
args = parser.parse_args()
url = urlsplit(args.base_url)
if (url.scheme != "http" or url.hostname != "127.0.0.1" or url.username or url.password
        or url.path not in ("", "/") or url.query or url.fragment):
    parser.error("Use a loopback http://127.0.0.1:port base URL")
request = Request(args.base_url.rstrip("/") + "/api/experimental/place-previews",
                  data=b"{}", headers={"Content-Type": "application/json"})
opener = build_opener(ProxyHandler({}), NoRedirect())
try:
    with opener.open(request, timeout=5):
        raise SystemExit("FAIL: unauthenticated session creation must not succeed")
except HTTPError as error:
    expected = 401 if args.enabled else 404
    if error.code != expected:
        raise SystemExit(f"FAIL: expected HTTP {expected}, received {error.code}")
    if args.enabled:
        assert error.headers["Cache-Control"] == "no-store"
        assert json.load(error)["code"] == "PREVIEW_AUTHENTICATION_REQUIRED"
    print(f"PASS: HTTP {expected}; no authenticated session or place query was created")
