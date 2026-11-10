#!/usr/bin/env python3
"""Offline OA-18655 XML/sheet profile. No HTTP, row/contact exports or booking authority."""
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
PILOT_STATUS_FIELDS = ("TRDSTATEGBN", "TRDSTATENM", "DTLSTATEGBN", "DTLSTATENM", "DCBYMD")
# Public Data Provision Standard, revision 20, section 127 (general restaurants).
STATUS_STANDARD_URL = "https://www.data.go.kr/bbs/rcr/selectRecsroom.do?originId=PDS_0000000001252"
ADMINISTRATIVE_STATUS = {
    "01": "영업/정상",
    "02": "휴업",
    "03": "폐업",
    "04": "취소/말소/만료/정지/중지",
}
DETAILED_STATUS = {"01": "영업", "02": "폐업"}
PILOT_NORMAL_TUPLE = ("01", "영업/정상", "01", "영업")


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


def quote_sheet_keys(text):
    """Normalize identifier keys/trailing commas only; never evaluate JavaScript values."""
    decoder = json.JSONDecoder()
    identifier = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
    chunks, position, previous = [], 0, ""
    while position < len(text):
        char = text[position]
        if char == '"':
            _, end = decoder.raw_decode(text, position)
            chunks.append(text[position:end])
            position, previous = end, '"'
            continue
        if char in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$":
            token = identifier.match(text, position)[0]
            end = position + len(token)
            lookahead = end
            while lookahead < len(text) and text[lookahead].isspace():
                lookahead += 1
            is_key = previous in ("{", ",") and lookahead < len(text) and text[lookahead] == ":"
            chunks.append(json.dumps(token) if is_key else token)
            position, previous = end, token[-1]
            continue
        if char == ",":
            lookahead = position + 1
            while lookahead < len(text) and text[lookahead].isspace():
                lookahead += 1
            if lookahead < len(text) and text[lookahead] in "]}":
                position += 1
                continue
        chunks.append(char)
        if not char.isspace():
            previous = char
        position += 1
    return "".join(chunks)


def parse_sheet_json(raw):
    """Local copy of the official HTTPS sheet envelope; no fetching or raw-value exports."""
    if not isinstance(raw, bytes) or len(raw) > MAX_BYTES:
        raise QualityError("INPUT_SIZE_LIMIT")

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise QualityError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    def reject_constant(_value):
        raise QualityError("INVALID_JSON")

    try:
        data = json.loads(
            quote_sheet_keys(raw.decode("utf-8-sig")),
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except UnicodeError:
        raise QualityError("INVALID_ENCODING") from None
    except (ValueError, RecursionError):
        raise QualityError("INVALID_JSON") from None
    if not isinstance(data, dict) or data.get("result") != "ok":
        raise QualityError("SHEET_RESULT_NOT_SUCCESS")
    source_rows, page = data.get("list"), data.get("page")
    if not isinstance(source_rows, list) or not isinstance(page, dict):
        raise QualityError("INVALID_SHEET_SHAPE")
    if len(source_rows) > MAX_ROWS:
        raise QualityError("ROW_LIMIT")
    metadata = {}
    for field in ("pageNo", "totalCount"):
        value = page.get(field)
        if isinstance(value, bool) or not re.fullmatch(r"\d{1,12}", str(value)):
            raise QualityError("INVALID_PAGE_METADATA")
        metadata[field] = int(value)
    if not (metadata["pageNo"] >= 1 and metadata["totalCount"] >= len(source_rows)):
        raise QualityError("INVALID_PAGE_METADATA")
    metadata["pageSize"] = None
    if "pageSize" in page:
        size = page["pageSize"]
        if isinstance(size, bool) or not re.fullmatch(r"\d{1,4}", str(size)):
            raise QualityError("INVALID_PAGE_METADATA")
        if not (1 <= int(size) <= MAX_ROWS and len(source_rows) <= int(size)):
            raise QualityError("INVALID_PAGE_METADATA")
        metadata["pageSize"] = int(size)
    if "pageCount" in page:
        count = page["pageCount"]
        if isinstance(count, bool) or not re.fullmatch(r"\d{1,12}", str(count)):
            raise QualityError("INVALID_PAGE_METADATA")
        metadata["reportedPageCount"] = int(count)
    if metadata["totalCount"] > 0 and not source_rows:
        raise QualityError("UNEXPECTED_EMPTY_PAGE")
    rows = []
    for source_row in source_rows:
        if not isinstance(source_row, dict):
            raise QualityError("INVALID_ROW_SHAPE")
        row = {}
        for field, value in source_row.items():
            if not isinstance(field, str) or isinstance(value, (dict, list, bool)):
                raise QualityError("INVALID_ROW_SHAPE")
            # A null closure value is not proof of an explicitly blank closure date.
            if value is None and field == "DCBYMD":
                continue
            value = "" if value is None else str(value)
            if len(field) > 128 or len(value) > 4096:
                raise QualityError("FIELD_SIZE_LIMIT")
            row[field] = value
        rows.append(row)
    return rows, metadata


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
            "sourceCrsBasis": "OA18655_DATASET_CATALOG",
            "genericStandardReferenceCrs": "EPSG:2097",
            "crsEvidenceState": "DATASET_SPECIFIC_RULE_DIFFERS_FROM_GENERIC_STANDARD",
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


def radius_band(meters):
    if isinstance(meters, bool) or not math.isfinite(meters) or meters < 0:
        raise QualityError("INVALID_DISTANCE")
    if meters <= 1000:
        return "WITHIN_1000M"
    return "BOUNDARY_1000_TO_1030M" if meters <= 1030 else "OUTSIDE_1030M"


def pilot_status(row):
    # Missing closure tag is different from an explicitly empty closure value.
    if any(field not in row for field in PILOT_STATUS_FIELDS):
        return "MISSING"
    values = tuple(row[field].strip() for field in PILOT_STATUS_FIELDS[:-1])
    if not all(values):
        return "MISSING"
    code, label, detail_code, detail_label = values
    if code not in ADMINISTRATIVE_STATUS or detail_code not in DETAILED_STATUS:
        return "UNRECOGNIZED_CODE"
    if label != ADMINISTRATIVE_STATUS[code] or detail_label != DETAILED_STATUS[detail_code]:
        return "CODE_LABEL_CONFLICT"
    if (code == "01" and detail_code != "01") or (code == "03" and detail_code != "02"):
        return "STATE_CONFLICT"
    if code == "01":
        return "CLOSURE_PRESENT" if row["DCBYMD"].strip() else "NORMAL_STATUS"
    return "CLOSED" if code == "03" else "NOT_NORMAL"


def pilot_screening(rows, coordinates):
    """Aggregate-only diagnostic on already validated rows; never selects or exports merchants."""
    id_counts = Counter(row.get("MGTNO", "").strip() for row in rows)
    signatures = {}
    for row in rows:
        record_id = row.get("MGTNO", "").strip()
        if record_id:
            signatures.setdefault(record_id, set()).add(
                tuple(row.get(field, "").strip() for field in (*FIELDS, *PILOT_STATUS_FIELDS))
            )
    stages = dict.fromkeys(
        (
            "input",
            "uniqueId",
            "normalStatus",
            "validCoordinates",
            "within1000m",
            "phoneSyntaxOnly",
            "nameAndRoadAddress",
        ),
        0,
    )
    exclusions = dict.fromkeys(
        (
            "missingId",
            "duplicateId",
            "statusNotNormalOrUnknown",
            "missingCoordinates",
            "invalidCoordinates",
            "boundary1000To1030m",
            "outside1030m",
            "phoneNotSyntaxOnly",
            "missingNameOrRoadAddress",
        ),
        0,
    )
    status_kinds = Counter(
        dict.fromkeys(
            (
                "MISSING",
                "UNRECOGNIZED_CODE",
                "CODE_LABEL_CONFLICT",
                "STATE_CONFLICT",
                "CLOSURE_PRESENT",
                "NORMAL_STATUS",
                "CLOSED",
                "NOT_NORMAL",
            ),
            0,
        )
    )
    for row in rows:
        stages["input"] += 1
        status = pilot_status(row)
        status_kinds[status] += 1
        record_id = row.get("MGTNO", "").strip()
        if not record_id:
            exclusions["missingId"] += 1
            continue
        # Exclude every member, even identical duplicates, rather than choosing the first row.
        if id_counts[record_id] != 1:
            exclusions["duplicateId"] += 1
            continue
        stages["uniqueId"] += 1
        if status != "NORMAL_STATUS":
            exclusions["statusNotNormalOrUnknown"] += 1
            continue
        stages["normalStatus"] += 1
        x, y = row.get("X", "").strip(), row.get("Y", "").strip()
        if not x or not y:
            exclusions["missingCoordinates"] += 1
            continue
        try:
            lon, lat = coordinates.convert(x, y)
        except QualityError:
            exclusions["invalidCoordinates"] += 1
            continue
        stages["validCoordinates"] += 1
        band = radius_band(distance(lon, lat))
        if band != "WITHIN_1000M":
            exclusions[
                "boundary1000To1030m" if band == "BOUNDARY_1000_TO_1030M" else "outside1030m"
            ] += 1
            continue
        stages["within1000m"] += 1
        if phone_kind(row.get("SITETEL", ""))[0] != "SYNTAX_ONLY":
            exclusions["phoneNotSyntaxOnly"] += 1
            continue
        stages["phoneSyntaxOnly"] += 1
        if not all(row.get(field, "").strip() for field in ("BPLCNM", "RDNWHLADDR")):
            exclusions["missingNameOrRoadAddress"] += 1
            continue
        stages["nameAndRoadAddress"] += 1
    n = len(rows)
    return {
        "state": "DIAGNOSTIC_ONLY" if n else "UNKNOWN_EMPTY",
        "statusRule": {
            "verification": "DOCUMENTED_STANDARD_20_GENERAL_RESTAURANTS",
            "source": STATUS_STANDARD_URL,
            "section": 127,
            "fields": list(PILOT_STATUS_FIELDS),
            "normalTuple": list(PILOT_NORMAL_TUPLE),
            "rowConformanceScope": "INPUT_BATCH_ONLY_NOT_REAL_TIME_OPERATION",
            "closureValueMustBeExplicitlyEmpty": True,
        },
        "statusKinds": dict(status_kinds),
        "statusKindDenominator": n,
        "cumulativeCounts": stages,
        "firstExclusionCounts": exclusions,
        "countDenominator": n,
        "duplicateIdGroups": sum(c > 1 for key, c in id_counts.items() if key),
        "conflictingIdGroups": sum(len(group) > 1 for group in signatures.values()),
        "screenedRecords": stages["nameAndRoadAddress"],
        "productionCandidateCount": None,
        "radiusMeters": 1000,
        "boundaryUpperMeters": 1030,
        "boundaryIncluded": False,
        "distanceMeaning": "SPHERICAL_STRAIGHT_LINE_NOT_ROAD_DISTANCE_OR_ETA",
        "grain": "INPUT_ADMINISTRATIVE_RECORD_NOT_UNIQUE_PHYSICAL_MERCHANT",
        "coverage": "INPUT_BATCH_ONLY_NOT_REPRESENTATIVE_OR_COMPLETE",
        "readyForRecommendation": False,
    }


def profile(rows, now, mode, snapshot_as_of=None, include_pilot_screening=False):
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
    result = {
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
    if include_pilot_screening:
        result["pilotScreening"] = pilot_screening(rows, coordinates)
    return result


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
            "DTLSTATEGBN": "01",
            "DTLSTATENM": "영업",
            "DCBYMD": "",
            "LASTMODTS": "2020-01-01 00:00:00",
            "UPDATEDT": "2026-11-06 12:00:00",
            "X": "204790.93835900025",
            "Y": "449083.68283992365",
        }
        for i in range(3)
    ]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input", type=Path, help="Permitted local XML/sheet; never fetched or echoed"
    )
    parser.add_argument(
        "--input-format",
        choices=("xml", "sheet-json"),
        default="xml",
        help="Explicit format for an already permitted local input",
    )
    parser.add_argument(
        "--pilot-screening",
        action="store_true",
        help="Aggregate cross-check; no product candidates",
    )
    parser.add_argument(
        "--snapshot-as-of", help="Verified source snapshot timestamp with UTC offset"
    )
    args = parser.parse_args(argv)
    try:
        now = datetime.now(timezone.utc) if args.input else instant("2026-11-09T00:00:00Z")
        page = None
        if args.input:
            with args.input.open("rb") as f:
                raw = f.read(MAX_BYTES + 1)
            if args.input_format == "sheet-json":
                rows, page = parse_sheet_json(raw)
            else:
                rows = parse_xml(raw)
        else:
            rows = fictional_rows()
        result = profile(
            rows,
            now,
            "LOCAL_INPUT" if args.input else "MOCK",
            args.snapshot_as_of,
            args.pilot_screening,
        )
        if page is not None:
            result["sheetPage"] = page
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except Exception as error:
        code = str(error) if isinstance(error, QualityError) else "QUALITY_CHECK_FAILED"
        print(json.dumps({"state": "FAILED", "code": code}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
