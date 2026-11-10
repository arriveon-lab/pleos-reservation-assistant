"""Offline fictional catalog boundaries; never fetch merchants or dial fixture values."""
import importlib.util
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "catalog", Path(__file__).parents[1] / "seoul-place-catalog.py"
)
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)
NOW = catalog.quality.instant("2026-11-10T00:00:00Z")
SNAPSHOT = catalog.quality.instant("2026-11-07T00:00:00Z")


def row(n=0):
    return {"MGTNO": f"fictional-catalog-{n}", "BPLCNM": f"가상 공공데이터 식당 {n}",
            "RDNWHLADDR": f"가상 성수 시험주소 {n}", "SITETEL": "02-1234-5678",
            "TRDSTATEGBN": "01", "TRDSTATENM": "영업/정상", "DTLSTATEGBN": "01",
            "DTLSTATENM": "영업", "DCBYMD": "", "X": "204790.93835900025",
            "Y": "449083.68283992365", "LASTMODTS": "20191231000000", "UPDATEDT": ""}


class SeoulCatalogTest(unittest.TestCase):
    def test_shared_java_fixture_matches_importer_and_preserves_raw_time(self):
        actual, report = catalog.build_catalog([row(n) for n in range(4)], NOW, SNAPSHOT)
        fixture = Path(__file__).parents[2] / "server/src/test/resources/places/seoul-catalog-fictional.json"
        self.assertEqual(actual, json.loads(fixture.read_text()))
        self.assertEqual(report["acceptedRows"], 4)
        self.assertFalse(report["inquiryDispatchAllowed"])
        self.assertEqual(actual["entries"][0]["rowTimeZone"], "UNKNOWN")
        self.assertEqual(actual["entries"][0]["rowModifiedRaw"], "20191231000000")

    def test_all_duplicate_id_rows_are_excluded_even_if_equal(self):
        data, report = catalog.build_catalog([row(), row(), row(1)], NOW)
        self.assertEqual(len(data["entries"]), 1)
        self.assertEqual(report["rejectedReasons"]["MISSING_OR_DUPLICATE_ID"], 2)

    def test_closed_conflicting_and_missing_closure_are_not_normal(self):
        for changes in [{"DTLSTATEGBN": "02"}, {"DCBYMD": "20261101"},
                        {"TRDSTATENM": "폐업"}]:
            bad = row() | changes
            self.assertEqual(catalog.build_catalog([bad], NOW)[1]["acceptedRows"], 0)
        bad = row()
        del bad["DCBYMD"]
        self.assertEqual(catalog.build_catalog([bad], NOW)[1]["acceptedRows"], 0)

    def test_phone_missing_mobile_placeholder_and_invalid_are_excluded(self):
        for phone in ["", "010-1234-5678", "02-0000-1234", "private-phone", "1588-1234"]:
            with self.subTest(phone=phone):
                _, report = catalog.build_catalog([row() | {"SITETEL": phone}], NOW)
                self.assertEqual(report["rejectedReasons"], {"PHONE_REVIEW_REQUIRED": 1})
                if phone:
                    self.assertNotIn(phone, json.dumps(report))

    def test_coordinate_failures_and_outside_pilot_are_excluded(self):
        for changes in [{"X": "nan"}, {"X": "0", "Y": "0"},
                        {"X": "449083", "Y": "204790"}, {"X": "200000", "Y": "500000"}]:
            self.assertEqual(catalog.build_catalog([row() | changes], NOW)[1]["acceptedRows"], 0)

    def test_identity_controls_and_oversized_catalog_do_not_create_output(self):
        for field in ["MGTNO", "BPLCNM", "RDNWHLADDR"]:
            self.assertEqual(catalog.build_catalog([row() | {field: "invalid\nidentity"}], NOW)[1]["acceptedRows"], 0)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "catalog.private.json"
            with self.assertRaisesRegex(catalog.quality.QualityError, "CATALOG_SIZE_LIMIT"):
                catalog.write_private(output, {"oversized": "x" * catalog.quality.MAX_BYTES})
            self.assertFalse(output.exists())

    def test_unknown_snapshot_is_not_replaced_with_acquisition_time(self):
        data, _ = catalog.build_catalog([row()], NOW)
        self.assertIsNone(data["sourceSnapshotAt"])
        self.assertEqual(data["acquiredAt"], "2026-11-10T00:00:00Z")
        with self.assertRaisesRegex(catalog.quality.QualityError, "FUTURE_SOURCE_SNAPSHOT"):
            catalog.build_catalog([row()], SNAPSHOT, NOW)

    def test_private_create_and_no_overwrite_or_symlink(self):
        data, _ = catalog.build_catalog([row()], NOW)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "private" / "catalog.json"
            catalog.write_private(output, data)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(output.parent.stat().st_mode & 0o777, 0o700)
            with self.assertRaises(FileExistsError):
                catalog.write_private(output, data)
            link = output.parent / "link.json"
            link.symlink_to(output)
            with self.assertRaises(FileExistsError):
                catalog.write_private(link, data)
            public = Path(directory) / "public"
            public.mkdir(mode=0o755)
            with self.assertRaisesRegex(catalog.quality.QualityError, "PRIVATE_OUTPUT_DIRECTORY"):
                catalog.write_private(public / "catalog.json", data)

    def test_cli_outputs_only_counts_and_does_not_leak_input_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.xml"
            root = ET.Element(catalog.quality.SERVICE)
            ET.SubElement(root, "list_total_count").text = "1"
            ET.SubElement(ET.SubElement(root, "RESULT"), "CODE").text = "INFO-000"
            item = ET.SubElement(root, "row")
            for key, value in row().items():
                ET.SubElement(item, key).text = value
            source.write_bytes(ET.tostring(root, encoding="utf-8"))
            output = Path(directory) / "private" / "catalog.private.json"
            args = ["--input", str(source), "--output", str(output), "--acquired-at", NOW.isoformat()]
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                self.assertEqual(catalog.main(args), 0)
                self.assertEqual(catalog.main(args), 2)
            text = stdout.getvalue()
            for value in [str(source), str(output), row()["SITETEL"], row()["BPLCNM"]]:
                self.assertNotIn(value, text)


if __name__ == "__main__":
    unittest.main()
