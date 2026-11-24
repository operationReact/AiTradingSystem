"""Offline tests for the sanitized owner-personal archive progress exporter."""
import datetime as dt
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
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
    def test_handover_waits_for_activating_oneshot_without_stopping_it(self):
        states = [subprocess.CompletedProcess([], 0, 'activating\n', ''),
                  subprocess.CompletedProcess([], 0, 'inactive\n', '')]
        with patch.object(progress.subprocess, 'run', side_effect=states) as run, \
                patch.object(progress.time, 'sleep') as sleep:
            progress.wait_for_previous_publisher()
            self.assertEqual(2, run.call_count)
            sleep.assert_called_once_with(2)
            for call in run.call_args_list:
                self.assertEqual(['systemctl', 'show', '--value', '-p', 'ActiveState',
                                  'minttick-owner-history.service'], call.args[0])

    def test_handover_is_bounded_and_rejects_unavailable_unit_state(self):
        with patch.object(progress.subprocess, 'run', return_value=
                subprocess.CompletedProcess([], 0, 'activating\n', '')), \
                patch.object(progress.time, 'monotonic', side_effect=[0, 1]):
            with self.assertRaisesRegex(progress.ProgressUnavailable, 'PUBLICATION_HANDOVER_BUSY'):
                progress.wait_for_previous_publisher(timeout=0)
        with patch.object(progress.subprocess, 'run', return_value=
                subprocess.CompletedProcess([], 1, '', 'fixture-private-details')):
            with self.assertRaisesRegex(progress.ProgressUnavailable, 'PUBLICATION_HANDOVER_UNAVAILABLE'):
                progress.wait_for_previous_publisher()

    def test_publication_lock_excludes_another_process_then_releases(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = '''import importlib.util,sys
from pathlib import Path
s=importlib.util.spec_from_file_location("progress",sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
with m.publication_lock(Path(sys.argv[2]), timeout=0):
 print("LOCKED",flush=True)
 sys.stdin.readline()
'''
            child = subprocess.Popen([sys.executable, '-c', script, str(MODULE), tmp],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual('LOCKED', child.stdout.readline().strip())
                with self.assertRaisesRegex(progress.ProgressUnavailable, 'PUBLICATION_BUSY'):
                    with progress.publication_lock(Path(tmp), timeout=0):
                        self.fail('Two publishers entered the critical section')
                child.communicate('\n', timeout=5)
                self.assertEqual(0, child.returncode)
                with progress.publication_lock(Path(tmp), timeout=0):
                    pass
                self.assertTrue((Path(tmp) / '.publication.lock').exists())
            finally:
                if child.poll() is None:
                    child.kill()
                    child.communicate()

    def test_publication_lock_rejects_symlink_and_public_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / 'fixture'
            target.write_text('fixture')
            lock = root / '.publication.lock'
            lock.symlink_to(target)
            with self.assertRaises(OSError):
                with progress.publication_lock(root, timeout=0):
                    self.fail('Symlink lock accepted')
            lock.unlink()
            lock.write_text('')
            lock.chmod(0o644)
            with self.assertRaisesRegex(progress.ProgressUnavailable, 'UNTRUSTED_PUBLICATION_LOCK'):
                with progress.publication_lock(root, timeout=0):
                    self.fail('Public lock accepted')

    def test_monthly_accepts_exact_integral_provider_volume_without_rounding(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sink = progress._OwnerMonthlySink(root,
                dt.datetime(2024, 1, 4, tzinfo=dt.timezone.utc))
            try:
                sink('2885', 'RELIANCE-EQ', ['2024-01-02T15:30:00+05:30', 100, 110, 99, 105, 12345.0])
                sink('2885', 'RELIANCE-EQ', ['2024-01-03T15:30:00+05:30', 106, 111, 102, 108, 9876])
                sink.complete()
            finally:
                sink.discard()
            row = json.loads((root / 'monthly/2885.json').read_text())['rows'][0]
            self.assertEqual([100, 111, 99, 108, 22221], row[1:])
            self.assertIs(type(row[5]), int)

    def test_monthly_rejects_fractional_nonfinite_boolean_and_invalid_volume(self):
        for volume in (0.5, float('nan'), float('inf'), True, -1, 10**12 + 1, '12345'):
            with self.subTest(volume=volume), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                sink = progress._OwnerMonthlySink(root,
                    dt.datetime(2024, 1, 4, tzinfo=dt.timezone.utc))
                try:
                    with self.assertRaises(progress.ProgressUnavailable):
                        sink('2885', 'RELIANCE-EQ', ['2024-01-02T15:30:00+05:30', 100, 110, 99, 105, volume])
                finally:
                    sink.discard()
                self.assertFalse((root / 'monthly').exists())

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
            self.assertEqual(data["scheduler"]["cadence"], "BOUNDED_CONTINUOUS_DAILY")
            self.assertEqual(data["scheduler"]["weekdayHoursIST"], "00:00–23:58")
            self.assertEqual(data["recentRequests"][0]["fromDate"], "2024-01-01")
            serialized = json.dumps(data)
            self.assertNotIn("2885", serialized)
            self.assertNotIn("12345", serialized)

    def test_owner_chart_export_uses_sha_checked_archive_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "archive"
            root.mkdir()
            fixture(root)
            output = Path(tmp) / "monitor"
            output.mkdir()
            now = dt.datetime(2024, 1, 4, 12, 0, tzinfo=dt.timezone.utc)
            sink = progress._OwnerChartSink(output, now)
            try:
                with patch.object(progress, "_private_archive", return_value=None):
                    report = progress.build_snapshot(root, now, "IDLE", {},
                                                      chart_sink=sink)
                sink.complete()
            finally:
                sink.discard()
            chart = json.loads((output / "charts" / "2885.json").read_text())
            self.assertEqual(report["task"]["candles"], 2)
            self.assertEqual(chart["schema"], "minttick.owner.chart.v1")
            self.assertEqual(chart["symbol"], "RELIANCE-EQ")
            self.assertEqual(chart["interval"], "ONE_DAY")
            self.assertEqual(len(chart["rows"]), 2)
            self.assertEqual(chart["rows"][1][4], 108)
            self.assertNotIn("password", json.dumps(chart).lower())

    def test_owner_monthly_aggregation_uses_verified_daily_ohlcv_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "archive"
            root.mkdir()
            fixture(root)
            output = Path(tmp) / "monitor"
            output.mkdir()
            now = dt.datetime(2024, 1, 4, 12, 0, tzinfo=dt.timezone.utc)
            month = progress._OwnerMonthlySink(output, now)
            try:
                with patch.object(progress, "_private_archive", return_value=None):
                    result = progress.build_snapshot(root, now, "IDLE", {},
                                                      monthly_sink=month)
                month.complete()
            finally:
                month.discard()
            report = json.loads((output / "monthly" / "2885.json").read_text())
            self.assertEqual(result["task"]["candles"], 2)
            self.assertEqual(report["schema"], "minttick.owner.chart.monthly.v1")
            self.assertEqual(report["interval"], "ONE_MONTH")
            self.assertEqual(report["sourceInterval"], "ONE_DAY")
            self.assertEqual(report["sessionCompleteness"], "UNVERIFIED")
            self.assertEqual(len(report["rows"]), 1)
            self.assertEqual(report["rows"][0][1:], [100, 111, 99, 108, 22221])

    def test_monthly_source_tamper_never_produces_a_monthly_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "archive"
            root.mkdir()
            fixture(root, tamper=True)
            output = Path(tmp) / "monitor"
            output.mkdir()
            sink = progress._OwnerMonthlySink(output,
                dt.datetime(2024, 1, 4, 12, 0, tzinfo=dt.timezone.utc))
            try:
                with patch.object(progress, "_private_archive", return_value=None):
                    with self.assertRaises(progress.ProgressUnavailable):
                        progress.build_snapshot(root,
                            dt.datetime(2024, 1, 4, 12, 0, tzinfo=dt.timezone.utc),
                            "IDLE", {}, monthly_sink=sink)
            finally:
                sink.discard()
            self.assertFalse((output / "monthly").exists())

    def test_tamper_never_promotes_owner_chart_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "archive"
            root.mkdir()
            fixture(root, tamper=True)
            output = Path(tmp) / "monitor"
            output.mkdir()
            sink = progress._OwnerChartSink(output,
                dt.datetime(2024, 1, 4, 12, 0, tzinfo=dt.timezone.utc))
            try:
                with patch.object(progress, "_private_archive", return_value=None):
                    with self.assertRaises(progress.ProgressUnavailable):
                        progress.build_snapshot(root,
                            dt.datetime(2024, 1, 4, 12, 0, tzinfo=dt.timezone.utc),
                            "IDLE", {}, chart_sink=sink)
            finally:
                sink.discard()
            self.assertFalse((output / "charts").exists())

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
