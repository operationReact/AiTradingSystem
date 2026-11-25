#!/usr/bin/env python3
"""Pure synthetic ZIP fixture test; no network access."""
import csv
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile
from datetime import date

p=Path(__file__).resolve().parents[2]/"infra/ec2-paper/fno_personal_nse_archive_probe.py"
spec=importlib.util.spec_from_file_location("archive_probe",p)
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

def fixture(data=True,wrongday=False):
    keys=["TckrSymb","FinInstrmTp","BizDt","XpryDt","StrkPric","OptnTp",
          "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst"]
    out=io.StringIO();writer=csv.DictWriter(out,fieldnames=keys)
    writer.writeheader()
    for kind in ("IDF","IDO","STO"):
        writer.writerow({"TckrSymb":"NIFTY","FinInstrmTp":kind,
          "BizDt":"2025-12-16" if wrongday else "2025-12-15",
          "XpryDt":"2025-12-30","StrkPric":"25000","OptnTp":"CE",
          "OpnPric":"100","HghPric":"102","LwPric":"99","ClsPric":"101",
          "TtlTradgVol":"20","OpnIntrst":"30"})
    zipped=io.BytesIO()
    with zipfile.ZipFile(zipped,"w",zipfile.ZIP_DEFLATED) as z:
        z.writestr("BhavCopy_NSE_FO_0_0_0_20251215_F_0000.csv",out.getvalue())
    return zipped.getvalue()

class NseArchiveProbeTests(unittest.TestCase):
    def test_one_day_index_rows(self):
        r=mod.read_udiff(fixture(),date(2025,12,15))
        self.assertEqual(r["indexFutures"],1)
        self.assertEqual(r["indexOptions"],1)
        self.assertFalse(r["localStorage"])
        self.assertFalse(r["rawDataPublished"])
        self.assertNotIn("25000",str(r))
    def test_wrong_day_rejected(self):
        with self.assertRaisesRegex(ValueError,"UDIFF_WRONG_TRADING_DAY"):
            mod.read_udiff(fixture(wrongday=True),date(2025,12,15))
    def test_unapproved_date_rejected(self):
        with self.assertRaisesRegex(ValueError,"NOT_APPROVED_ARCHIVE_DAY"):
            mod.archive_url(date(2025,12,16))
    def test_invalid_zip_rejected(self):
        with self.assertRaisesRegex(ValueError,"INVALID_ARCHIVE_PAYLOAD"):
            mod.read_udiff(b"fake",date(2025,12,15))

if __name__=="__main__":unittest.main()
