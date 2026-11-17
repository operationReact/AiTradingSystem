import unittest
from datetime import datetime, timedelta, timezone

import market_data_diagnostic as md

NOW = datetime(2026, 11, 17, 4, 0, tzinfo=timezone.utc)  # 09:30 IST


def candle(minutes_ago, source="ANGEL_ONE_SMARTAPI"):
    t = NOW - timedelta(minutes=minutes_ago)
    return {"candleTime": t.isoformat().replace("+00:00", "Z"), "source": source, "interval": "FIVE_MINUTE"}


class MarketDataDiagnosticTests(unittest.TestCase):
    def test_missing_candles_are_classified_missing(self):
        r = md.classify_candles([], NOW)
        self.assertEqual(r["verdict"], "MISSING")
        self.assertEqual(r["count"], 0)

    def test_fresh_permitted_candle_is_valid(self):
        r = md.classify_candles([candle(5), candle(10)], NOW)
        self.assertEqual(r["verdict"], "VALID")
        self.assertEqual(r["ageMinutes"], 5.0)
        self.assertEqual(r["source"], "ANGEL_ONE_SMARTAPI")

    def test_latest_candle_older_than_window_is_stale(self):
        r = md.classify_candles([candle(120), candle(125)], NOW)
        self.assertEqual(r["verdict"], "STALE")
        self.assertEqual(r["maxAgeMinutes"], 20)

    def test_candle_exactly_at_window_edge_is_valid(self):
        self.assertEqual(md.classify_candles([candle(20)], NOW)["verdict"], "VALID")
        self.assertEqual(md.classify_candles([candle(21)], NOW)["verdict"], "STALE")

    def test_future_candle_is_stale_not_valid(self):
        future = {"candleTime": (NOW + timedelta(hours=1)).isoformat(), "source": "ANGEL_ONE_SMARTAPI"}
        self.assertEqual(md.classify_candles([future], NOW)["verdict"], "STALE")

    def test_disallowed_source_is_classified_disallowed_even_when_fresh(self):
        r = md.classify_candles([candle(3, source="CSV_IMPORT")], NOW)
        self.assertEqual(r["verdict"], "DISALLOWED")
        self.assertEqual(r["source"], "CSV_IMPORT")

    def test_latest_candle_is_chosen_regardless_of_order(self):
        r = md.classify_candles([candle(60), candle(2), candle(30)], NOW)
        self.assertEqual(r["verdict"], "VALID")
        self.assertEqual(r["ageMinutes"], 2.0)

    def test_cycle_blocker_names_the_first_blocking_stage(self):
        cycle = {"outcome": "DATA_BLOCKED", "reasons": ["SIGNAL_CANDLE_STALE"], "stages": [
            {"code": "DATA_GATE", "result": "BLOCKED", "detail": "Latest candle too old"}]}
        r = md.classify_cycle(cycle)
        self.assertEqual(r["blocker"], "DATA_GATE:Latest candle too old")
        self.assertEqual(r["reasons"], ["SIGNAL_CANDLE_STALE"])

    def test_no_cycle_is_reported_not_guessed(self):
        self.assertEqual(md.classify_cycle(None)["blocker"], "NO_CYCLE_RECORDED")

    def test_passing_stages_have_no_blocker(self):
        cycle = {"outcome": "NO_ENTRY", "reasons": [], "stages": [{"code": "DATA_GATE", "result": "PASSED", "detail": "ok"}]}
        self.assertIsNone(md.classify_cycle(cycle)["blocker"])


if __name__ == "__main__":
    unittest.main()
