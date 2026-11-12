import csv
from datetime import date, datetime, timedelta, timezone
import io
from pathlib import Path
import runpy
import sqlite3
import tempfile
import unittest
import zipfile

LOOKUP = runpy.run_path(str(Path(__file__).resolve().parents[1] / "address-place-lookup.py"))


class AddressLookupTest(unittest.TestCase):
    def test_exact_address_radius_and_food_filter(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            columns = ["상호명", "도로명주소", "상권업종대분류명", "상권업종중분류명", "상권업종소분류명", "위도", "경도"]
            table = io.StringIO()
            writer = csv.writer(table)
            writer.writerow(columns)
            writer.writerows([
                ("가상 목적지", "서울특별시 가상구 가온로 50", "소매", "", "", 37.5, 127.0),
                ("가상 파스타", "서울특별시 가상구 가온로 52", "음식", "서양식", "경양식", 37.5009, 127.0),
                ("가상 피자", "서울특별시 가상구 누리길 8", "음식", "기타 간이", "피자", 37.504, 127.0),
                ("가상 버거", "서울특별시 가상구 누리길 9", "음식", "기타 간이", "버거", 37.513, 127.0),
                ("가상 원거리", "서울특별시 가상구 가온로 99", "음식", "서양식", "경양식", 37.525, 127.0),
                ("가상 카페", "서울특별시 가상구 가온로 51", "음식", "비알코올 ", "카페", 37.5005, 127.0),
                ("가상 다른 지역", "서울특별시 다른구 가온로 50", "음식", "서양식", "경양식", 37.600, 127.0),
            ])
            with zipfile.ZipFile(path / "sample.zip", "w") as archive:
                archive.writestr("sample_202609.csv", table.getvalue())
            LOOKUP["import_index"](path / "sample.zip", path / "test.sqlite3", date(2026, 9, 30))
            address = LOOKUP["road_address"]("서울 가상구 가온로 50 (가상빌딩) 3층")
            with sqlite3.connect(path / "test.sqlite3") as db:
                point, rows = LOOKUP["nearby"](db, address, "RESTAURANT", {"양식"})
                self.assertEqual(point, (37.5, 127.0))
                self.assertEqual([row[1] for row in rows], ["가상 파스타", "가상 피자", "가상 버거"])
                self.assertLess(rows[0][0], 120)
                self.assertTrue(1_000 < rows[-1][0] <= 2_000)
                with self.assertRaises(LookupError):
                    LOOKUP["nearby"](db, "서울특별시 가상구 가온로 500", "RESTAURANT", set())
                _, cafes = LOOKUP["nearby"](db, address, "CAFE", set())
                self.assertEqual([row[1] for row in cafes], ["가상 카페"])
                for index in range(6):
                    db.execute("INSERT INTO venues VALUES (?, ?, 'RESTAURANT', '양식', 37.5, 127)",
                               (f"가상 추가 {index}", address))
                branches = [{"name": "가상파스타", "address": "서울 가상구 가온로 52 가상빌딩 2층"},
                            {"name": "가상 피자", "address": "서울 가상구 가온로 52"},
                            {"name": "가상 카페", "address": "서울 가상구 가온로 51"},
                            {"name": "가상 원거리", "address": "서울 가상구 가온로 99"},
                            {"name": "가상 버거", "address": "서울 가상구 누리길 9"}]
                _, matched = LOOKUP["nearby"](db, address, "RESTAURANT", {"양식"}, branches=branches)
                self.assertEqual(matched, branches[:1])
                aliases = {("가상 파스타", "서울특별시 가상구 가온로 52"): {"aliases": ["가상 파스타 가온점"]}}
                alias = {"name": "가상 파스타 가온점", "address": branches[0]["address"]}
                _, matched = LOOKUP["nearby"](db, address, "RESTAURANT", {"양식"}, aliases,
                                               branches=[alias, *branches])
                self.assertEqual(matched, [alias])
                db.execute("INSERT INTO address_points VALUES (?, ?, ?)", (address, 37.7, 127))
                with self.assertRaises(LookupError):
                    LOOKUP["nearby"](db, address, "RESTAURANT", set())

    def test_detail_evidence_stays_with_the_exact_branch_and_expires(self):
        now = datetime(2026, 11, 12, tzinfo=timezone.utc)
        observed = (now - timedelta(hours=1)).isoformat()
        expires = (now + timedelta(days=1)).isoformat()
        address = "서울특별시 가상구 가온로 52"
        merchant = {"url": "https://merchant.example/place", "title": "가상 매장",
                    "sourceType": "MERCHANT_PAGE"}
        entries = [{
            "name": "가상 양식당", "address": address, "observedAt": observed, "validUntil": expires,
            "facts": {
                "OPENING_HOURS": {**merchant, "condition": "11:00–21:00"},
                "BREAK_TIME": {**merchant, "condition": "15:00–17:00"},
                "PARKING": {**merchant, "condition": "유료 공용 주차장", "answer": "YES"},
            },
        }]
        matches = [(0, "가상 다른 가게", address, "RESTAURANT", ""),
                   (10, "가상 양식당", address, "RESTAURANT", "양식")]
        details = LOOKUP["active_details"](entries, now)
        self.assertTrue(details[("가상 양식당", address)]["complete"])
        result = LOOKUP["report"](matches, "2026-09-30", details, now)
        other, restaurant = result["candidates"]
        self.assertEqual(other["reports"], [])
        self.assertEqual(other["businessDetails"], [])
        self.assertEqual(restaurant["reports"][0]["answer"], "YES")
        self.assertEqual(restaurant["reports"][0]["evidenceObservedAt"], observed)
        self.assertEqual(restaurant["businessDetails"][-1]["evidenceValidUntil"], expires)
        self.assertEqual(len(result["sources"]), 2)
        self.assertEqual(LOOKUP["active_details"](entries, now + timedelta(days=1)), {})
