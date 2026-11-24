#!/usr/bin/env python3
"""Exact-owner, private NSE EOD source capture; no historical backdating.
Raw exchange bytes remain on the existing encrypted host. Owner confirmed
private acquisition/retention; this is NOT independent rights verification,
a provider signature, calendar completeness, or strategy certification.
"""
from __future__ import annotations
import base64
from datetime import date, datetime, time as dt_time, timedelta, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import time
import types
import zipfile
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

ROOT=Path("/opt/aitradingsystem-paper")
PRIVATE=ROOT/"fno-history"/"nse-forward-observations"
DB=PRIVATE/"original-eod.sqlite"
INSTANCE="i-09c01b470fb39b85a"
ACCOUNT="098859530205"
REGION="ap-south-1"
IST=ZoneInfo("Asia/Kolkata")
MAX_ZIP=8*1024*1024
MAX_DB=128*1024*1024
REQUEST=Path("infra/ec2-paper/requests/str176-forward-nse-capture.json")
MARKER="MINTTICK_STR176_FORWARD_NSE:"
KIND="minttick-str176-owner-forward-nse-original-v1"
EXACT={"kind":KIND,"account":ACCOUNT,"region":REGION,"instance_id":INSTANCE,
       "session_date":"2026-11-23","max_archive_gets":1,
       "owner_confirmed_private_research_retention":True,
       "no_market_export":True,"no_orders":True,"no_rights_change":True}
SCHEMA="""CREATE TABLE IF NOT EXISTS original_exchange_eod(
 session_date TEXT NOT NULL,
 source_url TEXT NOT NULL,
 original_zip BLOB NOT NULL,
 sha256 TEXT NOT NULL,
 observed_at_utc TEXT NOT NULL,
 http_last_modified TEXT,
 nifty_futures INTEGER NOT NULL,
 nifty_options INTEGER NOT NULL,
 PRIMARY KEY(session_date,sha256))"""

FAILURE_MARKER="MINTTICK_STR176_FORWARD_NSE_FAILURE:"
SAFE_REASONS=frozenset("""SOURCE_SCOPE_INVALID SOURCE_OBSERVATION_WINDOW_EXPIRED
SOURCE_HTTP_REJECTED SOURCE_TOO_LARGE SOURCE_FILE_NOT_AVAILABLE SOURCE_TRANSPORT_UNAVAILABLE
PRIVATE_DIRECTORY_REQUIRED SOURCE_NIFTY_COHORT_MISSING PRIVATE_DB_INVALID_OR_FULL
ORIGINAL_SOURCE_READBACK_FAILED OBSERVATION_TIME_INVALID EXACT_PRIVATE_HOST_REQUIRED
SOURCE_RECEIPT_INVALID OWNER_REQUEST_REQUIRED TRANSPORT_BOUND SSM_COMMAND_INVALID
HOST_CAPTURE_FAILED HOST_CAPTURE_TIMEOUT SSM_STATUS_INVALID MODE_INVALID
SOURCE_ARCHIVE_VALIDATION_FAILED PRIVATE_STORAGE_UNAVAILABLE
HOST_CAPTURE_NO_SAFE_RECEIPT HOST_CAPTURE_TIMED_OUT HOST_CAPTURE_CANCELLED
AWS_PREFLIGHT_REJECTED SOURCE_CAPTURE_UNEXPECTED_FAILURE""".split())

class Blocked(Exception):pass

def safe_reason(exc):
    if isinstance(exc,Blocked) and str(exc) in SAFE_REASONS:
        return str(exc)
    if isinstance(exc,(OSError,sqlite3.Error)):
        return "PRIVATE_STORAGE_UNAVAILABLE"
    return "SOURCE_CAPTURE_UNEXPECTED_FAILURE"

def failure_receipt(raw):
    if not isinstance(raw,str) or len(raw)>2048:
        return "HOST_CAPTURE_NO_SAFE_RECEIPT"
    rows=[x[len(FAILURE_MARKER):] for x in raw.splitlines()
          if x.startswith(FAILURE_MARKER)]
    return rows[0] if len(rows)==1 and rows[0] in SAFE_REASONS else "HOST_CAPTURE_NO_SAFE_RECEIPT"

def transport():
    # Ship the exact reviewed parser along with this runner. The previously
    # deployed host need not contain the corresponding parser module.
    parser=Path(__file__).with_name("fno_personal_nse_archive_probe.py").read_text()
    source=Path(__file__).read_text()
    return ("import sys,types\n"
            "m=types.ModuleType('fno_personal_nse_archive_probe');"
            "sys.modules[m.__name__]=m;exec("+repr(parser)+",m.__dict__)\n"
            "exec("+repr(source)+",globals())\n").encode()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def require_scope(day,now):
    if now.tzinfo is None or day!=date.fromisoformat(EXACT["session_date"]):
        raise Blocked("SOURCE_SCOPE_INVALID")
    # The final EOD file may still be missing. No assumption that a
    # date is a verified trading day; absence never means holiday.
    cutoff=datetime.combine(day,dt_time(18,30),tzinfo=IST)
    if not cutoff<=now.astimezone(IST)<=cutoff+timedelta(hours=36):
        raise Blocked("SOURCE_OBSERVATION_WINDOW_EXPIRED")

def acquire(day, opener=None):
    url=("https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_"
         +day.strftime("%Y%m%d")+"_F_0000.csv.zip")
    req=urllib.request.Request(url,method="GET",headers={
        "Accept":"application/zip","User-Agent":"MintTick-Personal-Research/1.0"})
    try:
        with (opener or urllib.request.build_opener(NoRedirect())).open(req,timeout=25) as r:
            if r.status!=200 or r.url!=url:raise Blocked("SOURCE_HTTP_REJECTED")
            n=r.headers.get("Content-Length")
            if n and (not n.isdecimal() or int(n)>MAX_ZIP):
                raise Blocked("SOURCE_TOO_LARGE")
            raw=r.read(MAX_ZIP+1)
            modified=r.headers.get("Last-Modified")
            # Header is merely metadata, never used as original publication proof.
            if modified and (len(modified)>100 or not modified.isascii()
                             or any(ord(c)<32 for c in modified)):modified=None
    except urllib.error.HTTPError as e:
        raise Blocked("SOURCE_FILE_NOT_AVAILABLE" if e.code==404 else "SOURCE_HTTP_REJECTED") from None
    except (urllib.error.URLError,OSError,TimeoutError):
        raise Blocked("SOURCE_TRANSPORT_UNAVAILABLE") from None
    observed=datetime.now(timezone.utc)
    if not 1<=len(raw)<=MAX_ZIP:raise Blocked("SOURCE_TOO_LARGE")
    return url,raw,observed,modified

def private_dir(path):
    if path.is_symlink() or not path.is_dir() or path.stat().st_mode&0o077:
        raise Blocked("PRIVATE_DIRECTORY_REQUIRED")

def persist(path,day,url,raw,observed,modified,counts):
    if not 1<=len(raw)<=MAX_ZIP or counts["indexFutures"]<1 or counts["indexOptions"]<1:
        raise Blocked("SOURCE_NIFTY_COHORT_MISSING")
    private_dir(path.parent)
    if not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        os.close(fd)
    if (path.is_symlink() or not stat.S_ISREG(path.stat().st_mode)
            or path.stat().st_mode&0o077 or path.stat().st_size>MAX_DB-MAX_ZIP):
        raise Blocked("PRIVATE_DB_INVALID_OR_FULL")
    digest=hashlib.sha256(raw).hexdigest()
    with sqlite3.connect(str(path),timeout=15) as db:
        db.execute("PRAGMA busy_timeout=15000")
        db.execute("PRAGMA synchronous=FULL")
        db.execute(SCHEMA)
        db.execute("BEGIN IMMEDIATE")
        added=db.execute("INSERT OR IGNORE INTO original_exchange_eod VALUES(?,?,?,?,?,?,?,?)",
            (day.isoformat(),url,raw,digest,observed.isoformat(),modified,
             counts["indexFutures"],counts["indexOptions"])).rowcount
        row=db.execute("SELECT original_zip,observed_at_utc FROM original_exchange_eod "
            "WHERE session_date=? AND sha256=?",(day.isoformat(),digest)).fetchone()
        if row is None or hashlib.sha256(row[0]).hexdigest()!=digest or row[0]!=raw:
            raise Blocked("ORIGINAL_SOURCE_READBACK_FAILED")
        first=datetime.fromisoformat(row[1])
        if first.tzinfo is None or first>observed:raise Blocked("OBSERVATION_TIME_INVALID")
    return {"state":"ORIGINAL_EXCHANGE_EOD_RETAINED_NOT_CERTIFIED","newArchive":added==1,
            "sessionDate":day.isoformat(),"firstObservedAt":first.isoformat(),
            "niftyFutures":counts["indexFutures"],"niftyOptions":counts["indexOptions"],
            "originalBytes":len(raw),"originalPublicationVerified":False,
            "certificationStoreRowsAdded":0,"certifiedStrategies":0,"orders":False}

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise Blocked("EXACT_PRIVATE_HOST_REQUIRED")
    os.umask(0o077)
    day=date.fromisoformat(EXACT["session_date"])
    require_scope(day,datetime.now(timezone.utc))
    private_dir(PRIVATE.parent)
    PRIVATE.mkdir(mode=0o700,exist_ok=True)
    private_dir(PRIVATE)
    sys.path.insert(0,str(ROOT/"infra/ec2-paper"))
    from fno_personal_nse_archive_probe import read_udiff
    url,raw,observed,modified=acquire(day)
    require_scope(day,observed)
    try:
        counts=read_udiff(raw,day)
    except (ValueError,zipfile.BadZipFile,UnicodeError,KeyError,TypeError,OSError):
        raise Blocked("SOURCE_ARCHIVE_VALIDATION_FAILED") from None
    report=persist(DB,day,url,raw,observed,modified,counts)
    print(MARKER+json.dumps(report,separators=(",",":"),sort_keys=True),flush=True)

def validate_report(data):
    keys={"state","newArchive","sessionDate","firstObservedAt","niftyFutures",
          "niftyOptions","originalBytes","originalPublicationVerified",
          "certificationStoreRowsAdded","certifiedStrategies","orders"}
    if (type(data) is not dict or set(data)!=keys
        or data["state"]!="ORIGINAL_EXCHANGE_EOD_RETAINED_NOT_CERTIFIED"
        or type(data["newArchive"]) is not bool
        or data["sessionDate"]!=EXACT["session_date"]
        or any(type(data[k]) is not int or not 1<=data[k]<=180000
               for k in ("niftyFutures","niftyOptions"))
        or type(data["originalBytes"]) is not int or not 1<=data["originalBytes"]<=MAX_ZIP
        or any(data[k] is not False for k in ("originalPublicationVerified","orders"))
        or any(type(data[k]) is not int or data[k]!=0
               for k in ("certificationStoreRowsAdded","certifiedStrategies"))):
        raise Blocked("SOURCE_RECEIPT_INVALID")
    require_scope(date.fromisoformat(data["sessionDate"]),datetime.fromisoformat(data["firstObservedAt"]))
    return data

def runner():
    if (REQUEST.is_symlink() or not REQUEST.is_file() or REQUEST.stat().st_size>=1024
            or json.loads(REQUEST.read_text())!=EXACT):
        raise Blocked("OWNER_REQUEST_REQUIRED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    try:verify_aws()
    except (ValueError,RuntimeError):raise Blocked("AWS_PREFLIGHT_REJECTED") from None
    payload=base64.b64encode(gzip.compress(transport(),mtime=0)).decode()
    if len(payload)>20000 or not re.fullmatch(r"[A-Za-z0-9+/=]+",payload):
        raise Blocked("TRANSPORT_BOUND")
    shell=("set -eu\numask 077\nprintf '%s' '"+payload+
           "' | base64 -d | gzip -d | PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    sent=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
              "--instance-ids",INSTANCE,"--timeout-seconds","90",
              "--comment","Owner private NSE forward original observation no orders",
              "--parameters",json.dumps({"commands":[shell]}))
    cid=(sent.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch(r"[0-9a-f-]{36}",cid):
        raise Blocked("SSM_COMMAND_INVALID")
    for _ in range(30):
        time.sleep(5)
        result=_aws("ssm","get-command-invocation","--instance-id",INSTANCE,"--command-id",cid)
        status=result.get("Status")
        if status=="Success":
            out=result.get("StandardOutputContent","")
            rows=[x[len(MARKER):] for x in out.splitlines() if x.startswith(MARKER)]
            if len(out)>2048 or len(rows)!=1:raise Blocked("SOURCE_RECEIPT_INVALID")
            print(MARKER+json.dumps(validate_report(json.loads(rows[0])),sort_keys=True),flush=True)
            return
        if status in ("Failed","TimedOut","Cancelled"):
            if status=="TimedOut":raise Blocked("HOST_CAPTURE_TIMED_OUT")
            if status=="Cancelled":raise Blocked("HOST_CAPTURE_CANCELLED")
            raise Blocked(failure_receipt(result.get("StandardOutputContent","")))
        if status not in ("Pending","InProgress","Delayed"):
            raise Blocked("SSM_STATUS_INVALID")
    raise Blocked("HOST_CAPTURE_TIMEOUT")

if __name__=="__main__":
    try:
        if sys.argv[1:]==["host"]:host()
        elif sys.argv[1:]==["runner"]:runner()
        else:raise Blocked("MODE_INVALID")
    except (Blocked,OSError,ValueError,KeyError,TypeError,sqlite3.Error,ImportError,RuntimeError) as exc:
        print(FAILURE_MARKER+safe_reason(exc),flush=True)
        raise SystemExit(2)
