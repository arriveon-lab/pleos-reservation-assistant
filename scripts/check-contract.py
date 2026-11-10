#!/usr/bin/env python3
"""Validate the OpenAPI document, examples, and optional loopback HTTP workflow."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parents[1]
contract = json.loads((ROOT / "api/openapi.json").read_text())
validate(contract)


def check(schema, value):
    Draft202012Validator({**schema, "components": contract["components"]},
                         format_checker=FormatChecker()).validate(value)


check({"$ref": "#/components/schemas/SearchRequest"},
      json.loads((ROOT / "api/examples/search.json").read_text()))
print("PASS: OpenAPI 3.1 document and search example")
for name in ("completed", "rejected-closed"):
    check({"$ref": "#/components/schemas/SearchResolution"},
          json.loads((ROOT / ("api/examples/search-reconciliation-" + name + ".json")).read_text()))

preview_contract = json.loads((ROOT / "api/place-preview-draft.json").read_text())
validate(preview_contract)


def check_preview(schema, value):
    Draft202012Validator({**schema, "components": preview_contract["components"]},
                         format_checker=FormatChecker()).validate(value)


check_preview({"$ref": "#/components/schemas/QueryRequest"},
              json.loads((ROOT / "api/examples/place-preview-query.json").read_text()))
check_preview({"$ref": "#/components/schemas/QueryRequest"},
              json.loads((ROOT / "api/examples/place-preview-recommendation-query.json").read_text()))
plan_spec = importlib.util.spec_from_file_location("recommendation_plan", ROOT / "scripts/check-recommendation-plan.py")
plan_checker = importlib.util.module_from_spec(plan_spec)
plan_spec.loader.exec_module(plan_checker)
assert preview_contract["components"]["schemas"]["RecommendationPlan"] == {
    k: v for k, v in plan_checker.SCHEMA.items() if k != "$schema"}
assert preview_contract["components"]["schemas"]["RecommendationRequirements"] == plan_checker.SCHEMA["properties"]["requirements"]


def check_preview_result(result):
    check_preview({"$ref": "#/components/schemas/Result"}, result)
    plan = result["recommendationPlan"]
    if plan is None:
        return
    plan_checker.check(plan)
    assert plan["state"] == result["state"]
    assert plan["listingFetchedAt"] == result["fetchedAt"]
    assert plan["displayValidUntil"] == result["expiresAt"]
    assert plan["rejectedRecords"] == result["rejectedRecords"]
    assert len(plan["candidates"]) == len(result["places"])
    for candidate, place in zip(plan["candidates"], result["places"]):
        for key in ("name", "cuisine", "address"):
            assert candidate[key] == place[key]
        assert candidate["straightLineDistanceMeters"] == place["distanceMeters"]


example = json.loads((ROOT / "api/examples/place-preview-recommendation-result.json").read_text())
check_preview_result(example)
for mutation in ("authority", "scope", "cancelled", "time", "order"):
    unsafe = copy.deepcopy(example)
    if mutation == "authority":
        unsafe["recommendationPlan"]["candidates"][0]["inquiryDispatchAllowed"] = True
    elif mutation == "scope":
        unsafe["recommendationPlan"]["candidates"][0]["questions"][-1]["scope"] = "NOW"
    elif mutation == "cancelled":
        unsafe.update(state="CANCELLED", places=[], fetchedAt=None, expiresAt=None)
    elif mutation == "time":
        unsafe["recommendationPlan"]["listingFetchedAt"] = "2026-11-01T00:00:00Z"
    else:
        unsafe["places"].reverse()
    try:
        check_preview_result(unsafe)
    except (AssertionError, ValidationError):
        continue
    raise AssertionError("Unsafe preview recommendation accepted: " + mutation)
print("PASS: preview draft, listing/planned examples, shared plan schema and 5 unsafe mutations rejected")


def validate_preview_exchanges():
    """Validate HTTP exchanges exported by MockMvc; all inputs and results are fictional."""
    path = ROOT / "server/build/contract-exchanges/place-preview.json"
    if not path.exists():
        raise RuntimeError("Run ./gradlew -PincludeAndroid=false verify to generate preview exchanges")
    exchanges = json.loads(path.read_text())
    assert exchanges, "Preview HTTP exchange evidence is empty"
    operations = set()
    for exchange in exchanges:
        request_path = "/place-previews" + exchange["path"]
        matches = [(route, item["post"]) for route, item in preview_contract["paths"].items()
                   if re.fullmatch(re.escape(route).replace(r"\{id\}", "[^/]+"), request_path)]
        assert len(matches) == 1, request_path
        route, operation = matches[0]
        operations.add(route)
        if exchange["status"] == 200:
            check_preview(operation["requestBody"]["content"]["application/json"]["schema"],
                          exchange["request"])
        check_preview(operation["responses"][str(exchange["status"])]["content"]["application/json"]["schema"],
                      exchange["response"])
        if exchange["path"].endswith("/queries") and exchange["status"] == 200:
            check_preview_result(exchange["response"])
    assert operations == set(preview_contract["paths"]), operations
    print(f"PASS: {len(exchanges)} fictional preview HTTP exchanges match the draft (4 operations)")


def validate_exchange(path, body, result, status, method=None):
    method = method or ("get" if body is None else "post")
    matches = [item[method] for route, item in contract["paths"].items()
               if method in item and re.fullmatch(route.replace("{id}", "[^/]+"), path)]
    assert len(matches) == 1, (method, path)
    operation = matches[0]
    if body is not None:
        check(operation["requestBody"]["content"]["application/json"]["schema"], body)
    check(operation["responses"][str(status)]["content"]["application/json"]["schema"], result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url")
    parser.add_argument("--preview-exchanges", action="store_true",
                        help="Validate fictional MockMvc exchanges generated by server tests")
    args = parser.parse_args()
    if args.preview_exchanges:
        validate_preview_exchanges()
    if args.base_url:
        spec = importlib.util.spec_from_file_location("demo", ROOT / "scripts/mock-demo.py")
        demo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(demo)
        demo.run(args.base_url, validate=validate_exchange)
        print("PASS: HTTP requests and responses match the contract")
