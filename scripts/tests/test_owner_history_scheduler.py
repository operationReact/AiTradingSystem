"""Offline tests: no AWS, broker, host or clock-dependent side effects."""
import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

MODULE = Path(__file__).resolve().parents[2] / "infra/ec2-paper/owner_history_scheduler.py"
spec = importlib.util.spec_from_file_location("owner_history_scheduler", MODULE)
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
IST = ZoneInfo("Asia/Kolkata")


class SchedulerTests(unittest.TestCase):
    def stamp(self, weekday_date, hour, minute):
        return dt.datetime.fromisoformat(f"{weekday_date}T{hour:02d}:{minute:02d}:00").replace(tzinfo=IST)

    def test_weekday_market_and_shutdown_cutoff(self):
        self.assertFalse(s.allowed_time(self.stamp("2026-11-11", 15, 59)))
        self.assertTrue(s.allowed_time(self.stamp("2026-11-11", 16, 15)))
        self.assertTrue(s.allowed_time(self.stamp("2026-11-11", 20, 15)))
        self.assertFalse(s.allowed_time(self.stamp("2026-11-11", 21, 0)))
        self.assertFalse(s.allowed_time(self.stamp("2026-11-11", 22, 0)))

    def test_weekend_hours(self):
        self.assertFalse(s.allowed_time(self.stamp("2026-11-14", 8, 59)))
        self.assertTrue(s.allowed_time(self.stamp("2026-11-14", 9, 15)))
        self.assertFalse(s.allowed_time(self.stamp("2026-11-14", 21, 0)))

    def test_only_safe_counter_lines_pass(self):
        m = s.metrics_from_output("PERSONAL_ROWS_DOWNLOADED_THIS_RUN: 0\n"
            "SECRET: leave hidden\nPERSONAL_PAGES_DOWNLOADED_THIS_RUN: 12\n"
            "PERSONAL_REAL_EQUITY_ROWS_STORED_TOTAL: 56172\n")
        self.assertEqual(m["PERSONAL_PAGES_DOWNLOADED_THIS_RUN"], 12)
        self.assertEqual(len(m), 3)
        with self.assertRaises(s.SchedulerBlocked):
            s.metrics_from_output("PERSONAL_ROWS_DOWNLOADED_THIS_RUN: 2\n"
                                  "PERSONAL_ROWS_DOWNLOADED_THIS_RUN: 3")

    def test_two_empty_batches_pause_then_positive_resets(self):
        now = self.stamp("2026-11-11", 18, 15)
        initial = {"zero_streak": 0, "paused_until": None}
        zeros = {"PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 12,
                 "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 0}
        first = s.new_state(initial, zeros, now)
        self.assertEqual(first["zero_streak"], 1)
        self.assertIsNone(first["paused_until"])
        second = s.new_state(first, zeros, now)
        self.assertEqual(second["zero_streak"], 2)
        self.assertEqual(dt.datetime.fromisoformat(second["paused_until"]),
                         now + dt.timedelta(hours=24))
        saved = s.new_state(second, {"PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 5,
                                     "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 345}, now)
        self.assertEqual(saved, initial)

    def test_no_api_calls_does_not_reset_streak(self):
        state = {"zero_streak": 1, "paused_until": None}
        self.assertEqual(s.new_state(state, {"PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 0,
                                             "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 0},
                                     self.stamp("2026-11-11", 17, 15)), state)

    def test_root_private_state_roundtrip(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / ".scheduler-state.json"
            initial = {"zero_streak": 1, "paused_until": None}
            s.save_state(path, initial)
            self.assertEqual(path.stat().st_mode & 0o077, 0)
            if path.stat().st_uid == 0:
                self.assertEqual(s.load_state(path), initial)

    def test_fail_closed_non_ec2_or_non_root(self):
        with patch.object(s.os, "geteuid", return_value=1000):
            with self.assertRaisesRegex(s.SchedulerBlocked, "WRONG_HOST_IDENTITY"):
                s.verify_host(self.stamp("2026-11-11", 17, 15))


if __name__ == "__main__":
    unittest.main()
