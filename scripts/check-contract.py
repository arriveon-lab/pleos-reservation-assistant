#!/usr/bin/env python3
"""Validate the OpenAPI document, examples, and optional loopback HTTP workflow."""
import argparse
import importlib.util
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator, FormatChecker
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


def validate_exchange(path, body, result, status):
    method = "get" if body is None else "post"
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
    args = parser.parse_args()
    if args.base_url:
        spec = importlib.util.spec_from_file_location("demo", ROOT / "scripts/mock-demo.py")
        demo = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(demo)
        demo.run(args.base_url, validate=validate_exchange)
        print("PASS: HTTP requests and responses match the contract")
