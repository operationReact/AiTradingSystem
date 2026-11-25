#!/usr/bin/env python3
"""Activate attested news ingestion on the existing PAPER host through OIDC and SSM."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, "infra/ec2-paper")
import verify_ssm_release as gate

REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
ACCOUNT = "098859530205"
REQUEST_PATH = Path("infra/ec2-paper/requests/news-runtime-activate.json")
MARKER = "MINTTICK_NEWS_ACTIVATION_OK:"
EXPECTED = {
    "kind": "minttick-news-runtime-activate-v1",
    "account": ACCOUNT,
    "region": REGION,
    "instance_id": INSTANCE,
    "environment": "PAPER",
    "operation": "activate",
    "source_ids": ["moneycontrol", "economic-times", "sebi"],
    "permitted_uses": ["INGEST", "RETAIN", "DISPLAY", "NLP_TRANSFER"],
    "reviewer_ref": "owner-attestation:2026-11-25",
    "terms_ref": "owner-attested-publisher-permissions:personal-noncommercial:2026-11-25",
    "content_ttl_days": 30,
}

REMOTE = r"""#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test -x "$root/run_paper.sh" && test ! -L "$root/run_paper.sh"
chmod 0600 "$root/runtime.env"
stage=POLICY
python3 - "$root/runtime.env" <<'PY'
import json, os, re, stat, sys, tempfile, urllib.error, urllib.request
from datetime import datetime, timezone
from pathlib import Path
p=Path(sys.argv[1])
if not p.is_file() or p.is_symlink() or stat.S_IMODE(p.stat().st_mode) != 0o600:
    raise SystemExit("UNSAFE_RUNTIME_FILE")
lines=p.read_text().splitlines()
values={}
for line in lines:
    if not line or line.lstrip().startswith("#") or "=" not in line: continue
    k,v=line.split("=",1)
    if k in values: raise SystemExit("DUPLICATE_RUNTIME_KEY")
    values[k]=v
key=values.get("APP_API_KEY","")
if not re.fullmatch(r"[0-9a-f]{64}", key): raise SystemExit("APP_KEY_INVALID")
sources=("moneycontrol","economic-times","sebi")
uses=["INGEST","RETAIN","DISPLAY","NLP_TRANSFER"]
now=datetime.now(timezone.utc).isoformat()
for source in sources:
    body=json.dumps({
      "licenseState":"APPROVED","permittedUses":uses,
      "reviewerRef":"owner-attestation:2026-11-25",
      "termsRef":"owner-attested-publisher-permissions:personal-noncommercial:2026-11-25",
      "effectiveFrom":now,"effectiveTo":None,"contentTtlDays":30
    }).encode()
    req=urllib.request.Request(
      "http://127.0.0.1:8080/api/admin/market-intelligence/sources/"+source+"/policy",
      data=body,headers={"Content-Type":"application/json","X-API-Key":key},method="PUT")
    try:
        with urllib.request.urlopen(req,timeout=15) as response:
            if response.status != 200: raise SystemExit("POLICY_UPDATE_REJECTED")
            result=json.loads(response.read())
            if result.get("sourceId") != source or result.get("licenseState") != "APPROVED":
                raise SystemExit("POLICY_RECEIPT_INVALID")
    except (urllib.error.URLError, TimeoutError, ValueError):
        raise SystemExit("POLICY_UPDATE_FAILED")
updates={
 "NEWS_INGESTION_ENABLED":"true",
 "NEWS_EVENT_LEDGER_ENABLED":"true",
 "NEWS_SUMMARIZATION_ENABLED":"false",
 "NEWS_ANALYZER_BRIDGE_ENABLED":"true",
 "NEWS_ANALYSIS_SCHEDULER_ENABLED":"true",
 "NEWS_MIND_CONTEXT_ENABLED":"true",
 "NEWS_ANALYZER_APPROVED_SOURCES":",".join(sources),
 "NEWS_ANALYSIS_PROVIDER":"GEMINI",
 "NEWS_INGESTION_INTERVAL_MS":"900000",
 "NEWS_INGESTION_SOURCES":",".join(sources),
 "NEWS_WATCHLIST":"RELIANCE,TCS,INFY,HDFCBANK,ICICIBANK,SBIN,ITC",
}
for k,v in updates.items():
    if k in values: lines=[line for line in lines if not line.startswith(k+"=")]
    lines.append(k+"="+v)
fd,tmp=tempfile.mkstemp(prefix=".runtime-news-",dir=str(p.parent))
try:
    with os.fdopen(fd,"w") as out:
        out.write("\n".join(lines)+"\n"); out.flush(); os.fsync(out.fileno())
    os.chmod(tmp,0o600); os.replace(tmp,p)
finally:
    if os.path.exists(tmp): os.unlink(tmp)
PY
stage=RELOAD
current="$(docker ps --filter label=com.docker.compose.project=aitradingsystem-paper --filter label=com.docker.compose.service=backend --format '{{.Image}}' | sed -n 's/^aitradingsystem-paper-backend-arm64:\([0-9a-f]\{40\}\)$/\1/p')"
[[ "$current" =~ ^[0-9a-f]{40}$ ]]
cd "$root"
./run_paper.sh "$current"
stage=HEALTH
python3 - "$root/runtime.env" <<'PY'
import json,re,sys,urllib.error,urllib.request
from pathlib import Path
values={}
for line in Path(sys.argv[1]).read_text().splitlines():
    if line and not line.lstrip().startswith("#") and "=" in line:
        k,v=line.split("=",1)
        if k in values: raise SystemExit("DUPLICATE_RUNTIME_KEY")
        values[k]=v
key=values.get("APP_API_KEY","")
if not re.fullmatch(r"[0-9a-f]{64}",key): raise SystemExit("APP_KEY_INVALID")
req=urllib.request.Request("http://127.0.0.1:8080/api/admin/market-intelligence/health",
    headers={"X-API-Key":key})
try:
    with urllib.request.urlopen(req,timeout=15) as response:
        health=json.loads(response.read())
except (urllib.error.URLError,TimeoutError,ValueError):
    raise SystemExit("NEWS_HEALTH_UNAVAILABLE")
expected=("ingestionEnabled","eventLedgerEnabled","analyzerBridgeEnabled",
          "analysisSchedulerEnabled","mindContextEnabled")
if not all(health.get(k) is True for k in expected):
    raise SystemExit("NEWS_RUNTIME_FLAGS_NOT_ACTIVE")
if health.get("model",{}).get("selectedProvider") != "GEMINI":
    raise SystemExit("NEWS_GEMINI_NOT_SELECTED")
if any(source.get("rightsState") != "APPROVED" or
       not all(source.get(field) is True for field in
               ("canIngest","canRetain","canDisplay","canTransferToModel"))
       for source in health.get("sources",[])):
    raise SystemExit("NEWS_SOURCE_POLICY_NOT_APPROVED")
print("MINTTICK_NEWS_RUNTIME_FLAGS:READY")
PY
echo "MINTTICK_NEWS_ACTIVATION_OK:paper-only"
""";

function awsJson(...args) {
  const p = subprocess.run(["aws", ...args, "--region", REGION, "--output", "json",
      "--no-cli-pager"], capture_output=True, text=True, timeout=60);
  if (p.returncode) throw new Error("AWS_REQUEST_FAILED");
  return JSON.parse(p.stdout);
}

function validateRequest() {
  const data = JSON.parse(REQUEST_PATH.read_text());
  if (JSON.stringify(data) !== JSON.stringify(EXPECTED)) {
    throw new Error("EXACT_NEWS_ACTIVATION_REQUEST_MISMATCH");
  }
}

function run() {
  if (process.argv[2] !== "runner") throw new Error("RUNNER_MODE_REQUIRED");
  if (process.env.AWS_REGION !== REGION) throw new Error("WRONG_AWS_REGION");
  validateRequest();
  gate.verify_aws();
  const sent = awsJson("ssm", "send-command",
    "--document-name", "AWS-RunShellScript",
    "--instance-ids", INSTANCE,
    "--timeout-seconds", "1800",
    "--comment", "MintTick news activation: attested PAPER sources only",
    "--parameters", JSON.stringify({commands: [REMOTE]}));
  const commandId = sent?.Command?.CommandId;
  if (typeof commandId !== "string" || !/^[0-9a-f-]{36}$/.test(commandId)) {
    throw new Error("INVALID_SSM_COMMAND");
  }
  for (let attempt = 0; attempt < 90; attempt += 1) {
    time.sleep(10);
    let result;
    try {
      result = awsJson("ssm", "get-command-invocation", "--command-id", commandId,
        "--instance-id", INSTANCE);
    } catch {
      continue;
    }
    if (result.Status === "Success") {
      const lines = (result.StandardOutputContent || "").split("\n");
      if (!lines.includes(MARKER + "paper-only")) throw new Error("NEWS_RECEIPT_MISSING");
      console.log("PAPER news activation succeeded; credentials and article contents were not returned");
      return;
    }
    if (!["Pending", "InProgress", "Delayed"].includes(result.Status)) {
      const output = result.StandardErrorContent || "";
      const stage = output.match(/MINTTICK_PAPER_FAILED_STAGE:([A-Z_]+)/);
      throw new Error("NEWS_ACTIVATION_FAILED:" + (stage?.[1] || result.Status));
    }
  }
  throw new Error("NEWS_ACTIVATION_TIMEOUT");
}

try {
  run();
} catch (error) {
  console.error(error instanceof Error ? error.message : "NEWS_ACTIVATION_FAILED");
  process.exitCode = 1;
}
