#!/usr/bin/env python3
"""Validate only the committed fictional local-catalog contract. No network or private inputs."""
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "api/seoul-place-catalog-draft.schema.json").read_text())
fixture = json.loads((root / "server/src/test/resources/places/seoul-catalog-fictional.json").read_text())
Draft202012Validator.check_schema(schema)
Draft202012Validator(schema, format_checker=FormatChecker()).validate(fixture)
assert fixture["sourceKind"] == "SEOUL_OPEN_DATA"
assert all(row["sourceRecordId"].startswith("fictional-") for row in fixture["entries"])
assert all(row["contactStatus"] == "UNVERIFIED" for row in fixture["entries"])
print("Fictional public catalog schema: PASS; no private input or network")
