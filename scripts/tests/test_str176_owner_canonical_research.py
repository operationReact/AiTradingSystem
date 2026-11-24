"""No-network test of historical source reading, model assumptions and no-lookahead."""
from __future__ import annotations
from datetime import date,datetime,timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import os
import sqlite3
import tempfile
import unittest

HERE=Path(__file__).resolve().parents[2]
P=HERE/"infra/ec2-paper/str176_owner_canonical_research.py"
S=HERE/"infra/ec2-paper/fno_private_canonical_store.py"
spec=importlib.util.spec_from_file_location("str176_personal",P)
replay=importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)
source_spec=importlib.util.spec_from_file_location("private_store",S)
source=importlib.util.module_from_spec(source_spec)
source_spec.loader.exec_module(source)
from test_fno_private_canonical_store import make_rows,NOW

def sample(day,price,volume=40000,oi=130000):
    close=Decimal(str(price))
    return (day,close-Decimal("1"),close+Decimal(".5"),
            close-Decimal("1.5"),close,volume,oi,"NSE_PUBLIC_EOD")

def weekdays(end,count):
    d=end
    selected=[]
    while len(selected)<count:
        if d.weekday()<5:selected.append(d)
        d-=timedelta(days=1)
    return list(reversed(selected))

class OwnerRetrospectiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        os.chmod(self.root,0o700)

    def valid_source_cache(self):
        original=make_rows()
        lengths={}
        for (key,day) in original:lengths[key]=lengths.get(key,0)+1
        updated={}
        for key,length in lengths.items():
            expiry=datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
            source_name=("NSE_PUBLIC_EOD" if expiry.year==2025 and expiry.month==12
                         else "UPSTOX_PRIVATE")
            for day in weekdays(expiry,length):
                updated[(key,day)]=(day.isoformat()+"T00:00:00+05:30",
                                   (Decimal("100"),Decimal("106"),Decimal("99"),
                                    Decimal("103"),Decimal(7500),Decimal(200)),
                                   source_name)
        self.assertEqual(3929,len(updated))
        owner=sqlite3.connect(":memory:")
        try:
            owner.execute("CREATE TABLE fno_history_receipts(committed_at REAL)")
            owner.executemany("INSERT INTO fno_history_receipts VALUES(?)",
                              [(NOW-3600,)]*276)
            source.store(updated,source.EXPECTED,owner,root=self.root,now=NOW)
        finally:
            owner.close()
        keys={}
        for key in lengths:
            expiry=datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
            keys.setdefault(expiry,[]).append(key)
        return {expiry:sorted(values)[0] for expiry,values in keys.items()}

    def future_metadata(self,keys):
        def fetch(expiry):
            return [{"segment":"NSE_FO","exchange":"NSE",
                     "instrument_type":"FUT",
                     "underlying_key":"NSE_INDEX|Nifty 50",
                     "expiry":expiry.isoformat(),
                     "instrument_key":keys[expiry],
                     "lot_size":75}]
        return fetch

    def test_valid_owner_store_3929_real_format_rows_not_promoted_to_e2e(self):
        keys=self.valid_source_cache()
        result=replay.safe_receipt(replay.run_research(
            root=self.root,now=NOW,fetch=self.future_metadata(keys)))
        self.assertEqual(3929,result["sourceRows"])
        self.assertEqual(12,result["providerGets"])
        self.assertEqual(12,result["sourceFuturesContracts"])
        self.assertGreater(result["sourceFuturesRows"],100)
        self.assertGreater(result["decisionWindows"],0)
        self.assertEqual(0,result["candidateWindows"])
        self.assertEqual(0,result["simulatedRoundTrips"])
        self.assertEqual(result["decisionWindows"],
                         sum(result["diagnosticReasons"].values()))
        self.assertTrue(set(result["diagnosticReasons"])<=replay.REASONS)
        self.assertEqual("RESEARCH_SIMULATED_AFTER_COST_NOT_CERTIFIED",result["state"])
        self.assertFalse(result["historicalStrategyE2ECertified"])
        self.assertFalse(result["providerFirstPublishedAtVerified"])
        self.assertFalse(result["orders"])
        self.assertFalse(result["writes"])

    def test_expired_cache_refused_even_if_reused_for_43_strategy_percentage(self):
        keys=self.valid_source_cache()
        with self.assertRaisesRegex(ValueError,"PRIVATE_STORE_EXPIRED_OR_UNVERIFIED"):
            replay.run_research(root=self.root,now=NOW+8*86400,
                                fetch=self.future_metadata(keys))

    def test_one_day_future_prices_never_enter_a_prior_momentum_decision(self):
        expiry=date(2026,12,24)
        dates=weekdays(date(2026,12,15),9)
        prices=[100,101,102,103,104,105,111,113,109]
        rows=[sample(d,p,50000 if i>=4 else 30000,130000)
              for i,(d,p) in enumerate(zip(dates,prices))]
        a=replay.momentum(rows[:7],expiry,dates[7])
        self.assertEqual("LONG",a)
        self.assertEqual(("LONG","UPWARD_BREAKOUT"),
            replay.momentum(rows[:7],expiry,dates[7],details=True))
        changed=list(rows)
        changed[7]=sample(dates[7],Decimal("500"),50000,130000)
        changed[8]=sample(dates[8],Decimal("5"),50000,130000)
        self.assertEqual(a,replay.momentum(changed[:7],expiry,dates[7]))
        self.assertEqual("BLOCKED",replay.momentum(rows[:7],
            expiry,date(2026,12,20)))
        self.assertEqual(("BLOCKED","EXPIRY_OUT_OF_SCOPE"),
            replay.momentum(rows[:7],expiry,date(2026,12,20),details=True))

    def test_simulated_next_open_and_costs_are_actual_accounting_not_fills(self):
        metadata={}
        groups={}
        for n in range(12):
            year=2025+(11+n)//12
            month=(11+n)%12+1
            expiry=date(year,month,26)
            key=f"NSE_FO|FUT{n:04d}|{expiry.strftime('%d-%m-%Y')}"
            days=weekdays(expiry,15)
            close=[100,101,102,103,104,105,111,113,115,118,120,122,124,126,128]
            groups[key]=[
                sample(d,p,50000 if i>=4 else 30000,130000)
                for i,(d,p) in enumerate(zip(days,close))]
            metadata[expiry]=(key,75)
        out=replay.evaluate(groups,metadata)
        self.assertEqual("RESEARCH_SIMULATED_AFTER_COST_NOT_CERTIFIED",out["state"])
        self.assertGreater(out["counts"]["decisionWindows"],0)
        self.assertGreater(out["counts"]["candidateWindows"],0)
        self.assertGreater(out["counts"]["simulatedRoundTrips"],0)
        self.assertGreater(out["counts"]["trainingTrades"],0)
        self.assertGreater(out["counts"]["outOfSampleTrades"],0)
        self.assertEqual(out["counts"]["decisionWindows"],
            sum(v for k,v in out["counts"].items() if k.startswith("reason:")))
        self.assertIsInstance(out["oosNetPaise"],int)
        self.assertGreaterEqual(out["oosMaxDrawdownPaise"],0)

    def test_precise_validation_codes_do_not_change_signal_thresholds(self):
        expiry=date(2026,12,24)
        dates=weekdays(date(2026,12,15),9)
        prices=[100,101,102,103,104,105,111,113,109]
        rows=[sample(d,p,50000 if i>=4 else 30000,130000)
              for i,(d,p) in enumerate(zip(dates,prices))]
        signal=replay.momentum(rows[:7],expiry,dates[7])
        detailed=replay.momentum(rows[:7],expiry,dates[7],details=True)
        self.assertEqual(signal,detailed[0])
        self.assertEqual(("LONG","UPWARD_BREAKOUT"),detailed)
        reasons=[
            ([], "INVALID_WINDOW_SESSIONS"),
            ([(r[0],*r[1:6],0,r[7]) for r in rows[:7]],
             "MISSING_VOLUME_OR_OI"),
            (rows[:7], "EXPIRY_OUT_OF_SCOPE")
        ]
        for window,code in reasons:
            as_of=dates[7] if code!="EXPIRY_OUT_OF_SCOPE" else date(2026,12,20)
            self.assertEqual(("BLOCKED",code),
                             replay.momentum(window,expiry,as_of,details=True))
        cold=[sample(d,100,40000,100000) for d in dates[:7]]
        self.assertEqual(("NO_SIGNAL","VOLUME_OR_OI_PARTICIPATION_LOW"),
            replay.momentum(cold,expiry,dates[7],details=True))

    def test_private_receipt_rejects_invalid_diagnostic_totals_or_codes(self):
        keys=self.valid_source_cache()
        receipt=replay.run_research(
            root=self.root,now=NOW,fetch=self.future_metadata(keys))
        replay.safe_receipt(receipt)
        forged=dict(receipt)
        forged["diagnosticReasons"]={"EXPIRY_OUT_OF_SCOPE": 75}
        with self.assertRaisesRegex(ValueError,"STR176_PRIVATE_RECEIPT_INVALID"):
            replay.safe_receipt(forged)
        forged=dict(receipt)
        forged["diagnosticReasons"]={"UNVERIFIED_UNSAFE_REASON":
            receipt["decisionWindows"]}
        with self.assertRaisesRegex(ValueError,"STR176_PRIVATE_RECEIPT_INVALID"):
            replay.safe_receipt(forged)

    def test_fake_identity_and_false_certification_receipt_are_blocked(self):
        self.assertEqual("BLOCKED",replay.momentum([],date(2026,12,24),
                          date(2026,12,14)))
        with self.assertRaisesRegex(ValueError,"ORIGINAL_FUTURES_METADATA_MISSING"):
            replay.metadata_futures([date(2026,12,24)],lambda x: [])
        with self.assertRaisesRegex(ValueError,"STR176_PRIVATE_RECEIPT_INVALID"):
            replay.safe_receipt({"state":"RESEARCH_SIMULATED_AFTER_COST_NOT_CERTIFIED",
                                 "certifiedStrategies":1})
        with self.assertRaisesRegex(ValueError,"PRIVATE_STORE_UNAVAILABLE"):
            replay.read_eligible(self.root,now=NOW)

    def test_private_storage_is_read_only_and_no_source_prices_leave_host(self):
        script=P.read_text()
        for mandatory in ("?mode=ro","PRAGMA query_only=ON",
                          "sourceFuturesContracts","providerFirstPublishedAtVerified",
                          "COST_BPS=30","historicalStrategyE2ECertified",
                          "OWNER_END", "SOURCE_VERSION"):
            self.assertIn(mandatory,script)
        for forbidden in ("placeOrder","submitOrder",
                          "INSERT INTO","UPDATE fno_", "put-parameter"):
            self.assertNotIn(forbidden,script)

if __name__=="__main__":
    unittest.main()
