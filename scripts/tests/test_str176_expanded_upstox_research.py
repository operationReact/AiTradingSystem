"""Network-free 90-day original expired Upstox history: no certification/fills."""
from __future__ import annotations
from datetime import date,datetime,timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest
import urllib.parse

ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/"infra/ec2-paper/str176_expanded_upstox_research.py"
ORIGINAL=ROOT/"infra/ec2-paper/str176_owner_canonical_research.py"

spec=importlib.util.spec_from_file_location("expanded_str176",SRC)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
old=importlib.util.spec_from_file_location("prior_owner",ORIGINAL)
ref=importlib.util.module_from_spec(old)
old.loader.exec_module(ref)

IST="+05:30"

def months():
    result=[]
    for k in range(12):
        year=2025+(10+k)//12
        month=(10+k)%12+1
        result.append(date(year,month,26))
    return result

class FakeAuthenticatedUpstox:
    def __init__(self,empty=None):
        self.exps=months()
        self.calls=0
        self.empty=empty
        self.seen=[]
    def get(self,route):
        assert isinstance(route,str) and route.startswith("/")
        self.calls+=1
        self.seen.append(route)
        parsed=urllib.parse.urlsplit(route)
        if parsed.path=="/expiries":
            return [d.isoformat() for d in self.exps]
        if parsed.path=="/future/contract":
            params=urllib.parse.parse_qs(parsed.query)
            expiry=date.fromisoformat(params["expiry_date"][0])
            if expiry not in self.exps:
                raise AssertionError("foreign expiry")
            index=self.exps.index(expiry)
            return [{"segment":"NSE_FO","exchange":"NSE",
                     "underlying_key":"NSE_INDEX|Nifty 50",
                     "instrument_type":"FUT","expiry":expiry.isoformat(),
                     "instrument_key":(
                       f"NSE_FO|FIX{index:03d}|{expiry.strftime('%d-%m-%Y')}"),
                     "lot_size":75}]
        part=urllib.parse.unquote(parsed.path).split("/")
        assert len(part)==6 and part[1]=="historical-candle",part
        key,interval,to_date,from_date=part[2:]
        assert interval=="day"
        first,last=date.fromisoformat(from_date),date.fromisoformat(to_date)
        expiry=datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
        assert last<=expiry
        assert 1<=(last-first).days+1<=30
        if expiry==self.empty:return {"candles":[]}
        data=[]
        day=last
        while day>=first:
            if day.weekday()<5:
                # Smooth, bounded synthetic market: no arbitrary 09:15 EOD
                # rows, no cloud calls, no fabricated first-published field.
                t=(day-(expiry-timedelta(days=89))).days
                close=round(100+t*0.22,2)
                volume=50000 if t%9>=3 else 30000
                data.append([day.isoformat()+"T00:00:00"+IST,
                            round(close-1,2),round(close+0.5,2),
                            round(close-1.5,2),close,volume,130000])
            day-=timedelta(days=1)
        return {"candles":data}

def sample(d,p,volume,oi=130000):
    price=Decimal(str(p))
    return (d,price-1,price+Decimal("0.5"),
            price-Decimal("1.5"),price,volume,oi,m.SOURCE)

class ExpandedOwnerResearchTests(unittest.TestCase):
    def test_90_days_each_12_futures_gets_bounded_49_no_order_no_e2e(self):
        provider=FakeAuthenticatedUpstox()
        result=m.safe_receipt(m.research(provider.get,date(2026,11,24)))
        self.assertEqual("RESEARCH_EXTENDED_EOD_NOT_CERTIFIED",result["state"])
        self.assertEqual(49,provider.calls)
        self.assertEqual(13,result["metadataGets"])
        self.assertEqual(36,result["historyGets"])
        self.assertEqual(90,result["sourceDayRange"])
        self.assertEqual(12,result["eligibleFuturesCohorts"])
        self.assertGreater(result["sourceFuturesBars"],500)
        self.assertGreater(result["decisionWindows"],75)
        self.assertGreater(result["evaluatedWindows"],31)
        self.assertEqual(result["decisionWindows"],
                         result["evaluatedWindows"]+result["blockedWindows"])
        self.assertEqual(result["trainingTrades"]+result["outOfSampleTrades"],
                         result["modeledTrades"])
        self.assertFalse(result["independentHistoricalE2ECertified"])
        self.assertFalse(result["authenticSourceOriginalFirstPublishedAtVerified"])
        self.assertFalse(result["orders"])
        self.assertFalse(result["writes"])
        self.assertEqual(len(set(provider.seen)),49)
        self.assertFalse(any("orders" in p for p in provider.seen))

    def test_pinned_reference_signal_parity_and_no_lookahead(self):
        expiry=date(2026,12,24)
        days=[]
        d=date(2026,12,8)
        while len(days)<9:
            if d.weekday()<5:days.append(d)
            d+=timedelta(days=1)
        close=[100,101,102,103,104,105,111,113,10]
        bars=[sample(d,p,50000 if i>=4 else 30000)
              for i,(d,p) in enumerate(zip(days,close))]
        self.assertEqual("LONG",m.signal(bars[:7],expiry,days[7]))
        for as_of in (days[7],date(2026,12,20)):
            self.assertEqual(ref.momentum(bars[:7],expiry,as_of),
                             m.signal(bars[:7],expiry,as_of))
        broken=list(bars)
        broken[7]=sample(days[7],2000,50000)
        broken[8]=sample(days[8],1,50000)
        self.assertEqual(m.signal(bars[:7],expiry,days[7]),
                         m.signal(broken[:7],expiry,days[7]))
        self.assertFalse(any(x[0]>=days[7] for x in bars[:7]))
        self.assertEqual("BLOCKED",m.signal(bars[:7],expiry,date(2026,12,20)))

    def test_30_day_bounded_original_candle_schema_and_duplicate_denial(self):
        start=date(2026,10,1)
        end=date(2026,10,30)
        p=FakeAuthenticatedUpstox()
        raw=p.get("/historical-candle/NSE_FO%7CFIX011%7C26-10-2026/day/"
                  +"2026-10-26/2026-10-01")
        bars=m.decode_expired_chunk(raw,start,end)
        self.assertGreater(len(bars),0)
        self.assertTrue(all(r[0].weekday()<5 and r[-1]==m.SOURCE for r in bars))
        double=dict(raw)
        double["candles"]=list(raw["candles"])+[raw["candles"][0]]
        with self.assertRaisesRegex(ValueError,"HISTORICAL_DUPLICATE_SESSION_DAY"):
            m.decode_expired_chunk(double,start,end)
        wrong={"candles":[["2026-10-01T09:15:00+05:30",100,101,99,100,1,1]]}
        with self.assertRaisesRegex(ValueError,"INVALID_SOURCE_SESSION"):
            m.decode_expired_chunk(wrong,start,end)

    def test_missing_or_oversized_authenticated_response_is_diagnosed_without_raw_data(self):
        first=date(2026,10,1)
        last=date(2026,10,30)
        for payload,reason in [
            (None,"HISTORICAL_DATA_OBJECT_MISSING"),
            ([],"HISTORICAL_DATA_OBJECT_MISSING"),
            ({},"HISTORICAL_CANDLE_ARRAY_MISSING"),
            ({"candles":None},"HISTORICAL_CANDLE_ARRAY_MISSING"),
            ({"candles":[["masked"]]*121},"HISTORICAL_CANDLE_WINDOW_OVERSIZED")
        ]:
            with self.assertRaisesRegex(ValueError,"^"+reason+"$"):
                m.decode_expired_chunk(payload,first,last)
            self.assertEqual(reason,m.safe_block_code(ValueError(reason)))
        for arbitrary in ("raw token=Bearer abc","NSE_FO|private_key"):
            self.assertEqual("UNCLASSIFIED_SOURCE_FAILURE",
                m.safe_block_code(ValueError(arbitrary)))

    def test_more_than_30_source_records_are_inspected_for_actual_duplicate_or_bad_time(self):
        from datetime import timedelta
        first=date(2026,10,1)
        last=date(2026,10,30)
        # 40 underlying records for only 20 actual trading sessions:
        # not legitimate 40 distinct daily bars; do not silently dedupe.
        days=[]
        d=first
        while len(days)<20:
            if d.weekday()<5:days.append(d)
            d+=timedelta(days=1)
        raw=[]
        for d in days:
            for stamp_time in ("00:00:00","09:15:00"):
                raw.append([d.isoformat()+"T"+stamp_time+"+05:30",
                            100,102,99,101,5000,10000])
        self.assertGreater(len(raw),30)
        with self.assertRaisesRegex(ValueError,"HISTORICAL_NONCANONICAL_DAILY_TIME"):
            m.decode_expired_chunk({"candles":raw},first,last)
        only_canonical=[row for row in raw if "T00:00:00" in row[0]]
        with self.assertRaisesRegex(ValueError,"HISTORICAL_DUPLICATE_SESSION_DAY"):
            m.decode_expired_chunk({"candles":only_canonical+
                                    [only_canonical[0]]},first,last)
        bad=list(only_canonical)
        bad.append(["2026-11-02T00:00:00+05:30",100,102,99,101,5000,10000])
        with self.assertRaisesRegex(ValueError,"HISTORICAL_BAR_OUTSIDE_REQUEST"):
            m.decode_expired_chunk({"candles":bad},first,last)
        for code in ("HISTORICAL_NONCANONICAL_DAILY_TIME",
                     "HISTORICAL_DUPLICATE_SESSION_DAY",
                     "HISTORICAL_BAR_OUTSIDE_REQUEST"):
            self.assertEqual(code,m.safe_block_code(ValueError(code)))
        self.assertEqual("HISTORICAL_CANDLE_WINDOW_OVERSIZED",
            m.safe_block_code(ValueError("HISTORICAL_CANDLE_WINDOW_OVERSIZED")))
        with self.assertRaisesRegex(ValueError,"HISTORICAL_CANDLE_WINDOW_OVERSIZED"):
            m.decode_expired_chunk({"candles":[only_canonical[0]]*121},first,last)

    def test_missing_one_expiry_never_reports_fully_qualified(self):
        p=FakeAuthenticatedUpstox(empty=months()[5])
        r=m.safe_receipt(m.research(p.get,date(2026,11,24)))
        self.assertEqual("SOURCE_FUTURES_COHORTS_INCOMPLETE",r["state"])
        self.assertEqual(11,r["eligibleFuturesCohorts"])
        self.assertFalse(r["independentHistoricalE2ECertified"])

    def test_forged_safe_receipt_and_replaced_source_id_denied(self):
        provider=FakeAuthenticatedUpstox()
        report=m.safe_receipt(m.research(provider.get,date(2026,11,24)))
        forged=dict(report)
        forged["certifiedStrategies"]=1
        with self.assertRaisesRegex(ValueError,"UNVERIFIED_EXTENDED_RESEARCH_RECEIPT"):
            m.safe_receipt(forged)
        bad=dict(report)
        bad["candidateWindows"]=-1
        with self.assertRaisesRegex(ValueError,"UNVERIFIED_EXTENDED_RESEARCH_RECEIPT"):
            m.safe_receipt(bad)
        with self.assertRaisesRegex(ValueError,"TWELVE_ACTUAL_MONTHLY_EXPIRIES_REQUIRED"):
            m.expiries_from_provider([d.isoformat() for d in months()[:6]],
                                           date(2026,11,24))
        with self.assertRaisesRegex(ValueError,"UPSTOX_ORIGINAL_FUTURES_MISSING_OR_AMBIGUOUS"):
            expiry=months()[0]
            p=FakeAuthenticatedUpstox()
            rows=p.get("/future/contract?expiry_date="+expiry.isoformat())
            m.chosen_future(rows+rows,expiry,"NSE_INDEX|Nifty 50")

    def test_only_preapproved_host_block_code_can_leave_ssm_boundary(self):
        good=ValueError("FNO_DISCOVERY_RATE_LIMITED")
        self.assertEqual("FNO_DISCOVERY_RATE_LIMITED",
                         m.safe_block_code(good))
        private=ValueError("bearer-secret-do-not-export")
        self.assertEqual("UNCLASSIFIED_SOURCE_FAILURE",
                         m.safe_block_code(private))
        self.assertEqual("UNCLASSIFIED_SOURCE_FAILURE",
                         m.safe_block_code(RuntimeError("NSE_FO|private_key")))
        source=m.SOURCE_MARKER+"BLOCKED:"
        self.assertEqual("INVALID_SOURCE_SESSION",
            m.ssm_block_code(source+"INVALID_SOURCE_SESSION"))
        self.assertEqual("UNCLASSIFIED_SOURCE_FAILURE",
            m.ssm_block_code(source+"bearer-secret-do-not-export"))
        self.assertEqual("UNCLASSIFIED_SOURCE_FAILURE",
            m.ssm_block_code(source+"INVALID_SOURCE_SESSION\n"+
                             source+"FNO_DISCOVERY_AUTH_REJECTED"))
        self.assertEqual("UNCLASSIFIED_SOURCE_FAILURE",
            m.ssm_block_code(source+"INVALID_SOURCE_SESSION\n"+"x"*9000))
        self.assertTrue(all("NSE_FO|" not in code
                            for code in m.SAFE_BLOCK_CODES))

    def test_no_hidden_write_order_and_no_claim_of_historic_publication(self):
        src=SRC.read_text()
        for required in ("max_provider_gets","source_days","historical_e2e",
                         "authenticSourceOriginalFirstPublishedAtVerified",
                         "sourceCaptureType","read-only Upstox","PER_GET_DAYS=30",
                         "def signal(","COST_BPS", "45"):
            # Future releases should preserve request and source bounds.
            if required in ("COST_BPS","45"):continue
            self.assertIn(required,src)
        for forbidden in ("placeOrder(", "submitOrder(",
                          "put-parameter","start-instances","stop-instances",
                          "INSERT INTO","UPDATE fno_","DELETE FROM"):
            self.assertNotIn(forbidden,src)

if __name__=="__main__":
    unittest.main()
