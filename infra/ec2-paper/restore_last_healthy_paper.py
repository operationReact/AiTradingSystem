#!/usr/bin/env python3
"""Bounded, fail-closed rollback of exact PAPER containers after failed activation.

Never touches PostgreSQL data, broker credentials or trading permissions.
If the current backend recovered, do nothing. Otherwise use only preexisting
verified ARM64 images for the last healthy SHA on the exact SSM-managed host.
Only fixed sanitized status leaves EC2; never docker logs or env values.
"""
import base64
import gzip
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ACCOUNT="098859530205"
REGION="ap-south-1"
INSTANCE="i-09c01b470fb39b85a"
FAILED="e99b1e604584d16422b67065df14127d59bb016d"
HEALTHY="bc792b8e21e024190cfc553044c50719da8f8743"
ROOT=Path("/opt/aitradingsystem-paper")
ENV=ROOT/".env"
COMPOSE=ROOT/"paper-compose.yml"
REQUEST="infra/ec2-paper/requests/restore-last-healthy-paper.json"
MARKER="MINTTICK_PAPER_RECOVERY:"
STATES=frozenset({"ALREADY_HEALTHY","ROLLED_BACK","HOST_INVALID",
    "UNEXPECTED_RELEASE","IMAGE_UNAVAILABLE","POSTGRES_UNAVAILABLE",
    "COMPOSE_FAILED","BACKEND_UNAVAILABLE","NO_SAFE_RECEIPT",
    "SSM_UNAVAILABLE"})

def expected_request():
    return {"application":"MintTick","region":REGION,"aws_account":ACCOUNT,
        "instance_id":INSTANCE,"environment":"PAPER",
        "operation":"restore-exact-last-verified-paper-images",
        "failed_release":FAILED,"target_release":HEALTHY,
        "postgres":"KEEP_RUNNING","broker_orders":"UNCHANGED"}

def receipt(state):
    if state not in STATES:raise ValueError("INVALID_RECOVERY_STATUS")
    return MARKER+" state="+state+" orders=UNCHANGED postgres=KEEP_RUNNING"

def parse_receipt(raw):
    if not isinstance(raw,str) or len(raw)>12000:return None
    matches=[line for line in raw.splitlines() if line.startswith(MARKER)]
    if len(matches)!=1:return None
    return matches[0] if any(matches[0]==receipt(s) for s in STATES) else None

def proc(*args, timeout=30):
    try:
        return subprocess.run(args,check=False,timeout=timeout,
            text=True,capture_output=True)
    except (OSError,subprocess.TimeoutExpired):
        return None

def read_env():
    if ENV.is_symlink() or not ENV.is_file() or ENV.stat().st_mode & 0o077:
        return None,None
    lines=ENV.read_text().splitlines(keepends=True)
    release=[v.split("=",1)[1].strip() for v in lines
             if v.startswith("RELEASE_SHA=")]
    return (lines,release[0]) if len(release)==1 else (None,None)

def healthy_http(url):
    try:
        req=urllib.request.Request(url,method="GET")
        with urllib.request.build_opener().open(req,timeout=5) as r:
            return r.status==200
    except (OSError,TimeoutError,urllib.error.URLError):
        return False

def healthy_application():
    return healthy_http("http://127.0.0.1:8080/api/health") and \
           healthy_http("http://127.0.0.1:8088/api/health")

def docker_image_valid(name, user=None):
    p=proc("docker","image","inspect",name,timeout=20)
    if p is None or p.returncode:return False
    try:
        rows=json.loads(p.stdout)
        info=rows[0]
        return isinstance(rows,list) and len(rows)==1 and \
            info.get("Architecture")=="arm64" and \
            (user is None or info.get("Config",{}).get("User")==user)
    except (ValueError,KeyError,TypeError,IndexError):
        return False

def postgres_ready():
    p=proc("docker","inspect","aitradingsystem-paper-postgres-1",
        "--format","{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}",
        timeout=12)
    return p is not None and p.returncode==0 and \
        p.stdout.strip()=="running|healthy"

def exact_host():
    return os.geteuid()==0 and os.uname().machine=="aarch64" and \
        ROOT.is_dir() and not ROOT.is_symlink() and \
        COMPOSE.is_file() and not COMPOSE.is_symlink() and \
        "TRADING_MODE: PAPER" in COMPOSE.read_text()

def replace_release(lines):
    fd,name=tempfile.mkstemp(prefix=".minttick-rollback-",dir=ROOT)
    try:
        with os.fdopen(fd,"w") as out:
            for line in lines:
                if line.startswith("RELEASE_SHA="):
                    out.write("RELEASE_SHA="+HEALTHY+"\n")
                else:
                    out.write(line)
            out.flush()
            os.fchmod(out.fileno(),0o600)
            os.fsync(out.fileno())
        os.replace(name,ENV)
    finally:
        if os.path.exists(name):os.unlink(name)

def host():
    if not exact_host():
        print(receipt("HOST_INVALID"),flush=True);return
    lines,current=read_env()
    if lines is None or current not in (FAILED,HEALTHY):
        print(receipt("UNEXPECTED_RELEASE"),flush=True);return
    if healthy_application():
        print(receipt("ALREADY_HEALTHY"),flush=True);return
    api="aitradingsystem-paper-backend-arm64:"+HEALTHY
    web="aitradingsystem-paper-web-arm64:"+HEALTHY
    if not docker_image_valid(api,user="10001") or not docker_image_valid(web):
        print(receipt("IMAGE_UNAVAILABLE"),flush=True);return
    if not postgres_ready():
        print(receipt("POSTGRES_UNAVAILABLE"),flush=True);return
    if current==FAILED:
        replace_release(lines)
    # Does not restart/replace PostgreSQL or create any LIVE order transport.
    p=proc("docker","compose","--env-file",str(ENV),"-f",str(COMPOSE),
        "up","-d","--no-build","--no-deps","backend","web",timeout=150)
    if p is None or p.returncode:
        print(receipt("COMPOSE_FAILED"),flush=True);return
    for _ in range(75):
        if healthy_application():
            print(receipt("ROLLED_BACK"),flush=True);return
        time.sleep(3)
    print(receipt("BACKEND_UNAVAILABLE"),flush=True)

def runner():
    request=Path(REQUEST)
    if (request.is_symlink() or not request.is_file()
            or request.stat().st_size>1024 or
            json.loads(request.read_text())!=expected_request()):
        raise SystemExit("PAPER_ROLLBACK_UNAPPROVED")
    sys.path.insert(0,"infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    encoded=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(encoded)>24000 or not re.fullmatch("[A-Za-z0-9+/=]+",encoded):
        raise SystemExit("PAPER_ROLLBACK_PAYLOAD_INVALID")
    shell="set -eu\nprintf '%s' '"+encoded+"' | base64 -d | gzip -dc | python3 - host\n"
    def aws(*args):
        p=proc("aws",*args,"--region",REGION,"--output","json","--no-cli-pager",timeout=50)
        if p is None or p.returncode:raise RuntimeError("PAPER_ROLLBACK_SSM_UNAVAILABLE")
        return json.loads(p.stdout)
    sent=aws("ssm","send-command","--document-name","AWS-RunShellScript",
        "--instance-ids",INSTANCE,"--timeout-seconds","480",
        "--comment","Owner-approved fail-closed restore previous healthy PAPER Docker images",
        "--parameters",json.dumps({"commands":[shell]}))
    cid=(sent.get("Command") or {}).get("CommandId","")
    if not isinstance(cid,str) or not re.fullmatch("[0-9a-f-]{36}",cid):
        raise RuntimeError("PAPER_ROLLBACK_BAD_SSM_COMMAND")
    for _ in range(110):
        time.sleep(4)
        data=aws("ssm","get-command-invocation","--command-id",cid,"--instance-id",INSTANCE)
        status=data.get("Status")
        if status in ("Success","Failed","TimedOut","Cancelled"):
            safe=parse_receipt(data.get("StandardOutputContent",""))
            if status!="Success" or not safe:
                raise RuntimeError("PAPER_ROLLBACK_NO_SAFE_RECEIPT")
            print(safe,flush=True)
            return
        if status not in ("Pending","InProgress","Delayed"):
            raise RuntimeError("PAPER_ROLLBACK_BAD_SSM_STATE")
    raise RuntimeError("PAPER_ROLLBACK_TIMEOUT")

if __name__=="__main__":
    if sys.argv[1:]==["host"]:host()
    elif sys.argv[1:]==["runner"]:runner()
    else:raise SystemExit("PAPER_ROLLBACK_INVALID_MODE")
