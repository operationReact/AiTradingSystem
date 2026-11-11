#!/usr/bin/env python3
"""Dev-only exact-one-file request -> scoped SSM owner history timer activation."""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

import verify_ssm_release as gate

ROOT = Path(__file__).resolve().parents[2]
REQUEST = Path("infra/ec2-paper/requests/owner-history-enable-timer.json")
ACCOUNT = "098859530205"
INSTANCE = "i-09c01b470fb39b85a"
FILES = (
    "enable_owner_history_timer.sh",
    "minttick-owner-history.service",
    "minttick-owner-history.timer",
)
EXPECTED = {
    "kind": "minttick-owner-history-timer-enable-v1",
    "action": "enable-existing-history-timer",
    "account": ACCOUNT,
    "region": "ap-south-1",
    "instance_id": INSTANCE,
    "enable_trading": False,
    "confirmation": "ENABLE_OWNER_HISTORY_TIMER_ONLY",
}
REQUIRED_MARKERS = frozenset({
    "OWNER_TIMER_TRANSPORT: VERIFIED",
    "OWNER_TIMER_REPAIR: ENABLED",
    "OWNER_TIMER_REPAIR: ACTIVE",
    "OWNER_TIMER_REPAIR: NEXT_TICK_PRESENT",
    "OWNER_TIMER_REPAIR: HISTORICAL_ONLY",
})


def pairs_no_duplicates(pairs):
    result = {}
    for k, v in pairs:
        if k in result:
            raise ValueError("duplicate approval key")
        result[k] = v
    return result


def validate_request(changed: str, path: Path = REQUEST) -> None:
    if changed != REQUEST.as_posix() or path.as_posix() != REQUEST.as_posix():
        raise ValueError("only exact single approval file is permitted")
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024:
        raise ValueError("invalid owner approval file")
    data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs_no_duplicates)
    if data != EXPECTED or type(data.get("enable_trading")) is not bool:
        raise ValueError("owner history timer request mismatch")


def build_command() -> str:
    base = ROOT / "infra/ec2-paper"
    contents = []
    for name in FILES:
        path = base / name if name.startswith("enable_") else base / "systemd" / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 5000:
            raise ValueError("unreviewed history timer bundle")
        contents.append((name, path.read_bytes()))
    assert b"OnCalendar=*-*-* *:0/2:00 Asia/Kolkata" in contents[-1][1]
    lines = [
        "set -Eeuo pipefail",
        "umask 077",
        'test "$(id -u)" -eq 0',
        'test "$(uname -m)" = aarch64',
        'work="$(mktemp -d /run/minttick-owner-timer.XXXXXXXX)"',
        'trap \'rm -rf -- "$work"\' EXIT',
        'chmod 0700 "$work"',
    ]
    for name, body in contents:
        encoded = base64.b64encode(body).decode("ascii")
        lines.append(f"printf '%s' '{encoded}' | base64 -d > \"$work/{name}\"")
    lines.append('bash "$work/enable_owner_history_timer.sh" "$work"')
    source = ("\\n".join(lines) + "\\n").encode()
    if not (1500 <= len(source) < 12000):
        raise ValueError("invalid reviewed timer payload size")
    checksum = hashlib.sha256(source).hexdigest()
    compressed = gzip.compress(source, compresslevel=9, mtime=0)
    encoded = base64.b64encode(compressed).decode("ascii")
    if len(encoded) >= 6000:
        raise ValueError("SSM timer payload exceeds reviewed bound")
    python = (
        'import base64,gzip,hashlib,subprocess,sys;'
        'p=gzip.decompress(base64.b64decode(sys.argv[1],validate=True));'
        'assert 1500<=len(p)<12000 and hashlib.sha256(p).hexdigest()==sys.argv[2];'
        'print("OWNER_TIMER_TRANSPORT: VERIFIED",flush=True);'
        'r=subprocess.run(["bash","-s"],input=p,check=False);'
        'sys.exit(r.returncode)'
    )
    return "set -eu\\npython3 -c '" + python + "' '" + encoded + "' '" + checksum + "'\\n"


def activate() -> None:
    gate.verify_aws()  # Exact existing EC2/SSM, OIDC role, region, schedule target.
    command = build_command()
    result = gate.aws_json(
        "ssm", "send-command", "--document-name", "AWS-RunShellScript",
        "--instance-ids", INSTANCE, "--timeout-seconds", "480",
        "--comment", "MintTick owner history timer only, no broker calls",
        "--parameters", json.dumps({"commands": [command]}),
    )
    identifier = result.get("Command", {}).get("CommandId", "")
    if not isinstance(identifier, str) or not re.fullmatch(r"[0-9a-f-]{36}", identifier):
        raise gate.Rejected("SSM timer activation identifier absent")
    print("OWNER_TIMER_SSM: SUBMITTED", flush=True)
    for step in range(85):
        time.sleep(5)
        try:
            data = gate.aws_json(
                "ssm", "get-command-invocation",
                "--command-id", identifier, "--instance-id", INSTANCE,
            )
        except gate.Rejected:
            if step < 5:
                continue
            raise
        status = data.get("Status")
        if status == "Success":
            # The SSM success flag alone is insufficient: require all actual
            # systemctl and calendar markers. Never print raw host output.
            output = str(data.get("StandardOutputContent") or "")
            if not REQUIRED_MARKERS.issubset(set(output.splitlines())):
                raise gate.Rejected("SSM timer success without enabled/active evidence")
            print("OWNER_TIMER_SSM: VERIFIED_ACTIVE_AND_ENABLED", flush=True)
            return
        if status in ("Failed", "TimedOut", "Cancelled", "Cancelling"):
            raise gate.Rejected("existing EC2 timer activation failed")
        if status not in ("Pending", "InProgress", "Delayed"):
            raise gate.Rejected("unrecognized SSM timer state")
    raise gate.Rejected("owner history timer activation deadline exceeded")


def main(argv: list[str]) -> int:
    try:
        if len(argv) != 3 or argv[1] not in ("validate", "activate"):
            raise ValueError("invalid timer invocation")
        validate_request(argv[2])
        if argv[1] == "activate":
            activate()
    except (ValueError, OSError, gate.Rejected) as e:
        print("OWNER_TIMER_BLOCKED: " + str(e if isinstance(e, gate.Rejected) else "invalid request"),
              file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
