#!/usr/bin/env python3
"""Synthetic NSE UDiFF evidence triage tests; strictly no network or AWS."""
import csv
from datetime import date, timedelta
from decimal import Decimal
import importlib.util
import io
from pathlib import Path
import sys
import unittest
import zipfile

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "infra/ec2-paper"))
spec = importlib.util.spec_from_file_location(
    "three_gap_nse", root / "infra/ec2-paper/fno_private_three_gap_nse_eod.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
from fno_private_bulk_worker import UNDERLYING


def archive(day, entries):
    fields = ["BizDt","TckrSymb","FinInstrmTp","XpryDt","StrkPric",
              "OptnTp","TtlTradgVol"]
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=fields)
    writer.writeheader()
    for row in entries:
        writer.writerow({"BizDt": day.isoformat(), "TckrSymb": "NIFTY",
                         **row})
    data = io.BytesIO()
    filename = ("BhavCopy_NSE_FO_0_0_0_" +
                day.strftime("%Y%m%d") + "_F_0000.csv")
    with zipfile.ZipFile(data, mode="w", compression=zipfile.ZIP_DEFLATED) as zipout:
        zipout.writestr(filename, output.getvalue())
    return data.getvalue()


def gaps_fixture():
    may = date(2026, 5, 18)
    oct_day = date(2026, 10, 16)
    mayexp = date(2026, 5, 28)
    octexp = date(2026, 10, 28)
    records = [
        ("2026-05","NSE_FO|SYN001|28-05-2026",
         (mayexp-timedelta(days=20)).isoformat(),mayexp.isoformat(),may),
        ("2026-10","NSE_FO|SYN002|28-10-2026",
         (octexp-timedelta(days=20)).isoformat(),octexp.isoformat(),oct_day),
        ("2026-10","NSE_FO|SYN003|28-10-2026",
         (octexp-timedelta(days=20)).isoformat(),octexp.isoformat(),oct_day),
    ]
    return records, {
        ("2026-05", records[0][1],may):(mayexp,"CE",Decimal("25000")),
        ("2026-10", records[1][1],oct_day):(octexp,"PE",Decimal("25000")),
        ("2026-10", records[2][1],oct_day):(octexp,"CE",Decimal("25200")),
    }


class IndependentNseEodGapTests(unittest.TestCase):
    def test_exact_udiff_match_differentiates_traded_zero_and_no_row(self):
        day = date(2026, 10, 16)
        expiry = date(2026, 10, 28)
        identities = {(expiry,"PE",Decimal("25000")),
                      (expiry,"CE",Decimal("25200")),
                      (expiry,"CE",Decimal("25100"))}
        zipped = archive(day, [
            {"FinInstrmTp":"IDO","XpryDt":expiry.isoformat(),
             "StrkPric":"25000.0","OptnTp":"PE","TtlTradgVol":"42"},
            {"FinInstrmTp":"IDO","XpryDt":expiry.isoformat(),
             "StrkPric":"25200","OptnTp":"CE","TtlTradgVol":"0"},
            {"FinInstrmTp":"IDO","XpryDt":"2026-11-28",
             "StrkPric":"25100","OptnTp":"CE","TtlTradgVol":"999"},
        ])
        result = module.eod_presence(zipped,day,identities)
        self.assertEqual(result[(expiry,"PE",Decimal("25000"))],"EXCHANGE_TRADED")
        self.assertEqual(result[(expiry,"CE",Decimal("25200"))],"EXCHANGE_ZERO_VOLUME")
        self.assertEqual(result[(expiry,"CE",Decimal("25100"))],"EXCHANGE_ROW_ABSENT")

    def test_nse_archive_is_only_allowed_for_scoped_past_2026_dates(self):
        self.assertIn("20260518",module.nse_archive_url(date(2026,5,18)))
        with self.assertRaisesRegex(ValueError,"OUT_OF_SCOPE"):
            module.nse_archive_url(date(2026,8,18))
        with self.assertRaisesRegex(ValueError,"OUT_OF_SCOPE"):
            module.nse_archive_url(date(2026,5,17))

    def test_rejected_archive_is_not_treated_as_no_trades(self):
        gaps,crosswalk = gaps_fixture()
        calls = []
        def fetch(day):
            calls.append(day)
            return "SOURCE_REJECTED",None
        counts=module.triage(gaps,crosswalk,fetch)
        self.assertEqual(len(calls),2)
        self.assertEqual(counts["archiveUnavailableGaps"],3)
        self.assertEqual(counts["EXCHANGE_ROW_ABSENT"],0)

    def test_two_dates_three_gaps_aggregate_without_identifiers(self):
        gaps,crosswalk = gaps_fixture()
        def fetch(day):
            if day.month==5:
                return "FETCHED",archive(day,[{
                    "FinInstrmTp":"IDO","XpryDt":"2026-05-28",
                    "StrkPric":"25000","OptnTp":"CE","TtlTradgVol":"1",
                }])
            return "FETCHED",archive(day,[{
                "FinInstrmTp":"IDO","XpryDt":"2026-10-28",
                "StrkPric":"25000","OptnTp":"PE","TtlTradgVol":"0",
            }])
        counts=module.triage(gaps,crosswalk,fetch)
        self.assertEqual(counts["EXCHANGE_TRADED"],1)
        self.assertEqual(counts["EXCHANGE_ZERO_VOLUME"],1)
        self.assertEqual(counts["EXCHANGE_ROW_ABSENT"],1)
        self.assertEqual(counts["archivesFetched"],2)

    def test_provider_contract_identity_crosswalk_not_guessed(self):
        gaps,identities = gaps_fixture()
        def row(key,expiry,kind,strike):
            return {"segment":"NSE_FO","exchange":"NSE",
                    "underlying_key":UNDERLYING,
                    "instrument_type":kind,"instrument_key":key,
                    "expiry":expiry.isoformat(),"strike_price":strike}
        mayexp=date(2026,5,28)
        octexp=date(2026,10,28)
        catalogue={
            mayexp:([],[row(gaps[0][1],mayexp,"CE",25000)]),
            octexp:([],[row(gaps[1][1],octexp,"PE",25000),
                        row(gaps[2][1],octexp,"CE",25200)]),
        }
        self.assertEqual(module.map_gap_identities(gaps,catalogue),identities)
        catalogue[octexp][1][0]["instrument_key"]="NSE_FO|DIFFERENT|28-10-2026"
        with self.assertRaisesRegex(ValueError,"CROSSWALK_NOT_UNIQUE"):
            module.map_gap_identities(gaps,catalogue)

    def test_refuses_bad_volume_and_duplicate_official_identity(self):
        day=date(2026,5,18)
        expiry=date(2026,5,28)
        identity={(expiry,"CE",Decimal(25000))}
        def item(volume):
            return {"FinInstrmTp":"IDO","XpryDt":expiry.isoformat(),
                    "StrkPric":"25000","OptnTp":"CE","TtlTradgVol":volume}
        with self.assertRaisesRegex(ValueError,"INVALID_EXCHANGE_VOLUME"):
            module.eod_presence(archive(day,[item("-1")]),day,identity)
        with self.assertRaisesRegex(ValueError,"AMBIGUOUS_EXCHANGE_IDENTITY"):
            module.eod_presence(archive(day,[item("1"),item("1")]),day,identity)

    def test_out_of_scope_archive_name_fails_closed(self):
        day=date(2026,5,18)
        wrong=archive(date(2026,10,16),[])
        with self.assertRaisesRegex(ValueError,"UNEXPECTED_EXCHANGE_ARCHIVE"):
            module.eod_presence(wrong,day,set())


if __name__=="__main__":
    unittest.main()
