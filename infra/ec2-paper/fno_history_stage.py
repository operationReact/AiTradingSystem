#!/usr/bin/env python3
"""Stage only reviewed F&O backfill Python code on existing PAPER EC2.

ONE separately owner-approved GitHub dev request -> exact OIDC AWS role ->
AWS SSM RunShellScript on the fixed existing instance. Copies only three
reviewed Python library files, atomically and with SHA256 checks. No broker
token reads, AWS infrastructure changes, scheduler, journal, market-data
storage, order execution or Upstox requests.
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
import tempfile
import time

REGION = "ap-south-1"
ACCOUNT = "098859530205"
INSTANCE = "i-09c01b470fb39b85a"
ROOT = Path("/opt/aitradingsystem-paper")
DEST_REL = Path("infra/ec2-paper")
REQUEST = Path("infra/ec2-paper/requests/fno-history-code-stage.json")
FILES = ("fno_history_backfill.py",
         "upstox_fno_backfill_adapters.py",
         "fno_history_progress_snapshot.py")
MARKER = "MINTTICK_FNO_SOURCE_STAGE_V1:"
OUTCOMES = {"STAGED", "ALREADY_CURRENT", "HOST_REJECTED",
            "SOURCE_REJECTED", "FILESYSTEM_REJECTED"}


def expected_request():
    return {
        "kind": "minttick-fno-source-stage-v1",
        "operation": "stage-reviewed-fno-python-library-without-start",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "environment": "PAPER",
        "authorization": "OWNER_APPROVED_FNO_SOURCE_STAGE_NO_EXECUTION_2026_11_21",
        "max_provider_gets": 0,
        "persist_market_data": False,
        "start_service": False,
        "trading_rights_change": False,
    }


def archive_from_repo(root=Path("infra/ec2-paper")):
    bundle = {}
    for name in FILES:
        p = root / name
        if not p.is_file() or p.is_symlink():
            raise ValueError("FNO_STAGE_REVIEWED_SOURCE_MISSING")
        data = p.read_bytes()
        if not 1000 < len(data) < 30000:
            raise ValueError("FNO_STAGE_SOURCE_SIZE_INVALID")
        compile(data, name, "exec")
        bundle[name] = {
            "sha256": hashlib.sha256(data).hexdigest(),
            "source": base64.b64encode(data).decode("ascii"),
        }
    raw = json.dumps({"version":1, "files":bundle},
                     sort_keys=True, separators=(",",":")).encode()
    return base64.b64encode(gzip.compress(raw,mtime=0)).decode("ascii")


def decode_archive(encoded: str):
    if (not isinstance(encoded,str) or len(encoded)>60000
            or not re.fullmatch(r"[A-Za-z0-9+/=]+",encoded)):
        raise ValueError("FNO_STAGE_SOURCE_REJECTED")
    try:
        data = gzip.decompress(base64.b64decode(encoded,validate=True))
        if len(data)>100000:
            raise ValueError("FNO_STAGE_SOURCE_REJECTED")
        item=json.loads(data)
        if (not isinstance(item,dict) or item.get("version")!=1
                or not isinstance(item.get("files"),dict)
                or set(item["files"])!=set(FILES)):
            raise ValueError("FNO_STAGE_SOURCE_REJECTED")
        decoded={}
        for name in FILES:
            entry=item["files"][name]
            if not isinstance(entry,dict) or set(entry)!={"sha256","source"}:
                raise ValueError("FNO_STAGE_SOURCE_REJECTED")
            raw=base64.b64decode(entry["source"],validate=True)
            if (not 1000<len(raw)<30000
                    or hashlib.sha256(raw).hexdigest()!=entry["sha256"]):
                raise ValueError("FNO_STAGE_SOURCE_REJECTED")
            compile(raw,name,"exec")
            decoded[name]=raw
        return decoded
    except (OSError,ValueError,TypeError,KeyError,UnicodeDecodeError):
        raise ValueError("FNO_STAGE_SOURCE_REJECTED") from None


def install_archive(encoded: str, root=ROOT, enforce_identity=True):
    """Return enum; never read env content or open a market-data database."""
    if enforce_identity:
        if os.geteuid()!=0 or os.uname().machine!="aarch64":
            return "HOST_REJECTED"
        for name in (".env","runtime.env"):
            p=root/name
            if p.is_symlink() or not p.is_file() or p.stat().st_mode & 0o077:
                return "HOST_REJECTED"
    if root.is_symlink() or not root.is_dir():
        return "HOST_REJECTED"
    try:
        files=decode_archive(encoded)
    except ValueError:
        return "SOURCE_REJECTED"
    dest=root
    try:
        for part in DEST_REL.parts:
            dest=dest/part
            if dest.is_symlink() or (dest.exists() and not dest.is_dir()):
                return "FILESYSTEM_REJECTED"
            if not dest.exists():
                dest.mkdir(mode=0o700)
        # Refuse symlinks or unusual existing files before changing anything.
        for name in FILES:
            p=dest/name
            if p.is_symlink() or (p.exists() and not p.is_file()):
                return "FILESYSTEM_REJECTED"
        needs=[name for name in FILES if not (dest/name).is_file()
               or (dest/name).read_bytes()!=files[name]]
        for name in needs:
            fd, filename=tempfile.mkstemp(prefix=".fno-stage-",dir=dest)
            temp=Path(filename)
            try:
                with os.fdopen(fd,"wb") as f:
                    os.fchmod(f.fileno(),0o600)
                    f.write(files[name])
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(temp,dest/name)
            finally:
                temp.unlink(missing_ok=True)
        if any(hashlib.sha256((dest/name).read_bytes()).hexdigest()
               !=hashlib.sha256(files[name]).hexdigest() for name in FILES):
            return "FILESYSTEM_REJECTED"
        return "STAGED" if needs else "ALREADY_CURRENT"
    except (OSError,PermissionError):
        return "FILESYSTEM_REJECTED"


def make_receipt(result):
    if result not in OUTCOMES:
        raise ValueError("INVALID_FNO_STAGE_RESULT")
    return MARKER+result


def parse_receipt(raw):
    if not isinstance(raw,str) or len(raw)>3500:
        return None
    matches=[line.strip() for line in raw.splitlines() if line.startswith(MARKER)]
    if len(matches)!=1:
        return None
    value=matches[0][len(MARKER):]
    return value if value in OUTCOMES and matches[0]==make_receipt(value) else None


def host():
    status=install_archive(os.environ.get("MINTTICK_FNO_STAGE_ARCHIVE",""))
    print(make_receipt(status),flush=True)
    return 0 if status in {"STAGED","ALREADY_CURRENT"} else 2


def _aws(*args):
    try:
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json",
                         "--no-cli-pager"],capture_output=True,text=True,
                         timeout=45,check=False)
    except (OSError,subprocess.TimeoutExpired):
        raise SystemExit("FNO_STAGE_SSM_UNAVAILABLE") from None
    if p.returncode:
        raise SystemExit("FNO_STAGE_SSM_UNAVAILABLE")
    try:
        return json.loads(p.stdout)
    except ValueError:
        raise SystemExit("FNO_STAGE_SSM_INVALID") from None


def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink()
            or REQUEST.stat().st_size>1024
            or json.loads(REQUEST.read_text())!=expected_request()):
        raise SystemExit("FNO_STAGE_REQUEST_UNAPPROVED")
    sys.path.insert(0,"infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    source=archive_from_repo()
    script=base64.b64encode(
        gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if any(len(x)>60000 or not re.fullmatch(r"[A-Za-z0-9+/=]+",x)
           for x in (source,script)):
        raise SystemExit("FNO_STAGE_OVERSIZED")
    shell=("set -eu\n"
           "MINTTICK_FNO_STAGE_ARCHIVE='"+source+"' "
           "python3 -c 'import base64,gzip;"
           "exec(gzip.decompress(base64.b64decode(\""+script+"\")),"
           "{\"__name__\":\"__main__\"})' host\n")
    result=_aws("ssm","send-command","--document-name",
                "AWS-RunShellScript","--instance-ids",INSTANCE,
                "--timeout-seconds","150","--comment",
                "MintTick reviewed FNO Python source staging only - no execution",
                "--parameters",json.dumps({"commands":[shell]}))
    command_id=(result.get("Command") or {}).get("CommandId")
    if not isinstance(command_id,str) or not re.fullmatch(
            r"[0-9a-f-]{36}",command_id):
        raise SystemExit("FNO_STAGE_SSM_ID_INVALID")
    for _ in range(35):
        time.sleep(4)
        r=_aws("ssm","get-command-invocation","--command-id",command_id,
               "--instance-id",INSTANCE)
        status=r.get("Status")
        if status in {"Success","Failed","TimedOut","Cancelled"}:
            result=parse_receipt(r.get("StandardOutputContent") or "")
            if result is None:
                raise SystemExit("FNO_STAGE_NO_SAFE_RECEIPT")
            print("FNO SOURCE STAGE: "+result+
                  "; stagedFiles=3; providerGets=0; downloads=0; "
                  "storage=OFF; service=NOT_STARTED; rights=UNVERIFIED",flush=True)
            if status!="Success" or result not in {"STAGED","ALREADY_CURRENT"}:
                raise SystemExit("FNO_STAGE_HOST_BLOCKED")
            return
        if status not in {"Pending","InProgress","Delayed"}:
            raise SystemExit("FNO_STAGE_SSM_STATE_INVALID")
    raise SystemExit("FNO_STAGE_SSM_TIMEOUT")


if __name__=="__main__":
    if len(sys.argv)!=2 or sys.argv[1] not in {"host","runner"}:
        raise SystemExit("USAGE: fno_history_stage.py host|runner")
    if sys.argv[1]=="host":
        raise SystemExit(host())
    runner()
