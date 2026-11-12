#!/usr/bin/env python3
"""Resolve road addresses and search a local index of the public SBIZ shop dataset."""
import argparse
import csv
from datetime import date, datetime, timezone
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import unicodedata
import zipfile

SOURCE = "https://www.data.go.kr/data/15083033/fileData.do"
REGIONS = dict(zip(
    "서울 부산 대구 인천 광주 대전 울산 세종 경기 강원 충북 충남 전북 전남 경북 경남 제주".split(),
    "서울특별시 부산광역시 대구광역시 인천광역시 광주광역시 대전광역시 울산광역시 세종특별자치시 경기도 강원특별자치도 충청북도 충청남도 전북특별자치도 전라남도 경상북도 경상남도 제주특별자치도".split(),
))
REGIONS.update({"강원도": REGIONS["강원"], "전라북도": REGIONS["전북"], "제주도": REGIONS["제주"]})
REGION_NAMES = set(REGIONS.values())
ROAD_ADDRESS = re.compile(r"^(.+?)\s+([^\s]+(?:로|길))\s+(\d+(?:-\d+)?)(?=$|[\s,(])")
CUISINES = {
    "한식": "한식", "중식": "중식", "일식": "일식", "서양식": "양식",
    "동남아시아": "아시아음식", "기타 외국": "아시아음식", "구내식당·뷔페": "",
}
QUICK_MEALS = {"김밥/만두/분식": "분식", "치킨": "", "피자": "양식",
               "버거": "양식", "토스트/샌드위치/샐러드": ""}
PREFERENCES = {"한식", "중식", "일식", "양식", "분식", "아시아음식"}
MAX_RADIUS = 2_000


def road_address(text):
    if not isinstance(text, str) or not 1 <= len(text) <= 250 or any(ord(c) < 32 for c in text):
        return None
    match = ROAD_ADDRESS.match(" ".join(unicodedata.normalize("NFKC", text).split()))
    if not match:
        return None
    prefix, road, number = match.groups()
    region, _, area = prefix.partition(" ")
    region = REGIONS.get(region, region)
    if region not in REGION_NAMES or (not area and region != REGIONS["세종"]):
        return None
    return " ".join(part for part in (region, area, road, number) if part)


def venue_category(row):
    if row["상권업종대분류명"].strip() != "음식":
        return None, ""
    middle = row["상권업종중분류명"].strip()
    small = row["상권업종소분류명"].strip()
    if middle == "비알코올" and small == "카페":
        return "CAFE", ""
    if middle in CUISINES:
        return "RESTAURANT", CUISINES[middle]
    if middle == "기타 간이" and small in QUICK_MEALS:
        return "RESTAURANT", QUICK_MEALS[small]
    return None, ""


def import_index(archive, output, snapshot):
    if output.exists():
        raise FileExistsError(output)
    with sqlite3.connect(output) as db, zipfile.ZipFile(archive) as files:
        db.executescript("""
            CREATE TABLE metadata (snapshot TEXT NOT NULL);
            CREATE TABLE address_points (
                address TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
                PRIMARY KEY (address, latitude, longitude)
            ) WITHOUT ROWID;
            CREATE TABLE venues (
                name TEXT NOT NULL, address TEXT NOT NULL, category TEXT NOT NULL, cuisine TEXT NOT NULL,
                latitude REAL NOT NULL, longitude REAL NOT NULL,
                UNIQUE (name, address, category, latitude, longitude)
            );
        """)
        db.execute("INSERT INTO metadata VALUES (?)", (snapshot.isoformat(),))
        members = [member for member in files.infolist() if member.filename.endswith(".csv")]
        if not members or any(snapshot.strftime("%Y%m") not in member.filename for member in members):
            raise ValueError("CSV filenames do not match the source snapshot month")
        for member in members:
            with files.open(member) as file:
                points, venues = [], []
                rows = csv.DictReader(io.TextIOWrapper(file, encoding="utf-8-sig", newline=""))
                required = {"도로명주소", "상호명", "상권업종대분류명", "상권업종중분류명", "상권업종소분류명", "위도", "경도"}
                if not required.issubset(rows.fieldnames or ()):
                    raise ValueError("Missing SBIZ columns")
                for row in rows:
                    address = road_address(row["도로명주소"])
                    if address is None:
                        continue
                    try:
                        lat, lon = float(row["위도"]), float(row["경도"])
                    except (ValueError, TypeError):
                        continue
                    if not (33 <= lat <= 39 and 124 <= lon <= 132):
                        continue
                    points.append((address, lat, lon))
                    category, cuisine = venue_category(row)
                    name = " ".join(row["상호명"].split())
                    if category and 1 <= len(name) <= 150:
                        venues.append((name, address, category, cuisine, lat, lon))
                    if len(points) >= 10_000:
                        insert_batch(db, points, venues)
                insert_batch(db, points, venues)
        db.execute("CREATE INDEX venues_by_latitude ON venues (latitude)")
        print(json.dumps({"addresses": db.execute("SELECT count(DISTINCT address) FROM address_points").fetchone()[0],
                          "venues": db.execute("SELECT count(*) FROM venues").fetchone()[0]}))


def insert_batch(db, points, venues):
    db.executemany("INSERT OR IGNORE INTO address_points VALUES (?, ?, ?)", points)
    db.executemany("INSERT OR IGNORE INTO venues VALUES (?, ?, ?, ?, ?, ?)", venues)
    points.clear()
    venues.clear()


def distance(lat1, lon1, lat2, lon2):
    dlat, dlon = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return 12_742_000 * math.asin(min(1, math.sqrt(h)))


def nearby(db, address, category, cuisines, details=None, branches=None):
    points = db.execute("SELECT latitude, longitude FROM address_points WHERE address=?", (address,)).fetchall()
    if not points:
        raise LookupError("No exact address")
    minimum_lat, maximum_lat = min(p[0] for p in points), max(p[0] for p in points)
    minimum_lon, maximum_lon = min(p[1] for p in points), max(p[1] for p in points)
    if distance(minimum_lat, minimum_lon, maximum_lat, maximum_lon) > 200:
        raise LookupError("Ambiguous coordinates for the address")
    midpoint = ((minimum_lat + maximum_lat) / 2, (minimum_lon + maximum_lon) / 2)
    lat, lon = min(points, key=lambda point: distance(*midpoint, *point))
    lat_delta = MAX_RADIUS / 110_000
    lon_delta = MAX_RADIUS / (110_000 * math.cos(math.radians(lat)))
    rows = db.execute("""SELECT name, address, category, cuisine, latitude, longitude FROM venues
                          WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?
                          AND (?='ALL' OR category=?)""",
                      (lat - lat_delta, lat + lat_delta, lon - lon_delta, lon + lon_delta, category, category))
    ranked = []
    for name, road, kind, cuisine, y, x in rows:
        if cuisines and cuisine not in cuisines:
            continue
        meters = distance(lat, lon, y, x)
        if meters <= MAX_RADIUS:
            ranked.append((meters, name, road, kind, cuisine))
    distinct = {}
    for meters, name, road, kind, cuisine in sorted(ranked):
        distinct.setdefault((name, road), (meters, name, road, kind, cuisine))
    matches = list(distinct.values())
    within_one_km = [row for row in matches if row[0] <= 1_000]
    eligible = within_one_km if len(within_one_km) >= 3 else matches
    if branches is not None:
        identities = {}
        for _, name, road, _, _ in eligible:
            aliases = (details or {}).get((name, road), {}).get("aliases", [])
            for alias in [name, *aliases]:
                identities.setdefault((normalized_name(alias), road), (name, road))
        verified = {}
        for branch in branches:
            key = (normalized_name(branch["name"]), road_address(branch["address"]))
            if key in identities:
                verified.setdefault(identities[key], branch)
        return (lat, lon), list(verified.values())
    if details:
        # Prefer candidates with the three requested facts, then keep distance order.
        eligible.sort(key=lambda row: not details.get((row[1], row[2]), {}).get("complete", False))
    return (lat, lon), eligible[:6]


def normalized_name(name):
    return "".join(c for c in unicodedata.normalize("NFKC", name).casefold() if c.isalnum())


def active_details(entries, now):
    details = {}
    for place in entries:
        observed = datetime.fromisoformat(place["observedAt"])
        expires = datetime.fromisoformat(place["validUntil"])
        if not observed <= now < expires:
            continue
        business_details, reports, sources = [], [], []
        for kind, fact in place["facts"].items():
            url = fact["url"]
            sources.append({"url": url, "title": fact["title"]})
            evidence = {"condition": fact["condition"], "sourceUrl": url,
                        "sourceType": fact["sourceType"], "sourceUpdatedAt": None,
                        "evidenceObservedAt": place["observedAt"], "evidenceValidUntil": place["validUntil"]}
            if kind == "PARKING":
                reports.append({**evidence, "need": kind, "answer": fact["answer"]})
            elif kind in ("OPENING_HOURS", "BREAK_TIME"):
                business_details.append({**evidence, "kind": kind})
        details[(place["name"], place["address"])] = {
            "businessDetails": business_details, "reports": reports, "sources": sources,
            "aliases": place.get("aliases", []),
            "complete": len(business_details) == 2 and any(report["answer"] == "YES" for report in reports),
        }
    return details


def report(matches, snapshot, details=None, now=None):
    observed_at = (now or datetime.now(timezone.utc)).isoformat()
    source = {"sourceUrl": SOURCE, "sourceType": "OTHER", "sourceUpdatedAt": snapshot + "T00:00:00+09:00"}
    sources = {SOURCE: {"url": SOURCE, "title": "소상공인시장진흥공단 상가정보 " + snapshot}}
    candidates = []
    for _, name, address, category, cuisine in matches:
        verified = (details or {}).get((name, address), {})
        business_details = [{**source, "kind": "CUISINE", "condition": cuisine}] if cuisine else []
        business_details.extend(verified.get("businessDetails", []))
        for citation in verified.get("sources", []):
            sources[citation["url"]] = citation
        candidates.append({
            "name": name, "address": address, "identitySourceUrl": SOURCE,
            "venueCategory": category, "cuisine": cuisine,
            "businessDetails": business_details, "reports": verified.get("reports", []),
        })
    return {"observedAt": observed_at, "sources": list(sources.values()), "candidates": candidates}


def serve(db, port, token, snapshot, evidence=()):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            self.connection.settimeout(3)
            if self.path != "/places":
                self.send_error(404)
                return
            authorization = self.headers.get("Authorization", "").encode()
            if not hmac.compare_digest(authorization, ("Bearer " + token).encode()):
                self.send_error(401)
                return
            try:
                length = int(self.headers["Content-Length"])
                if not 0 < length <= 16384:
                    raise ValueError("Invalid request size")
                query = json.loads(self.rfile.read(length))
                if not isinstance(query, dict):
                    raise ValueError("Expected a query object")
                category = query["venueCategory"]
                cuisines = query["preferredCuisines"]
                if category not in ("ALL", "CAFE", "RESTAURANT") or not isinstance(cuisines, list):
                    raise ValueError("Invalid filters")
                if not all(isinstance(cuisine, str) and cuisine in PREFERENCES for cuisine in cuisines):
                    raise ValueError("Invalid cuisine")
                address = road_address(query["destination"])
                if address is None:
                    self.send_error(422)
                    return
                now = datetime.now(timezone.utc)
                details = active_details(evidence, now)
                if "verifyBranches" in query:
                    branches = query["verifyBranches"]
                    if not isinstance(branches, list) or len(branches) > 8 or not all(
                        isinstance(branch, dict) and set(branch) == {"name", "address"}
                        and isinstance(branch["name"], str) and 1 <= len(branch["name"]) <= 150
                        and isinstance(branch["address"], str) and 1 <= len(branch["address"]) <= 250
                        for branch in branches
                    ):
                        raise ValueError("Invalid branches")
                    _, verified = nearby(db, address, category, set(cuisines), details, branches)
                    result = {"verified": verified}
                else:
                    _, matches = nearby(db, address, category, set(cuisines), details)
                    result = report(matches, snapshot, details, now)
                body = json.dumps(result, ensure_ascii=False).encode()
            except (KeyError, ValueError, TypeError, OSError):
                self.send_error(400)
                return
            except LookupError:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    with HTTPServer(("127.0.0.1", port), Handler) as server:
        print(f"Place lookup listening on 127.0.0.1:{server.server_port}", flush=True)
        server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    load = sub.add_parser("import")
    load.add_argument("--archive", type=Path, required=True)
    load.add_argument("--snapshot", type=date.fromisoformat, required=True)
    load.add_argument("--index", type=Path, required=True)
    check = sub.add_parser("probe")
    check.add_argument("--index", type=Path, required=True)
    check.add_argument("address")
    run = sub.add_parser("serve")
    run.add_argument("--index", type=Path, required=True)
    run.add_argument("--port", type=int, default=18770)
    run.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.command == "import":
        import_index(args.archive, args.index, args.snapshot)
        return
    with sqlite3.connect(args.index.resolve().as_uri() + "?mode=ro", uri=True) as db:
        snapshot, = db.execute("SELECT snapshot FROM metadata").fetchone()
        if args.command == "probe":
            address = road_address(args.address)
            if address is None:
                parser.error("A Korean road-name address is required.")
            point, matches = nearby(db, address, "RESTAURANT", set())
            print(json.dumps({"address": address, "latitude": point[0], "longitude": point[1],
                              "candidates": [{"name": row[1], "address": row[2], "distanceMeters": round(row[0])}
                                             for row in matches]}, ensure_ascii=False, indent=2))
        else:
            token = os.environ.get("PLACE_LOOKUP_TOKEN", "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
                parser.error("Set PLACE_LOOKUP_TOKEN to a dedicated service token.")
            evidence = json.loads(args.evidence.read_text(encoding="utf-8")) if args.evidence else []
            active_details(evidence, datetime.now(timezone.utc))
            serve(db, args.port, token, snapshot, evidence)


if __name__ == "__main__":
    main()
