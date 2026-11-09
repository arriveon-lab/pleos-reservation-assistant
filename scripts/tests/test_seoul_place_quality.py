"""Fictional local data only. Public merchant rows are never test fixtures."""

from contextlib import redirect_stdout
from copy import deepcopy
from datetime import timedelta
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from pyproj import Transformer, network

SPEC = importlib.util.spec_from_file_location(
    "seoul_quality", Path(__file__).parents[1] / "seoul-place-quality.py"
)
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)
NOW = quality.instant("2026-11-09T00:00:00Z")


def xml(rows, total=None, code="INFO-000"):
    root = ET.Element(quality.SERVICE)
    ET.SubElement(root, "list_total_count").text = str(len(rows) if total is None else total)
    ET.SubElement(ET.SubElement(root, "RESULT"), "CODE").text = code
    for row in rows:
        node = ET.SubElement(root, "row")
        for key, value in row.items():
            ET.SubElement(node, key).text = value
    return ET.tostring(root, encoding="utf-8")


class SeoulPlaceQualityTest(unittest.TestCase):
    def profile(self, rows=None, snapshot=None):
        return quality.profile(
            quality.fictional_rows() if rows is None else rows, NOW, "MOCK", snapshot
        )

    def test_documented_fields_parse_without_row_value_exports(self):
        rows = quality.fictional_rows()
        parsed = quality.parse_xml(xml(rows))
        self.assertEqual(parsed, rows)
        report = self.profile(parsed)
        self.assertEqual(report["rows"], 3)
        self.assertFalse(
            report["adoptionReady"] or report["bookable"] or report["inquiryDispatchAllowed"]
        )
        self.assertEqual(sum(report["phoneKinds"].values()), 3)
        self.assertEqual(report["checks"]["withinPilot1030m"]["count"], 3)
        self.assertNotIn("fictional-", json.dumps(report))

    def test_projection_origin_matches_epsg_definition_not_axis_swap(self):
        check = quality.CoordinateCheck()
        inverse = Transformer.from_crs(check.crs, check.crs.geodetic_crs, always_xy=True)
        longitude, latitude = inverse.transform(200000, 500000, errcheck=True)
        self.assertAlmostEqual(longitude, 127.002890277778, places=8)
        self.assertAlmostEqual(latitude, 38, places=8)
        lon, lat = check.convert(204790.93835900025, 449083.68283992365)
        self.assertAlmostEqual(lon, 127.055, places=6)
        self.assertAlmostEqual(lat, 37.544, places=6)
        # This latter point is a synthetic PROJ-derived reference, not a surveyed merchant point.
        with self.assertRaises(quality.QualityError):
            check.convert(449083.68283992365, 204790.93835900025)

    def test_projection_never_enables_network_or_ballpark(self):
        with patch.dict(os.environ, {"PROJ_NETWORK": "ON"}):
            check = quality.CoordinateCheck()
            self.assertFalse(network.is_network_enabled())
            self.assertFalse(check.info()["allowBallpark"])

    def test_invalid_coordinates_do_not_leak_values(self):
        check = quality.CoordinateCheck()
        for x, y in [
            (None, 1),
            (True, 1),
            ("nan", 1),
            ("inf", 1),
            (0, 0),
            (1e100, 1e100),
            ("private-coordinate", 1),
        ]:
            with (
                self.subTest(x=x),
                self.assertRaisesRegex(quality.QualityError, "^INVALID_COORDINATES$"),
            ):
                check.convert(x, y)

    def test_coordinate_counts_have_explicit_batch_denominator(self):
        rows = quality.fictional_rows()
        rows[0]["X"] = ""
        rows[1]["Y"] = "nan"
        result = self.profile(rows)
        for key in ["missingCoordinates", "invalidCoordinates", "validCoordinates"]:
            self.assertEqual(result["checks"][key], {"count": 1, "denominator": 3, "rate": 1 / 3})

    def test_old_row_modification_does_not_mark_snapshot_stale(self):
        result = self.profile(snapshot="2026-11-08T00:00:00Z")
        self.assertEqual(result["snapshotFreshness"]["state"], "WITHIN_DIAGNOSTIC_WINDOW")
        self.assertEqual(self.profile()["snapshotFreshness"]["state"], "UNKNOWN")
        self.assertTrue(result["rowModifiedRange"]["max"].startswith("2019-12-31"))

    def test_snapshot_age_boundaries_future_and_naive_time(self):
        for age, state in [(7, "WITHIN_DIAGNOSTIC_WINDOW"), (7.001, "STALE"), (-0.1, "FUTURE")]:
            with self.subTest(age=age):
                result = self.profile(snapshot=(NOW - timedelta(days=age)).isoformat())
                self.assertEqual(result["snapshotFreshness"]["state"], state)
        with self.assertRaises(quality.QualityError):
            self.profile(snapshot="2026-11-09T00:00:00")

    def test_row_times_preserve_missing_invalid_future_and_kst_assumption(self):
        rows = quality.fictional_rows()
        rows[0]["LASTMODTS"] = ""
        rows[1]["LASTMODTS"] = "private-invalid-time"
        rows[2]["LASTMODTS"] = "2026-11-09 09:00:01"
        result = self.profile(rows)
        for key in ["LASTMODTSMissing", "LASTMODTSInvalid", "LASTMODTSFuture"]:
            self.assertEqual(result["checks"][key]["count"], 1)
        self.assertIn("upstream timezone unverified", result["rowTimezoneAssumption"])
        self.assertEqual(quality.row_time("20261109090000"), NOW)

    def test_phone_missing_placeholder_unknown_and_shared_values_are_distinct(self):
        rows = quality.fictional_rows()
        rows[0]["SITETEL"] = "unknown"
        result = self.profile(rows)
        self.assertEqual(result["phoneKinds"]["MISSING"], 1)
        self.assertEqual(result["phoneKinds"]["PLACEHOLDER"], 2)
        self.assertEqual(result["sharedRecognizedPhoneGroups"], 1)
        self.assertEqual(quality.phone_kind("02-0000-0000 / private-number")[0], "UNRECOGNIZED")
        self.assertEqual(quality.phone_kind("+82 (2)0000-0000")[0], "PLACEHOLDER")
        self.assertNotIn("02-0000", json.dumps(result))

    def test_duplicate_ids_and_conflicting_checked_fields(self):
        row = quality.fictional_rows()[0]
        changed = {**row, "BPLCNM": "다른 가상 이름"}
        result = self.profile([row, deepcopy(row), changed])
        self.assertEqual(result["checks"]["duplicateId"]["count"], 2)
        self.assertEqual(result["checks"]["conflictingId"]["count"], 1)

    def test_schema_drift_missing_all_phone_tags_is_not_zero_missing_phones(self):
        rows = quality.fictional_rows()
        for row in rows:
            del row["SITETEL"]
        with self.assertRaisesRegex(quality.QualityError, "^SCHEMA_MISMATCH$"):
            self.profile(rows)

    def test_normal_empty_and_upstream_failure_are_distinct(self):
        for raw in [xml([], 0), b"<RESULT><CODE>INFO-200</CODE></RESULT>", xml([], 0, "INFO-200")]:
            self.assertEqual(quality.parse_xml(raw), [])
        report = self.profile([])
        self.assertEqual(report["state"], "EMPTY")
        self.assertEqual(report["schema"]["state"], "UNKNOWN_EMPTY")
        self.assertIsNone(report["checks"]["missingId"]["rate"])
        for raw in [xml([], 4), xml([], 0, "INFO-100"), xml([], 0, "ERROR-500")]:
            with self.assertRaises(quality.QualityError):
                quality.parse_xml(raw)

    def test_xml_limits_unsafe_entities_encoding_and_duplicate_tags(self):
        row = quality.fictional_rows()[0]
        repeated = xml([row]).replace(b"</row>", b"<MGTNO>private-id</MGTNO></row>")
        inputs = [
            b"x" * (quality.MAX_BYTES + 1),
            b"<!DOCTYPE x [<!ENTITY x 'private'>]><x>&x;</x>",
            "<root/>".encode("utf-16"),
            b"<not-service/>",
            b"<broken",
            repeated,
            xml([row] * (quality.MAX_ROWS + 1)),
        ]
        for raw in inputs:
            with self.subTest(length=len(raw)), self.assertRaises(quality.QualityError):
                quality.parse_xml(raw)

    def test_administrative_status_never_authorizes_booking(self):
        rows = quality.fictional_rows()
        rows[1]["TRDSTATENM"] = "폐업"
        rows[2]["TRDSTATENM"] = "private-unknown-status"
        result = self.profile(rows)
        self.assertEqual(
            result["administrativeStatusLabels"], {"UNKNOWN": 1, "영업/정상": 1, "폐업": 1}
        )
        self.assertFalse(result["bookable"] or result["inquiryDispatchAllowed"])
        self.assertNotIn("private-unknown-status", json.dumps(result))

    def test_cli_defaults_to_mock_and_never_prints_input_or_error_values(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(quality.main([]), 0)
        self.assertEqual(json.loads(output.getvalue())["mode"], "MOCK")
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "private-file-name.xml"
            rows = quality.fictional_rows()
            for row in rows:
                row.update(
                    MGTNO="private-row-id", BPLCNM="private-merchant", SITETEL="private-contact"
                )
            path.write_bytes(xml(rows))
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(quality.main(["--input", str(path)]), 0)
            self.assertNotIn("private-", output.getvalue())
            path.write_text("private-parser-text")
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(quality.main(["--input", str(path)]), 1)
            self.assertEqual(
                json.loads(output.getvalue()), {"state": "FAILED", "code": "INVALID_XML"}
            )


if __name__ == "__main__":
    unittest.main()
