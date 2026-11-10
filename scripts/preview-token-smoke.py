#!/usr/bin/env python3
"""Validate #16 with actual server-issued tokens against an opt-in loopback MOCK server."""
import argparse
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def exercise(base):
    parsed = urllib.parse.urlsplit(base)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.username
            or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("Use a loopback http://127.0.0.1:port base URL")
    preview_path = ROOT / "api/place-preview-draft.json"
    if not preview_path.exists():
        raise RuntimeError("Integrate #16 before running the preview token smoke test")
    contracts = {"/api/v1": json.loads((ROOT / "api/openapi.json").read_text()),
                 "/api/experimental": json.loads(preview_path.read_text())}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    count = 0

    def api(path, body=None, token=None, key=None, expected=200, method="POST"):
        nonlocal count
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(base.rstrip("/") + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with opener.open(request, timeout=10) as response:
                status, result = response.status, json.load(response)
                assert response.headers["Cache-Control"] == "no-store"
        except urllib.error.HTTPError as error:
            status, result = error.code, json.load(error)
            assert error.headers["Cache-Control"] == "no-store"
            assert error.headers["Referrer-Policy"] == "no-referrer"
        assert status == expected, (path, status, expected, result.get("code"))
        prefix = "/api/v1" if path.startswith("/api/v1/") else "/api/experimental"
        contract = contracts[prefix]
        route = path.removeprefix(prefix)
        operations = [item[method.lower()] for pattern, item in contract["paths"].items()
                      if method.lower() in item and re.fullmatch(re.escape(pattern).replace(r"\{id\}", "[^/]+"), route)]
        assert len(operations) == 1
        schema = operations[0]["responses"][str(status)]["content"]["application/json"]["schema"]
        Draft202012Validator({**schema, "components": contract["components"]},
                             format_checker=FormatChecker()).validate(result)
        count += 1
        return result

    a = api("/api/v1/anonymous-sessions", {})
    b = api("/api/v1/anonymous-sessions", {})
    session = api("/api/experimental/place-previews", {}, a["accessToken"])
    path = "/api/experimental/place-previews/" + session["previewId"]
    query = json.loads((ROOT / "api/examples/place-preview-query.json").read_text())
    result = api(path + "/queries", query, a["accessToken"])
    assert result["state"] == "RESULTS" and len(result["places"]) == 3
    assert all(not place["bookable"] for place in result["places"])
    for suffix, body in [("/queries", query), ("/cancel", {"generation": 1}), ("/close", {})]:
        api(path + suffix, body, b["accessToken"], expected=404)
    key = str(uuid.uuid4())
    refreshed = api("/api/v1/anonymous-sessions/refresh", {"refreshToken": a["refreshToken"]}, key=key)
    assert api("/api/v1/anonymous-sessions/refresh", {"refreshToken": a["refreshToken"]}, key=key) == refreshed
    query["generation"] = 2
    assert api(path + "/queries", query, refreshed["accessToken"])["state"] == "RESULTS"
    api(path + "/cancel", {"generation": 2}, refreshed["accessToken"])
    api(path + "/close", {}, refreshed["accessToken"])
    api(path + "/queries", query, refreshed["accessToken"], expected=404)
    api("/api/v1/anonymous-sessions/current", token=refreshed["accessToken"], method="DELETE")
    api("/api/experimental/place-previews", {}, refreshed["accessToken"], expected=401)
    api("/api/experimental/place-previews", {}, b["refreshToken"], expected=401)
    print(f"PASS: {count} real token HTTP exchanges; preview lifecycle, cross-owner isolation, refresh identity, revocation, schemas")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    exercise(parser.parse_args().base_url)
