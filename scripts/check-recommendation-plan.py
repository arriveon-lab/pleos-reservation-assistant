#!/usr/bin/env python3
"""Validate the REC-08 draft and fictional examples; no network or provider execution."""
import argparse
import json
from datetime import datetime, timedelta
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = json.loads((ROOT / "api/recommendation-plan-draft.schema.json").read_text())
Draft202012Validator.check_schema(SCHEMA)
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())


def check(plan):
    VALIDATOR.validate(plan)
    candidates = plan["candidates"]
    assert [c["ordinal"] for c in candidates] == list(range(1, len(candidates) + 1))
    assert plan["duplicateRecords"] + plan["outsideWindowRecords"] + len(candidates) <= plan["scannedRecords"]
    required = set(plan["requirements"]["requiredFeatures"])
    if plan["requirements"]["party"]["children"] > 0:
        required.add("childFriendly")
    expected = sorted(required) + ["arrivalWaitMinutes", "reservationAvailable"]
    for candidate in candidates:
        assert [q["field"] for q in candidate["questions"]] == expected
        preferred = candidate["cuisine"] in plan["requirements"]["preferredCuisines"]
        assert ("PREFERRED_CUISINE_MATCH" in candidate["reasons"]) == preferred
        for key in ("cuisine", "address"):
            assert (key.upper() in candidate["missingInformation"]) == (candidate[key] is None)
    if plan["state"] != "FAILED":
        start = datetime.fromisoformat(plan["listingFetchedAt"].replace("Z", "+00:00"))
        end = datetime.fromisoformat(plan["displayValidUntil"].replace("Z", "+00:00"))
        assert end - start == timedelta(minutes=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, help="Validate an additional locally generated MOCK plan")
    args = parser.parse_args()
    paths = [ROOT / "api/examples/recommendation-plan-mock.json",
             ROOT / "server/build/rec08/mock-recommendation-plan.json"]
    if args.file:
        paths.append(args.file)
    for path in paths:
        raw = path.read_bytes()
        assert len(raw) <= 65_536
        check(json.loads(raw))
    print(f"PASS: REC-08 draft schema and {len(paths)} fictional plans, including verification questions")
