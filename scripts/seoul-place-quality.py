#!/usr/bin/env python3
"""Offline OA-18655 XML quality profile. No HTTP, row exports, contact values or booking authority."""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import pyproj
from pyproj import CRS, Transformer, network

SERVICE = "LOCALDATA_072404_SD"
MAX_BYTES = 2 * 1024 * 1024
MAX_ROWS = 1000
KST = timezone(timedelta(hours=9))
FIELDS = (
    "MGTNO",
    "BPLCNM",
    "SITETEL",
    "RDNWHLADDR",
    "TRDSTATEGBN",
    "TRDSTATENM",
    "LASTMODTS",
    "UPDATEDT",
    "X",
    "Y",
)
PILOT = (127.055, 37.544)


class QualityError(Exception):
    """Codes only: never attach input values, paths or parser/PROJ exception messages."""


def instant(value):
    if not isinstance(value, str):
        raise QualityError("INVALID_TIMESTAMP")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError()
        return parsed.astimezone(timezone.utc)
    except ValueError:
        raise QualityError("INVALID_TIMESTAMP") from None


def row_time(value):
    if not isinstance(value, str) or not value.strip():
        return None
    value = value.strip()
    try:
        if re.fullmatch(r"\d{14}", value):
            parsed = datetime.strptime(value, "%Y%m%d%H%M%S")
        elif re.fullmatch(r"\d{8}", value):
            parsed = datetime.strptime(value, "%Y%m%d")
        else:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        # Explicit assessment assumption; upstream field timezone remains to be confirmed.
        return (
            parsed.replace(tzinfo=KST).astimezone(timezone.utc)
            if parsed.tzinfo is None
            else parsed.astimezone(timezone.utc)
        )
    except ValueError:
        raise QualityError("INVALID_ROW_TIMESTAMP") from None


def parse_xml(raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_BYTES:
        raise QualityError("INPUT_SIZE_LIMIT")
    try:
        text = raw.decode("utf-8-sig")
        if "\x00" in text:
            raise UnicodeError()
    except UnicodeError:
        raise QualityError("INVALID_ENCODING") from None
    if re.search(r"<!\s*(DOCTYPE|ENTITY)", text, re.I):
        raise QualityError("UNSAFE_XML")
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        raise QualityError("INVALID_XML") from None
    if root.tag == "RESULT" and root.findtext("CODE") == "INFO-200" and not root.findall("row"):
        return []
    if root.tag != SERVICE:
        raise QualityError("UNEXPECTED_SERVICE")
    code = root.findtext("RESULT/CODE")
    rows = root.findall("row")
    if code == "INFO-200" and not rows:
        return []
    if code != "INFO-000":
        raise QualityError("UPSTREAM_RESULT_NOT_SUCCESS")
    try:
        total = int(root.findtext("list_total_count", ""))
        if total < len(rows) or total < 0 or (total > 0 and not rows):
            raise ValueError()
    except ValueError:
        raise QualityError("INVALID_TOTAL_COUNT") from None
    if len(rows) > MAX_ROWS:
        raise QualityError("ROW_LIMIT")
    result = []
    for row in rows:
        fields = {}
        for child in row:
            if child.tag in fields or len(child):
                raise QualityError("INVALID_ROW_SHAPE")
            if len(child.text or "") > 4096:
                raise QualityError("FIELD_SIZE_LIMIT")
            fields[child.tag] = child.text or ""
        result.append(fields)
    return result


class CoordinateCheck:
    def __init__(self):
        # Explicitly disable PROJ downloads, including environments that set PROJ_NETWORK=ON.
        network.set_network_enabled(False)
        self.crs = CRS.from_epsg(5174)
        self.transformer = Transformer.from_crs(
            self.crs, 4326, always_xy=True, allow_ballpark=False, only_best=True
        )

    def convert(self, x, y):
        try:
            if isinstance(x, bool) or isinstance(y, bool):
                raise ValueError()
            easting, northing = float(x), float(y)
            if not all(math.isfinite(v) for v in (easting, northing)) or (
                easting == 0 and northing == 0
            ):
                raise ValueError()
            lon, lat = self.transformer.transform(easting, northing, errcheck=True)
            area = self.crs.area_of_use
            if not (
                math.isfinite(lon)
                and math.isfinite(lat)
                and area.west <= lon <= area.east
                and area.south <= lat <= area.north
            ):
                raise ValueError()
            return lon, lat
        except (ValueError, TypeError, pyproj.exceptions.ProjError):
            raise QualityError("INVALID_COORDINATES") from None

    def info(self):
        return {
            "sourceCrs": "EPSG:5174",
            "targetCrs": "EPSG:4326",
            "axisOrder": "X_EASTING_Y_NORTHING_TO_LONGITUDE_LATITUDE",
            "pyprojVersion": pyproj.__version__,
            "projVersion": pyproj.proj_version_str,
            "operation": self.transformer.description,
            "operationAccuracyMeters": self.transformer.accuracy,
            "networkEnabled": network.is_network_enabled(),
            "allowBallpark": False,
        }


def phone_kind(value):
    value = value.strip()
    if not value or value.lower() in ("null", "none", "n/a", "-", "unknown"):
        return "MISSING", None
    if not re.fullmatch(r"[0-9 ()+\-]+", value):
        return "UNRECOGNIZED", None
    digits = re.sub(r"\D", "", value)
    if digits.startswith("82"):
        digits = "0" + digits[2:]
    if not re.fullmatch(r"(?:02\d{7,8}|0[3-6]\d\d{7,8}|070\d{8}|01[016789]\d{7,8})", digits):
        return "UNRECOGNIZED", None
    if re.fullmatch(r"0+", digits[-7:]) or re.match(r"^(?:02|0\d{2})0{3,4}", digits):
        return "PLACEHOLDER", digits
    return ("MOBILE_REVIEW" if digits.startswith("01") else "SYNTAX_ONLY"), digits


def distance(lon, lat):
    lon1, lat1, lon2, lat2 = map(math.radians, (*PILOT, lon, lat))
    a = (
        math.sin((lat2 - lat1) / 2) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    )
    return 6_371_000 * 2 * math.asin(math.sqrt(min(1, a)))


def profile(rows, now, mode, snapshot_as_of=None):
    if mode not in ("MOCK", "PUBLIC_SAMPLE", "LOCAL_INPUT") or now.tzinfo is None:
        raise QualityError("INVALID_PROFILE_CONTEXT")
    if (
        not isinstance(rows, list)
        or len(rows) > MAX_ROWS
        or any(not isinstance(r, dict) for r in rows)
    ):
        raise QualityError("INVALID_ROWS")
    now = now.astimezone(timezone.utc)
    for row in rows:
        if any(
            not isinstance(k, str) or not isinstance(v, str) or len(v) > 4096
            for k, v in row.items()
        ):
            raise QualityError("INVALID_ROW_SHAPE")
    observed = set().union(*(r.keys() for r in rows)) if rows else set()
    missing_columns = sorted(set(FIELDS) - observed) if rows else []
    if missing_columns:
        raise QualityError("SCHEMA_MISMATCH")
    coordinates = CoordinateCheck()
    counts = Counter(
        {
            key: 0
            for key in (
                "missingId",
                "duplicateId",
                "conflictingId",
                "missingName",
                "missingAddress",
                "missingCoordinates",
                "invalidCoordinates",
                "validCoordinates",
                "withinPilot1030m",
                "LASTMODTSMissing",
                "LASTMODTSFuture",
                "LASTMODTSInvalid",
                "UPDATEDTMissing",
                "UPDATEDTFuture",
                "UPDATEDTInvalid",
            )
        }
    )
    phones = Counter()
    phone_counts = Counter(
        {
            key: 0
            for key in ("MISSING", "UNRECOGNIZED", "PLACEHOLDER", "MOBILE_REVIEW", "SYNTAX_ONLY")
        }
    )
    status_counts = Counter()
    ids = {}
    modified = []
    for row in rows:
        record_id = row.get("MGTNO", "").strip()
        signature = tuple((k, row.get(k, "").strip()) for k in FIELDS)
        if not record_id:
            counts["missingId"] += 1
        elif record_id in ids:
            counts["duplicateId"] += 1
            counts["conflictingId"] += signature != ids[record_id]
        else:
            ids[record_id] = signature
        counts["missingName"] += not row.get("BPLCNM", "").strip()
        counts["missingAddress"] += not row.get("RDNWHLADDR", "").strip()
        kind, phone = phone_kind(row.get("SITETEL", ""))
        phone_counts[kind] += 1
        if phone:
            phones[phone] += 1
        x, y = row.get("X", "").strip(), row.get("Y", "").strip()
        if not x or not y:
            counts["missingCoordinates"] += 1
        else:
            try:
                lon, lat = coordinates.convert(x, y)
                counts["validCoordinates"] += 1
                counts["withinPilot1030m"] += distance(lon, lat) <= 1030
            except QualityError:
                counts["invalidCoordinates"] += 1
        label = row.get("TRDSTATENM", "").strip()
        status_counts[label if label in ("영업/정상", "폐업", "휴업") else "UNKNOWN"] += 1
        for field in ("LASTMODTS", "UPDATEDT"):
            try:
                value = row_time(row.get(field, ""))
                counts[field + "Missing"] += value is None
                if value is not None:
                    counts[field + "Future"] += value > now
                    if field == "LASTMODTS":
                        modified.append(value)
            except QualityError:
                counts[field + "Invalid"] += 1
    freshness = {
        "state": "UNKNOWN",
        "snapshotAsOf": None,
        "ageDays": None,
        "diagnosticMaxAgeDays": 7,
        "basis": "EXPLICIT_SOURCE_SNAPSHOT_ONLY_NOT_ROW_MODIFICATION_OR_FETCH_TIME",
    }
    if snapshot_as_of is not None:
        snapshot = instant(snapshot_as_of)
        age = (now - snapshot).total_seconds() / 86400
        freshness.update(
            snapshotAsOf=snapshot.isoformat(),
            ageDays=round(age, 6),
            state="FUTURE" if age < 0 else "STALE" if age > 7 else "WITHIN_DIAGNOSTIC_WINDOW",
        )
    n = len(rows)
    ratios = {
        key: {"count": value, "denominator": n, "rate": value / n if n else None}
        for key, value in sorted(counts.items())
    }
    return {
        "service": SERVICE,
        "dataset": "OA-18655",
        "mode": mode,
        "observedAt": now.isoformat(),
        "state": "PROFILED" if n else "EMPTY",
        "rows": n,
        "sampleScope": "INPUT_BATCH_ONLY_NOT_POPULATION_COVERAGE",
        "schema": {"state": "OBSERVED" if n else "UNKNOWN_EMPTY", "requiredFields": list(FIELDS)},
        "checks": ratios,
        "phoneKinds": dict(sorted(phone_counts.items())),
        "phoneKindDenominator": n,
        "recognizedPhoneGroups": len(phones),
        "sharedRecognizedPhoneGroups": sum(c > 1 for c in phones.values()),
        "administrativeStatusLabels": dict(sorted(status_counts.items())),
        "rowModifiedRange": {
            "min": min(modified).isoformat() if modified else None,
            "max": max(modified).isoformat() if modified else None,
        },
        "rowTimezoneAssumption": "Asia/Seoul (UTC+09:00), upstream timezone unverified",
        "snapshotFreshness": freshness,
        "coordinateCheck": coordinates.info(),
        "adoptionReady": False,
        "bookable": False,
        "inquiryDispatchAllowed": False,
        "externalCallsByProfiler": 0,
        "rawRowsExported": False,
        "contactValuesExported": False,
    }


def fictional_rows():
    # Public pilot coordinates projected for a synthetic point; no real merchant rows/contacts.
    return [
        {
            "MGTNO": "fictional-" + str(i),
            "BPLCNM": "가상 매장",
            "SITETEL": "02-0000-0000" if i else "",
            "RDNWHLADDR": "가상 주소",
            "TRDSTATEGBN": "01",
            "TRDSTATENM": "영업/정상",
            "LASTMODTS": "2020-01-01 00:00:00",
            "UPDATEDT": "2026-11-06 12:00:00",
            "X": "204790.93835900025",
            "Y": "449083.68283992365",
        }
        for i in range(3)
    ]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Local XML only; never fetched or echoed")
    parser.add_argument(
        "--snapshot-as-of", help="Verified source snapshot timestamp with UTC offset"
    )
    args = parser.parse_args(argv)
    try:
        now = datetime.now(timezone.utc) if args.input else instant("2026-11-09T00:00:00Z")
        if args.input:
            with args.input.open("rb") as f:
                rows = parse_xml(f.read(MAX_BYTES + 1))
        else:
            rows = fictional_rows()
        result = profile(rows, now, "LOCAL_INPUT" if args.input else "MOCK", args.snapshot_as_of)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except Exception as error:
        code = str(error) if isinstance(error, QualityError) else "QUALITY_CHECK_FAILED"
        print(json.dumps({"state": "FAILED", "code": code}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
