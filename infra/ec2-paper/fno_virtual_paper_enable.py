#!/usr/bin/env python3
"""Owner-attested, exact-host VIRTUAL PAPER-only F&O enablement over SSM.

Enables the two explicitly requested virtual-only flags on the existing
PAPER EC2 backend. No real broker order path, no scheduler opt-in, no LIVE
flag or market-data fetch is changed. Check exact instance, deployment,
source/display prerequisites, backend health, env safety and post-restart
mode. Atomic rollback on any host failure; never log sensitive env values.
"""
from __future__ import annotations

import base64
import datetime as dt
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
from zoneinfo import ZoneInfo

ACCOUNT="098859530205"
REGION="ap-south-1"
INSTANCE="i-09c01b470fb39b85a"
ROOT=Path("/opt/aitradingsystem-paper")
FLAGS=("MINTTICK_UPSTOX_FNO_PAPER_ENABLED",
       "MINTTICK_UPSTOX_FNO_PAPER_USAGE_RIGHTS_VERIFIED")
REQUIRED_TRUE=("MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED",
               "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED")
REQUIRED_FALSE=("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED",)
IST=ZoneInfo("Asia/Kolkata")
MARK="FNO_VIRTUAL_PAPER_ENABLE:"


class Blocked(ValueError):
    pass


def env_entries(blob: bytes) -> dict[str,str]:
    entries={}
    try:
        lines=blob.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        raise Blocked("INVALID_HOST_ENV_ENCODING") from None
    for line in lines:
        if not line or line.startswith("#"):
            continue
        key,sep,val=line.partition("=")
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*",key):
            continue
        if key in entries:
            raise Blocked("DUPLICATE_HOST_FLAG")
        entries[key]=val
    return entries


def patch_env(blob: bytes) -> bytes:
    """Pure replacement of precisely two flags, preserving every other line."""
    original=env_entries(blob)
    lines=blob.decode("utf-8").splitlines()
    keep=[line for line in lines
          if line.partition("=")[0] not in FLAGS]
    keep += [key+"=true" for key in FLAGS]
    updated=("\n".join(keep)+"\n").encode()
    after=env_entries(updated)
    if any(after.get(key)!="true" for key in FLAGS):
        raise Blocked("PAPER_FLAG_UPDATE_INVALID")
    for key,value in original.items():
        if key not in FLAGS and after.get(key)!=value:
            raise Blocked("OTHER_HOST_FLAG_MODIFIED")
    return updated


def secure_files(root: Path):
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise Blocked("WRONG_INSTANCE_ARCH_OR_PRIVILEGE")
    if root.is_symlink() or not root.is_dir():
        raise Blocked("HOST_ROOT_INVALID")
    for name in (".env","runtime.env","paper-compose.yml"):
        path=root/name
        if path.is_symlink() or not path.is_file():
            raise Blocked("HOST_FILES_INVALID")
        if name!="paper-compose.yml" and path.stat().st_mode & 0o077:
            raise Blocked("HOST_ENV_PERMISSIONS_UNSAFE")


def ready_to_update(runtime: dict, compose: dict, now: dt.datetime):
    local=now.astimezone(IST)
    if 9<=local.hour<16:
        raise Blocked("MARKET_HOURS_NO_RESTART")
    release=compose.get("RELEASE_SHA","")
    if not re.fullmatch(r"[0-9a-f]{40}",release):
        raise Blocked("HOST_RELEASE_UNVERIFIED")
    for key in REQUIRED_TRUE:
        if runtime.get(key)!="true" or compose.get(key)!="true":
            raise Blocked("SOURCE_OR_DISPLAY_NOT_VERIFIED")
    for key in REQUIRED_FALSE:
        if runtime.get(key,"false")!="false" or compose.get(key,"false")!="false":
            raise Blocked("LIVE_FLAG_NOT_DISABLED")
    for key in FLAGS:
        if runtime.get(key,"false") not in ("true","false") or (
                compose.get(key,"false") not in ("true","false")):
            raise Blocked("PAPER_FLAG_NONBOOLEAN")
    return release


def install_secure(path: Path, blob: bytes):
    fd,name=tempfile.mkstemp(prefix=".paper-fno.",dir=path.parent)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,"wb") as target:
            target.write(blob)
            target.flush()
            os.fsync(target.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def backend_health():
    try:
        req=urllib.request.Request("http://127.0.0.1:8080/api/health")
        with urllib.request.urlopen(req,timeout=5) as response:
            return response.status==200
    except (urllib.error.URLError,OSError,TimeoutError):
        return False


def restart_backend(root: Path):
    args=["docker","compose","--env-file",str(root/".env"),
          "-f",str(root/"paper-compose.yml"),"up","-d",
          "--no-deps","--no-build","--force-recreate","backend"]
    try:
        proc=subprocess.run(args,check=False,timeout=100,
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if proc.returncode:
            return False
        for _ in range(30):
            if backend_health():
                return True
            time.sleep(2)
    except (OSError,subprocess.TimeoutExpired):
        pass
    return False


def validate_compose(root: Path, release: str):
    result=subprocess.run(["docker","compose","--env-file",str(root/".env"),
        "-f",str(root/"paper-compose.yml"),"config","--format","json"],
        capture_output=True,text=True,check=False,timeout=25)
    if result.returncode:
        raise Blocked("COMPOSE_CONFIG_INVALID")
    try:
        payload=json.loads(result.stdout)
        backend=payload["services"]["backend"]
        env=backend["environment"]
        image=backend["image"]
    except (KeyError,TypeError,ValueError):
        raise Blocked("COMPOSE_CONFIG_INVALID") from None
    if (env.get("TRADING_MODE")!="PAPER" or
            env.get("APP_BUILD_REVISION")!=release or
            image!="aitradingsystem-paper-backend-arm64:"+release or
            any(env.get(k)!="true" for k in (*FLAGS,*REQUIRED_TRUE)) or
            any(env.get(k)!="false" for k in REQUIRED_FALSE)):
        raise Blocked("COMPOSE_SAFETY_INVARIANT_FAILED")


def host():
    root=ROOT
    secure_files(root)
    if not backend_health():
        raise Blocked("BACKEND_UNHEALTHY_PRECHECK")
    originals={name:(root/name).read_bytes() for name in (".env","runtime.env")}
    values={name:env_entries(blob) for name,blob in originals.items()}
    release=ready_to_update(values["runtime.env"],values[".env"],dt.datetime.now(dt.timezone.utc))
    changed=False
    try:
        for name in originals:
            updated=patch_env(originals[name])
            if originals[name]!=updated:
                changed=True
                install_secure(root/name,updated)
        validate_compose(root,release)
        if not restart_backend(root):
            raise Blocked("BACKEND_RESTART_FAILED")
        validate_compose(root,release)
    except Exception:
        # Even on uncertain outcome, restore both original files and backend.
        for name,blob in originals.items():
            install_secure(root/name,blob)
        if not restart_backend(root):
            raise Blocked("ROLLBACK_HEALTH_UNVERIFIED") from None
        raise Blocked("ACTIVATION_ROLLED_BACK") from None
    print(MARK+"PAPER_FLAGS_ENABLED",flush=True)
    print(MARK+"EXISTING_DEPLOYMENT_VERIFIED",flush=True)
    print(MARK+"LIVE_ORDER_FLAG_UNCHANGED_DISABLED",flush=True)
    print(MARK+"SCHEDULER_OPT_IN_UNCHANGED",flush=True)
    print(MARK+"BACKEND_HEALTH:OK",flush=True)


def aws_json(*args):
    proc=subprocess.run(["aws",*args,"--region",REGION,
        "--output","json","--no-cli-pager"],capture_output=True,
        text=True,timeout=50,check=False)
    if proc.returncode:
        raise Blocked("SSM_OPERATION_UNAVAILABLE")
    try:
        return json.loads(proc.stdout)
    except ValueError:
        raise Blocked("SSM_RESPONSE_INVALID") from None


def runner():
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    body=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(body)>18000:
        raise Blocked("SSM_PAYLOAD_TOO_LARGE")
    command="set -eu\nprintf '%s' '"+body+"' | base64 -d | gzip -d | python3 - host\n"
    data=aws_json("ssm","send-command",
        "--instance-ids",INSTANCE,
        "--document-name","AWS-RunShellScript",
        "--timeout-seconds","240",
        "--comment","User-attested FNO virtual PAPER only; no LIVE order or scheduler opt-in",
        "--parameters",json.dumps({"commands":[command]}))
    identifier=(data.get("Command") or {}).get("CommandId","")
    if not re.fullmatch(r"[0-9a-f-]{36}",identifier):
        raise Blocked("SSM_COMMAND_ID_INVALID")
    for _ in range(48):
        time.sleep(4)
        response=aws_json("ssm","get-command-invocation",
            "--command-id",identifier,"--instance-id",INSTANCE)
        state=response.get("Status")
        if state in ("Success","Failed","TimedOut","Cancelled"):
            stdout=response.get("StandardOutputContent") or ""
            if (state=="Success" and
                    stdout.count(MARK+"PAPER_FLAGS_ENABLED")==1 and
                    MARK+"LIVE_ORDER_FLAG_UNCHANGED_DISABLED" in stdout and
                    MARK+"BACKEND_HEALTH:OK" in stdout):
                print(MARK+"VERIFIED_PRIVATE_HOST_ACTIVATION",flush=True)
                return
            # Never relay remote stdout/stderr; may contain sensitive env.
            raise Blocked("SSM_HOST_ACTIVATION_UNVERIFIED")
        if state not in ("Pending","InProgress","Delayed"):
            raise Blocked("SSM_STATE_UNKNOWN")
    raise Blocked("SSM_HOST_ACTIVATION_TIMEOUT")


if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("host","runner"):
            raise Blocked("INVALID_MODE")
        host() if sys.argv[1]=="host" else runner()
    except (Blocked,OSError,ValueError,subprocess.TimeoutExpired) as exc:
        safe={"WRONG_INSTANCE_ARCH_OR_PRIVILEGE","HOST_ROOT_INVALID",
              "HOST_FILES_INVALID","HOST_ENV_PERMISSIONS_UNSAFE",
              "MARKET_HOURS_NO_RESTART","HOST_RELEASE_UNVERIFIED",
              "SOURCE_OR_DISPLAY_NOT_VERIFIED","LIVE_FLAG_NOT_DISABLED",
              "PAPER_FLAG_NONBOOLEAN","BACKEND_UNHEALTHY_PRECHECK",
              "ACTIVATION_ROLLED_BACK","ROLLBACK_HEALTH_UNVERIFIED",
              "SSM_OPERATION_UNAVAILABLE","SSM_RESPONSE_INVALID",
              "SSM_PAYLOAD_TOO_LARGE","SSM_COMMAND_ID_INVALID",
              "SSM_HOST_ACTIVATION_UNVERIFIED","SSM_STATE_UNKNOWN",
              "SSM_HOST_ACTIVATION_TIMEOUT","INVALID_MODE"}
        reason=str(exc)
        print(MARK+"BLOCKED:"+(reason if reason in safe else "UNAVAILABLE"),file=sys.stderr)
        sys.exit(2)
