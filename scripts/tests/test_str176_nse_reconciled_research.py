"""Offline contracts only; synthetic archives never certify a strategy."""
from copy import deepcopy
from datetime import date
from decimal import Decimal
import base64
import csv
import gzip
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
import urllib.parse
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"infra/ec2-paper"))
import str176_nse_reconciled_research as m
spec=importlib.util.spec_from_file_location("expanded_fixture",ROOT/"scripts/tests/test_str176_expanded_upstox_research.py")
fixture=importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

class Source(fixture.FakeAuthenticatedUpstox):
    def __init__(self):
        super().__init__()
        self.exps[1]=m.TARGET
        self.original={}
    def get(self,route):
        result=super().get(route)
        if "/historical-candle/" in route and "30-12-2025" in route:
            rows=[]
            for r in result["candles"]:
                row=list(r)
                row[5]=52500 if row[5]==50000 else 30000
                self.original[date.fromisoformat(row[0][:10])]=list(row)
                rows.extend([row,[row[0].replace("T00:00:00","T09:15:00"),*row[1:]]])
            result={"candles":rows}
        return result

FIELDS=["BizDt","TckrSymb","FinInstrmTp","XpryDt","StrkPric","OptnTp",
        "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst","NewBrdLotQty"]
def archive(day,raw,change=None,duplicate=False):
    row=dict(zip(FIELDS,[day.isoformat(),"NIFTY","IDF",m.TARGET.isoformat(),"0","",
                        *raw[1:5],raw[5]//75,raw[6],75]))
    row.update(change or {})
    text=io.StringIO(newline="")
    writer=csv.DictWriter(text,FIELDS)
    writer.writeheader()
    writer.writerow(row)
    if duplicate:writer.writerow(row)
    output=io.BytesIO()
    with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo("BhavCopy_NSE_FO_0_0_0_"+day.strftime("%Y%m%d")+"_F_0000.csv",date_time=(2026,1,1,0,0,0)),text.getvalue())
    return output.getvalue()

class ReconciledResearchTests(unittest.TestCase):
    def run_source(self,change=None):
        source=Source()
        calls=[]
        def fetch(day):
            calls.append(day)
            return archive(day,source.original[day],change)
        result=m.research(source.get,date(2026,11,24),fetch)
        return source,calls,result

    def test_original_quarantine_and_independent_cohort_remain_distinct(self):
        source,calls,r=self.run_source()
        self.assertEqual(49,source.calls)
        self.assertEqual(11,r["originalEligibleCohorts"])
        self.assertEqual(1,r["originalUpstoxQuarantinedCohorts"])
        self.assertEqual(1,r["exchangeReconciledCohorts"])
        self.assertEqual(12,r["eligibleFuturesCohorts"])
        self.assertEqual(len(source.original),len(calls))
        self.assertEqual(len(calls),r["exchangeSourceBars"])
        self.assertEqual(len(set(calls)),len(calls))
        self.assertEqual(0,r["midnightOhlcDisagreements"])
        self.assertFalse(r["authenticSourceOriginalFirstPublishedAtVerified"])
        self.assertFalse(r["calendarCompletenessVerified"])
        self.assertFalse(r["independentHistoricalE2ECertified"])
        self.assertFalse(r["orders"])
        self.assertFalse(r["writes"])
        self.assertEqual(0,r["certifiedStrategies"])
        self.assertEqual(m.safe_receipt(r),r)
        self.assertLess(len(json.dumps(r)),4000)

    def test_lot_volume_oi_and_identity_disagreements_reject_entire_cohort(self):
        for change in ({"NewBrdLotQty":50},{"TtlTradgVol":999},
                       {"OpnIntrst":999},{"ClsPric":999},
                       {"XpryDt":"2025-12-29"},{"TckrSymb":"OTHER"},
                       {"FinInstrmTp":"IDO"}):
            with self.subTest(change=change),self.assertRaises(ValueError):
                self.run_source(change)

    def test_exchange_ohlc_wins_without_selecting_upstox_observations(self):
        _,_,r=self.run_source({"OpnPric":"99.50","HghPric":"140.00","LwPric":"98.00"})
        self.assertEqual(r["exchangeSourceBars"],r["midnightOhlcDisagreements"])
        self.assertFalse(r["independentHistoricalE2ECertified"])

    def test_missing_day_is_not_treated_as_holiday_or_partial_success(self):
        source=Source()
        with self.assertRaisesRegex(ValueError,"SOURCE_UNAVAILABLE"):
            m.research(source.get,date(2026,11,24),lambda day:None)

    def test_ambiguous_exchange_future_is_not_deduplicated(self):
        source=Source()
        with self.assertRaisesRegex(ValueError,"NSE_DUPLICATE_CONTRACT_ROW"):
            m.research(source.get,date(2026,11,24),
                       lambda d:archive(d,source.original[d],duplicate=True))

    def test_every_original_day_requires_unique_midnight_unit_audit(self):
        source=Source()
        source.get("/historical-candle/NSE_FO%7CFIX001%7C30-12-2025/day/2025-12-30/2025-12-01")
        day=sorted(source.original)[0]
        raw=source.original[day]
        for rows in ([raw,raw],[[raw[0].replace("T00:00:00","T09:15:00"),*raw[1:]]],
                     [["private-invalid",*raw[1:]]]):
            with self.subTest(rows=rows),self.assertRaises(ValueError):
                m.target_observations([{"candles":rows}])

    def test_receipt_cannot_claim_certification_or_forge_cost_outcomes(self):
        _,_,r=self.run_source()
        for field,value in (("certifiedStrategies",1),("calendarCompletenessVerified",True),
                            ("authenticSourceOriginalFirstPublishedAtVerified",True),
                            ("eligibleFuturesCohorts",11),("exchangeGets",67),
                            ("exchangeArchiveSetSha256","private-token"),
                            ("outOfSampleTrades",1000)):
            with self.subTest(field=field),self.assertRaises(ValueError):
                m.safe_receipt({**r,field:value})
        with self.assertRaises(ValueError):m.safe_receipt({**r,"privatePayload":"must-not-export"})
        with self.assertRaises(ValueError):m.safe_receipt({**r,"exchangeReconciledCohorts":True})
        forged=deepcopy(r)
        forged["windowOutcomesBySplit"]["outOfSample"]["NO_SIGNAL"]+=1
        with self.assertRaises(ValueError):m.safe_receipt(forged)

    def test_hashes_are_reproducible_and_change_with_source(self):
        _,_,a=self.run_source()
        _,_,b=self.run_source()
        _,_,c=self.run_source({"OpnPric":"99.50","HghPric":"140.00","LwPric":"98.00"})
        self.assertEqual(a["parsedUpstoxSourceSha256"],b["parsedUpstoxSourceSha256"])
        self.assertEqual(a["exchangeArchiveSetSha256"],b["exchangeArchiveSetSha256"])
        self.assertNotEqual(a["exchangeArchiveSetSha256"],c["exchangeArchiveSetSha256"])

    def test_bundled_host_code_compiles_with_exact_local_modules_and_no_file_write(self):
        bundle=m.bundle()
        compile(bundle,"<ssm-bundle>","exec")
        encoded=base64.b64encode(gzip.compress(bundle,mtime=0))
        self.assertLess(len(encoded),24000)
        scope={"__name__":"bundled_test"}
        originals={name:sys.modules.get(name) for name in m.MODULES}
        try:exec(bundle,scope)
        finally:
            for name,value in originals.items():
                if value is None:sys.modules.pop(name,None)
                else:sys.modules[name]=value
        self.assertEqual(m.EXACT,scope["EXACT"])
        self.assertNotIn("provider",scope)
        code=(ROOT/"infra/ec2-paper/str176_nse_reconciled_research.py").read_text()
        for forbidden in ("placeOrder(","submitOrder(","put-parameter","INSERT INTO",
                          "DELETE FROM","UPDATE fno_","start-instances","stop-instances"):
            self.assertNotIn(forbidden,code)

    def test_exchange_request_budget_and_date_bounds_fail_before_network(self):
        fetch=m.ExchangeArchives()
        fetch.calls=m.MAX_NSE_GETS
        with self.assertRaisesRegex(ValueError,"REQUEST_BOUND"):fetch.get(m.TARGET)
        fetch.calls=0
        with self.assertRaisesRegex(ValueError,"REQUEST_BOUND"):fetch.get(date(2026,1,1))

if __name__=="__main__":unittest.main()
