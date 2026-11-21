#!/usr/bin/env python3
import csv
from datetime import date,timedelta
from decimal import Decimal
import importlib.util
import io
from pathlib import Path
import sys
import unittest
import zipfile

root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/"infra/ec2-paper"))
spec=importlib.util.spec_from_file_location("nsecmp",root/"infra/ec2-paper/fno_personal_nse_december_compare.py")
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class NseDecemberCompareTest(unittest.TestCase):
    def test_archive_parser_extracts_specific_futures_and_options(self):
        fields=["BizDt","TckrSymb","FinInstrmTp","XpryDt","StrkPric","OptnTp",
                "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst"]
        data=io.StringIO()
        w=csv.DictWriter(data,fieldnames=fields);w.writeheader()
        for kind,code,price in (("IDF","",0),("IDO","CE",25000),("IDO","PE",25000)):
            w.writerow(dict(BizDt="2025-12-15",TckrSymb="NIFTY",
                 FinInstrmTp=kind,XpryDt="2025-12-30",StrkPric=str(price),
                 OptnTp=code,OpnPric="100",HghPric="103",LwPric="99",
                 ClsPric="102",TtlTradgVol="20",OpnIntrst="15"))
        zipbuf=io.BytesIO()
        with zipfile.ZipFile(zipbuf,"w",zipfile.ZIP_DEFLATED) as z:
            z.writestr("BhavCopy_NSE_FO_0_0_0_20251215_F_0000.csv",data.getvalue())
        rows=m.parse_udiff(zipbuf.getvalue(),date(2025,12,15))
        self.assertEqual(len(rows),3)
        self.assertEqual(rows[("CE",Decimal(25000),date(2025,12,15))][-1],Decimal(15))

    def test_full_321_duplicate_pairs_compare_independently(self):
        mapping={}
        saved={}
        nse={}
        for n in range(23):
            key=f"NSE_FO|SYN{n:03}|30-12-2025"
            kind="FUT" if n==0 else "CE" if n<12 else "PE"
            strike=Decimal(0) if n==0 else Decimal(24000+n*50)
            mapping[key]=(kind,strike)
            for d in range(14):
                day=date(2025,12,10)+timedelta(days=d)
                official=tuple(Decimal(n) for n in (100,103,99,102,20,15))
                nse[(kind,strike,day)]=official
                saved[(key,day)]=[("00:00",official)]
                if (n,d)!=(22,13):
                    identical=n==0 and d<5
                    changed=official if identical else official[:3]+(Decimal(101),)+official[4:]
                    saved[(key,day)].append(("09:15",changed))
        totals=m.compare(saved,mapping,nse)
        self.assertEqual(totals["duplicates"],321)
        self.assertEqual(totals["providerConflicts"],316)
        self.assertEqual(totals["providerIdenticalPairs"],5)
        self.assertEqual(totals["midnightAllSixMatch"],322)
        self.assertEqual(totals["openingAllSixMatch"],5)
        self.assertEqual(totals["midnightOnlyFullMatch"],316)
        self.assertEqual(totals["bothFullMatch"],5)
        self.assertEqual(totals["missingNSEContractDays"],0)

    def test_missing_exchange_rows_detected_without_fabrication(self):
        mapping={}
        saved={}
        nse={}
        for n in range(23):
            key=f"NSE_FO|SYN{n:03}|30-12-2025"
            kind="FUT" if n==0 else "CE" if n<12 else "PE"
            strike=Decimal(0) if n==0 else Decimal(24000+n*50)
            mapping[key]=(kind,strike)
            for d in range(14):
                day=date(2025,12,10)+timedelta(days=d)
                official=(Decimal(100),Decimal(103),Decimal(99),Decimal(102),Decimal(20),Decimal(15))
                saved[(key,day)]=[("00:00",official)]
                if (n,d)!=(22,13):
                    saved[(key,day)].append(("09:15",official if n==0 and d<5 else
                                            official[:3]+(Decimal(101),)+official[4:]))
                if (n,d)!=(22,13):
                    nse[(kind,strike,day)]=official
        result=m.compare(saved,mapping,nse)
        self.assertEqual(result["missingNSEContractDays"],1)

    def test_source_url_is_fixed_to_window_and_weekdays(self):
        self.assertIn("20251215",m.source_url(date(2025,12,15)))
        with self.assertRaisesRegex(ValueError,"INVALID_PROBE_DAY"):
            m.source_url(date(2025,12,14))
        with self.assertRaisesRegex(ValueError,"INVALID_PROBE_DAY"):
            m.source_url(date(2025,11,15))

if __name__=="__main__":unittest.main()
