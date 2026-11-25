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
                "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst","NewBrdLotQty"]
        data=io.StringIO()
        w=csv.DictWriter(data,fieldnames=fields);w.writeheader()
        for kind,code,price in (("IDF","",0),("IDO","CE",25000),("IDO","PE",25000)):
            w.writerow(dict(BizDt="2025-12-15",TckrSymb="NIFTY",
                 FinInstrmTp=kind,XpryDt="2025-12-30",StrkPric=str(price),
                 OptnTp=code,OpnPric="100",HghPric="103",LwPric="99",
                 ClsPric="102",TtlTradgVol="20",OpnIntrst="15",NewBrdLotQty="75"))
        zipbuf=io.BytesIO()
        with zipfile.ZipFile(zipbuf,"w",zipfile.ZIP_DEFLATED) as z:
            z.writestr("BhavCopy_NSE_FO_0_0_0_20251215_F_0000.csv",data.getvalue())
        rows=m.parse_udiff(zipbuf.getvalue(),date(2025,12,15))
        self.assertEqual(len(rows),3)
        self.assertEqual(rows[("CE",Decimal(25000),date(2025,12,15))][-2],Decimal(15))
        self.assertEqual(rows[("CE",Decimal(25000),date(2025,12,15))][-1],Decimal(75))

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
                nse[(kind,strike,day)]=official+(Decimal(75),)
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
        self.assertEqual(totals["midnightCloseMatch"],322)
        self.assertEqual(totals["midnightOpenMatch"],322)
        self.assertEqual(totals["midnightHighMatch"],322)
        self.assertEqual(totals["midnightLowMatch"],322)
        self.assertEqual(totals["openingAllSixMatch"],5)
        self.assertEqual(totals["midnightOnlyFullMatch"],316)
        self.assertEqual(totals["bothFullMatch"],5)
        self.assertEqual(totals["missingNSEContractDays"],0)
        # NSE counts contracts; a broker can express volume in lot units.
        adjusted={
            k:[(clock,v[:4]+(v[4]*Decimal(75),)+v[5:]) for clock,v in rows]
            for k,rows in saved.items()
        }
        normalized=m.compare(adjusted,mapping,nse)
        self.assertEqual(normalized["midnightNormalizedVolumeMatch"],322)
        self.assertEqual(normalized["openingNormalizedVolumeMatch"],321)
        self.assertEqual(normalized["midnightVolumeMatch"],0)

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
                    nse[(kind,strike,day)]=official+(Decimal(75),)
        result=m.compare(saved,mapping,nse)
        self.assertEqual(result["missingNSEContractDays"],1)

    def test_builds_one_provenanced_personal_daily_candle_per_contract_date(self):
        december="NSE_FO|D1|30-12-2025"
        november="NSE_FO|N1|26-11-2025"
        day=date(2025,12,15)
        ref=(Decimal(100),Decimal(104),Decimal(99),Decimal(103),
             Decimal(20),Decimal(50),Decimal(75))
        nondec=[(november,"2025-11-24T00:00:00+05:30",
                 110,111,109,110,200,10)]
        original={(december,day):[
            ("00:00",tuple(ref[:4])+(Decimal(1500),ref[5])),
            ("09:15",(Decimal(99),Decimal(100),Decimal(95),
                      Decimal(97),Decimal(100),Decimal(40)))]}
        mapping={december:("CE",Decimal(25000))}
        source={("CE",Decimal(25000),day):ref}
        canonical,summary=m.canonical_personal_rows(nondec,original,mapping,source)
        self.assertEqual(summary["total"],2)
        self.assertEqual(summary["excludedDecember"],0)
        self.assertEqual(canonical[(december,day)][2],"NSE_PUBLIC_EOD")
        self.assertEqual(canonical[(december,day)][1][4],Decimal(1500))
        self.assertEqual(canonical[(november,date(2025,11,24))][2],"UPSTOX_PRIVATE")
        self.assertNotEqual(canonical[(december,day)][1][3],Decimal(97))
        bad_source={("CE",Decimal(25000),day):
                    (Decimal(0),Decimal(0),Decimal(0),Decimal(103),
                     Decimal(0),Decimal(50),Decimal(75))}
        excluded,report=m.canonical_personal_rows(nondec,original,mapping,bad_source)
        self.assertEqual(report["excludedDecember"],1)
        self.assertEqual(report["nseDecember"],0)
        self.assertEqual(report["total"],1)
        self.assertNotIn((december,day),excluded)
        with self.assertRaisesRegex(ValueError,"DUPLICATE_NON_DECEMBER_DAY"):
            m.canonical_personal_rows(nondec+nondec,original,mapping,source)
        with self.assertRaisesRegex(ValueError,"MISSING_EXCHANGE_REFERENCE_DAY"):
            m.canonical_personal_rows(nondec,original,mapping,{})

    def test_source_url_is_fixed_to_window_and_weekdays(self):
        self.assertIn("20251215",m.source_url(date(2025,12,15)))
        with self.assertRaisesRegex(ValueError,"INVALID_PROBE_DAY"):
            m.source_url(date(2025,12,14))
        with self.assertRaisesRegex(ValueError,"INVALID_PROBE_DAY"):
            m.source_url(date(2025,11,15))

if __name__=="__main__":unittest.main()
