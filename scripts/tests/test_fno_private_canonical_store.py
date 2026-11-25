#!/usr/bin/env python3
"""Synthetic-only validation of canonical owner cache, access and expiration."""
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo
from decimal import Decimal
from pathlib import Path
import importlib.util
import os
import sqlite3
import stat
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[2] / "infra/ec2-paper/fno_private_canonical_store.py"
spec = importlib.util.spec_from_file_location("private_canonical", MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

NOW = datetime(2026,11,21,12,tzinfo=timezone.utc).timestamp()
VALUES = (Decimal("100"), Decimal("106"), Decimal("99"), Decimal("103"),
          Decimal("7500"), Decimal("200"))

def make_rows():
    data={}
    non_dec=[(2025,11)]+[(2026,month) for month in range(1,11)]
    for n in range(253):
        year,month=non_dec[n//23]
        expiry=date(year,month,26)
        key=f"NSE_FO|TEST{n:04d}|{expiry.strftime('%d-%m-%Y')}"
        for day_offset in range(15 if n<66 else 14):
            day=expiry-timedelta(days=20-day_offset)
            data[(key,day)]=(day.isoformat()+"T00:00:00+05:30",
                             VALUES,"UPSTOX_PRIVATE")
    for n in range(23):
        expiry=date(2025,12,30)
        key=f"NSE_FO|DEC{n:04d}|30-12-2025"
        for day_offset in range(14):
            if n==22 and day_offset==13:
                continue
            day=expiry-timedelta(days=20-day_offset)
            data[(key,day)]=(day.isoformat()+"T00:00:00+05:30",
                             VALUES,"NSE_PUBLIC_EOD")
    assert len(data)==3929
    return data

class FnoPrivateCanonicalStoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.entries=make_rows()

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        os.chmod(self.root,0o700)
        self.source=sqlite3.connect(":memory:")
        self.addCleanup(self.source.close)
        self.source.execute("CREATE TABLE fno_history_receipts(committed_at REAL)")
        self.source.executemany("INSERT INTO fno_history_receipts VALUES (?)",
                                [(NOW-3600,)]*276)

    def test_persist_and_read_preserve_source_and_3929_unique_days(self):
        result=m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        self.assertEqual(result["total"],3929)
        self.assertEqual(result["nse"],321)
        self.assertFalse(result["backtestingApproved"])
        self.assertEqual(result["expiresEpoch"],int(NOW-3600+m.LIFETIME))
        target=self.root/m.TARGET.name
        self.assertTrue(target.is_file())
        self.assertEqual(stat.S_IMODE(target.stat().st_mode),0o600)
        db=sqlite3.connect(target)
        try:
            self.assertEqual(db.execute("PRAGMA quick_check").fetchone(),("ok",))
            self.assertEqual(db.execute("SELECT COUNT(*) FROM daily").fetchone()[0],3929)
            self.assertEqual(dict(db.execute("SELECT source,COUNT(*) FROM daily GROUP BY source")),
                             {"NSE_PUBLIC_EOD":321,"UPSTOX_PRIVATE":3608})
        finally:db.close()
        key=next(iter(self.entries))[0]
        got=m.read_canonical(root=self.root,now=NOW,key=key,limit=20,
                             as_of=datetime.fromtimestamp(NOW,timezone.utc))
        self.assertGreater(len(got),0)
        self.assertTrue(all(row[-1]=="UPSTOX_PRIVATE" for row in got))

    def test_copy_does_not_extend_original_seven_day_expiry(self):
        m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        first=NOW-3600+m.LIFETIME
        result=m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW+300)
        self.assertEqual(result["expiresEpoch"],int(first))
        with self.assertRaisesRegex(ValueError,"CANONICAL_READ_EXPIRED_OR_INVALID"):
            m.read_canonical(root=self.root,now=first,key=next(iter(self.entries))[0],
                             as_of=datetime.fromtimestamp(first,timezone.utc))
        self.assertEqual(m.purge(root=self.root,now=first),"CANONICAL_EXPIRED_REMOVED")
        self.assertFalse((self.root/m.TARGET.name).exists())

    def test_reject_missing_or_expired_receipts(self):
        self.source.execute("DELETE FROM fno_history_receipts")
        with self.assertRaisesRegex(ValueError,"CANONICAL_SOURCE_RECEIPTS_CHANGED"):
            m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        self.source.executemany("INSERT INTO fno_history_receipts VALUES (?)",
                                [(NOW-m.LIFETIME-60,)]*276)
        with self.assertRaisesRegex(ValueError,"CANONICAL_SOURCE_EXPIRED"):
            m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        self.assertFalse((self.root/m.TARGET.name).exists())

    def test_reject_synthetic_wrong_provenance_or_inconsistent_ohlc(self):
        data=dict(self.entries)
        key,day=next(iter(data))
        ts,vals,src=data[(key,day)]
        data[(key,day)]=(ts,vals,"NSE_PUBLIC_EOD")
        with self.assertRaisesRegex(ValueError,"CANONICAL_SOURCE_CLASSIFICATION_WRONG"):
            m.store(data,m.EXPECTED,self.source,root=self.root,now=NOW)
        data[(key,day)]=(ts,(Decimal(105),Decimal(104),Decimal(99),Decimal(103),*vals[4:]),src)
        with self.assertRaisesRegex(ValueError,"CANONICAL_OHLC_INCONSISTENT"):
            m.store(data,m.EXPECTED,self.source,root=self.root,now=NOW)
        self.assertFalse((self.root/m.TARGET.name).exists())

    def test_reject_symlink_and_unapproved_query(self):
        p=self.root/m.TARGET.name
        p.symlink_to(self.root/"outside")
        with self.assertRaisesRegex(ValueError,"CANONICAL_SYMLINK_REFUSED"):
            m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        p.unlink()
        m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        with self.assertRaisesRegex(ValueError,"CANONICAL_INVALID_QUERY"):
            m.read_canonical(root=self.root,now=NOW,key="bad key",limit=100,
                             as_of=datetime.fromtimestamp(NOW,timezone.utc))
        with self.assertRaisesRegex(ValueError,"CANONICAL_INVALID_QUERY"):
            m.read_canonical(root=self.root,now=NOW,key=next(iter(self.entries))[0],limit=100000,
                             as_of=datetime.fromtimestamp(NOW,timezone.utc))

    def test_daily_midnight_label_never_becomes_same_session_future_ohlc(self):
        m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        key,day=next(iter(self.entries))
        # Even at 15:45 IST that day, the daily EOD row cannot be treated as
        # a midnight-observed value by a strategy replay.
        decision=datetime.combine(day,time(15,45),tzinfo=ZoneInfo("Asia/Kolkata"))
        rows=m.read_canonical(root=self.root,now=NOW,key=key,
                              as_of=decision)
        self.assertTrue(all(item[0] < day.isoformat() for item in rows))
        # At the next Indian local session date, it is a PROVISIONAL
        # historical research input only, not a verified source publish time.
        later=datetime.combine(day+timedelta(days=1),time(9,20),
                               tzinfo=ZoneInfo("Asia/Kolkata"))
        available=m.read_canonical(root=self.root,now=NOW,key=key,as_of=later)
        self.assertIn(day.isoformat(),[item[0] for item in available])
        self.assertNotEqual(available[0][1][:10],later.date().isoformat())

    def test_asof_is_explicit_timezone_aware_and_not_in_future(self):
        m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        key=next(iter(self.entries))[0]
        with self.assertRaisesRegex(ValueError,"CANONICAL_ASOF_REQUIRED"):
            m.read_canonical(root=self.root,now=NOW,key=key)
        with self.assertRaisesRegex(ValueError,"CANONICAL_ASOF_REQUIRED"):
            m.read_canonical(root=self.root,now=NOW,key=key,
                             as_of=datetime(2025,11,20,10))
        with self.assertRaisesRegex(ValueError,"CANONICAL_ASOF_IN_FUTURE"):
            m.read_canonical(root=self.root,now=NOW,key=key,
                             as_of=datetime.fromtimestamp(NOW+3600,timezone.utc))
        # Compare as-of in UTC after Indian midnight: it must respect the
        # Indian session calendar instead of using the server's UTC date.
        key,day=next(iter(self.entries))
        midnight_ist=datetime.combine(day+timedelta(days=1),time(0,10),
                                      tzinfo=ZoneInfo("Asia/Kolkata"))
        got=m.read_canonical(root=self.root,now=NOW,key=key,
                             as_of=midnight_ist.astimezone(timezone.utc))
        self.assertIn(day.isoformat(),[item[0] for item in got])

    def test_hard_stop_by_end_of_owner_scope(self):
        with self.assertRaisesRegex(ValueError,"CANONICAL_OWNER_SCOPE_EXPIRED"):
            m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=m.END_IST)
        m.store(self.entries,m.EXPECTED,self.source,root=self.root,now=NOW)
        with self.assertRaisesRegex(ValueError,"CANONICAL_NOT_AVAILABLE"):
            m.read_canonical(root=self.root,now=m.END_IST,key=next(iter(self.entries))[0],
                             as_of=datetime.fromtimestamp(m.END_IST,timezone.utc))
        self.assertEqual(m.purge(root=self.root,now=m.END_IST),"CANONICAL_EXPIRED_REMOVED")

if __name__=="__main__":unittest.main()
