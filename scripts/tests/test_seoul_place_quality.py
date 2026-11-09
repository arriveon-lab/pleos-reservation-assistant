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


class SeoulPilotScreeningTest(unittest.TestCase):
    def row(self, record_id="synthetic-id", **changes):
        row = {**quality.fictional_rows()[0], "MGTNO": record_id, "SITETEL": "synthetic-accepted"}
        return {**row, **changes}

    def report(self, rows):
        # Exercise a positive intersection without inventing any dialable fixture number.
        original = quality.phone_kind
        with patch.object(
            quality,
            "phone_kind",
            side_effect=lambda value: (
                ("SYNTAX_ONLY", None) if value == "synthetic-accepted" else original(value)
            ),
        ):
            return quality.profile(rows, NOW, "MOCK", include_pilot_screening=True)

    def test_same_record_must_satisfy_all_conditions_not_independent_marginals(self):
        rows = [
            self.row("outside", Y="451083.68283992365"),
            self.row("no-phone", SITETEL=""),
            self.row(
                "closed", TRDSTATEGBN="03", TRDSTATENM="폐업", DTLSTATEGBN="02", DTLSTATENM="폐업"
            ),
        ]
        result = self.report(rows)
        self.assertGreater(result["checks"]["validCoordinates"]["count"], 0)
        self.assertGreater(result["phoneKinds"]["SYNTAX_ONLY"], 0)
        self.assertGreater(result["checks"]["withinPilot1030m"]["count"], 0)
        self.assertEqual(result["pilotScreening"]["screenedRecords"], 0)

    def test_positive_diagnostic_still_has_no_product_or_call_authority(self):
        result = self.report([self.row()])
        pilot = result["pilotScreening"]
        self.assertEqual(list(pilot["cumulativeCounts"].values()), [1] * 7)
        self.assertEqual(pilot["screenedRecords"], 1)
        self.assertEqual(pilot["statusRule"]["verification"], "UNVERIFIED_FOR_OA18655")
        self.assertIsNone(pilot["productionCandidateCount"])
        self.assertFalse(pilot["readyForRecommendation"])
        self.assertFalse(
            result["bookable"] or result["inquiryDispatchAllowed"] or result["adoptionReady"]
        )
        self.assertEqual(result["snapshotFreshness"]["state"], "UNKNOWN")
        self.assertNotIn("synthetic-", json.dumps(result))

    def test_missing_status_tag_and_explicit_empty_closure_are_distinct(self):
        for field in quality.PILOT_STATUS_FIELDS:
            row = self.row()
            del row[field]
            with self.subTest(field=field):
                # Keep the base schema present elsewhere while one row has a missing tag.
                pilot = self.report([row, self.row("schema-reference", SITETEL="")])[
                    "pilotScreening"
                ]
                self.assertEqual(pilot["statusKinds"]["MISSING"], 1)
                self.assertEqual(pilot["screenedRecords"], 0)
        self.assertEqual(self.report([self.row()])["pilotScreening"]["screenedRecords"], 1)

    def test_code_label_variants_and_closure_values_cannot_be_normalized_into_match(self):
        for changes in (
            {"TRDSTATEGBN": "1"},
            {"TRDSTATENM": "정상"},
            {"DTLSTATEGBN": "13"},
            {"DTLSTATENM": "영업중"},
            {"TRDSTATEGBN": "03"},
            {"DTLSTATENM": ""},
            {"DCBYMD": "20260101"},
            {"DCBYMD": "unknown"},
        ):
            with self.subTest(changes=changes):
                pilot = self.report([self.row(**changes)])["pilotScreening"]
                self.assertEqual(pilot["screenedRecords"], 0)
                self.assertEqual(pilot["firstExclusionCounts"]["statusUnverifiedOrConflicting"], 1)

    def test_identical_duplicate_group_is_excluded_in_full(self):
        row = self.row()
        pilot = self.report([row, deepcopy(row)])["pilotScreening"]
        self.assertEqual(pilot["duplicateIdGroups"], 1)
        self.assertEqual(pilot["conflictingIdGroups"], 0)
        self.assertEqual(pilot["firstExclusionCounts"]["duplicateId"], 2)
        self.assertEqual(pilot["cumulativeCounts"]["uniqueId"], 0)

    def test_conflicting_detail_state_or_closure_blocks_whole_group_in_any_order(self):
        for changes in ({"DTLSTATENM": "폐업"}, {"DCBYMD": "20260101"}):
            rows = [self.row(), self.row(**changes), self.row("unrelated")]
            forward = self.report(rows)["pilotScreening"]
            self.assertEqual(forward, self.report(list(reversed(rows)))["pilotScreening"])
            self.assertEqual(forward["conflictingIdGroups"], 1)
            self.assertEqual(forward["screenedRecords"], 1)

    def test_missing_id_never_becomes_a_candidate(self):
        pilot = self.report([self.row("")])["pilotScreening"]
        self.assertEqual(pilot["firstExclusionCounts"]["missingId"], 1)
        self.assertEqual(pilot["screenedRecords"], 0)

    def test_missing_invalid_and_outside_coordinates_have_distinct_exclusions(self):
        rows = [
            self.row("missing", X=""),
            self.row("invalid", Y="nan"),
            self.row("outside", Y="451083.68283992365"),
        ]
        pilot = self.report(rows)["pilotScreening"]
        for name in ("missingCoordinates", "invalidCoordinates", "outside1030m"):
            self.assertEqual(pilot["firstExclusionCounts"][name], 1)
        self.assertEqual(pilot["screenedRecords"], 0)

    def test_radius_boundary_is_diagnostic_only_and_never_rounded_into_1km(self):
        for meters, screened, band in (
            (0, 1, "WITHIN_1000M"),
            (1000, 1, "WITHIN_1000M"),
            (1000.00001, 0, "BOUNDARY_1000_TO_1030M"),
            (1030, 0, "BOUNDARY_1000_TO_1030M"),
            (1030.00001, 0, "OUTSIDE_1030M"),
        ):
            with (
                self.subTest(meters=meters),
                patch.object(quality, "distance", return_value=meters),
            ):
                self.assertEqual(quality.radius_band(meters), band)
                pilot = self.report([self.row()])["pilotScreening"]
                self.assertEqual(pilot["screenedRecords"], screened)
                self.assertFalse(pilot["boundaryIncluded"])
        for invalid in (float("nan"), float("inf"), -1, True):
            with self.assertRaises(quality.QualityError):
                quality.radius_band(invalid)

    def test_mobile_placeholder_and_unknown_contact_need_review(self):
        original = quality.phone_kind
        for kind in ("MISSING", "MOBILE_REVIEW", "PLACEHOLDER", "UNRECOGNIZED"):
            with (
                self.subTest(kind=kind),
                patch.object(quality, "phone_kind", return_value=(kind, None)),
            ):
                report = quality.profile([self.row()], NOW, "MOCK", include_pilot_screening=True)
                self.assertEqual(report["pilotScreening"]["screenedRecords"], 0)
                self.assertEqual(
                    report["pilotScreening"]["firstExclusionCounts"]["phoneNotSyntaxOnly"], 1
                )
        self.assertEqual(original("02-0000-0000")[0], "PLACEHOLDER")

    def test_basic_context_is_required_after_phone_syntax(self):
        for field in ("BPLCNM", "RDNWHLADDR"):
            pilot = self.report([self.row(**{field: ""})])["pilotScreening"]
            self.assertEqual(pilot["cumulativeCounts"]["phoneSyntaxOnly"], 1)
            self.assertEqual(pilot["firstExclusionCounts"]["missingNameOrRoadAddress"], 1)
            self.assertEqual(pilot["screenedRecords"], 0)

    def test_counts_reconcile_without_double_counting_and_do_not_claim_coverage(self):
        rows = [
            self.row("pass"),
            self.row("same"),
            self.row("same"),
            self.row(""),
            self.row("no-phone", SITETEL=""),
            self.row("no-coordinate", X=""),
            self.row("unknown-status", TRDSTATEGBN="unknown"),
        ]
        pilot = self.report(rows)["pilotScreening"]
        self.assertEqual(pilot["screenedRecords"], 1)
        self.assertEqual(sum(pilot["firstExclusionCounts"].values()) + pilot["screenedRecords"], 7)
        self.assertEqual(sum(pilot["statusKinds"].values()), 7)
        self.assertEqual(pilot["countDenominator"], 7)
        counts = list(pilot["cumulativeCounts"].values())
        self.assertEqual(counts, sorted(counts, reverse=True))
        self.assertIn("NOT_REPRESENTATIVE_OR_COMPLETE", pilot["coverage"])

    def test_empty_batch_is_unknown_coverage_and_no_production_count(self):
        pilot = self.report([])["pilotScreening"]
        self.assertEqual(pilot["state"], "UNKNOWN_EMPTY")
        self.assertEqual(pilot["countDenominator"], 0)
        self.assertIsNone(pilot["productionCandidateCount"])

    def test_cli_screening_is_opt_in_mock_with_no_row_or_contact_export(self):
        for args, expected in (([], False), (["--pilot-screening"], True)):
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(quality.main(args), 0)
            result = json.loads(output.getvalue())
            self.assertEqual("pilotScreening" in result, expected)
            self.assertEqual(result["mode"], "MOCK")
            self.assertEqual(result["externalCallsByProfiler"], 0)
            self.assertNotIn("fictional-", output.getvalue())
            self.assertNotIn("02-0000", output.getvalue())


if __name__ == "__main__":
    unittest.main()
