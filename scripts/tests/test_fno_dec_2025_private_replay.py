#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location("private_replay",ROOT/"infra/ec2-paper/fno_dec_2025_private_replay.py")
module=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def candle(hour, close=105):
    return (f"2025-12-15T{hour}:00+05:30",101,108,99,close,1000,100)


class DecReplayTests(unittest.TestCase):
    def test_provider_midnight_only_matches_saved_midnight(self):
        saved=[candle("00:00"),candle("09:15",107)]
        counts=module.compare_one(saved,[list(candle("00:00"))])
        self.assertEqual(counts["providerMidnightOnlyDays"],1)
        self.assertEqual(counts["matchedMidnightValues"],1)
        self.assertEqual(counts["matched0915Values"],0)
        self.assertEqual(counts["saved0915Rows"],1)

    def test_source_contains_both_conflicting_daily_records(self):
        saved=[candle("00:00"),candle("09:15",107)]
        counts=module.compare_one(saved,[list(c) for c in saved])
        self.assertEqual(counts["sourceMultiRowDays"],1)
        self.assertEqual(counts["matchedMidnightValues"],1)
        self.assertEqual(counts["matched0915Values"],1)

    def test_wrong_midnight_price_does_not_count_as_match(self):
        counts=module.compare_one([candle("00:00")],[list(candle("00:00",106))])
        self.assertEqual(counts["matchedMidnightValues"],0)

    def test_bad_source_payload_counted_without_leaking_values(self):
        counts=module.compare_one([candle("00:00")],[["not-a-timestamp",1,2,3,4,5,6]])
        self.assertEqual(counts["badSourceRows"],1)
        self.assertEqual(counts["sourceMissingSavedDays"],1)


if __name__=="__main__":
    unittest.main()
