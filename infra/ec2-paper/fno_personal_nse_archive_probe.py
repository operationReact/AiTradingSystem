#!/usr/bin/env python3
"""Bounded personal-research probe of NSE public F&O UDiFF day archive.

Fetches one exact file in memory; no tokens, price export, persisted files,
provider API calls, database access, authentication bypass or synthetic data.
If the NSE archive rejects a request, report BLOCKED and stop.
"""
from __future__ import annotations

from collections import Counter
from datetime import date
import csv
import io
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile

MARKER = "MINTTICK_NSE_FO_PUBLIC_ARCHIVE_V1:"
ARCHIVE_HOST = "nsearchives.nseindia.com"
PROBE_DAY = date(2025, 12, 15)
MAX_ZIP_BYTES = 8 * 1024 * 1024
MAX_CSV_BYTES = 45 * 1024 * 1024
MAX_ROWS = 180000

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def archive_url(day):
    if day != PROBE_DAY:
        raise ValueError("NOT_APPROVED_ARCHIVE_DAY")
    return "https://" + ARCHIVE_HOST + "/content/fo/BhavCopy_NSE_FO_0_0_0_" + day.strftime("%Y%m%d") + "_F_0000.csv.zip"

def read_udiff(blob, day):
    if not blob.startswith(b"PK\x03\x04") or len(blob)>MAX_ZIP_BYTES:
        raise ValueError("INVALID_ARCHIVE_PAYLOAD")
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        names = z.infolist()
        if len(names)!=1 or not re.fullmatch(r"BhavCopy_NSE_FO_0_0_0_\d{8}_F_0000.csv",names[0].filename):
            raise ValueError("UNEXPECTED_ARCHIVE_MEMBER")
        if names[0].file_size>MAX_CSV_BYTES or names[0].is_dir():
            raise ValueError("ARCHIVE_UNBOUNDED")
        data=z.read(names[0])
        if len(data)>MAX_CSV_BYTES:
            raise ValueError("ARCHIVE_UNBOUNDED")
    file=io.StringIO(data.decode("utf-8-sig"),newline="")
    rows=csv.DictReader(file)
    essential={"TckrSymb","FinInstrmTp","BizDt","XpryDt","StrkPric","OptnTp",
               "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst"}
    if rows.fieldnames is None or not essential.issubset(rows.fieldnames):
        raise ValueError("UDIFF_SCHEMA_MISMATCH")
    found=Counter()
    for n,row in enumerate(rows):
        if n>=MAX_ROWS:
            raise ValueError("UDIFF_ROWS_LIMIT")
        if row["BizDt"]!=day.isoformat():
            raise ValueError("UDIFF_WRONG_TRADING_DAY")
        if row["TckrSymb"].strip()=="NIFTY":
            t=row["FinInstrmTp"].strip()
            if t in ("IDF","IDO"):
                found[t]+=1
    return {"state":"FETCHED","day":day.isoformat(),"indexFutures":found["IDF"],
            "indexOptions":found["IDO"],
            "requiredFields":len(essential),"archiveBytes":len(blob),
            "rawDataPublished":False,"localStorage":False}

def run():
    url=archive_url(PROBE_DAY)
    parsed=urllib.parse.urlparse(url)
    if parsed.scheme!="https" or parsed.netloc!=ARCHIVE_HOST:
        raise ValueError("INVALID_ARCHIVE_ORIGIN")
    opener=urllib.request.build_opener(NoRedirect())
    req=urllib.request.Request(url,method="GET",headers={
        "Accept":"application/zip",
        "User-Agent":"MintTick-Personal-Research/1.0",
    })
    try:
        with opener.open(req,timeout=30) as res:
            if res.status!=200 or res.url!=url:
                raise ValueError("ARCHIVE_HTTP_NOT_OK")
            size=res.headers.get("Content-Length")
            if size and (not size.isdecimal() or int(size)>MAX_ZIP_BYTES):
                raise ValueError("ARCHIVE_UNBOUNDED")
            blob=res.read(MAX_ZIP_BYTES+1)
    except urllib.error.HTTPError as e:
        if e.code==404:
            return {"state":"NOT_FOUND","day":PROBE_DAY.isoformat()}
        return {"state":"SOURCE_REJECTED","day":PROBE_DAY.isoformat()}
    except (urllib.error.URLError,TimeoutError,OSError):
        return {"state":"TRANSPORT_UNAVAILABLE","day":PROBE_DAY.isoformat()}
    return read_udiff(blob,PROBE_DAY)

if __name__=="__main__":
    try:
        report=run()
        print(MARKER+json.dumps(report,sort_keys=True,separators=(",",":")))
    except (ValueError,OSError,zipfile.BadZipFile,UnicodeError):
        print(MARKER+json.dumps({"state":"INVALID_SOURCE_RESPONSE","day":PROBE_DAY.isoformat()},
                                sort_keys=True,separators=(",",":")))
