#!/usr/bin/env python3
import importlib.util
import sys
from pathlib import Path
from datetime import date,timedelta
from types import SimpleNamespace
import unittest
from unittest.mock import patch

root=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("partialgaps",root/"infra/ec2-paper/fno_private_partial_gaps.py")
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def fixture():
    jobs=[]
    candles=[]
    missing={}
    for year,month,expiryday in [(2026,5,28),(2026,10,28),(2025,12,30)]:
        last=date(year,month,expiryday)
        first=last-timedelta(days=20)
        keyset=[]
        for n in range(23):
            key=f"NSE_FO|SYN{n:03d}|{last:%d-%m-%Y}"
            keyset.append(key)
            jobs.append((key,first.isoformat(),last.isoformat(),"DONE","day"))
        for n,key in enumerate(keyset):
            for d in range(21):
                day=first+timedelta(days=d)
                if day.weekday()>=5:
                    continue
                if (year,month)==(2026,5) and n==0 and d==7:
                    missing[(key,day)]=1
                    continue
                if (year,month)==(2026,10) and n<2 and d==8:
                    missing[(key,day)]=1
                    continue
                candles.append((key,day.isoformat()+"T00:00:00+05:30"))
    return jobs,candles,missing


class PartialGapTests(unittest.TestCase):
    def test_only_three_non_december_isolated_gaps_selected(self):
        jobs,candles,missing=fixture()
        gaps=module.identify_partial_gaps(jobs,candles)
        self.assertEqual(len(gaps),3)
        self.assertEqual(sorted(x[0] for x in gaps),["2026-05","2026-10","2026-10"])
        self.assertEqual({(x[1],x[4]) for x in gaps},set(missing))

    def test_provider_absent_all_three_does_not_claim_exchange_closure(self):
        jobs,candles,_=fixture()
        gaps=module.identify_partial_gaps(jobs,candles)
        with patch.object(module.time,"sleep",return_value=None):
            results=module.compare_gaps(gaps,lambda *a:SimpleNamespace(status=200,candles=[]))
        self.assertEqual(results["requests"],3)
        self.assertEqual(results["providerAlsoMissingDay"],3)
        self.assertEqual(results["providerReturnedMissingDay"],0)

    def test_provider_returns_one_missing_contract_day(self):
        jobs,candles,_=fixture()
        gaps=module.identify_partial_gaps(jobs,candles)
        def reply(key,interval,first,last):
            day=next(day for month,k,a,b,day in gaps if k==key)
            item=[day.isoformat()+"T00:00:00+05:30",100,105,98,102,10,50]
            return SimpleNamespace(status=200,candles=[item])
        with patch.object(module.time,"sleep",return_value=None):
            results=module.compare_gaps(gaps,reply)
        self.assertEqual(results["providerReturnedMissingDay"],3)

    def test_changed_cohort_fails_closed(self):
        jobs,candles,_=fixture()
        with self.assertRaisesRegex(ValueError,"ISOLATED_GAP_COHORT_CHANGED"):
            module.identify_partial_gaps(jobs,candles+[
                (jobs[0][0],"2026-05-15T00:00:00+05:30")])


if __name__=="__main__":
    unittest.main()
