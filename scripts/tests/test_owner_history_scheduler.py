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
        initial = {"zero_streak": 0, "paused_until": None,
                   "last_finished_at": None}
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
        self.assertEqual(saved["zero_streak"], 0)
        self.assertIsNone(saved["paused_until"])
        self.assertIsNotNone(saved["last_finished_at"])

    def test_no_api_calls_does_not_reset_streak(self):
        state = {"zero_streak": 1, "paused_until": None,
                 "last_finished_at": None}
        self.assertEqual(s.new_state(state, {"PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 0,
                                             "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 0},
                                     self.stamp("2026-11-11", 17, 15)), state)

    def test_root_private_state_roundtrip(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / ".scheduler-state.json"
            old = {"zero_streak": 1, "paused_until": None}
            s.save_state(path, old)
            self.assertEqual(path.stat().st_mode & 0o077, 0)
            if path.stat().st_uid == 0:
                expected = {**old, "last_finished_at": None}
                self.assertEqual(s.load_state(path), expected)
                s.save_state(path, expected)
                self.assertEqual(s.load_state(path), expected)

    def test_recovery_empty_windows_count_toward_circuit_breaker(self):
        now = self.stamp("2026-11-11", 18, 2)
        old = {"zero_streak": 0, "paused_until": None, "last_finished_at": None}
        # The Angel One short-window recovery runs had zero *stored* pages,
        # but did query windows. The original breaker missed these.
        empty = {
            "PERSONAL_RECOVERY_WINDOWS_SELECTED": 13,
            "PERSONAL_SHORT_WINDOW_RESPONSES": 13,
            "PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 0,
            "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 0,
        }
        first = s.new_state(old, empty, now)
        self.assertEqual(first["zero_streak"], 1)
        self.assertIsNone(first["paused_until"])
        second = s.new_state(first, empty, now + dt.timedelta(minutes=4))
        self.assertEqual(second["zero_streak"], 2)
        self.assertIsNotNone(second["paused_until"])

    def test_cross_batch_spacing_and_clock_regression(self):
        now = self.stamp("2026-11-11", 18, 2)
        state = {"zero_streak": 0, "paused_until": None,
                 "last_finished_at": now.isoformat()}
        self.assertTrue(s.recently_finished(state, now + dt.timedelta(seconds=10)))
        self.assertFalse(s.recently_finished(state, now + dt.timedelta(seconds=32)))
        with self.assertRaisesRegex(s.SchedulerBlocked, "INVALID_LAST_RUN_CLOCK"):
            s.recently_finished(state, now - dt.timedelta(seconds=2))

    def test_provider_cooldown_file_absent_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(s, "ROOT", Path(tmp)):
                with self.assertRaisesRegex(
                        s.SchedulerBlocked, "UNTRUSTED_PROVIDER_COOLDOWN_LEDGER"):
                    s.provider_cooldown_until(self.stamp("2026-11-11", 18, 0))

    def test_new_state_preserves_legacy_no_work_and_positive_progress(self):
        now = self.stamp("2026-11-11", 17, 30)
        prior = {"zero_streak": 1, "paused_until": None,
                 "last_finished_at": None}
        no_work = {"PERSONAL_RECOVERY_WINDOWS_SELECTED": 0,
                   "PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 0,
                   "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 0}
        self.assertEqual(s.new_state(prior, no_work, now), prior)
        positive = {**no_work,
                    "PERSONAL_PAGES_DOWNLOADED_THIS_RUN": 2,
                    "PERSONAL_ROWS_DOWNLOADED_THIS_RUN": 350}
        self.assertEqual(s.new_state(prior, positive, now)["zero_streak"], 0)

    def test_wrong_instance_rejected(self):
        class FakeResponse:
            def __init__(self, value):
                self.value = value
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def read(self, n):
                return self.value
        with patch.object(s, "urlopen", side_effect=[FakeResponse(b"token"),
                                                       FakeResponse(b"i-other")]):
            with self.assertRaisesRegex(s.SchedulerBlocked, "WRONG_EC2_INSTANCE"):
                s.verify_instance_identity()

    def test_fail_closed_non_ec2_or_non_root(self):
        with patch.object(s.os, "geteuid", return_value=1000):
            with self.assertRaisesRegex(s.SchedulerBlocked, "WRONG_HOST_IDENTITY"):
                s.verify_host(self.stamp("2026-11-11", 17, 15))


if __name__ == "__main__":
    unittest.main()
