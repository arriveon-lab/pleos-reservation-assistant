#!/usr/bin/env python3
"""Offline, private OA-18655 catalog. No fetching, dialing or authority to dispatch."""
from collections import Counter
from datetime import datetime, timezone
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import unicodedata

SPEC = importlib.util.spec_from_file_location(
    "catalog_quality", Path(__file__).with_name("seoul-place-quality.py")
)
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)
CATALOG_URL = "https://data.seoul.go.kr/dataList/OA-18655/A/1/datasetView.do"
LICENSE_URL = "https://www.kogl.or.kr/info/licenseType1.do"
ATTRIBUTION = "서울특별시 성동구 · 서울 열린데이터광장 OA-18655 · 공공누리 제1유형"


def catalog_id(source_id):
    return "sdo-" + hashlib.sha256((CATALOG_URL + "#" + source_id).encode()).hexdigest()[:32]


def timestamp(value):
    return value.isoformat().replace("+00:00", "Z") if value is not None else None


def build_catalog(rows, acquired_at, source_snapshot_at=None):
    """Caller-supplied snapshot provenance is never inferred from retrieval/row timestamps."""
    if len(rows) > quality.MAX_ROWS:
        raise quality.QualityError("ROW_LIMIT")
    if source_snapshot_at is not None and source_snapshot_at > acquired_at:
        raise quality.QualityError("FUTURE_SOURCE_SNAPSHOT")
    counts = Counter(row.get("MGTNO", "").strip() for row in rows)
    coordinates = quality.CoordinateCheck()
    entries, rejected = [], Counter()
    for row in rows:
        source_id = row.get("MGTNO", "").strip()
        name, address = (row.get(k, "").strip() for k in ("BPLCNM", "RDNWHLADDR"))
        if not source_id or counts[source_id] != 1:
            rejected["MISSING_OR_DUPLICATE_ID"] += 1
            continue
        if quality.pilot_status(row) != "NORMAL_STATUS":
            rejected["STATUS_NOT_NORMAL"] += 1
            continue
        if (not name or not address or max(len(source_id), len(name), len(address)) > 512
                or any(unicodedata.category(c) == "Cc" for value in (source_id, name, address) for c in value)):
            rejected["INVALID_IDENTITY"] += 1
            continue
        kind, phone = quality.phone_kind(row.get("SITETEL", ""))
        # Syntax is not merchant identity verification. Mobile numbers require separate review.
        if kind != "SYNTAX_ONLY":
            rejected["PHONE_REVIEW_REQUIRED"] += 1
            continue
        try:
            lon, lat = coordinates.convert(row.get("X"), row.get("Y"))
        except quality.QualityError:
            rejected["INVALID_COORDINATES"] += 1
            continue
        if quality.distance(lon, lat) > 1000:
            rejected["OUTSIDE_PILOT"] += 1
            continue
        entries.append({
            "catalogPlaceId": catalog_id(source_id),
            "sourceRecordId": source_id,
            "name": name,
            "address": address,
            "phone": phone,
            "latitude": lat,
            "longitude": lon,
            "rowModifiedRaw": row.get("LASTMODTS", ""),
            "rowUpdatedRaw": row.get("UPDATEDT", ""),
            "rowTimeZone": "UNKNOWN",
            "administrativeStatus": "NORMAL_REPORTED",
            "contactStatus": "UNVERIFIED",
        })
    entries.sort(key=lambda entry: entry["catalogPlaceId"])
    catalog = {
        "schemaVersion": "0.1.0-draft",
        "sourceKind": "SEOUL_OPEN_DATA",
        "datasetId": "OA-18655",
        "catalogUrl": CATALOG_URL,
        "licenseUrl": LICENSE_URL,
        "attribution": ATTRIBUTION,
        "sourceCrs": "EPSG:5174",
        "targetCrs": "EPSG:4326",
        "acquiredAt": timestamp(acquired_at),
        "sourceSnapshotAt": timestamp(source_snapshot_at),
        "inputRows": len(rows),
        "rejectedRows": len(rows) - len(entries),
        "entries": entries,
    }
    return catalog, {"inputRows": len(rows), "acceptedRows": len(entries),
                     "rejectedReasons": dict(rejected), "inquiryDispatchAllowed": False}


def write_private(path, catalog):
    """Create once; never overwrite existing files or relax a directory's permissions."""
    encoded = (json.dumps(catalog, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    if len(encoded) > quality.MAX_BYTES:
        raise quality.QualityError("CATALOG_SIZE_LIMIT")
    parent = path.parent
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    mode = parent.lstat().st_mode
    if not stat.S_ISDIR(mode) or stat.S_IMODE(mode) & 0o077:
        raise quality.QualityError("PRIVATE_OUTPUT_DIRECTORY_REQUIRED")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(encoded)
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Separately obtained local XML")
    parser.add_argument("--output", type=Path, required=True, help="New private local JSON file")
    parser.add_argument("--acquired-at", required=True, help="Actual input retrieval timestamp")
    parser.add_argument("--source-snapshot-at", help="Confirmed source snapshot time; omit if unknown")
    args = parser.parse_args(argv)
    try:
        if not args.output.name.endswith(".private.json"):
            raise quality.QualityError("PRIVATE_OUTPUT_SUFFIX_REQUIRED")
        acquired = quality.instant(args.acquired_at)
        if acquired > datetime.now(timezone.utc):
            raise quality.QualityError("FUTURE_ACQUISITION")
        snapshot = quality.instant(args.source_snapshot_at) if args.source_snapshot_at else None
        with args.input.open("rb") as stream:
            rows = quality.parse_xml(stream.read(quality.MAX_BYTES + 1))
        catalog, report = build_catalog(rows, acquired, snapshot)
        write_private(args.output, catalog)
        print(json.dumps(report, ensure_ascii=False))
        return 0
    except quality.QualityError as error:
        print(json.dumps({"error": str(error)}))
        return 2
    except (OSError, ValueError):
        print(json.dumps({"error": "CATALOG_IO_FAILED"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
