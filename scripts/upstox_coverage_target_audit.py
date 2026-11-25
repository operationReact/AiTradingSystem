#!/usr/bin/env python3
"""Read-only historical target manifest audit. No source API or database access.

Input is an explicitly reviewed JSON export, never inferred from source credentials.
Usage: python3 scripts/upstox_coverage_target_audit.py manifest.json
Self-test: python3 scripts/upstox_coverage_target_audit.py --self-test
"""
import datetime as dt
import json
import sys
import unittest

REQUIRED = ("provider", "exchange", "universe_as_of", "window_start",
            "window_end", "timeframes", "listings", "rights_receipt_id",
            "rights_valid_from", "rights_valid_until", "source_domains",
            "revision_policy")


def audit(m):
    if not isinstance(m, dict) or any(k not in m for k in REQUIRED):
        return {"state": "UNKNOWN", "reason": "MANIFEST_INCOMPLETE",
                "planned_partitions": None, "verified_partitions": None}
    if m["provider"] != "UPSTOX" or m["exchange"] != "NSE_EQ":
        return {"state": "UNKNOWN", "reason": "SCOPE_UNREVIEWED",
                "planned_partitions": None, "verified_partitions": None}
    try:
        start = dt.date.fromisoformat(m["window_start"])
        end = dt.date.fromisoformat(m["window_end"])
        asof = dt.date.fromisoformat(m["universe_as_of"])
        rights_from = dt.date.fromisoformat(m["rights_valid_from"])
        rights_until = dt.date.fromisoformat(m["rights_valid_until"])
        if start > end or asof < start or rights_from > start or rights_until < end:
            raise ValueError("window or rights invalid")
        if (not isinstance(m["rights_receipt_id"], str) or not m["rights_receipt_id"].strip()
                or not isinstance(m["source_domains"], list) or not m["source_domains"]
                or any(not isinstance(x, str) or not x.strip() for x in m["source_domains"])
                or not isinstance(m["revision_policy"], str) or not m["revision_policy"].strip()):
            raise ValueError("rights provenance missing")
        # Receipt identity alone does not establish permission for this use.
        if (m.get("rights_review_status") != "VERIFIED"
                or m.get("rights_permitted_use") != "HISTORICAL_COVERAGE_PLANNING"
                or "historical" not in m["source_domains"]):
            raise ValueError("rights review or permitted use missing")
        frames = m["timeframes"]
        listings = m["listings"]
        if (not isinstance(frames, list) or not frames
                or any(not isinstance(x, str) or not x.strip() for x in frames)
                or len(set(frames)) != len(frames)):
            raise ValueError("timeframes invalid")
        if not isinstance(listings, list) or not listings:
            raise ValueError("listings missing")
        keys = []
        # A listing episode must be observable by the reviewed universe snapshot.
        # Future-starting episodes cannot enter an as-of denominator.
        if any(start <= dt.date.fromisoformat(item["active_from"]) <= end
               and dt.date.fromisoformat(item["active_from"]) > asof for item in listings):
            raise ValueError("future listing relative to universe snapshot")
        for item in listings:
            if (not isinstance(item, dict) or any(
                    not isinstance(item.get(k), str) or not item[k].strip()
                    for k in ("instrument_id", "listing_id", "symbol", "active_from", "active_until"))):
                raise ValueError("identity missing")
            active_from = dt.date.fromisoformat(item["active_from"])
            active_until = dt.date.fromisoformat(item["active_until"])
            if active_from > active_until:
                raise ValueError("listing episode reversed")
            keys.append((item["instrument_id"], item["listing_id"]))
        if len(keys) != len(set(keys)):
            raise ValueError("duplicate listing")
        # Monthly partition denominator is independent of candle counts and PR counts.
        months = (end.year - start.year) * 12 + end.month - start.month + 1
        planned = 0
        for item in listings:
            active_from = max(start, dt.date.fromisoformat(item["active_from"]))
            active_until = min(end, dt.date.fromisoformat(item["active_until"]))
            if active_from <= active_until:
                episode_months = ((active_until.year - active_from.year) * 12
                                  + active_until.month - active_from.month + 1)
                planned += episode_months * len(frames)
        return {"state": "PLANNED_ONLY", "reason": "PERSISTED_RECEIPTS_NOT_AUDITED",
                "planned_partitions": planned, "verified_partitions": None,
                "listing_count": len(keys), "month_count": months}
    except (ValueError, TypeError, KeyError):
        return {"state": "UNKNOWN", "reason": "INVALID_OR_UNAUTHORIZED_MANIFEST",
                "planned_partitions": None, "verified_partitions": None}


class Tests(unittest.TestCase):
    def fixture(self):
        return dict(provider="UPSTOX", exchange="NSE_EQ", universe_as_of="2026-01-01",
                    window_start="2026-01-01", window_end="2026-02-28",
                    timeframes=["day", "hour"], listings=[
                        dict(instrument_id="i1", listing_id="l1", symbol="EXAMPLE", active_from="2025-01-01", active_until="2027-01-01")],
                    rights_receipt_id="reviewed-receipt", rights_valid_from="2025-01-01",
                    rights_valid_until="2027-01-01", source_domains=["historical"],
                    revision_policy="immutable-versioned", rights_review_status="VERIFIED",
                    rights_permitted_use="HISTORICAL_COVERAGE_PLANNING")
    def test_fixed_monthly_denominator(self):
        self.assertEqual(audit(self.fixture())["planned_partitions"], 4)
    def test_missing_rights_fails_closed(self):
        m = self.fixture(); m["rights_receipt_id"] = ""
        self.assertIsNone(audit(m)["planned_partitions"])
    def test_duplicate_listing_fails_closed(self):
        m = self.fixture(); m["listings"] *= 2
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_never_claim_verified_from_manifest(self):
        self.assertIsNone(audit(self.fixture())["verified_partitions"])
    def test_malformed_timeframe_fails_closed(self):
        m = self.fixture(); m["timeframes"] = [["day"]]
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_malformed_listing_fails_closed(self):
        m = self.fixture(); m["listings"] = [None]
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_malformed_rights_domain_fails_closed(self):
        m = self.fixture(); m["source_domains"] = ["historical", None]
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_listing_episode_restricts_months(self):
        m = self.fixture()
        m["listings"][0]["active_from"] = "2026-02-01"
        self.assertEqual(audit(m)["planned_partitions"], 2)
    def test_listing_episode_outside_window_zero_planned(self):
        m = self.fixture()
        m["listings"][0]["active_from"] = "2027-01-01"
        self.assertEqual(audit(m)["planned_partitions"], 0)
    def test_future_listing_not_in_asof_universe(self):
        m = self.fixture(); m["listings"][0]["active_from"] = "2026-01-15"
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_malformed_episode_fails_closed(self):
        m = self.fixture()
        m["listings"][0]["active_until"] = "invalid"
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_receipt_id_without_review_is_not_authorization(self):
        m = self.fixture(); m.pop("rights_review_status")
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_wrong_rights_use_fails_closed(self):
        m = self.fixture(); m["rights_permitted_use"] = "MODEL_TRAINING"
        self.assertIsNone(audit(m)["planned_partitions"])
    def test_unrelated_source_domain_fails_closed(self):
        m = self.fixture(); m["source_domains"] = ["quotes"]
        self.assertEqual(audit(m)["state"], "UNKNOWN")
    def test_rights_window_fails_closed(self):
        m = self.fixture(); m["rights_valid_until"] = "2026-01-31"
        self.assertEqual(audit(m)["state"], "UNKNOWN")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        unittest.main(argv=[sys.argv[0]])
    elif len(sys.argv) == 2:
        with open(sys.argv[1], encoding="utf-8") as fh:
            result = audit(json.load(fh))
        print(json.dumps(result, sort_keys=True))
        sys.exit(0 if result["state"] == "PLANNED_ONLY" else 2)
    else:
        sys.exit("usage: audit.py manifest.json | --self-test")
