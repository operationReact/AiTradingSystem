"""No broker/network/cloud access: deterministic tests for 20-year fleet planning."""
from __future__ import annotations

import datetime as dt
import json
import pathlib
import tempfile
import unittest
import uuid
from unittest.mock import patch
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import golden_source_fleet_preflight as fleet


class FleetPreflightTest(unittest.TestCase):
    def item(self, first="2000-01-01", last=None, exchange="NSE"):
        return {
            "listing_id": str(uuid.uuid4()),
            "exchange": exchange,
            "listed_from": first,
            "delisted_on": last,
            "identity_evidence_digest": "a" * 64,
        }

    def inventory(self, records):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = pathlib.Path(temp.name) / "verified-identity-input.json"
        path.write_text(json.dumps({
            "schema": fleet.INVENTORY_SCHEMA,
            "reference_digest": "b" * 64,
            "listings": records,
        }), encoding="utf-8")
        return path

    def test_first_window_matches_real_2006_probe_and_ranges_are_contiguous(self):
        target = list(fleet.windows(fleet.START, fleet.END, 2000))
        self.assertEqual(len(target), 4)
        self.assertEqual(target[0][0], dt.date(2006, 11, 10))
        self.assertEqual(target[0][1], dt.date(2012, 5, 1))
        self.assertEqual(target[1][0], dt.date(2012, 5, 2))
        self.assertEqual(target[2][1], dt.date(2023, 4, 14))
        self.assertEqual(target[-1][1], dt.date(2026, 11, 10))
        self.assertEqual(sum((end-start).days+1 for start,end in target),
                         (fleet.END-fleet.START).days+1)
        self.assertTrue(all(1 <= (end-start).days+1 <= 2000
                            for start,end in target))

    def test_listing_episodes_only_after_actual_listing_and_before_delisting(self):
        x = self.item(first="2020-01-01", last="2024-02-01")
        rows = fleet.require_strict_inventory(self.inventory([x]))
        plan = fleet.plan_inventory(rows)
        self.assertEqual(plan["listing_episodes_intersecting_target"], 1)
        self.assertEqual(plan["planned_2000_day_provider_reads_if_approved"], 1)
        self.assertEqual(plan["sample_plans_not_executable"][0]["episode_first"],
                         "2020-01-01")
        self.assertEqual(plan["sample_plans_not_executable"][0]["episode_last"],
                         "2024-01-31")

    def test_legacy_listing_ending_before_2006_is_not_fetched(self):
        x = self.item(first="1990-01-01", last="2005-01-01")
        plan = fleet.plan_inventory(fleet.require_strict_inventory(
            self.inventory([x])))
        self.assertEqual(plan["episodes_outside_target"], 1)
        self.assertEqual(plan["planned_2000_day_provider_reads_if_approved"], 0)

    def test_2778_fixture_stock_scale_is_planning_not_actual_calls(self):
        rows = [(str(uuid.uuid4()), "NSE", dt.date(2000, 1, 1), None)
                for _ in range(2778)]
        plan = fleet.plan_inventory(rows)
        self.assertEqual(plan["listing_episodes_intersecting_target"], 2778)
        self.assertEqual(plan["planned_2000_day_provider_reads_if_approved"], 4*2778)
        self.assertGreater(plan["planned_31_day_archive_segments_if_approved"],
                           plan["planned_2000_day_provider_reads_if_approved"])
        self.assertEqual(len(plan["sample_plans_not_executable"]), 5)

    def test_empty_default_does_not_claim_repository_is_loaded(self):
        report = fleet.generate()
        self.assertEqual(report["planning"]["listing_episodes_supplied"], 0)
        self.assertEqual(report["planning"]["planned_2000_day_provider_reads_if_approved"], 0)
        self.assertEqual(report["inventory_input_status"], "ABSENT")
        self.assertFalse(report["eligible_to_start_bulk_source_download"])
        self.assertFalse(report["eligible_to_publish_model_features"])
        self.assertEqual(report["historical_angelone_probe_for_one_sample_stock"]
                         ["stored_bars"], 0)

    def test_every_model_screen_strategy_and_data_source_is_covered(self):
        report = fleet.generate()
        self.assertEqual(len(report["models"]), 18)
        self.assertEqual(report["screens_total"], 43)
        self.assertEqual(report["strategy_families_total"], 20)
        self.assertEqual(report["strategy_templates_total"], 232)
        self.assertEqual(report["data_families_total"], 24)
        self.assertTrue(report["no_models_claimed_ready"])
        self.assertTrue(all(not x["model_data_ready"] and
                            x["required_sources"] == x["missing_verified_sources"]
                            for x in report["models"]))
        self.assertIn("fundamental_filings", report["source_families"])
        self.assertIn("derivatives_chain", report["source_families"])
        self.assertIn("corporate_actions", report["source_families"])
        self.assertIn("daily_bars", report["source_families"]
                      ["required_by_models"] if False else report["source_families"])

    def test_missing_source_prevents_any_model_readiness_claim(self):
        report = fleet.generate()
        self.assertIn("Daily", report["source_families"]["daily_bars"]
                      ["history_requirement"].capitalize())
        self.assertTrue(report["source_families"]["fundamental_filings"]
                        ["required_by_screens"])
        self.assertIn("Financial NLP Challenger",
                      report["source_families"]["news_events"]
                      ["required_by_models"])
        self.assertIn("NO_VERIFIED_MODEL_TRAINING_AND_INFERENCE_RIGHTS",
                      report["requires_review_before_api_or_storage"])

    def test_rejects_unverified_or_tampered_listing_inputs(self):
        one = self.item()
        second = dict(one)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            fleet.require_strict_inventory(self.inventory([one, second]))
        bad_token = dict(one, listing_id="2885")
        with self.assertRaises((ValueError, TypeError)):
            fleet.require_strict_inventory(self.inventory([bad_token]))
        bad_digest = dict(one, identity_evidence_digest="")
        with self.assertRaisesRegex(ValueError, "unevidenced"):
            fleet.require_strict_inventory(self.inventory([bad_digest]))
        bad_exchange = dict(one, exchange="NFO")
        with self.assertRaisesRegex(ValueError, "unsupported exchange"):
            fleet.require_strict_inventory(self.inventory([bad_exchange]))

    def test_sample_inventory_is_only_planning_not_data_attestation(self):
        path = self.inventory([self.item()])
        report = fleet.generate(path)
        self.assertEqual(report["inventory_input_status"],
                         "SUPPLIED_NOT_INDEPENDENTLY_ATTESTED")
        self.assertFalse(report["eligible_to_start_bulk_source_download"])
        self.assertFalse(report["eligible_to_publish_model_features"])

    def test_script_has_no_network_aws_database_or_broker_execution(self):
        code = (ROOT / "scripts/golden_source_fleet_preflight.py").read_text()
        for forbidden in ("boto3", "requests.get(", "urlopen(", "urllib.request",
                          "psycopg", "placeOrder", "getCandleData", "subprocess",
                          "aws ssm", "INSERT INTO", "list_instruments("):
            self.assertNotIn(forbidden, code)


if __name__ == "__main__":
    unittest.main()
