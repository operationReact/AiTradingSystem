#!/usr/bin/env python3
"""Synthetic source-plan and weekday-coverage tests; zero network access."""
import importlib.util
from datetime import date,timedelta
from pathlib import Path
import sys
import unittest

root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/"infra/ec2-paper"))
spec=importlib.util.spec_from_file_location("coverage",root/"infra/ec2-paper/fno_private_source_coverage.py")
c=importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
from fno_private_bulk_worker import UNDERLYING,contracts


class SourceCoverageTests(unittest.TestCase):
    def test_weekday_gaps_are_candidates_not_certified_holidays(self):
        expiry=date(2025,11,26)
        first=expiry-timedelta(days=20)
        k="NSE_FO|SYN001|26-11-2025"
        result=c.calendar_candidates([(k,first.isoformat(),expiry.isoformat())],
            [(k,first.isoformat()+"T00:00:00+05:30"),
             (k,first.isoformat()+"T09:15:00+05:30")])
        m=next(x for x in result if x["month"]=="2025-11")
        self.assertEqual(m["contracts"],1)
        self.assertEqual(m["expectedWeekdaySlots"],15)
        self.assertEqual(m["missingWeekdayCandidates"],14)
        self.assertEqual(m["dailyDateDuplicates"],1)
        self.assertEqual(m["nonMidnightRows"],1)
        self.assertEqual(m["weekendRows"],0)

    def test_contract_plan_counts_one_future_eleven_strike_pairs(self):
        expiries=[date(2025,11,26),date(2025,12,30)]+[date(2026,m,28) for m in range(1,11)]
        data={}
        jobs=[]
        for exp in expiries:
            ending=exp.strftime("%d-%m-%Y")
            def item(i,kind,strike=None):
                d={"segment":"NSE_FO","exchange":"NSE",
                   "underlying_key":UNDERLYING,
                   "expiry":exp.isoformat(),"instrument_type":kind,
                   "instrument_key":f"NSE_FO|SYN{i:03d}|{ending}"}
                if strike is not None:
                    d["strike_price"]=strike
                return d
            fut=[item(1,"FUT")]
            options=[]
            for i in range(11):
                options += [item(2+2*i,"CE",20000+i*50),
                            item(3+2*i,"PE",20000+i*50)]
            data[exp]=(fut,options)
            for key in contracts(fut,options,exp):
                jobs.append((key,(exp-timedelta(days=20)).isoformat(),
                             exp.isoformat(),"DONE"))
        ok,result,months=c.metadata_match(jobs,expiries,data)
        self.assertTrue(ok)
        self.assertEqual(result["futures"],12)
        self.assertEqual(result["CE"],132)
        self.assertEqual(result["PE"],132)
        self.assertEqual(result["selectedMatches"],276)
        self.assertEqual(len(months),12)
        corrupt=jobs[:-1]
        bad,detail,_=c.metadata_match(corrupt,expiries,data)
        self.assertFalse(bad)
        self.assertEqual(detail["selectedMissing"],1)

    def test_wrong_window_rejected(self):
        k="NSE_FO|SYN001|26-11-2025"
        with self.assertRaisesRegex(ValueError,"COVERAGE_UNEXPECTED_WINDOW"):
            c.calendar_candidates([(k,"2025-11-01","2025-11-26")],[])

    def test_non_ist_timestamp_rejected(self):
        k="NSE_FO|SYN001|26-11-2025"
        with self.assertRaisesRegex(ValueError,"COVERAGE_INVALID_TIMESTAMP"):
            c.calendar_candidates([(k,"2025-11-06","2025-11-26")],
                                  [(k,"2025-11-06T00:00:00+00:00")])


if __name__=="__main__":
    unittest.main()
