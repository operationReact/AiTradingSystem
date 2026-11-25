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
    "source_ids": ["nse-announcements", "moneycontrol", "economic-times", "sebi"],
    "permitted_uses": ["INGEST", "RETAIN", "DISPLAY", "NLP_TRANSFER"],
    "reviewer_ref": "owner-attestation:2026-11-25",
    "terms_ref": "owner-attested-publisher-permissions:personal-noncommercial:2026-11-25",
    "content_ttl_days": 30,
}

REMOTE = r"""#!/usr/bin/env bash
set -euo pipefail
trap 'code=$?; echo "MINTTICK_NEWS_FAILED_STAGE:${stage:-BOOT}" >&2; exit "$code"' ERR
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
from datetime import datetime, timezone, timedelta
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
sources=("nse-announcements","moneycontrol","economic-times","sebi")
uses=["INGEST","RETAIN","DISPLAY","NLP_TRANSFER"]
now=datetime.now(timezone.utc).isoformat()
for source in sources:
    body=json.dumps({
      "licenseState":"APPROVED","permittedUses":uses,
      "reviewerRef":"owner-attestation:2026-11-25",
      "termsRef":"owner-attested-publisher-permissions:personal-noncommercial:2026-11-25",
      "effectiveFrom":now,"effectiveTo":(datetime.now(timezone.utc)+timedelta(days=30)).isoformat(),"contentTtlDays":30
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
 "MINTTICK_NEWS_ACTIVATED_AT":now,
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
import datetime,json,re,sys,time,urllib.error,urllib.request
from pathlib import Path
values={}
for line in Path(sys.argv[1]).read_text().splitlines():
    if line and not line.lstrip().startswith("#") and "=" in line:
        k,v=line.split("=",1)
        if k in values: raise SystemExit("DUPLICATE_RUNTIME_KEY")
        values[k]=v
key=values.get("APP_API_KEY","")
if not re.fullmatch(r"[0-9a-f]{64}",key): raise SystemExit("APP_KEY_INVALID")
try:
    activated=datetime.datetime.fromisoformat(values["MINTTICK_NEWS_ACTIVATED_AT"])
except (KeyError,ValueError):
    raise SystemExit("ACTIVATION_TIMESTAMP_MISSING")
url="http://127.0.0.1:8080/api/admin/market-intelligence/health"
health={}
def safe_health_exception(exc_type, exc, traceback):
    print("MINTTICK_NEWS_HEALTH_EXCEPTION:"+exc_type.__name__,file=sys.stderr,flush=True)
    try: emit_health_snapshot(health)
    except Exception: pass
sys.excepthook=safe_health_exception
def safe_health_snapshot(payload):
    model=payload.get("model",{})
    return {
      "flags":{k:payload.get(k) is True for k in
        ("ingestionEnabled","eventLedgerEnabled","analyzerBridgeEnabled",
         "analysisSchedulerEnabled","mindContextEnabled")},
      "provider":model.get("selectedProvider"),
      "sources":[{"sourceId":x.get("sourceId"),"status":x.get("latestStatus"),
                  "errorCode":x.get("lastErrorCode"),"items":x.get("lastFetchedItems"),
                  "inserted":x.get("lastInsertedItems"),
                  "rights":x.get("rightsState"),
                  "uses":[x.get(k) is True for k in
                    ("canIngest","canRetain","canDisplay","canTransferToModel")]}
                 for x in payload.get("sources",[]) if x.get("sourceId") in
                    ("nse-announcements","moneycontrol","economic-times","sebi")],
      "geminiAttempted":bool(model.get("lastGeminiAttemptAt")),
      "geminiOutcome":model.get("lastGeminiAttemptOutcome"),
      "lastSuccessfulAnalysisAtBySource":model.get("lastSuccessfulAnalysisAtBySource",{}),
    }
def emit_health_snapshot(payload):
    line="MINTTICK_NEWS_HEALTH_DIAG:"+json.dumps(safe_health_snapshot(payload),sort_keys=True)
    print(line,flush=True)
    print(line,file=sys.stderr,flush=True)
def utc_instant(value, label):
    if not isinstance(value,str):
        raise SystemExit("NEWS_INVALID_"+label+"_TIMESTAMP")
    match=re.fullmatch(
      r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?",
      value.strip())
    if not match:
        raise SystemExit("NEWS_INVALID_"+label+"_TIMESTAMP")
    fraction=(match.group(2) or "")[:6]
    offset=match.group(3) or "Z"
    normalized=match.group(1)+(("." + fraction.ljust(6,"0")) if fraction else "")
    normalized+=("+00:00" if offset=="Z" else offset)
    return datetime.datetime.fromisoformat(normalized)
deadline=time.monotonic()+1500
source_done=False
gemini_done=False
while time.monotonic()<deadline:
    req=urllib.request.Request(url,headers={"X-API-Key":key})
    try:
        with urllib.request.urlopen(req,timeout=15) as response:
            health=json.loads(response.read())
    except (urllib.error.URLError,TimeoutError,ValueError):
        time.sleep(20); continue
    flags=("ingestionEnabled","eventLedgerEnabled","analyzerBridgeEnabled",
           "analysisSchedulerEnabled","mindContextEnabled")
    if not all(health.get(k) is True for k in flags):
        emit_health_snapshot(health)
        raise SystemExit("NEWS_RUNTIME_FLAGS_NOT_ACTIVE")
    model=health.get("model",{})
    if model.get("selectedProvider")!="GEMINI":
        emit_health_snapshot(health)
        raise SystemExit("NEWS_GEMINI_NOT_SELECTED")
    for source_id in ("nse-announcements","moneycontrol","economic-times","sebi"):
        source=next((x for x in health.get("sources",[]) if x.get("sourceId")==source_id),None)
        if not source or source.get("rightsState")!="APPROVED" or not all(
            source.get(field) is True for field in
            ("canIngest","canRetain","canDisplay","canTransferToModel")):
            emit_health_snapshot(health)
            raise SystemExit("NEWS_SOURCE_POLICY_NOT_APPROVED")
    source_by_id={source.get("sourceId"):source for source in health.get("sources",[])}
    source_done=all(
        source_by_id.get(source_id,{}).get("latestStatus")=="SUCCESS"
        and source_by_id.get(source_id,{}).get("lastFinishedAt")
        and utc_instant(source_by_id[source_id]["lastFinishedAt"],"SOURCE")>=activated
        and (source_id=="sebi" or
             (source_by_id.get(source_id,{}).get("lastFetchedItems") or 0)>0)
        for source_id in ("nse-announcements","moneycontrol","economic-times","sebi"))
    # Feed-only batches may be fully deduplicated. In that case, the scheduler has
    # no unanalyzed row to select, so submit exactly one real, recent article through
    # the existing rights-gated analyzer and verify its persisted Gemini receipt.
    if source_done and not analysis_triggered:
        list_req=urllib.request.Request(
            "http://127.0.0.1:8080/api/news/articles?page=0&size=50",
            headers={"X-API-Key":key})
        try:
            with urllib.request.urlopen(list_req,timeout=15) as response:
                page=json.loads(response.read())
        except (urllib.error.URLError,TimeoutError,ValueError):
            page={}
        candidates=[item for item in page.get("items",[])
            if item.get("source") in ("nse-announcements","moneycontrol","economic-times","sebi")
            and item.get("publishedAt")
            and utc_instant(item["publishedAt"],"ARTICLE")>=activated-datetime.timedelta(days=30)]
        if candidates:
            article=candidates[0]
            analysis_triggered=True
            analyze_req=urllib.request.Request(
                "http://127.0.0.1:8080/api/news/articles/"+article["id"]+"/analyze?force=true",
                headers={"X-API-Key":key},method="POST")
            try:
                with urllib.request.urlopen(analyze_req,timeout=90) as response:
                    receipt=json.loads(response.read())
            except (urllib.error.URLError,TimeoutError,ValueError):
                raise SystemExit("NEWS_ANALYSIS_TRIGGER_FAILED")
            if receipt.get("status")!="ANALYZED" or receipt.get("provider")!="GEMINI" \\
                or not receipt.get("providerRequestId") or not receipt.get("analyzedAt") \\
                or utc_instant(receipt["analyzedAt"],"ANALYSIS")<activated:
                raise SystemExit("NEWS_ANALYSIS_RESULT_INVALID")
    attempted_at=model.get("lastGeminiAttemptAt")
    outcome=model.get("lastGeminiAttemptOutcome") or ""
    successful_analyses=model.get("lastSuccessfulAnalysisAtBySource") or {}
    fresh_analysis=any(
        utc_instant(value,"ANALYSIS")>=activated for value in successful_analyses.values())
    gemini_done=bool(fresh_analysis and attempted_at and
        utc_instant(attempted_at,"GEMINI")>=activated and
        outcome in ("COMPLETED_WITH_REQUEST_ID","COMPLETED_WITHOUT_REQUEST_ID"))
    if source_done and gemini_done:
        print("MINTTICK_NEWS_SOURCE_AND_GEMINI:VERIFIED")
        break
    time.sleep(20)
else:
    emit_health_snapshot(health)
    if not source_done: raise SystemExit("NEWS_SOURCE_EVIDENCE_TIMEOUT")
if not gemini_done:
    emit_health_snapshot(health)
    raise SystemExit("NEWS_GEMINI_EVIDENCE_TIMEOUT")
print("MINTTICK_NEWS_RUNTIME_FLAGS:READY")
PY
echo "MINTTICK_NEWS_ACTIVATION_OK:paper-only"
"""

def aws_json(*args: str) -> dict:
    proc = subprocess.run(["aws", *args, "--region", REGION, "--output", "json",
                           "--no-cli-pager"], capture_output=True, text=True, timeout=60)
    if proc.returncode:
        raise RuntimeError("AWS_REQUEST_FAILED")
    try:
        result = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("AWS_RESPONSE_INVALID") from exc
    if not isinstance(result, dict):
        raise RuntimeError("AWS_RESPONSE_INVALID")
    return result


def run() -> None:
    if len(sys.argv) != 2 or sys.argv[1] != "runner":
        raise RuntimeError("RUNNER_MODE_REQUIRED")
    if os.environ.get("AWS_REGION") != REGION:
        raise RuntimeError("WRONG_AWS_REGION")
    if not REQUEST_PATH.is_file() or REQUEST_PATH.is_symlink():
        raise RuntimeError("ACTIVATION_REQUEST_MISSING")
    if json.loads(REQUEST_PATH.read_text()) != EXPECTED:
        raise RuntimeError("EXACT_NEWS_ACTIVATION_REQUEST_MISMATCH")

    gate.verify_aws()
    sent = aws_json("ssm", "send-command", "--document-name", "AWS-RunShellScript",
        "--instance-ids", INSTANCE, "--timeout-seconds", "1800",
        "--comment", "MintTick news activation: attested PAPER sources only",
        "--parameters", json.dumps({"commands": [REMOTE]}))
    command_id = sent.get("Command", {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise RuntimeError("INVALID_SSM_COMMAND")

    for _ in range(180):
        time.sleep(10)
        try:
            result = aws_json("ssm", "get-command-invocation",
                "--command-id", command_id, "--instance-id", INSTANCE)
        except RuntimeError:
            continue
        if result.get("Status") == "Success":
            if MARKER + "paper-only" not in (result.get("StandardOutputContent") or "").splitlines():
                raise RuntimeError("NEWS_RECEIPT_MISSING")
            print("PAPER news activation succeeded; credentials and article contents were not returned")
            return
        if result.get("Status") not in {"Pending", "InProgress", "Delayed"}:
            error = result.get("StandardErrorContent") or ""
            output = result.get("StandardOutputContent") or ""
            diagnostics = error + "\n" + output
            stage_match = re.search(r"MINTTICK_NEWS_FAILED_STAGE:([A-Z_]+)", diagnostics)
            allowed = {"NEWS_SOURCE_EVIDENCE_TIMEOUT", "NEWS_GEMINI_EVIDENCE_TIMEOUT", "NEWS_ANALYSIS_TRIGGER_FAILED",\n                       "NEWS_ANALYSIS_RESULT_INVALID",
                       "NEWS_RUNTIME_FLAGS_NOT_ACTIVE", "NEWS_GEMINI_NOT_SELECTED",
                       "NEWS_SOURCE_POLICY_NOT_APPROVED", "NEWS_INVALID_SOURCE_TIMESTAMP",
                       "NEWS_INVALID_GEMINI_TIMESTAMP", "APP_KEY_INVALID",
                       "ACTIVATION_TIMESTAMP_MISSING", "UNSAFE_RUNTIME_FILE",
                       "DUPLICATE_RUNTIME_KEY", "POLICY_UPDATE_REJECTED",
                       "POLICY_RECEIPT_INVALID", "POLICY_UPDATE_FAILED",
                       "NEWS_HEALTH_EVIDENCE_EVALUATION_ERROR"}
            reason = next((item for item in allowed if item in diagnostics), None)
            exception_match = re.search(r"MINTTICK_NEWS_HEALTH_EXCEPTION:([A-Za-z][A-Za-z0-9_]*)", diagnostics)
            if not reason and exception_match: reason = "HEALTH_EXCEPTION_" + exception_match.group(1)
            stage = stage_match.group(1) if stage_match else "UNKNOWN"
            snapshot_match = re.search(r"MINTTICK_NEWS_HEALTH_DIAG:(\{[^\n]{1,3000}\})", diagnostics)
            snapshot = snapshot_match.group(1) if snapshot_match else ""
            raise RuntimeError("NEWS_ACTIVATION_FAILED:" + stage + ":" +
                               (reason or result.get("Status", "UNKNOWN")) +
                               (":" + snapshot if snapshot else ""))
    raise RuntimeError("NEWS_ACTIVATION_TIMEOUT")


if __name__ == "__main__":
    try:
        run()
    except Exception as exc:
        print(str(exc) if isinstance(exc, RuntimeError) else "NEWS_ACTIVATION_FAILED",
              file=sys.stderr)
        sys.exit(1)
