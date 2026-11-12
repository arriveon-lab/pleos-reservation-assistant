#!/usr/bin/env python3
"""Loopback MOCK caller; direct feedback is collected only from the person running --interactive."""
import argparse
import json
from urllib.parse import urlsplit
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18084")
    parser.add_argument("--area", default="가상 서울숲")
    parser.add_argument("--needs", nargs="+", choices=["PET_FRIENDLY", "INDOOR_PET_FRIENDLY", "PARKING", "CHILD_FRIENDLY"],
                        default=["PET_FRIENDLY", "INDOOR_PET_FRIENDLY", "PARKING"])
    parser.add_argument("--personalization-consent", action="store_true")
    parser.add_argument("--interactive", action="store_true")
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port or url.path not in ("", "/") or url.query or url.fragment or url.username:
        parser.error("Use an explicit loopback MOCK server origin")
    origin = args.base_url.rstrip("/")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(method, path, body=None, token=None, key=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(origin + path, headers=headers, method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        with opener.open(request, timeout=35) as response:
            return json.load(response)

    status = call("GET", "/api/v1/status")
    if status["mode"] != "MOCK" or status["externalCallsEnabled"]:
        raise SystemExit("MOCK server with external calls disabled required")
    token = call("POST", "/api/v1/anonymous-sessions", {}, key=str(uuid.uuid4()))["accessToken"]
    base = "/api/experimental/web-recommendations"
    result = call("POST", base, {"viewId": str(uuid.uuid4()), "generation": 1, "area": args.area,
                                 "requiredNeeds": sorted(set(args.needs)),
                                 "personalizationConsent": args.personalization_consent}, token, str(uuid.uuid4()))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    request_id = result["requestId"]
    try:
        if args.interactive and result["result"]["state"] == "RESULTS":
            selected = input("선택한 가상 후보 번호(그냥 Enter면 피드백 저장 안 함): ").strip()
            if selected:
                fit = input("직접 평가 MATCH / PARTIAL / NO_MATCH: ").strip().upper()
                if fit not in ("MATCH", "PARTIAL", "NO_MATCH"):
                    raise ValueError("Invalid direct feedback")
                saved = call("PUT", base + "/" + request_id + "/feedback",
                             {"selectedOrdinal": int(selected), "fit": fit, "reasons": []}, token)
                print(json.dumps(saved, ensure_ascii=False, indent=2))
    finally:
        call("POST", base + "/" + request_id + "/close", token=token)


if __name__ == "__main__":
    main()
