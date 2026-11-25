#!/usr/bin/env python3
"""Owner-approved, reversible activation of the *manual*, one-lot Upstox LIVE F&O gate.

The existing authenticated customer must still explicitly confirm EVERY order.
Never submits/cancels/retries broker orders. Does not change NFO entitlements,
display rights, static-IP registration, PAPER wallets or emergency stop.
The host is a shared PAPER+LIVE-room application, not an autonomous LIVE bot.
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
import urllib.request
import urllib.error

REGION="ap-south-1"
ACCOUNT="098859530205"
INSTANCE="i-09c01b470fb39b85a"
EXPECTED_RELEASE="1c2bfae773f7ffb10c6c0a0c3406564e6f9962d5"
REQUEST_FILE="infra/ec2-paper/requests/upstox-fno-live-execution-activate.json"
FLAG="MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED"
EXACT_REQUEST={
    "kind":"minttick-owner-manual-upstox-fno-live-activation-v1",
    "operation":"activate-confirmed-one-lot-live-upstox-order-policy",
    "account":ACCOUNT,
    "region":REGION,
    "instance_id":INSTANCE,
    "expected_backend_release":EXPECTED_RELEASE,
    "authorization":"OWNER_EXPLICITLY_REQUESTED_LIVE_EXECUTION_2026_11_20",
}
REQUIRED_SOURCE=(
    "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED",
    "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED",
)

def source_flags_ready(flags):
    """Mirror customer discovery semantics: verified display implies listing.

    This never grants or independently proves display rights. It only avoids
    requiring a redundant discovery flag when display-rights config is true.
    The actual customer order path still checks each source and broker gate.
    """
    if not isinstance(flags,dict):
        return False
    if any(flags.get(key)!="true" for key in REQUIRED_SOURCE):
        return False
    return (flags.get("MINTTICK_UPSTOX_FNO_CONTRACT_DISCOVERY_VERIFIED")=="true"
            or flags.get("MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED")=="true")
ERRORS={
    "INVALID_MODE","BAD_REQUEST_FILE","BAD_OWNER_REQUEST",
    "INVALID_HOST","INVALID_HOST_FILES","INSECURE_HOST_ENV",
    "DUPLICATE_HOST_FLAG","DEPLOYED_RELEASE_CHANGED",
    "BROKER_SOURCE_OR_RIGHTS_NOT_READY",
    "UPSTOX_SERVER_CONFIG_UNAVAILABLE","BACKEND_UNHEALTHY",
    "COMPOSE_CONFIGURATION_INVALID","UNEXPECTED_NONLIVE_CONFIG_CHANGE",
    "BACKEND_RESTART_FAILED","ROLLBACK_UNVERIFIED",
    "ACTIVATION_FAILED_ROLLED_BACK","SSM_PAYLOAD_OVERFLOW",
    "SSM_OPERATION_UNAVAILABLE","SSM_BAD_COMMAND_ID",
    "SSM_ACTIVATION_UNVERIFIED","SSM_UNEXPECTED_STATE","SSM_ACTIVATION_TIMEOUT",
}

def entries(raw):
    result={}
    for line in raw.decode().splitlines():
        key, sep, val=line.partition("=")
        if sep and re.fullmatch(r"[A-Z][A-Z0-9_]*",key):
            if key in result:
                raise ValueError("DUPLICATE_HOST_FLAG")
            result[key]=val
    return result

def replace_flag(raw, flag=FLAG):
    if b"\r" in raw or b"\x00" in raw:
        raise ValueError("INVALID_HOST_FILES")
    lines=raw.decode().splitlines()
    # Preserve everything including opaque host credentials, with one flag.
    lines=[line for line in lines if not line.startswith(flag+"=")]
    lines.append(flag+"=true")
    return ("\n".join(lines)+"\n").encode()

def check_files(root):
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise ValueError("INVALID_HOST")
    if not root.is_dir() or root.is_symlink():
        raise ValueError("INVALID_HOST")
    for name in ("runtime.env",".env","paper-compose.yml"):
        path=root/name
        if not path.is_file() or path.is_symlink():
            raise ValueError("INVALID_HOST_FILES")
    for name in ("runtime.env",".env"):
        if (root/name).stat().st_mode & 0o077:
            raise ValueError("INSECURE_HOST_ENV")
    if "TRADING_MODE: PAPER" not in (root/"paper-compose.yml").read_text():
        raise ValueError("INVALID_HOST_FILES")
    if FLAG+":" not in (root/"paper-compose.yml").read_text():
        raise ValueError("INVALID_HOST_FILES")

def healthy():
    try:
        req=urllib.request.Request("http://127.0.0.1:8080/api/health",
                                   method="GET")
        with urllib.request.urlopen(req,timeout=5) as res:
            return res.status==200
    except (OSError, urllib.error.URLError, TimeoutError):
        return False

def docker(*args, timeout=30):
    try:
        p=subprocess.run(["docker",*args],capture_output=True,text=True,
                         timeout=timeout,check=False)
        if p.returncode:
            raise ValueError("COMPOSE_CONFIGURATION_INVALID")
        return p.stdout
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("COMPOSE_CONFIGURATION_INVALID") from None

def configuration(root):
    raw=docker("compose","--env-file",str(root/".env"),
        "-f",str(root/"paper-compose.yml"),"config","--format","json")
    try:
        return json.loads(raw)["services"]["backend"]["environment"]
    except (KeyError, ValueError, TypeError):
        raise ValueError("COMPOSE_CONFIGURATION_INVALID") from None

def restart(root):
    args=["compose","--env-file",str(root/".env"),
          "-f",str(root/"paper-compose.yml")]
    try:
        p=subprocess.run(["docker",*args,"up","-d","--no-deps","--no-build",
                          "--force-recreate","backend"],
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
            timeout=100,check=False)
        if p.returncode:
            return False
        for _ in range(36):
            if healthy():
                return True
            time.sleep(2)
    except (OSError,subprocess.TimeoutExpired):
        pass
    return False

def atomic_write(root,name,raw):
    fd,tmp=tempfile.mkstemp(prefix=".approved-live-",dir=root)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,"wb") as f:
            f.write(raw)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp,root/name)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)

def host():
    root=Path("/opt/aitradingsystem-paper")
    check_files(root)
    before={name:(root/name).read_bytes() for name in ("runtime.env",".env")}
    runtime=entries(before["runtime.env"])
    previous=entries(before[".env"])
    if previous.get("RELEASE_SHA")!=EXPECTED_RELEASE:
        raise ValueError("DEPLOYED_RELEASE_CHANGED")
    if not source_flags_ready(previous):
        raise ValueError("BROKER_SOURCE_OR_RIGHTS_NOT_READY")
    if not (previous.get("MINTTICK_UPSTOX_OAUTH_SSM_ENABLED","false")=="true" or
            (bool(previous.get("UPSTOX_CLIENT_ID")) and
             bool(previous.get("UPSTOX_CLIENT_SECRET")) and
             previous.get("UPSTOX_REDIRECT_URI")==
                 "https://api.minttick.com/api/customer/brokers/upstox/callback")):
        raise ValueError("UPSTOX_SERVER_CONFIG_UNAVAILABLE")
    if not healthy():
        raise ValueError("BACKEND_UNHEALTHY")

    before_config=configuration(root)
    if before_config.get("TRADING_MODE")!="PAPER":
        raise ValueError("COMPOSE_CONFIGURATION_INVALID")
    # Never modify source/data licenses, broker credentials or emergency stop.
    # Re-verify the running container even if host env already says "true".
    # An env-file value alone is NOT evidence of activated execution.
    try:
        for name,raw in before.items():
            atomic_write(root,name,replace_flag(raw))
        after=configuration(root)
        expected={**before_config,FLAG:"true"}
        if after!=expected or after.get("TRADING_MODE")!="PAPER":
            raise ValueError("UNEXPECTED_NONLIVE_CONFIG_CHANGE")
        if not restart(root):
            raise ValueError("BACKEND_RESTART_FAILED")
        # Verify actual running container, not just Compose config.
        cid=docker("compose","--env-file",str(root/".env"),
            "-f",str(root/"paper-compose.yml"),"ps","-q","backend").strip()
        if not re.fullmatch(r"[0-9a-f]{12,64}",cid):
            raise ValueError("BACKEND_RESTART_FAILED")
        actual=json.loads(docker("inspect","--format","{{json .Config.Env}}",cid))
        values=dict(pair.split("=",1) for pair in actual if "=" in pair)
        if (values.get(FLAG)!="true"
                or values.get("APP_BUILD_REVISION")!=EXPECTED_RELEASE
                or values.get("TRADING_MODE")!="PAPER"):
            raise ValueError("BACKEND_RESTART_FAILED")
    except Exception:
        for name,raw in before.items():
            atomic_write(root,name,raw)
        if not restart(root):
            raise ValueError("ROLLBACK_UNVERIFIED") from None
        raise ValueError("ACTIVATION_FAILED_ROLLED_BACK") from None
    print("UPSTOX_FNO_LIVE_POLICY:ENABLED_CONFIRMED",flush=True)
    print("UPSTOX_FNO_LIVE_RELEASE:"+EXPECTED_RELEASE,flush=True)
    print("UPSTOX_FNO_LIVE_ORDERS:NOT_SUBMITTED",flush=True)
    print("UPSTOX_FNO_LIVE_BROKER_RIGHTS:UNCHANGED",flush=True)
    print("UPSTOX_FNO_LIVE_PER_ORDER_CONFIRMATION:REQUIRED",flush=True)

def runner():
    req=Path(REQUEST_FILE)
    if req.is_symlink() or not req.is_file() or req.stat().st_size>1500:
        raise ValueError("BAD_REQUEST_FILE")
    if json.loads(req.read_text())!=EXACT_REQUEST:
        raise ValueError("BAD_OWNER_REQUEST")
    sys.path.insert(0,"infra/ec2-paper")
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    payload=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(payload)>14500:
        raise ValueError("SSM_PAYLOAD_OVERFLOW")
    script="set -eu\nprintf '%s' '"+payload+"' | base64 -d | gzip -d | python3 - host\n"
    def aws(*args):
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json",
                "--no-cli-pager"],capture_output=True,text=True,
                timeout=45,check=False)
        if p.returncode:
            raise ValueError("SSM_OPERATION_UNAVAILABLE")
        return json.loads(p.stdout)
    cmd=aws("ssm","send-command","--instance-ids",INSTANCE,
            "--document-name","AWS-RunShellScript","--timeout-seconds","190",
            "--comment","Owner-approved manual Upstox FNO LIVE execution flag",
            "--parameters",json.dumps({"commands":[script]}))
    command_id=(cmd.get("Command") or {}).get("CommandId","")
    if not re.fullmatch(r"[0-9a-f-]{36}",command_id):
        raise ValueError("SSM_BAD_COMMAND_ID")
    for _ in range(45):
        time.sleep(4)
        response=aws("ssm","get-command-invocation","--command-id",command_id,
                     "--instance-id",INSTANCE)
        state=response.get("Status")
        if state in ("Success","Failed","TimedOut","Cancelled"):
            output=response.get("StandardOutputContent") or ""
            marker="UPSTOX_FNO_LIVE_POLICY:ENABLED_CONFIRMED"
            already="UPSTOX_FNO_LIVE_POLICY:ALREADY_ENABLED_VERIFIED"
            if state=="Success" and (marker in output or already in output):
                print("UPSTOX_FNO_LIVE_ACTIVATION:POLICY_ENABLED",flush=True)
                print("UPSTOX_FNO_LIVE_ACTIVATION:OWNER_ORDER_CONFIRMATION_STILL_REQUIRED",
                      flush=True)
                return
            # Display only one pre-approved fixed blocker code, never raw SSM
            # stderr, JSON, host environment, broker tokens or provider body.
            sanitized=re.findall(r"UPSTOX_FNO_LIVE_ERROR:([A-Z_]+)",
                response.get("StandardErrorContent") or "")
            blocker=sanitized[-1] if sanitized and sanitized[-1] in ERRORS else "SSM_ACTIVATION_UNVERIFIED"
            print("UPSTOX_FNO_LIVE_ACTIVATION:NOT_VERIFIED",flush=True)
            print("UPSTOX_FNO_LIVE_BLOCKER:"+blocker,flush=True)
            raise ValueError("SSM_ACTIVATION_UNVERIFIED")
        if state not in ("Pending","InProgress","Delayed"):
            raise ValueError("SSM_UNEXPECTED_STATE")
    raise ValueError("SSM_ACTIVATION_TIMEOUT")

if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("host","runner"):
            raise ValueError("INVALID_MODE")
        host() if sys.argv[1]=="host" else runner()
    except (ValueError,OSError,subprocess.TimeoutExpired) as exc:
        reason=str(exc)
        print("UPSTOX_FNO_LIVE_ERROR:"+(reason if reason in ERRORS else "UNAVAILABLE"),
              file=sys.stderr)
        sys.exit(2)
