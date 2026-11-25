#!/usr/bin/env python3
"""Owner-private NIFTY futures 60-day original Upstox HTTP archive.
No orders, permissions change, file export, or historical E2E pass claim.
"""
from __future__ import annotations
import base64,gzip,hashlib,json,os,re,sqlite3,subprocess,sys,time
import urllib.parse,urllib.request,urllib.error
from datetime import datetime,timedelta
from pathlib import Path

ROOT=Path("/opt/aitradingsystem-paper")
DIR=ROOT/"fno-history"
DB=DIR/"str176-private-futures-60day.sqlite"
INSTANCE="i-09c01b470fb39b85a"
REQUEST=Path("infra/ec2-paper/requests/str176-private-futures-60day.json")
MARKER="MINTTICK_STR176_REAL_60DAY:"
EXACT={"kind":"minttick-str176-owner-futures-60day-private-v1",
       "account":"098859530205","region":"ap-south-1","instance_id":INSTANCE,
       "max_contract_months":12,"max_provider_gets":25,
       "no_market_export":True,"no_orders":True,"no_rights_change":True}
URL="https://api.upstox.com/v2/expired-instruments"
TABLE="""CREATE TABLE IF NOT EXISTS original_futures(
    contract_key TEXT NOT NULL,expiry TEXT NOT NULL,
    lot_size INTEGER NOT NULL,original_http BLOB NOT NULL,
    original_sha256 TEXT NOT NULL,first_captured_at REAL NOT NULL,
    verified_candles INTEGER NOT NULL,
    PRIMARY KEY(contract_key,expiry)
)"""

class Blocked(Exception):pass

def original_rows(raw,key,start,expiry):
    from fno_history_backfill import Batch,validate_candles
    try:
        document=json.loads(raw)
        candles=document["data"]["candles"]
        if document.get("status")!="success" or not isinstance(candles,list):
            raise ValueError("INVALID_PROVIDER_PAYLOAD")
        rows=validate_candles(candles,Batch(0,key,"day",start,expiry,1))
        if len(rows)!=len(candles) or len(rows)<10 or len(rows)>65:
            raise ValueError("INVALID_FUTURES_DAILY_COHORT")
        return rows
    except (KeyError,ValueError,TypeError,UnicodeError):
        raise Blocked("INVALID_UPSTOX_DAILY_SOURCE") from None

def fetch(token,key,from_day,expiry,opener=None):
    from upstox_fno_backfill_adapters import NoRedirect
    path=("/historical-candle/"+urllib.parse.quote(key,safe="")
          +"/day/"+expiry.isoformat()+"/"+from_day.isoformat())
    url=URL+path
    parts=urllib.parse.urlsplit(url)
    if parts.scheme!="https" or parts.netloc!="api.upstox.com" or len(url)>512:
        raise Blocked("HISTORY_SOURCE_HOST_INVALID")
    req=urllib.request.Request(url,method="GET",headers={
        "Authorization":"Bearer "+token,"Accept":"application/json",
        "User-Agent":"MintTick-Upstox-ReadOnly-Diagnostics/1.0"})
    op=opener or urllib.request.build_opener(NoRedirect())
    try:
        with op.open(req,timeout=20) as response:
            if response.status!=200: raise Blocked("HISTORY_SOURCE_REJECTED")
            raw=response.read(4*1024*1024+1)
    except urllib.error.HTTPError as error:
        if error.code==429:raise Blocked("UPSTOX_RATE_LIMITED") from None
        if error.code in (401,403):raise Blocked("UPSTOX_TOKEN_REJECTED") from None
        raise Blocked("HISTORY_SOURCE_REJECTED") from None
    except (urllib.error.URLError,TimeoutError,OSError):
        raise Blocked("HISTORY_TRANSPORT") from None
    if not 0<len(raw)<=4*1024*1024:
        raise Blocked("UPSTOX_BODY_LIMIT")
    return raw

def archive_one(db,key,expiry,lot,body,first):
    rows=original_rows(body,key,first,expiry)
    digest=hashlib.sha256(body).hexdigest()
    old=db.execute("SELECT original_http,original_sha256,verified_candles "
                   "FROM original_futures WHERE contract_key=? AND expiry=?",
                   (key,expiry.isoformat())).fetchone()
    if old:
        if old!=(body,digest,len(rows)):
            raise Blocked("ORIGINAL_IMMUTABLE_SOURCE_CONFLICT")
        return False,len(rows)
    db.execute("BEGIN IMMEDIATE")
    try:
        db.execute("INSERT INTO original_futures VALUES(?,?,?,?,?,?,?)",
                   (key,expiry.isoformat(),lot,body,digest,time.time(),len(rows)))
        db.execute("COMMIT")
    except BaseException:
        db.execute("ROLLBACK")
        raise
    return True,len(rows)

def collect():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise Blocked("EXACT_OWNER_INSTANCE_REQUIRED")
    if (not DIR.is_dir() or DIR.is_symlink()
            or DIR.stat().st_mode & 0o077
            or DB.is_symlink()):
        raise Blocked("PRIVATE_CACHE_DIRECTORY_NOT_SECURE")
    # Reuse existing approved personal scope and credential from SSM.
    sys.path.insert(0,str(ROOT/"infra/ec2-paper"))
    import fno_private_bulk_worker as owner
    owner.grant().require("day",owner.today())
    token=owner.ssm_token()
    discovery=owner.Discovery(token)
    underlying=urllib.parse.quote(owner.UNDERLYING,safe="")
    expiries=owner.monthly_expiries(
        discovery.get("/expiries?instrument_key="+underlying),owner.today())
    if len(expiries)!=12:
        raise Blocked("FUTURES_EXPIRIES_INCOMPLETE")
    db=sqlite3.connect(str(DB),timeout=20,isolation_level=None)
    os.chmod(DB,0o600)
    db.execute("PRAGMA busy_timeout=20000")
    db.execute(TABLE)
    good=0;new=0;rows=0;quarantined=0
    try:
        for expiry in expiries:
            qry="?instrument_key="+underlying+"&expiry_date="+expiry.isoformat()
            metadata=discovery.get("/future/contract"+qry)
            matches=sorted((rec for rec in metadata
                if owner._key(rec,expiry,"FUT")),key=lambda r:r["instrument_key"])
            if len(matches)!=1:
                quarantined+=1
                continue
            item=matches[0];key=item["instrument_key"];lot=item.get("lot_size")
            if type(lot) is not int or not 1<=lot<=100000:
                quarantined+=1
                continue
            start=expiry-timedelta(days=60)
            # Avoid a redundant paid provider candle GET for a valid
            # existing immutable receipt. Never revise old source bytes.
            old=db.execute("SELECT original_http FROM original_futures "
                "WHERE contract_key=? AND expiry=?",(key,expiry.isoformat())).fetchone()
            if old:
                raw=old[0]
            else:
                # Existing discovery API uses 2.5s GET pacing; bound history too.
                time.sleep(2.5)
                try:raw=fetch(token,key,start,expiry)
                except Blocked as error:
                    if str(error) in ("UPSTOX_RATE_LIMITED","UPSTOX_TOKEN_REJECTED"):
                        raise
                    quarantined+=1
                    continue
            try:was_new,n=archive_one(db,key,expiry,lot,raw,start)
            except Blocked:
                quarantined+=1
                continue
            new+=int(was_new);good+=1;rows+=n
        if not 1<=good<=12 or not 10<=rows<=780 or good+quarantined!=12:
            raise Blocked("ORIGINAL_SOURCE_COHORT_NOT_VERIFIED")
        return {"state":"OWNER_PRIVATE_FUTURES_HTTP_CAPTURED",
                "futureMonths":good,"newMonths":new,"quarantinedMonths":quarantined,
                "dailyRows":rows,"historicalE2ECertified":0,
                "brokerOrders":False,"rawSourceExported":False}
    finally:
        db.close()

def run_host():
    try: result=collect()
    except (Blocked,OSError,ValueError,KeyError,TypeError,sqlite3.Error,
            RuntimeError,UnicodeError):
        result={"state":"OWNER_PRIVATE_SOURCE_BLOCKED",
                "historicalE2ECertified":0,"brokerOrders":False,
                "rawSourceExported":False}
    print(MARKER+json.dumps(result,separators=(",",":"),sort_keys=True))
    if result["state"]!="OWNER_PRIVATE_FUTURES_HTTP_CAPTURED":
        raise SystemExit(2)

def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink()
            or REQUEST.stat().st_size>1024
            or json.loads(REQUEST.read_text())!=EXACT):
        raise ValueError("EXACT_REQUEST_REQUIRED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    blob=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(blob)>20000 or not re.fullmatch("[A-Za-z0-9+/=]+",blob):
        raise ValueError("SCRIPT_SIZE_INVALID")
    cmd=("set -eu\nPYTHONDONTWRITEBYTECODE=1 "
         "printf '%s' '"+blob+"' | base64 -d | gzip -d | "
         "PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    sent=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
              "--instance-ids",INSTANCE,"--timeout-seconds","600",
              "--comment","Owner STR176 60-day futures historical capture no orders",
              "--parameters",json.dumps({"commands":[cmd]}))
    cid=(sent.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch("[0-9a-f-]{36}",cid):
        raise ValueError("SSM_COMMAND_INVALID")
    for _ in range(115):
        time.sleep(5)
        q=_aws("ssm","get-command-invocation",
               "--instance-id",INSTANCE,"--command-id",cid)
        if q.get("Status")=="Success":
            txt=q.get("StandardOutputContent","")
            values=[x[len(MARKER):] for x in txt.splitlines() if x.startswith(MARKER)]
            if len(values)!=1 or len(values[0])>450:
                raise ValueError("SOURCE_RECEIPT_INVALID")
            result=json.loads(values[0])
            if (result.get("state")!="OWNER_PRIVATE_FUTURES_HTTP_CAPTURED"
                    or not 1<=result.get("futureMonths",0)<=12
                    or result.get("historicalE2ECertified")!=0
                    or result.get("brokerOrders") is not False):
                raise ValueError("SOURCE_RECEIPT_INVALID")
            print("STR176_PRIVATE_FUTURES:months="+str(result["futureMonths"])
                  +";new="+str(result["newMonths"])
                  +";quarantine="+str(result["quarantinedMonths"])
                  +";dailyRows="+str(result["dailyRows"])
                  +";certified=0/43;orders=false")
            return
        if q.get("Status") in ("Failed","Cancelled","TimedOut"):
            raise ValueError("OWNER_SOURCE_CAPTURE_FAILED")
        if q.get("Status") not in ("Pending","InProgress","Delayed"):
            raise ValueError("OWNER_SOURCE_CAPTURE_STATUS_UNKNOWN")
    raise ValueError("OWNER_SOURCE_CAPTURE_TIMEOUT")

if __name__=="__main__":
    if sys.argv[1:]==["host"]:run_host()
    elif sys.argv[1:]==["runner"]:runner()
    else:raise SystemExit("USAGE: host or runner")
