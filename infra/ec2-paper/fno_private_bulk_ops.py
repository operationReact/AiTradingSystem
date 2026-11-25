#!/usr/bin/env python3
"""One-time exact-host owner-approved F&O private research bootstrap.

A fixed owner request in a separate dev-only PR triggers GitHub OIDC -> SSM.
Stage one reviewed worker; prepare a 7-day private cache; persist 3 initial
batches via real Upstox GETs; verify rows; enable one restricted systemd unit.
Never places broker orders or publishes raw market data. This grants NO
independent NSE/Upstox distribution or perpetual archival license.
"""
from __future__ import annotations
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

REGION="ap-south-1"
ACCOUNT="098859530205"
INSTANCE="i-09c01b470fb39b85a"
ROOT=Path("/opt/aitradingsystem-paper")
WORKER=ROOT/"infra/ec2-paper/fno_private_bulk_worker.py"
UNIT="/etc/systemd/system/minttick-fno-private-backfill.service"
REQUEST=Path("infra/ec2-paper/requests/fno-owner-private-bulk-start.json")
MARKER="MINTTICK_FNO_PRIVATE_BULK_START_V1:"
STATES={"STARTED","ALREADY_RUNNING","OWNER_SCOPE_REJECTED",
        "HOST_REJECTED","CODE_REJECTED","SMOKE_FAILED","UNIT_REJECTED"}
SAFE_SMOKE_FAILURES=frozenset({
    "FNO_DATA_RETENTION_NOT_AUTHORIZED", "FNO_DISCOVERY_AUTH_REJECTED",
    "FNO_DISCOVERY_INVALID_JSON", "FNO_DISCOVERY_INVALID_RESPONSE",
    "FNO_DISCOVERY_PROVIDER_REJECTED", "FNO_DISCOVERY_RATE_LIMITED",
    "FNO_DISCOVERY_TOO_LARGE", "FNO_DISCOVERY_TRANSPORT",
    "FNO_PRIVATE_DIR_NOT_SECURE", "FNO_PRIVATE_PERMIT_SYMLINK",
    "FNO_REQUEST_BUDGET_EXHAUSTED", "FNO_STATUS_INVALID_PATH",
    "FNO_STATUS_TOO_LARGE", "INVALID_FNO_DISCOVERY_ROUTE",
    "LOST_FNO_JOB_LEASE", "OWNER_PRIVATE_FNO_SCOPE_MISSING_OR_EXPIRED",
    "UPSTOX_HISTORY_TOKEN_UNAVAILABLE", "UPSTOX_TOKEN_UNAVAILABLE",
    "WRONG_EC2_RUNTIME", "PRIVATE_DB_ERROR", "PRIVATE_STORAGE_ERROR",
    "UNCLASSIFIED_GATE_BLOCK", "UNCLASSIFIED_RUNTIME_FAILURE",
    "UNCLASSIFIED_VALIDATION_FAILURE", "UNCLASSIFIED_PROVIDER_SCHEMA_FAILURE",
    "UNCLASSIFIED_SAFE_FAILURE",
    "FNO_EXPIRIES_INVALID", "FNO_CONTRACT_INVALID",
    "FNO_DISCOVERY_CONTRACTS_INVALID", "FNO_STRIKE_INVALID",
    "INVALID_UPSTOX_EXPIRED_INTERVAL", "INVALID_UPSTOX_HISTORY_DATES",
    "UPSTOX_EXPIRED_HISTORY_NETWORK_ERROR", "INVALID_FNO_SINK_BATCH",
    "INVALID_FNO_SINK_ROWS", "INVALID_HTTP_OBSERVATION",
    "SOURCE_CAPTURE_TIME_INVALID", "ORIGINAL_HTTP_BODY_INVALID",
    "HTTP_CANDLES_DO_NOT_MATCH_SOURCE",
    "UNATTESTED_LEGACY_BATCH_CANNOT_BE_REWRITTEN",
    "HISTORICAL_PROVIDER_REVISION_DETECTED", "CANNOT_RETROFIT_OLD_HTTP_CAPTURE",
    "ORIGINAL_PROVIDER_RESPONSE_REVISION",
    "ORIGINAL_HTTP_CAPTURE_CANNOT_BE_DOWNGRADED",
    "HISTORICAL_CANDLE_IMMUTABILITY_CONFLICT", "INVALID_DATE",
    "INVALID_EXPIRED_INSTRUMENT_KEY", "INVALID_FNO_WINDOW",
    "INVALID_FNO_CANDLES", "INVALID_FNO_TIMESTAMP", "INVALID_FNO_DAILY_CANDLE",
    "INVALID_FNO_OHLCV_OI", "CONTRACT_NOT_EXPIRED_OR_WINDOW_EXCEEDS_EXPIRY",
    "INVALID_FNO_STATE", "INVALID_FNO_RECEIPT", "INVALID_RATE_LIMIT",
    "ORIGINAL_HTTP_RECEIPT_SINK_REQUIRED",
})

def smoke_failure(stdout):
    if not isinstance(stdout,str) or len(stdout)>4096:
        return "UNCLASSIFIED_SAFE_FAILURE"
    found=re.findall(r"^FNO_BULK_ERROR:([A-Z0-9_]{1,64})$",stdout,re.M)
    if len(found)==1 and found[0] in SAFE_SMOKE_FAILURES:
        return found[0]
    return "UNCLASSIFIED_SAFE_FAILURE"

def expected_request():
    return {
        "kind":"minttick-owner-private-fno-bulk-start-v1",
        "operation":"start-private-owner-scoped-expired-nifty-fno-backfill",
        "account":ACCOUNT,
        "region":REGION,
        "instance_id":INSTANCE,
        "environment":"PAPER",
        "authorization":"OWNER_APPROVED_PRIVATE_FNO_RESEARCH_2026_11_21",
        "max_months":12,
        "interval":"day",
        "private_cache_days":7,
        "max_provider_gets_per_run":600,
        "provider_licence_verified":False,
        "redistribution":False,
        "live_orders":False,
    }

def receipt(status,complete=0,total=0,rows=0):
    if (status not in STATES or any(type(n) is not int or n<0 or n>20000
                                   for n in (complete,total,rows))
            or complete>total):
        raise ValueError("BAD_FNO_START_RECEIPT")
    return MARKER+f"{status}|{complete}|{total}|{rows}"

def parse_receipt(raw):
    if not isinstance(raw,str) or len(raw)>8000:
        return None
    lines=[line.strip() for line in raw.splitlines()
           if line.startswith(MARKER)]
    if len(lines)!=1 or len(lines[0])>110:
        return None
    values=lines[0][len(MARKER):].split("|")
    if len(values)!=4 or any(not re.fullmatch("[0-9]{1,5}",v)
                            for v in values[1:]):
        return None
    try:
        nums=[int(x) for x in values[1:]]
        if receipt(values[0],*nums)!=lines[0]:
            return None
    except ValueError:
        return None
    return values[0],*nums

def parse_smoke_failure(raw):
    if not isinstance(raw,str) or len(raw)>8000:
        return "UNCLASSIFIED_SAFE_FAILURE"
    found=re.findall(r"^FNO_BULK_SMOKE_FAILURE:([A-Z0-9_]{1,64})$",raw,re.M)
    if len(found)==1 and found[0] in SAFE_SMOKE_FAILURES:
        return found[0]
    return "UNCLASSIFIED_SAFE_FAILURE"

def _call(*args,timeout=45):
    try:
        return subprocess.run(args,stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL,
                              text=True,timeout=timeout,check=False)
    except (OSError,subprocess.TimeoutExpired):
        return None

def _installed_worker_ok():
    if WORKER.is_symlink() or not WORKER.is_file():
        return False
    if WORKER.stat().st_mode & 0o077:
        return False
    src=WORKER.read_bytes()
    if not 1000<len(src)<40000:
        return False
    try:
        compile(src,str(WORKER),"exec")
    except (SyntaxError,ValueError):
        return False
    return True

def unit_text():
    return ("[Unit]\n"
            "Description=MintTick owner-private expired NIFTY F&O historical backfill\n"
            "After=network-online.target\n"
            "Wants=network-online.target\n"
            "[Service]\n"
            "Type=simple\n"
            "User=root\n"
            "WorkingDirectory=/opt/aitradingsystem-paper/infra/ec2-paper\n"
            "Environment=PYTHONDONTWRITEBYTECODE=1\n"
            "ExecStart=/usr/bin/python3 /opt/aitradingsystem-paper/infra/ec2-paper/fno_private_bulk_worker.py run\n"
            "Restart=on-failure\n"
            "RestartSec=120\n"
            "TimeoutStopSec=25\n"
            "NoNewPrivileges=true\n"
            "ProtectSystem=full\n"
            "ProtectHome=true\n"
            "ReadWritePaths=/opt/aitradingsystem-paper/fno-history\n"
            "UMask=0077\n"
            "[Install]\n"
            "WantedBy=multi-user.target\n")

def purge_unit():
    return ("[Unit]\nDescription=MintTick private FNO cache TTL maintenance\n"
            "[Service]\nType=oneshot\nUser=root\n"
            "ExecStart=/usr/bin/python3 /opt/aitradingsystem-paper/infra/ec2-paper/fno_private_bulk_worker.py purge\n"
            "NoNewPrivileges=true\nUMask=0077\n")

def purge_timer():
    return ("[Unit]\nDescription=MintTick private FNO cache daily expiry check\n"
            "[Timer]\nOnCalendar=*-*-* 03:00:00\nPersistent=true\n"
            "Unit=minttick-fno-private-purge.service\n"
            "[Install]\nWantedBy=timers.target\n")

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        print(receipt("HOST_REJECTED"),flush=True)
        return 2
    if not ROOT.is_dir() or ROOT.is_symlink():
        print(receipt("HOST_REJECTED"),flush=True)
        return 2
    for n in (".env","runtime.env"):
        p=ROOT/n
        if p.is_symlink() or not p.is_file() or p.stat().st_mode&0o077:
            print(receipt("HOST_REJECTED"),flush=True)
            return 2
    if not _installed_worker_ok():
        print(receipt("CODE_REJECTED"),flush=True)
        return 2
    status=_call("systemctl","is-active","--","minttick-fno-private-backfill.service")
    if status and status.returncode==0 and status.stdout.strip()=="active":
        record=_call("/usr/bin/python3",str(WORKER),"status",timeout=10)
        match=re.search(r"FNO_BULK_STATUS:jobs=(\d+);complete=(\d+);rows=(\d+)",
                        record.stdout if record else "")
        if match:
            total,done,rows=map(int,match.groups())
            print(receipt("ALREADY_RUNNING",done,total,rows),flush=True)
            return 0
        print(receipt("ALREADY_RUNNING"),flush=True)
        return 0
    # Scope can only be created after exact GitHub request and host verify.
    init=_call("/usr/bin/python3",str(WORKER),"init",timeout=20)
    if init is None or init.returncode!=0 or "FNO_OWNER_SCOPE_CREATED" not in init.stdout:
        print(receipt("OWNER_SCOPE_REJECTED"),flush=True)
        return 2
    smoke=_call("/usr/bin/python3",str(WORKER),"smoke",timeout=210)
    if smoke is None or smoke.returncode!=0:
        safe_cause=smoke_failure(smoke.stdout if smoke else "")
        print(receipt("SMOKE_FAILED"),flush=True)
        print("FNO_BULK_SMOKE_FAILURE:"+safe_cause,flush=True)
        return 2
    m=re.search(r"FNO_BULK_SMOKE:FIRST_BATCH_COMPLETE;complete=(\d+);"
                r"total=(\d+);rows=(\d+);calls=(\d+)",smoke.stdout)
    if not m:
        print(receipt("SMOKE_FAILED"),flush=True)
        return 2
    done,total,rows,calls=map(int,m.groups())
    if not (0<done<=total<=20000 and rows>0 and 0<calls<=12):
        print(receipt("SMOKE_FAILED"),flush=True)
        return 2
    unit=Path(UNIT)
    if unit.is_symlink() or (unit.exists() and not unit.is_file()):
        print(receipt("UNIT_REJECTED",done,total,rows),flush=True)
        return 2
    temp=unit.with_suffix(".service.tmp")
    temp.write_text(unit_text())
    os.chmod(temp,0o600)
    os.replace(temp,unit)
    for path,data in (
        (Path("/etc/systemd/system/minttick-fno-private-purge.service"),purge_unit()),
        (Path("/etc/systemd/system/minttick-fno-private-purge.timer"),purge_timer())):
        if path.is_symlink() or (path.exists() and not path.is_file()):
            print(receipt("UNIT_REJECTED",done,total,rows),flush=True)
            return 2
        p=path.with_suffix(path.suffix+".tmp")
        p.write_text(data)
        os.chmod(p,0o600)
        os.replace(p,path)
    for command in (("systemctl","daemon-reload"),
                    ("systemctl","enable","--now","minttick-fno-private-purge.timer"),
                    ("systemctl","enable","--now","minttick-fno-private-backfill.service")):
        result=_call(*command,timeout=40)
        if result is None or result.returncode!=0:
            print(receipt("UNIT_REJECTED",done,total,rows),flush=True)
            return 2
    check=_call("systemctl","is-active","--","minttick-fno-private-backfill.service")
    if check is None or check.returncode!=0:
        print(receipt("UNIT_REJECTED",done,total,rows),flush=True)
        return 2
    print(receipt("STARTED",done,total,rows),flush=True)
    return 0

def _aws(*args):
    try:
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json",
                          "--no-cli-pager"],capture_output=True,text=True,
                          timeout=45,check=False)
    except (OSError,subprocess.TimeoutExpired):
        raise SystemExit("FNO_BULK_SSM_UNAVAILABLE") from None
    if p.returncode:
        raise SystemExit("FNO_BULK_SSM_UNAVAILABLE")
    try:
        return json.loads(p.stdout)
    except ValueError:
        raise SystemExit("FNO_BULK_SSM_UNAVAILABLE") from None

def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink()
            or REQUEST.stat().st_size>1024
            or json.loads(REQUEST.read_text())!=expected_request()):
        raise SystemExit("FNO_BULK_REQUEST_NOT_APPROVED")
    sys.path.insert(0,"infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    worker_src=Path("infra/ec2-paper/fno_private_bulk_worker.py").read_bytes()
    worker_sha=hashlib.sha256(worker_src).hexdigest()
    worker_pkg=base64.b64encode(gzip.compress(worker_src,mtime=0)).decode()
    host_src=Path(__file__).read_bytes()
    host_pkg=base64.b64encode(gzip.compress(host_src,mtime=0)).decode()
    if (max(len(worker_pkg),len(host_pkg))>60000
            or not re.fullmatch("[A-Za-z0-9+/=]+",worker_pkg)
            or not re.fullmatch("[A-Za-z0-9+/=]+",host_pkg)):
        raise SystemExit("FNO_BULK_SOURCE_TOO_LARGE")
    shell=("set -eu\n"
           "umask 077\n"
           "test -d /opt/aitradingsystem-paper/infra/ec2-paper\n"
           "printf '%s' '"+worker_pkg+"' | base64 -d | gzip -dc "
           "> /opt/aitradingsystem-paper/infra/ec2-paper/.fno-owner-stage.tmp\n"
           "printf '%s  %s\\n' '"+worker_sha+"' "
           "'/opt/aitradingsystem-paper/infra/ec2-paper/.fno-owner-stage.tmp' "
           "| sha256sum -c - >/dev/null\n"
           "mv -f /opt/aitradingsystem-paper/infra/ec2-paper/.fno-owner-stage.tmp "
           "/opt/aitradingsystem-paper/infra/ec2-paper/fno_private_bulk_worker.py\n"
           "printf '%s' '"+host_pkg+"' | base64 -d | gzip -dc "
           "| python3 - host\n")
    command=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
                 "--instance-ids",INSTANCE,"--timeout-seconds","480",
                 "--comment","MintTick owner-approved private FNO checkpoint bootstrap and start",
                 "--parameters",json.dumps({"commands":[shell]}))
    command_id=(command.get("Command") or {}).get("CommandId")
    if not isinstance(command_id,str) or not re.fullmatch(
            r"[0-9a-f-]{36}",command_id):
        raise SystemExit("FNO_BULK_COMMAND_ID_INVALID")
    for _ in range(100):
        time.sleep(5)
        response=_aws("ssm","get-command-invocation","--command-id",command_id,
                      "--instance-id",INSTANCE)
        state=response.get("Status")
        if state in {"Success","Failed","TimedOut","Cancelled"}:
            stdout=response.get("StandardOutputContent") or ""
            result=parse_receipt(stdout)
            if result is None:
                raise SystemExit("FNO_BULK_NO_SAFE_RECEIPT")
            code,done,total,rows=result
            failure=("; cause="+parse_smoke_failure(stdout)
                     if code=="SMOKE_FAILED" else "")
            print(f"FNO PRIVATE BULK: state={code}; finished={done}; "
                  f"planned={total}; persistedRows={rows}; "
                  "singleService=true; redistribution=false"+failure+"; "
                  "ownerScopeExpiry=2026-11-28; providerLicenceUnverified=true",
                  flush=True)
            if state!="Success" or code not in {"STARTED","ALREADY_RUNNING"}:
                raise SystemExit("FNO_BULK_HOST_START_FAILED")
            return
        if state not in {"Pending","InProgress","Delayed"}:
            raise SystemExit("FNO_BULK_SSM_INVALID_STATE")
    raise SystemExit("FNO_BULK_SSM_TIMEOUT")

if __name__=="__main__":
    if len(sys.argv)!=2 or sys.argv[1] not in {"host","runner"}:
        raise SystemExit("USAGE: fno_private_bulk_ops.py host|runner")
    if sys.argv[1]=="host":
        raise SystemExit(host())
    runner()
