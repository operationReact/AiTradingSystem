#!/usr/bin/env python3
"""Refresh the exact already-deployed PAPER image after secure Gemini binding."""
import json, os, re, subprocess, sys, time
sys.path.insert(0, "infra/ec2-paper")
import verify_ssm_release as gate

SHA = "1c2bfae773f7ffb10c6c0a0c3406564e6f9962d5"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
MARKER = "MINTTICK_GEMINI_RELOAD_OK:"


def aws(*args):
    p = subprocess.run(["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
                       capture_output=True, text=True, timeout=60)
    if p.returncode:
        raise RuntimeError("AWS_REQUEST_FAILED")
    return json.loads(p.stdout)


def run():
    gate.verify_aws()
    cmd = """set -euo pipefail
test "$(id -u)" = 0
test "$(uname -m)" = aarch64
cd /opt/aitradingsystem-paper
test -x ./run_paper.sh
current="$(docker ps --filter label=com.docker.compose.project=aitradingsystem-paper --filter label=com.docker.compose.service=backend --format '{{.Image}}' | sed -n 's/^aitradingsystem-paper-backend-arm64:\\([0-9a-f]\\{40\\}\\)$/\\1/p')"
test "$current" = "__SHA__"
grep -q '^      TRADING_MODE: PAPER$' paper-compose.yml
./run_paper.sh "__SHA__"
printf '__MARKER__%s\\n' "__SHA__"
"""
    cmd=cmd.replace("__SHA__",SHA).replace("__MARKER__",MARKER)
    out=aws("ssm","send-command","--document-name","AWS-RunShellScript","--instance-ids",INSTANCE,
            "--timeout-seconds","240","--comment","MintTick Gemini activation: refresh existing PAPER image",
            "--parameters",json.dumps({"commands":[cmd]}))
    cid=out.get("Command",{}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch(r"[0-9a-f-]{36}",cid):
        raise RuntimeError("INVALID_SSM_COMMAND")
    for _ in range(55):
        time.sleep(3)
        r=aws("ssm","get-command-invocation","--command-id",cid,"--instance-id",INSTANCE)
        if r.get("Status")=="Success":
            lines=(r.get("StandardOutputContent") or "").splitlines()
            if MARKER+SHA not in lines:
                raise RuntimeError("RELOAD_RECEIPT_MISSING")
            print("Existing PAPER backend reloaded on pinned release; health gates passed")
            return
        if r.get("Status") not in {"Pending","InProgress","Delayed"}:
            err=r.get("StandardErrorContent") or ""
            m=re.search(r"MINTTICK_PAPER_FAILED_STAGE:([A-Z_]+)",err)
            raise RuntimeError("PAPER_RELOAD_FAILED_STAGE:"+(m.group(1) if m else "UNCLASSIFIED"))
    raise RuntimeError("PAPER_RELOAD_TIMEOUT")


if __name__=="__main__":
    run()
