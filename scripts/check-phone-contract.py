#!/usr/bin/env python3
"""Validate the standalone phone contract; optionally compare the recommendation integration."""
import argparse
import copy
import json
from pathlib import Path

from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parents[1]
PHONE = ROOT / "prototypes/phone_agent/openapi.json"


def recommendation_schemas(root):
    """A WEB_SEARCH result has no public-catalog listing or Kakao matches."""
    source = root / "api/destination-recommendation-draft.json"
    schemas = json.loads(source.read_text())["components"]["schemas"]
    web = copy.deepcopy(schemas["Recommendation"])
    web["properties"]["listing"] = {"type": "null"}
    web["properties"]["matches"] = {"type": "null"}

    def expand(value, document):
        if isinstance(value, list):
            return [expand(item, document) for item in value]
        if not isinstance(value, dict):
            return value
        if "$ref" in value:
            filename, pointer = value["$ref"].split("#", 1)
            target = (document.parent / filename).resolve() if filename else document
            if not target.is_relative_to((root / "api").resolve()):
                raise ValueError("Only local API schema references are allowed")
            selected = json.loads(target.read_text())
            for part in pointer.lstrip("/").split("/"):
                selected = selected[part.replace("~1", "/").replace("~0", "~")]
            return expand(selected, target)
        return {key: expand(item, document) for key, item in value.items()}

    return {
        "RecommendationJobState": expand(schemas["JobState"], source),
        "WebTestRecommendation": expand(web, source),
        "MockPhoneReceipt": expand(schemas["MockPhoneReceipt"], source),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--integration", action="store_true")
    args = parser.parse_args()
    contract = json.loads(PHONE.read_text())
    validate(contract, base_uri=PHONE.as_uri())
    if args.integration:
        expected = recommendation_schemas(ROOT)
        assert contract["components"]["schemas"] == expected, "Phone/recommendation schemas drifted"
    print("PASS: standalone phone OpenAPI" + (" and recommendation schema parity" if args.integration else ""))


if __name__ == "__main__":
    main()
