"""Offline tests for the sanitized owner-personal archive progress exporter."""
import datetime as dt
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[2] / "infra/ec2-paper/owner_history_progress.py"
spec = importlib.util.spec_from_file_location("owner_history_progress", MODULE)
progress = importlib.util.module_from_spec(spec)
spec.loader.exec_module(progress)


def fixture(root: Path, tamper=False):
    (root / "pages").mkdir()
    conn = sqlite3.connect(root / "manifest.sqlite3")
    conn.execute("""CREATE TABLE page(
        exchange TEXT, symbol TEXT, token TEXT, source_sha256 TEXT,
        archive_relative_path TEXT, row_count INTEGER, earliest TEXT,
        latest TEXT, from_date TEXT)""")
    conn.execute("CREATE TABLE page_failure(token TEXT)")
    conn.execute("CREATE TABLE recovery_scan(next_through TEXT)")
    conn.execute("""CREATE TABLE candle_request(
        id INTEGER PRIMARY KEY, symbol TEXT, from_date TEXT, through_date TEXT,
        outcome TEXT, returned_rows INTEGER)""")
    record = {
        "format": "minttick.owner.personal.angelone.daily.v1",
        "symbol": "RELIANCE-EQ",
        "token": "2885",
        "interval": "ONE_DAY",
        "rows": [
            ["2024-01-02T15:30:00+05:30", 100, 110, 99, 105, 12345],
            ["2024-01-03T15:30:00+05:30", 106, 111, 102, 108, 9876],
        ],
    }
    payload = json.dumps(record).encode("utf-8")
    digest = hashlib.sha256(payload).hexdigest()
    rel = f"pages/{digest}.json.gz"
    (root / rel).write_bytes(gzip.compress(payload if not tamper else b"tampered"))
    (root / rel).chmod(0o600)
    conn.execute("INSERT INTO page VALUES(?,?,?,?,?,?,?,?,?)",
        ("NSE", "RELIANCE-EQ", "2885", digest, rel, 2,
         "2024-01-02", "2024-01-03", "2024-01-01"))
    conn.execute("INSERT INTO page_failure VALUES(?)", ("2885",))
    conn.execute("INSERT INTO recovery_scan VALUES(?)", ("2023-12-31",))
    conn.execute("""INSERT INTO candle_request(symbol,from_date,through_date,outcome,returned_rows)
                     VALUES(?,?,?,?,?)""",
        ("RELIANCE-EQ", "2024-01-01", "2024-12-31", "EMPTY_CAUSE_UNKNOWN", 0))
    conn.commit()
    conn.close()


class ProgressSnapshotTests(unittest.TestCase):
    def test_exact_year_counts_and_incomplete_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root)
            with patch.object(progress, "_private_archive", return_value=None):
                data = progress.build_snapshot(root,
                    dt.datetime(2026, 11, 11, 3, 30, tzinfo=dt.timezone.utc),
                    "IDLE", {"zero_streak": 1, "paused_until": None})
            self.assertEqual(data["task"]["candles"], 2)
            self.assertEqual(data["task"]["stocksWithCandles"], 1)
            self.assertEqual(data["task"]["pendingRecoveryScans"], 1)
            self.assertEqual(data["task"]["failedWindows"], 1)
            self.assertEqual(len(data["task"]["years"]), 21)
            self.assertEqual(data["task"]["years"][2024-2006]["candles"], 2)
            self.assertEqual(data["task"]["years"][2024-2006]["completeness"], "UNVERIFIED")
            self.assertFalse(data["task"]["yearCoverageVerified"])
            self.assertEqual(data["scheduler"]["cadence"], "BOUNDED_CONTINUOUS_24H")
            self.assertEqual(data["scheduler"]["weekdayHoursIST"], "00:00–23:58")
            self.assertEqual(data["recentRequests"][0]["fromDate"], "2024-01-01")
            serialized = json.dumps(data)
            self.assertNotIn("2885", serialized)
            self.assertNotIn("12345", serialized)

    def test_two_minute_cadence_includes_market_hours(self):
        in_window = dt.datetime.fromisoformat("2026-11-11T16:00:15+05:30")
        self.assertEqual(progress._next_scheduled_at(in_window),
                         "2026-11-11T16:02:00+05:30")
        closed = dt.datetime.fromisoformat("2026-11-11T21:15:00+05:30")
        self.assertEqual(progress._next_scheduled_at(closed),
                         "2026-11-11T21:16:00+05:30")

    def test_overnight_tick_wraps_to_next_day(self):
        late = dt.datetime.fromisoformat("2026-11-11T23:59:59+05:30")
        self.assertEqual(progress._next_scheduled_at(late),
                         "2026-11-12T00:00:00+05:30")
        with self.assertRaises(progress.ProgressUnavailable):
            progress._next_scheduled_at(dt.datetime(2026, 11, 11, 21, 0))

    def test_tampered_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root, tamper=True)
            with patch.object(progress, "_private_archive", return_value=None):
                with self.assertRaises(progress.ProgressUnavailable):
                    progress.build_snapshot(root,
                        dt.datetime.now(dt.timezone.utc), "IDLE", {})

    def test_unsupported_status_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(progress.ProgressUnavailable):
                progress.build_snapshot(Path(tmp), dt.datetime.now(dt.timezone.utc),
                                        "FINISHED_FOREVER", {})


if __name__ == "__main__":
    unittest.main()
