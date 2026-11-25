#!/usr/bin/env python3
"""Synthetic-only tests: no provider account, licensed file, or network calls."""
import csv
from datetime import date,timedelta
from decimal import Decimal
import hashlib
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import os
import json

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location(
    "fno_licensed_eod_reconcile",
    ROOT/"infra/ec2-paper/fno_licensed_eod_reconcile.py")
f=importlib.util.module_from_spec(spec)
spec.loader.exec_module(f)


class LicensedEodGateTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir=Path(self.temp.name)
        self.catalogue={}
        self.db=sqlite3.connect(self.dir/"private.sqlite")
        self.addCleanup(self.db.close)
        self.db.execute("""CREATE TABLE fno_historical_candles(
            contract_key TEXT,candle_ts TEXT,open_price REAL,high_price REAL,
            low_price REAL,close_price REAL,volume INTEGER,open_interest INTEGER,
            interval TEXT,source TEXT)""")
        self.ref={}
        expiry=date(2025,12,30)
        day0=date(2025,12,10)
        for n in range(23):
            kind=("FUT" if n==0 else "CE" if n<=11 else "PE")
            strike=Decimal(0) if n==0 else Decimal(25000+50*((n-1)%11))
            key=f"NSE_FO|SYN{n:03d}|30-12-2025"
            self.catalogue[key]=(expiry,kind,strike,Decimal(1))
            for d in range(14):
                day=day0+timedelta(days=d)
                identity=(expiry,kind,strike,day)
                values=(Decimal(100),Decimal(105),Decimal(99),
                        Decimal(102),Decimal(10),Decimal(5))
                self.ref[identity]=values
                self._row(key,day,"00:00",102)
                if (n,d)!=(22,13):
                    self._row(key,day,"09:15",102 if n==0 and d<5 else 103)
        self.db.commit()

    def _row(self,key,day,clock,close):
        self.db.execute(
            "INSERT INTO fno_historical_candles VALUES (?,?,?,?,?,?,?,?,?,?)",
            (key,f"{day.isoformat()}T{clock}:00+05:30",100.,105.,99.,
             float(close),10,5,"day",f.SOURCE))

    def test_accurate_midnight_316_conflicting_and_5_identical(self):
        result=f.reconcile(self.db,self.catalogue,self.ref)
        count=result["counts"]
        self.assertEqual(result["result"],"REVIEW")
        self.assertFalse(result["backtestingApproved"])
        self.assertFalse(result["commercialRightsVerified"])
        self.assertEqual(count["totalCandles"],643)
        self.assertEqual(count["contractDates"],322)
        self.assertEqual(count["duplicateDays"],321)
        self.assertEqual(count["providerConflictingDays"],316)
        self.assertEqual(count["providerIdenticalDays"],5)
        self.assertEqual(count["midnightExactMatches"],322)
        self.assertEqual(count["openingExactMatches"],5)
        self.assertEqual(count["midnightOnlyExactMatches"],316)
        self.assertEqual(count["bothExactMatches"],5)
        self.assertNotIn("SYN000",json.dumps(result))

    def test_independent_reference_can_disagree_with_midnight(self):
        key=next(iter(self.catalogue))
        expiry,kind,strike,lot=self.catalogue[key]
        day=date(2025,12,15)
        self.ref[(expiry,kind,strike,day)]=(Decimal(100),Decimal(105),Decimal(99),
                                           Decimal(103),Decimal(10),Decimal(5))
        result=f.reconcile(self.db,self.catalogue,self.ref)["counts"]
        self.assertEqual(result["openingOnlyExactMatches"],1)
        self.assertEqual(result["midnightExactMatches"],321)
        self.assertEqual(result["openingExactMatches"],6)

    def test_missing_independent_source_never_marks_pass(self):
        self.ref.clear()
        result=f.reconcile(self.db,self.catalogue,self.ref)
        self.assertEqual(result["counts"]["missingIndependentEod"],322)
        self.assertEqual(result["result"],"REVIEW")
        self.assertFalse(result["backtestingApproved"])

    def test_unmapped_contract_and_non_ist_fail_closed(self):
        with self.assertRaisesRegex(ValueError,"PRIVATE_CATALOGUE_INCOMPLETE"):
            f.reconcile(self.db,dict(list(self.catalogue.items())[:-1]),self.ref)
        self.db.execute("UPDATE fno_historical_candles SET candle_ts='2025-12-10T00:00:00+00:00' WHERE candle_ts LIKE '2025-12-10T00:%'")
        with self.assertRaisesRegex(ValueError,"PRIVATE_EOD_UNEXPECTED_TIMEZONE"):
            f.reconcile(self.db,self.catalogue,self.ref)

    def test_explicit_catalogue_csv_maps_23_and_rejects_ambiguous(self):
        p=self.dir/"contract-map.csv"
        with p.open("w",newline="") as handle:
            writer=csv.DictWriter(handle,fieldnames=sorted(f.CATALOGUE_HEADER))
            writer.writeheader()
            for key,(expiry,kind,strike,lot) in self.catalogue.items():
                writer.writerow({"contract_key":key,"underlying":"NIFTY",
                                 "expiry":expiry.isoformat(),"kind":kind,
                                 "strike":str(strike) if kind!="FUT" else "",
                                 "lot_size":str(lot)})
        self.assertEqual(f.load_catalogue(p),self.catalogue)
        with p.open("a",newline="") as handle:
            writer=csv.DictWriter(handle,fieldnames=sorted(f.CATALOGUE_HEADER))
            key=next(iter(self.catalogue))
            expiry,kind,strike,lot=self.catalogue[key]
            writer.writerow({"contract_key":key,"underlying":"NIFTY",
                             "expiry":expiry.isoformat(),"kind":kind,"strike":"",
                             "lot_size":str(lot)})
        with self.assertRaisesRegex(ValueError,"PRIVATE_CONTRACT_CATALOGUE_AMBIGUOUS"):
            f.load_catalogue(p)

    def test_nse_udiff_csv_decimals_and_duplicate_reference_rejected(self):
        p=self.dir/"nse-licensed.csv"
        fields=sorted(f.REFERENCE_HEADER)
        day=date(2025,12,15)
        sample={
            "BizDt":day.isoformat(),"TckrSymb":"NIFTY","FinInstrmTp":"IDO",
            "XpryDt":"2025-12-30","StrkPric":"25000","OptnTp":"CE",
            "OpnPric":"100.00","HghPric":"105.00","LwPric":"99.00",
            "ClsPric":"102.00","TtlTradgVol":"10","OpnIntrst":"5",
        }
        with p.open("w",newline="") as handle:
            writer=csv.DictWriter(handle,fieldnames=fields)
            writer.writeheader()
            writer.writerow(sample)
        parsed,scanned=f.load_reference(p)
        self.assertEqual(scanned,1)
        self.assertEqual(len(parsed),1)
        self.assertEqual(next(iter(parsed.values()))[3],Decimal("102.00"))
        with p.open("a",newline="") as handle:
            writer=csv.DictWriter(handle,fieldnames=fields)
            writer.writerow(sample)
        with self.assertRaisesRegex(ValueError,"PRIVATE_EOD_DUPLICATE_EXCHANGE_KEY"):
            f.load_reference(p)

    def test_true_contract_volume_differs_but_lot_normalized_ohlcvoi_matches(self):
        # Official NSE UDiFF counts contracts, provider daily source volume
        # counts underlying units: observed Dec 15 2025 pilot is 23/23.
        self.catalogue={key:(expiry,kind,strike,Decimal(75))
                        for key,(expiry,kind,strike,lot) in self.catalogue.items()}
        self.db.execute("UPDATE fno_historical_candles SET volume=volume*75")
        result=f.reconcile(self.db,self.catalogue,self.ref)
        counts=result["counts"]
        self.assertEqual(counts["midnightExactMatches"],322)
        self.assertEqual(counts["openingExactMatches"],5)
        self.assertEqual(counts["midnightScaledVolumeMatches"],322)
        self.assertEqual(counts["midnightUnscaledVolumeMatches"],0)
        self.assertEqual(counts["openingScaledVolumeMatches"],321)
        self.assertEqual(counts["openingUnscaledVolumeMatches"],0)
        self.assertEqual(result["volumeNormalization"],
                         "NSE_CONTRACTS_TIMES_AUTHENTICATED_HISTORICAL_LOT")
        self.assertFalse(result["backtestingApproved"])
        self.assertFalse(result["commercialRightsVerified"])
        self.assertFalse(result["mutatedRawCache"])

    def test_invalid_historical_lot_size_cannot_reconcile_or_load(self):
        key=next(iter(self.catalogue))
        expiry,kind,strike,lot=self.catalogue[key]
        self.catalogue[key]=(expiry,kind,strike,Decimal("1.5"))
        with self.assertRaisesRegex(ValueError,"PRIVATE_CONTRACT_LOT_SIZE_INVALID"):
            f.reconcile(self.db,self.catalogue,self.ref)
        self.assertEqual(f.validated_lot(75),Decimal(75))
        for bad in ("0","-1","NaN","1.5","10001"):
            with self.subTest(bad=bad),self.assertRaises(ValueError):
                f.validated_lot(bad)

    def test_unverified_rights_fail_closed_even_if_file_claims_approval(self):
        p=self.dir/"attestation.json"
        obj=dict(
            licence_reference="SYNTHETIC-TEST-LICENCE",
            permitted_private_reconciliation=True,
            may_read_eod_on_host=True,may_retain_reference_data=False,
            may_redistribute=False,expires_on="2026-12-31",
            source="NSE_DATA_AND_ANALYTICS_LICENSED_FO_EOD")
        p.write_text(json.dumps(obj))
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaisesRegex(ValueError,"INDEPENDENT_EOD_RIGHTS_NOT_APPROVED"):
                f.rights_attestation(p)
        with patch.dict(os.environ,{"MINTTICK_REVIEWED_EOD_RIGHTS_SHA256":"0"*64}):
            with self.assertRaisesRegex(ValueError,"INDEPENDENT_EOD_RIGHTS_NOT_APPROVED"):
                f.rights_attestation(p)


if __name__=="__main__":
    unittest.main()
