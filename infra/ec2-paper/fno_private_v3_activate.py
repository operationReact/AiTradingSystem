#!/usr/bin/env python3
"""One-time, reversible, operator-only Upstox V3 source activation over existing EC2 SSM.

Enables ONLY the ephemeral read-only source bean. Customer display entitlement,
PAPER-use rights, automatic subscriptions and LIVE broker order policy remain
untouched and must be false. No market quote, broker token or host env printed.
"""
import base64
import gzip
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

REGION = "ap-south-1"
ACCOUNT = "098859530205"
INSTANCE = "i-09c01b470fb39b85a"
# Last independently witnessed running SHA from real 2026-11-18 SSM HTTP smoke.
EXPECTED_RELEASE = "4ecc90348b56f5aa696d23b68ed32950eb82fa38"
REQUEST_FILE = "infra/ec2-paper/requests/fno-private-v3-activate.json"
EXACT_REQUEST = {
    "kind": "minttick-operator-only-v3-read-probe-v1",
    "operation": "activate-private-upstox-v3-operator-source-only",
    "aws_account": ACCOUNT,
    "region": REGION,
    "instance_id": INSTANCE,
    "backend_release": EXPECTED_RELEASE,
    "authorization": "OWNER_APPROVED_PRIVATE_FNO_V3_READ_ONLY_2026_11_18",
}
FLAG = "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED"
FORBIDDEN = (
    "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED",
    "MINTTICK_UPSTOX_FNO_PAPER_ENABLED",
    "MINTTICK_UPSTOX_FNO_PAPER_USAGE_RIGHTS_VERIFIED",
    "MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED",
    "MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED",
)

def entries(path):
    result = {}
    for line in path.read_text().splitlines():
        name, sep, value = line.partition("=")
        if sep and re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
            if name in result:
                raise ValueError("DUPLICATE_HOST_FLAG")
            result[name] = value
    return result

def check_host_files(root):
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise ValueError("INVALID_HOST")
    if root.is_symlink() or not root.is_dir():
        raise ValueError("INVALID_HOST")
    for name in ("runtime.env", ".env", "paper-compose.yml"):
        p = root / name
        if p.is_symlink() or not p.is_file():
            raise ValueError("INVALID_HOST_FILES")
    if any((root / name).stat().st_mode & 0o077
           for name in ("runtime.env", ".env")):
        raise ValueError("INSECURE_HOST_ENV")

def get_health():
    try:
        with urllib.request.urlopen(
            urllib.request.Request("http://127.0.0.1:8080/api/health"),
            timeout=5) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError, TimeoutError):
        return False

def operator_read(operator_key):
    url = "http://127.0.0.1:8080/api/admin/market-data/upstox-analytics/fno/live/status"
    req = urllib.request.Request(url, headers={
        "X-API-Key": operator_key, "Accept": "application/json"
    }, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=6) as response:
            if response.status != 200:
                return False
            raw = response.read(4097)
        if len(raw) > 4096:
            return False
        parsed = json.loads(raw)
        return (isinstance(parsed, dict)
                and parsed.get("state") == "STOPPED"
                and parsed.get("displayRightsVerified") is False
                and parsed.get("liveTradingEnabled") is False
                and parsed.get("freshExchangeTimedQuotes") == 0)
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return False

def recreate(root):
    args = ["docker", "compose", "--env-file", str(root / ".env"),
            "-f", str(root / "paper-compose.yml"), "up",
            "-d", "--no-deps", "--no-build", "--force-recreate", "backend"]
    try:
        result = subprocess.run(args, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, check=False, timeout=90)
        if result.returncode:
            return False
        for _ in range(35):
            if get_health():
                return True
            time.sleep(2)
    except (OSError, subprocess.TimeoutExpired):
        pass
    return False

def host():
    import tempfile
    root = Path("/opt/aitradingsystem-paper")
    check_host_files(root)
    original = entries(root / "runtime.env")
    compose = entries(root / ".env")
    if compose.get("RELEASE_SHA") != EXPECTED_RELEASE:
        raise ValueError("DEPLOYED_RELEASE_CHANGED")
    if original.get("MINTTICK_UPSTOX_ANALYTICS_ENABLED") != "true":
        raise ValueError("SOURCE_DISABLED")
    if any(original.get(k,"false") != "false" or
           compose.get(k,"false") != "false" for k in FORBIDDEN):
        raise ValueError("OTHER_FNO_PERMISSION_ALREADY_CHANGED")
    key = original.get("APP_API_KEY", "")
    if not re.fullmatch(r"[0-9a-f]{64}", key):
        raise ValueError("OPERATOR_KEY_UNAVAILABLE")
    if not get_health():
        raise ValueError("HOST_BACKEND_UNHEALTHY")
    # Validate the configured SSM source is still available by its name/type;
    # its sensitive value must never enter this process or Github Actions.
    ssm = subprocess.run(["aws", "ssm", "get-parameter",
        "--region", REGION, "--name", "/minttick/upstox/analytics-token",
        "--query", "Parameter.Type", "--output", "text", "--no-cli-pager"],
        capture_output=True, text=True, timeout=20, check=False)
    if ssm.returncode or ssm.stdout.strip() != "SecureString":
        raise ValueError("UPSTOX_SSM_SOURCE_UNAVAILABLE")
    before = {
        "runtime.env": (root / "runtime.env").read_bytes(),
        ".env": (root / ".env").read_bytes(),
    }
    def next_bytes(raw):
        keep = [x for x in raw.decode().splitlines() if not x.startswith(FLAG+"=")]
        keep.append(FLAG+"=true")
        return ("\n".join(keep)+"\n").encode()
    # Atomic replacement with same strict mode, backup retained only in memory.
    def install(p, raw):
        fd, name = tempfile.mkstemp(prefix=".fno-source.", dir=root)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(raw)
                f.flush()
                os.fsync(f.fileno())
            os.replace(name, p)
        finally:
            if os.path.exists(name):
                os.unlink(name)
    try:
        for name in before:
            install(root/name, next_bytes(before[name]))
        cmd = subprocess.run(
            ["docker","compose","--env-file",str(root/".env"),
             "-f",str(root/"paper-compose.yml"),"config","--format","json"],
            capture_output=True,text=True,check=False,timeout=20)
        if cmd.returncode:
            raise ValueError("COMPOSE_CONFIGURATION_INVALID")
        env = json.loads(cmd.stdout)["services"]["backend"]["environment"]
        if (env.get(FLAG) != "true" or env.get("TRADING_MODE") != "PAPER"
                or any(env.get(k) != "false" for k in FORBIDDEN)):
            raise ValueError("UNSAFE_FNO_CONFIG")
        if not recreate(root):
            raise ValueError("BACKEND_RESTART_FAILED")
        if not operator_read(key):
            raise ValueError("PRIVATE_OPERATOR_API_UNVERIFIED")
    except Exception as error:
        # Fail closed and restore old runtime flags, then reboot the backend.
        for name, raw in before.items():
            install(root/name, raw)
        if not recreate(root):
            raise ValueError("ROLLBACK_UNVERIFIED") from None
        # Do not pass through upstream exception/secret/path data.
        raise ValueError("ACTIVATION_FAILED_ROLLED_BACK") from None
    print("PRIVATE_FNO_RESULT:SOURCE_BEAN_ENABLED", flush=True)
    print("PRIVATE_FNO_RELEASE:"+EXPECTED_RELEASE, flush=True)
    print("PRIVATE_FNO_RIGHTS:DISPLAY_PAPER_LIVE_ORDER_UNCHANGED_DISABLED", flush=True)
    print("PRIVATE_FNO_SOCKET:STOPPED_WAITING_OWNER_SOURCE_RECEIPT", flush=True)

def runner():
    request = Path(REQUEST_FILE)
    if request.is_symlink() or not request.is_file() or request.stat().st_size>1500:
        raise ValueError("BAD_REQUEST_FILE")
    if json.loads(request.read_text()) != EXACT_REQUEST:
        raise ValueError("BAD_OWNER_REQUEST")
    sys.path.insert(0, "infra/ec2-paper")
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    data = Path(__file__).read_bytes()
    payload = base64.b64encode(gzip.compress(data, mtime=0)).decode()
    if len(payload)>14500:
        raise ValueError("SSM_PAYLOAD_OVERFLOW")
    cmd = ("set -eu\nprintf '%s' '"+payload+
           "' | base64 -d | gzip -d | python3 - host\n")
    def aws(*args):
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json",
                 "--no-cli-pager"],capture_output=True,text=True,
                 timeout=50,check=False)
        if p.returncode:
            raise ValueError("SSM_OPERATION_UNAVAILABLE")
        return json.loads(p.stdout)
    message=aws("ssm","send-command","--instance-ids",INSTANCE,
        "--document-name","AWS-RunShellScript","--timeout-seconds","180",
        "--comment","Owner-approved private FNO V3 read source enablement",
        "--parameters",json.dumps({"commands":[cmd]}))
    cid=(message.get("Command") or {}).get("CommandId","")
    if not re.fullmatch(r"[0-9a-f-]{36}",cid):
        raise ValueError("SSM_BAD_COMMAND_ID")
    for _ in range(44):
        time.sleep(4)
        result=aws("ssm","get-command-invocation","--command-id",cid,
            "--instance-id",INSTANCE)
        state=result.get("Status")
        if state in ("Success","Failed","TimedOut","Cancelled"):
            stdout=result.get("StandardOutputContent") or ""
            marker="PRIVATE_FNO_RESULT:SOURCE_BEAN_ENABLED"
            if state=="Success" and stdout.count(marker)==1 and (
                "PRIVATE_FNO_RIGHTS:DISPLAY_PAPER_LIVE_ORDER_UNCHANGED_DISABLED" in stdout):
                print("PRIVATE_FNO_ACTIVATION:SOURCE_ENABLED_OPERATOR_ONLY",flush=True)
                print("PRIVATE_FNO_DEPLOYED_SHA:"+EXPECTED_RELEASE,flush=True)
                return
            # No upstream stdout/stderr or source response is printable.
            print("PRIVATE_FNO_ACTIVATION:NOT_VERIFIED",flush=True)
            raise ValueError("SSM_ACTIVATION_UNVERIFIED")
        if state not in ("Pending","InProgress","Delayed"):
            raise ValueError("SSM_UNEXPECTED_STATE")
    raise ValueError("SSM_ACTIVATION_TIMEOUT")

if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("host","runner"):
            raise ValueError("INVALID_MODE")
        host() if sys.argv[1]=="host" else runner()
    except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
        reason=str(exc)
        allowed={"INVALID_HOST","INVALID_HOST_FILES","INSECURE_HOST_ENV",
                 "DEPLOYED_RELEASE_CHANGED","SOURCE_DISABLED",
                 "OTHER_FNO_PERMISSION_ALREADY_CHANGED","OPERATOR_KEY_UNAVAILABLE",
                 "HOST_BACKEND_UNHEALTHY","UPSTOX_SSM_SOURCE_UNAVAILABLE",
                 "ROLLBACK_UNVERIFIED","ACTIVATION_FAILED_ROLLED_BACK",
                 "BAD_REQUEST_FILE","BAD_OWNER_REQUEST","SSM_PAYLOAD_OVERFLOW",
                 "SSM_OPERATION_UNAVAILABLE","SSM_BAD_COMMAND_ID",
                 "SSM_ACTIVATION_UNVERIFIED","SSM_UNEXPECTED_STATE",
                 "SSM_ACTIVATION_TIMEOUT","INVALID_MODE"}
        print("PRIVATE_FNO_ERROR:"+(reason if reason in allowed else "UNAVAILABLE"),
              file=sys.stderr)
        sys.exit(2)
