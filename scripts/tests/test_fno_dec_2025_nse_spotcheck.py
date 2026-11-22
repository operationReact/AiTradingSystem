#!/usr/bin/env python3
"""Synthetic tests only: 1 NSE archive, 23 crosswalk keys, zero real data."""
import csv
from datetime import date
from decimal import Decimal
import importlib.util
import io
from pathlib import Path
import sys
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"infra/ec2-paper"))
spec=importlib.util.spec_from_file_location(
    "dec_nse",ROOT/"infra/ec2-paper/fno_dec_2025_nse_spotcheck.py")
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
from fno_private_bulk_worker import UNDERLYING

def fixture():
    keys=[]
    catalogue={}
    rows=[]
    refs={}
    for n in range(23):
        kind="FUT" if n==0 else "CE" if n<=11 else "PE"
        strike=Decimal(0 if n==0 else 25000+50*(n-1 if n<=11 else n-12))
        key=f"NSE_FO|SYN{n:03d}|30-12-2025"
        ident=(m.EXPIRY,kind,strike)
        keys.append(key)
        catalogue[key]=ident
        close_eod=100 if n<10 else 101 if n<15 else 102
        refs[ident]=tuple(Decimal(str(x)) for x in (100,105,99,close_eod,10,5))
        for mark,close in (("00:00",100),("09:15",101)):
            rows.append((key,"2025-12-15T"+mark+":00+05:30",100,105,99,close,10,5))
    return keys,catalogue,rows,refs

def zipped_eod(entries,day="2025-12-15"):
    fields=["BizDt","TckrSymb","FinInstrmTp","XpryDt",
            "StrkPric","OptnTp",*m.DATA_KEYS]
    output=io.StringIO()
    writer=csv.DictWriter(output,fieldnames=fields)
    writer.writeheader()
    for row in entries:
        writer.writerow({"BizDt":day,"TckrSymb":"NIFTY",
                         "XpryDt":"2025-12-30",**row})
    blob=io.BytesIO()
    with zipfile.ZipFile(blob,"w",zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("BhavCopy_NSE_FO_0_0_0_20251215_F_0000.csv",output.getvalue())
    return blob.getvalue()

def nse_row(kind="IDO",strike="25000",option="CE",close="100",volume="10",oi="5"):
    return {"FinInstrmTp":kind,"StrkPric":strike,"OptnTp":option,
            "OpnPric":"100","HghPric":"105","LwPric":"99",
            "ClsPric":close,"TtlTradgVol":volume,"OpnIntrst":oi}

class DecemberSpotcheckTests(unittest.TestCase):
    def test_independently_classifies_candidates_without_selecting_them(self):
        keys,catalogue,rows,reference=fixture()
        result=m.compare_one_day(rows,catalogue,reference)
        self.assertEqual(result["savedBars"],46)
        self.assertEqual(result["duplicatePairs"],23)
        self.assertEqual(result["conflictingPairs"],23)
        self.assertEqual(result["matchedNseIdentities"],23)
        self.assertEqual(result["midnightOnlyExact"],10)
        self.assertEqual(result["openingOnlyExact"],5)
        self.assertEqual(result["neitherExact"],8)
        self.assertEqual(result["bothExact"],0)
        self.assertEqual(result["midnightExactAllSix"],10)
        self.assertEqual(result["openingExactAllSix"],5)
        self.assertNotIn("SYN",str(result))
        self.assertNotIn("25000",str(result))

    def test_identical_pair_and_nse_missing_remain_distinct(self):
        keys,catalogue,rows,reference=fixture()
        change=list(rows[1])
        change[5]=100
        rows[1]=tuple(change)
        removed=reference.pop(catalogue[keys[-1]])
        report=m.compare_one_day(rows,catalogue,reference)
        self.assertEqual(report["identicalPairs"],1)
        self.assertEqual(report["conflictingPairs"],22)
        self.assertEqual(report["noNseContractRow"],1)
        self.assertEqual(report["matchedNseIdentities"],22)
        self.assertEqual(removed[3],Decimal(102))

    def test_mismatched_timestamp_and_incomplete_cache_fail_closed(self):
        keys,catalogue,rows,reference=fixture()
        with self.assertRaisesRegex(ValueError,"DECEMBER_SAVED_COHORT_INCOMPLETE"):
            m.compare_one_day(rows[:-2],catalogue,reference)
        bad=list(rows)
        record=list(bad[0])
        record[1]="2025-12-15T00:00:00+00:00"
        bad[0]=tuple(record)
        with self.assertRaisesRegex(ValueError,"DECEMBER_TIMESTAMP_INVALID"):
            m.compare_one_day(bad,catalogue,reference)

    def test_wrong_price_or_nan_is_not_accepted(self):
        keys,catalogue,rows,reference=fixture()
        bad=list(rows)
        item=list(bad[1])
        item[5]="NaN"
        bad[1]=tuple(item)
        with self.assertRaisesRegex(ValueError,"EOD_NUMBER_INVALID"):
            m.compare_one_day(bad,catalogue,reference)

    def test_one_day_archive_works_and_rejects_bad_rows(self):
        blob=zipped_eod([nse_row()])
        parsed=m.parse_eod(blob)
        self.assertEqual(len(parsed),1)
        self.assertEqual(parsed[(m.EXPIRY,"CE",Decimal(25000))][3],Decimal(100))
        with self.assertRaisesRegex(ValueError,"NSE_DUPLICATE_IDENTITY"):
            m.parse_eod(zipped_eod([nse_row(),nse_row()]))
        with self.assertRaisesRegex(ValueError,"NSE_ARCHIVE_DATE_OR_SIZE_INVALID"):
            m.parse_eod(zipped_eod([nse_row()],day="2025-12-16"))
        with self.assertRaisesRegex(ValueError,"NSE_ARCHIVE_INVALID"):
            m.parse_eod(b"wrong")

    def test_broker_metadata_requires_exact_expiry_type_and_strike(self):
        keys,catalogue,rows,reference=fixture()
        futures=[]
        options=[]
        for key,(expiry,kind,strike) in catalogue.items():
            item={"segment":"NSE_FO","exchange":"NSE",
                  "underlying_key":UNDERLYING,"expiry":expiry.isoformat(),
                  "instrument_key":key,"instrument_type":kind,
                  "strike_price":float(strike)}
            (futures if kind=="FUT" else options).append(item)
        self.assertEqual(m.map_contracts(set(keys),futures,options),catalogue)
        options[0]["expiry"]="2026-01-27"
        with self.assertRaisesRegex(ValueError,"DECEMBER_CATALOGUE_CROSSWALK_INCOMPLETE"):
            m.map_contracts(set(keys),futures,options)

    def test_rejects_fabricated_extra_candles(self):
        keys,catalogue,rows,reference=fixture()
        with self.assertRaisesRegex(ValueError,"DECEMBER_DUPLICATE_STAMP_OR_COLUMNS"):
            m.compare_one_day(rows+[rows[0]],catalogue,reference)

    def test_broker_lot_scaled_nse_volume_for_both_timestamp_families(self):
        keys,catalogue,rows,reference=fixture()
        lots={key:Decimal(75) for key in keys}
        scaled=[]
        for key,stamp,op,high,low,close,vol,oi in rows:
            scaled.append((key,stamp,op,high,low,close,vol*75,oi))
        evidence=m.volume_unit_evidence(scaled,catalogue,reference,lots)
        self.assertEqual(evidence["midnightLotMetadataChecked"],23)
        self.assertEqual(evidence["openingLotMetadataChecked"],23)
        self.assertEqual(evidence["midnightNseVolumeTimesLotMatches"],23)
        self.assertEqual(evidence["openingNseVolumeTimesLotMatches"],23)
        self.assertEqual(evidence["midnightUnscaledVolumeMatches"],0)
        self.assertEqual(evidence["openingUnscaledVolumeMatches"],0)
        self.assertNotIn("SYN",str(evidence))

    def test_authenticated_lot_sizes_required_and_invalid_values_blocked(self):
        keys,catalogue,rows,reference=fixture()
        futures=[]
        options=[]
        for key,(expiry,kind,strike) in catalogue.items():
            item={"segment":"NSE_FO","exchange":"NSE",
                  "underlying_key":UNDERLYING,"expiry":expiry.isoformat(),
                  "instrument_key":key,"instrument_type":kind,
                  "strike_price":float(strike),"lot_size":75}
            (futures if kind=="FUT" else options).append(item)
        mapped,lots=m.map_contracts(set(keys),futures,options,include_lots=True)
        self.assertEqual(mapped,catalogue)
        self.assertEqual(set(lots),set(keys))
        self.assertTrue(all(x==Decimal(75) for x in lots.values()))
        options[0]["lot_size"]=None
        with self.assertRaisesRegex(ValueError,"DECEMBER_AUTHENTICATED_LOT_SIZE_INVALID"):
            m.map_contracts(set(keys),futures,options,include_lots=True)

if __name__=="__main__":
    unittest.main()
