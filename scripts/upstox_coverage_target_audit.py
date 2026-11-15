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
        if (not isinstance(m["rights_receipt_id"], str) or not m["rights_receipt_id"].strip()\n                or not isinstance(m["source_domains"], list) or not m["source_domains"]\n                or any(not isinstance(x, str) or not x.strip() for x in m["source_domains"])\n                or not isinstance(m["revision_policy"], str) or not m["revision_policy"].strip()):
            raise ValueError("rights provenance missing")
        frames = m["timeframes"]
        listings = m["listings"]
        if (not isinstance(frames, list) or not frames\n                or any(not isinstance(x, str) or not x.strip() for x in frames)\n                or len(set(frames)) != len(frames)):
            raise ValueError("timeframes invalid")
        if not isinstance(listings, list) or not listings:
            raise ValueError("listings missing")
        keys = []
        for item in listings:
            if (not isinstance(item, dict) or any(\n                    not isinstance(item.get(k), str) or not item[k].strip()\n                    for k in ("instrument_id", "listing_id", "symbol"))):
                raise ValueError("identity missing")
            keys.append((item["instrument_id"], item["listing_id"]))
        if len(keys) != len(set(keys)):
            raise ValueError("duplicate listing")
        # Monthly partition denominator is independent of candle counts and PR counts.
        months = (end.year - start.year) * 12 + end.month - start.month + 1
        planned = len(keys) * len(frames) * months
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
                        dict(instrument_id="i1", listing_id="l1", symbol="EXAMPLE")],
                    rights_receipt_id="reviewed-receipt", rights_valid_from="2025-01-01",
                    rights_valid_until="2027-01-01", source_domains=["historical"],
                    revision_policy="immutable-versioned")
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
    def test_malformed_timeframe_fails_closed(self):\n        m = self.fixture(); m["timeframes"] = [["day"]]\n        self.assertEqual(audit(m)["state"], "UNKNOWN")\n    def test_malformed_listing_fails_closed(self):\n        m = self.fixture(); m["listings"] = [None]\n        self.assertEqual(audit(m)["state"], "UNKNOWN")\n    def test_malformed_rights_domain_fails_closed(self):\n        m = self.fixture(); m["source_domains"] = ["historical", None]\n        self.assertEqual(audit(m)["state"], "UNKNOWN")\n    def test_rights_window_fails_closed(self):
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
