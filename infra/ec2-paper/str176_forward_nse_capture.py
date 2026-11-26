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
FIXED_REQUEST={"kind":KIND,"account":ACCOUNT,"region":REGION,"instance_id":INSTANCE,
       "max_archive_gets":1,
       "owner_confirmed_private_research_retention":True,
       "no_market_export":True,"no_orders":True,"no_rights_change":True}
BACKFILL_DAYS=tuple(date(2026,11,day) for day in (17,18,19,20))
BACKFILL_START=datetime(2026,11,26,18,30,tzinfo=IST)
BACKFILL_END=BACKFILL_START+timedelta(hours=36)
STR177_BACKFILL_DAYS=tuple(date(2026,11,day) for day in (12,13,16))
RESEARCH_DAY=date(2026,11,27)
RESEARCH_EXPIRY=date(2026,12,29)
RESEARCH_DAYS=BACKFILL_DAYS+tuple(date(2026,11,day) for day in (23,25,26))
STR177_DAYS=STR177_BACKFILL_DAYS+RESEARCH_DAYS
JAVA_EVALUATOR=Path("backend/src/main/java/in/aitradingsystem/tradingrooms/FnoFuturesMeanReversionV1.java")
STR177_MARKER="MINTTICK_STR177_RETAINED_ARCHIVE_RESEARCH:"
RESEARCH_MARKER="MINTTICK_STR176_RETAINED_ARCHIVE_RESEARCH:"
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
AWS_PREFLIGHT_REJECTED STR177_JAVA_UNAVAILABLE STR177_EVALUATOR_FAILED SOURCE_CAPTURE_UNEXPECTED_FAILURE""".split())

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

def request_date(data,now):
    if (type(data) is not dict or set(data)!=(set(FIXED_REQUEST)|{"session_date"})
            or any(type(data.get(k)) is not type(v) or data.get(k)!=v
                   for k,v in FIXED_REQUEST.items())):
        raise Blocked("OWNER_REQUEST_REQUIRED")
    raw_day=data.get("session_date")
    if type(raw_day) is not str or not re.fullmatch(r"\d{4}-\d{2}-\d{2}",raw_day):
        raise Blocked("OWNER_REQUEST_REQUIRED")
    try:day=date.fromisoformat(raw_day)
    except ValueError:raise Blocked("OWNER_REQUEST_REQUIRED") from None
    if day.isoformat()!=raw_day:raise Blocked("OWNER_REQUEST_REQUIRED")
    require_scope(day,now)
    return day

def _unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise Blocked("OWNER_REQUEST_REQUIRED")
        result[key]=value
    return result

def load_request(path,now):
    if path.is_symlink() or not path.is_file() or path.stat().st_size>=1024:
        raise Blocked("OWNER_REQUEST_REQUIRED")
    try:data=json.loads(path.read_text(),object_pairs_hook=_unique_object)
    except (ValueError,TypeError,UnicodeError):raise Blocked("OWNER_REQUEST_REQUIRED") from None
    if (type(data) is dict and set(data)==set(FIXED_REQUEST)|{"research_decision_date"}
            and all(type(data[k]) is type(value) and data[k]==value for k,value in FIXED_REQUEST.items())
            and data["research_decision_date"]==RESEARCH_DAY.isoformat()
            and BACKFILL_START<=now<=BACKFILL_END):
        return "research"
    if (type(data) is dict and set(data)==set(FIXED_REQUEST)|{"str177_decision_date"}
            and all(type(data[k]) is type(value) and data[k]==value for k,value in FIXED_REQUEST.items())
            and data["str177_decision_date"]==RESEARCH_DAY.isoformat()
            and BACKFILL_START<=now<=BACKFILL_END):
        return "research177"
    if type(data) is dict and "session_dates" in data:
        requested=data.pop("session_dates")
        if not BACKFILL_START<=now<=BACKFILL_END:
            raise Blocked("OWNER_REQUEST_REQUIRED")
        for allowed_days in (BACKFILL_DAYS,STR177_BACKFILL_DAYS):
            if requested==[day.isoformat() for day in allowed_days]:
                return tuple(request_date({**data,"session_date":day.isoformat()},now) for day in allowed_days)
        raise Blocked("OWNER_REQUEST_REQUIRED")
    return request_date(data,now)

def transport(research=False, str177=False):
    # Ship the exact reviewed parser along with this runner. The previously
    # deployed host need not contain the corresponding parser module.
    import ast
    modules = {
        "fno_personal_nse_archive_probe": None,
    }
    if research:
        modules = {
            "str176_expanded_upstox_research": {"bps", "signal", "REASON_OUTCOME"},
            "fno_personal_nse_december_compare": {
                "dec", "parse_udiff", "MAX_ARCHIVE", "MAX_CSV", "EXPIRY",
            },
        }
    parts = ["import sys,types"]
    if str177:
        modules.pop("str176_expanded_upstox_research")
        java = JAVA_EVALUATOR.read_text()
        split = java.index("/**")
        launcher = '''
class ArchiveMeanReversion {
    public static void main(String[] args) {
        var input = new java.util.Scanner(System.in);
        var asOf = Instant.parse(input.next());
        var expiry = LocalDate.parse(input.next());
        var lot = input.nextInt();
        var rows = new java.util.ArrayList<FnoFuturesMeanReversionV1.CompletedSession>();
        String instrument = "NSE_FO|NIFTY-" + expiry;
        while (input.hasNext() && rows.size() < 10) {
            var day = LocalDate.parse(input.next());
            var observed = Instant.parse(input.next());
            rows.add(new FnoFuturesMeanReversionV1.CompletedSession(day, true, instrument,
                "NSE_RETAINED_EOD", new BigDecimal(input.next()), new BigDecimal(input.next()),
                new BigDecimal(input.next()), new BigDecimal(input.next()), true, observed,
                new BigDecimal(input.next()).longValueExact(),
                new BigDecimal(input.next()).longValueExact(), observed));
        }
        if (rows.size() != 10 || input.hasNext()) throw new IllegalArgumentException("Session count");
        var contract = new FnoFuturesMeanReversionV1.Contract(instrument, "NSE_INDEX|Nifty 50",
            rows.get(0).sessionDate(), expiry, lot, true);
        var thresholds = new FnoFuturesMeanReversionV1.Thresholds(
            25, 200, 60, 100, 25, 9000, 1500, 1000, 900, 5000);
        var decision = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(contract, rows, thresholds, asOf);
        System.out.println(decision.status() + " " + decision.pattern() + " " + decision.reasonCode());
    }
}
'''
        java = java[:split] + launcher + java[split:].replace(
            "public final class FnoFuturesMeanReversionV1", "final class FnoFuturesMeanReversionV1", 1
        )
        import subprocess
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)/"ArchiveMeanReversion.java"
            source.write_text(java)
            subprocess.run(["javac","--release","17","-g:none","-d",temp,str(source)],
                           check=True,capture_output=True,timeout=30)
            jar = Path(temp)/"archive-research.jar"
            with zipfile.ZipFile(jar,"w") as archive:
                for item in sorted(Path(temp).rglob("*.class")):
                    archive.write(item,item.relative_to(temp).as_posix())
            parts.append("import base64\nSTR177_CLASSES=base64.b64decode("+repr(base64.b64encode(jar.read_bytes()))+")")
    for name, selected in modules.items():
        body = Path(__file__).with_name(name + ".py").read_text()
        if selected is not None:
            nodes = []
            for node in ast.parse(body).body:
                if (isinstance(node, (ast.Import, ast.ImportFrom))
                    or isinstance(node, ast.FunctionDef) and node.name in selected
                    or isinstance(node, ast.Assign) and any(
                        isinstance(target, ast.Name) and target.id in selected
                        for target in node.targets
                    )):
                    nodes.append(ast.get_source_segment(body, node))
            body = "\n".join(nodes)
        parts.append(f"m=types.ModuleType({name!r});sys.modules[m.__name__]=m;exec({body!r},m.__dict__)")
    script = Path(__file__).read_text()
    if str177:
        needed = {"safe_reason", "private_dir", "read_retained_window",
                  "str177_backend_image", "retained_str177_research"}
        script = "\n".join(
            ast.get_source_segment(script,node) for node in ast.parse(script).body
            if not isinstance(node, (ast.FunctionDef, ast.If))
            or isinstance(node, ast.FunctionDef) and node.name in needed
        ) + '\ntry:\n    retained_str177_research()\nexcept (Blocked,OSError,ValueError,KeyError,TypeError,sqlite3.Error,ImportError,RuntimeError) as exc:\n    print(FAILURE_MARKER+safe_reason(exc),flush=True)\n    raise SystemExit(2)'
    parts.append("exec(" + repr(script) + ",globals())")
    return "\n".join(parts).encode()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def require_scope(day,now):
    if now.tzinfo is None:
        raise Blocked("SOURCE_SCOPE_INVALID")
    if not isinstance(day,date) or isinstance(day,datetime):
        raise Blocked("SOURCE_SCOPE_INVALID")
    # The owner request selects a session date; this window prevents stale
    # archive retrieval. A missing file is a safe failure, never a holiday proof.
    cutoff=datetime.combine(day,dt_time(18,30),tzinfo=IST)
    if day in BACKFILL_DAYS+STR177_BACKFILL_DAYS and BACKFILL_START<=now<=BACKFILL_END:
        return
    if not cutoff<=now.astimezone(IST)<=cutoff+timedelta(hours=36):
        raise Blocked("SOURCE_OBSERVATION_WINDOW_EXPIRED")

def archive_url(day):
    return ("https://nsearchives.nseindia.in/content/fo/BhavCopy_NSE_FO_0_0_0_"
            +day.strftime("%Y%m%d")+"_F_0000.csv.zip")

def acquire(day, opener=None):
    url=archive_url(day)
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

def host(session_date):
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise Blocked("EXACT_PRIVATE_HOST_REQUIRED")
    os.umask(0o077)
    try:day=date.fromisoformat(session_date)
    except (TypeError,ValueError):raise Blocked("SOURCE_SCOPE_INVALID") from None
    if day.isoformat()!=session_date:raise Blocked("SOURCE_SCOPE_INVALID")
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

def read_retained_window(days, include_identity=False):
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise Blocked("EXACT_PRIVATE_HOST_REQUIRED")
    private_dir(PRIVATE)
    if DB.is_symlink() or not DB.is_file() or DB.stat().st_mode&0o077 or DB.stat().st_size>MAX_DB:
        raise Blocked("PRIVATE_DB_INVALID_OR_FULL")
    from contextlib import closing
    import fno_personal_nse_december_compare as nse
    nse.EXPIRY=RESEARCH_EXPIRY
    now=datetime.now(timezone.utc)
    decision_cutoff=datetime.combine(RESEARCH_DAY,dt_time(9,15),tzinfo=IST)
    window=[]
    observations=[]
    lots=set()
    identities=[]
    modified_headers=[]
    with closing(sqlite3.connect(DB.as_uri()+"?mode=ro",uri=True)) as db:
        for day in days:
            rows=db.execute(
                "SELECT original_zip,sha256,observed_at_utc,http_last_modified FROM original_exchange_eod "
                "WHERE session_date=? LIMIT 2",(day.isoformat(),)).fetchall()
            if len(rows)!=1:
                raise Blocked("ORIGINAL_SOURCE_READBACK_FAILED")
            raw,digest,stamp,modified=rows[0]
            modified_headers.append(modified)
            observed=datetime.fromisoformat(stamp)
            if observed.tzinfo is None or not observed<=min(now,decision_cutoff):
                raise Blocked("OBSERVATION_TIME_INVALID")
            if hashlib.sha256(raw).hexdigest()!=digest:
                raise Blocked("ORIGINAL_SOURCE_READBACK_FAILED")
            values=nse.parse_udiff(raw,day,include_identity)[("FUT",0,day)]
            op,hi,lo,close,volume,oi,lot=values[:7]
            if include_identity:
                identities.append(values[7:])
            if not 0<lo<=min(op,close)<=max(op,close)<=hi or not 0<=volume*lot<=10**12 or not 0<oi<=10**12:
                raise Blocked("SOURCE_ARCHIVE_VALIDATION_FAILED")
            window.append((day,op,hi,lo,close,volume*lot,oi))
            observations.append(observed)
            lots.add(lot)
    if len(lots)!=1 or any(lot!=int(lot) for lot in lots):
        raise Blocked("SOURCE_ARCHIVE_VALIDATION_FAILED")
    return window, observations, int(lots.pop()), identities, modified_headers

def retained_research():
    from str176_expanded_upstox_research import signal
    window, observations, _, _, _ = read_retained_window(RESEARCH_DAYS)
    outcome,reason=signal(window,RESEARCH_EXPIRY,RESEARCH_DAY,details=True)
    print(RESEARCH_MARKER+json.dumps({
        "state":"RETAINED_ARCHIVE_SIGNAL_RESEARCH_ONLY","sessions":len(window),
        "decisionDate":RESEARCH_DAY.isoformat(),"expiry":RESEARCH_EXPIRY.isoformat(),
        "latestObservedAt":max(observations).isoformat(),"outcome":outcome,
        "reason":reason,"certifiedStrategies":0,"orders":False
    },sort_keys=True),flush=True)

def str177_backend_image(subprocess):
    listed=subprocess.run(
        ["docker","ps","--format","{{.ID}} {{.Image}} {{.Names}} {{.Label \"com.docker.compose.service\"}}"],
        check=True,capture_output=True,text=True,timeout=8).stdout
    candidates=[]
    for line in listed.splitlines():
        fields=line.split()
        if len(fields)>=3 and (fields[-1]=="backend" or "backend" in fields[1].lower()):
            if re.fullmatch(r"[0-9a-f]{12}",fields[0]):
                candidates.append(fields[0])
    if len(candidates)!=1:
        raise Blocked("STR177_JAVA_UNAVAILABLE")
    image=subprocess.run(["docker","inspect","--format","{{.Image}}",candidates[0]],
                         check=True,capture_output=True,text=True,timeout=8).stdout.strip()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}",image):
        raise Blocked("STR177_JAVA_UNAVAILABLE")
    return image

def retained_str177_research():
    import subprocess
    import tempfile
    from shutil import which

    window, observations, lot, identities, modified_headers = read_retained_window(STR177_DAYS, include_identity=True)
    if len({identity for identity,_ in identities})!=1:
        raise Blocked("SOURCE_ARCHIVE_VALIDATION_FAILED")
    as_of = max(observations)
    lines = [f"{as_of.isoformat().replace('+00:00','Z')} {RESEARCH_EXPIRY} {lot}"]
    for row, observed in zip(window, observations):
        stamp = observed.isoformat().replace("+00:00","Z")
        lines.append(" ".join(map(str, (row[0], stamp, *row[1:]))))
    with tempfile.TemporaryDirectory(prefix="minttick-str177-") as temp:
        jar = Path(temp)/"archive-research.jar"
        jar.write_bytes(STR177_CLASSES)
        java = ["java","-cp","/tmp/archive-research.jar",
                "in.aitradingsystem.tradingrooms.ArchiveMeanReversion"]
        try:
            if which("java"):
                java[2]=str(jar)
                command=java
            else:
                image=str177_backend_image(subprocess)
                jar.chmod(0o444)
                command=["docker","run","--rm","--pull","never","-i","--network","none",
                         "--read-only","--memory","256m","--pids-limit","64",
                         "--mount",f"type=bind,src={jar},dst=/tmp/archive-research.jar,readonly",
                         "--entrypoint","java",image,*java[1:]]
            result = subprocess.run(command,input="\n".join(lines)+"\n",
                                    text=True,capture_output=True,timeout=30)
        except (OSError,subprocess.TimeoutExpired,subprocess.CalledProcessError):
            raise Blocked("STR177_JAVA_UNAVAILABLE") from None
    if result.returncode or len(result.stdout)>128:
        raise Blocked("STR177_EVALUATOR_FAILED")
    status,pattern,reason = result.stdout.split()
    from email.utils import parsedate_to_datetime
    header_count=0
    headers_before_next_decision=0
    for day,next_day,header in zip(STR177_DAYS,STR177_DAYS[1:]+(RESEARCH_DAY,),modified_headers):
        if not header:
            continue
        try:
            modified=parsedate_to_datetime(header)
        except (TypeError,ValueError,OverflowError):
            continue
        if modified.tzinfo is None:
            continue
        header_count+=1
        if datetime.combine(day,dt_time(15,40),tzinfo=IST)<=modified<=datetime.combine(next_day,dt_time(9,15),tzinfo=IST):
            headers_before_next_decision+=1
    outlier=None
    if reason=="FUT_MR_DAILY_CHANGE_OUTLIER":
        for index,(previous,current) in enumerate(zip(window,window[1:]),1):
            fields=[]
            if abs(current[4]-previous[4])*10000>previous[4]*1000:
                fields.append("CLOSE")
            if abs(current[6]-previous[6])*10000>previous[6]*5000:
                fields.append("OPEN_INTEREST")
            if fields:
                outlier={"previous":previous[0].isoformat(),"current":current[0].isoformat(),"fields":fields,
                         "exchangeDeltaMatches":current[6]-previous[6]==identities[index][1]}
                break
    print(STR177_MARKER+json.dumps({
        "state":"RETAINED_ARCHIVE_SIGNAL_RESEARCH_ONLY","sessions":len(window),
        "decisionDate":RESEARCH_DAY.isoformat(),"expiry":RESEARCH_EXPIRY.isoformat(),
        "latestObservedAt":as_of.isoformat(),"outcome":status,"pattern":pattern,
        "reason":reason,"outlier":outlier,"modifiedHeaders":header_count,
        "modifiedBeforeNextDecision":headers_before_next_decision,"certifiedStrategies":0,"orders":False
    },sort_keys=True),flush=True)

def validate_report(data,expected_day):
    keys={"state","newArchive","sessionDate","firstObservedAt","niftyFutures",
          "niftyOptions","originalBytes","originalPublicationVerified",
          "certificationStoreRowsAdded","certifiedStrategies","orders"}
    if (type(data) is not dict or set(data)!=keys
        or data["state"]!="ORIGINAL_EXCHANGE_EOD_RETAINED_NOT_CERTIFIED"
        or type(data["newArchive"]) is not bool
        or data["sessionDate"]!=expected_day.isoformat()
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
    selection=load_request(REQUEST,datetime.now(timezone.utc))
    if selection in ("research","research177"):
        capture_on_host(None, str177=selection=="research177")
        return
    days=selection if isinstance(selection,tuple) else (selection,)
    failed=False
    for day in days:
        try:
            capture_on_host(day)
        except Blocked as exc:
            if len(days)==1:
                raise
            print(FAILURE_MARKER+day.isoformat()+":"+safe_reason(exc),flush=True)
            failed=True
    if failed:
        raise Blocked("HOST_CAPTURE_FAILED")

def capture_on_host(day, str177=False):
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    try:verify_aws()
    except (ValueError,RuntimeError):raise Blocked("AWS_PREFLIGHT_REJECTED") from None
    payload=base64.b64encode(gzip.compress(transport(day is None, str177),mtime=0)).decode()
    if len(payload)>(40000 if str177 else 24000 if day is None else 20000) or not re.fullmatch(r"[A-Za-z0-9+/=]+",payload):
        raise Blocked("TRANSPORT_BOUND")
    mode=("research177" if str177 else "research") if day is None else "host "+day.isoformat()
    chunks=[payload[i:i+6000] for i in range(0,len(payload),6000)]
    encoded="\n".join("printf '%s' '"+chunk+"'" for chunk in chunks)
    shell=("set -eu\numask 077\n{\n"+encoded+"\n}"
           " | base64 -d | gzip -d | PYTHONDONTWRITEBYTECODE=1 python3 - "+mode+"\n")
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
            if str177:
                rows=[x[len(STR177_MARKER):] for x in out.splitlines() if x.startswith(STR177_MARKER)]
                if len(out)>2048 or len(rows)!=1:
                    raise Blocked("SOURCE_RECEIPT_INVALID")
                data=json.loads(rows[0])
                if (set(data)!={"state","sessions","decisionDate","expiry","latestObservedAt",
                               "outcome","pattern","reason","outlier","modifiedHeaders","modifiedBeforeNextDecision",
                               "certifiedStrategies","orders"}
                        or data["state"]!="RETAINED_ARCHIVE_SIGNAL_RESEARCH_ONLY"
                        or data["sessions"]!=10 or data["decisionDate"]!=RESEARCH_DAY.isoformat()
                        or data["expiry"]!=RESEARCH_EXPIRY.isoformat()
                        or data["certifiedStrategies"]!=0 or data["orders"] is not False
                        or data["outcome"] not in ("BLOCKED_RIGHTS","BLOCKED_DATA","NO_SIGNAL","RESEARCH_CANDIDATE")
                        or data["pattern"] not in ("NONE","LOW_EXTREME_REBOUND","HIGH_EXTREME_PULLBACK")
                        or not re.fullmatch(r"FUT_MR_[A-Z0-9_]+",data["reason"])
                        or datetime.fromisoformat(data["latestObservedAt"])>datetime.now(timezone.utc)):
                    raise Blocked("SOURCE_RECEIPT_INVALID")
                if (any(type(data[key]) is not int for key in ("modifiedHeaders","modifiedBeforeNextDecision"))
                    or not 0<=data["modifiedBeforeNextDecision"]<=data["modifiedHeaders"]<=10):
                    raise Blocked("SOURCE_RECEIPT_INVALID")
                issue=data["outlier"]
                if issue is not None and (type(issue) is not dict or set(issue)!={"previous","current","fields","exchangeDeltaMatches"}
                    or type(issue["exchangeDeltaMatches"]) is not bool
                    or [issue["previous"],issue["current"]] not in [[a.isoformat(),b.isoformat()] for a,b in zip(STR177_DAYS,STR177_DAYS[1:])]
                    or issue["fields"] not in (["CLOSE"],["OPEN_INTEREST"],["CLOSE","OPEN_INTEREST"])):
                    raise Blocked("SOURCE_RECEIPT_INVALID")
                print(STR177_MARKER+json.dumps(data,sort_keys=True),flush=True)
                return
            if day is None:
                from str176_expanded_upstox_research import REASON_OUTCOME
                rows=[x[len(RESEARCH_MARKER):] for x in out.splitlines() if x.startswith(RESEARCH_MARKER)]
                if len(out)>2048 or len(rows)!=1:
                    raise Blocked("SOURCE_RECEIPT_INVALID")
                data=json.loads(rows[0])
                if (set(data)!={"state","sessions","decisionDate","expiry","latestObservedAt",
                               "outcome","reason","certifiedStrategies","orders"}
                        or data["state"]!="RETAINED_ARCHIVE_SIGNAL_RESEARCH_ONLY"
                        or data["sessions"]!=7 or data["decisionDate"]!=RESEARCH_DAY.isoformat()
                        or data["expiry"]!=RESEARCH_EXPIRY.isoformat()
                        or data["certifiedStrategies"]!=0 or data["orders"] is not False
                        or data["reason"] not in REASON_OUTCOME
                        or data["outcome"]!=REASON_OUTCOME[data["reason"]]
                        or datetime.fromisoformat(data["latestObservedAt"])>datetime.now(timezone.utc)):
                    raise Blocked("SOURCE_RECEIPT_INVALID")
                print(RESEARCH_MARKER+json.dumps(data,sort_keys=True),flush=True)
                return
            rows=[x[len(MARKER):] for x in out.splitlines() if x.startswith(MARKER)]
            if len(out)>2048 or len(rows)!=1:raise Blocked("SOURCE_RECEIPT_INVALID")
            print(MARKER+json.dumps(validate_report(json.loads(rows[0]),day),sort_keys=True),flush=True)
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
        if len(sys.argv)==3 and sys.argv[1]=="host":host(sys.argv[2])
        elif sys.argv[1:]==["research"]:retained_research()
        elif sys.argv[1:]==["research177"]:retained_str177_research()
        elif sys.argv[1:]==["runner"]:runner()
        else:raise Blocked("MODE_INVALID")
    except (Blocked,OSError,ValueError,KeyError,TypeError,sqlite3.Error,ImportError,RuntimeError) as exc:
        print(FAILURE_MARKER+safe_reason(exc),flush=True)
        raise SystemExit(2)
