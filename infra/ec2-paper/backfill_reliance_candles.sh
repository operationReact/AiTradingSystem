#!/usr/bin/env bash
# Bounded, one-shot historical candle backfill for RELIANCE-EQ (instrument 417378) on the PAPER host.
# Uses the existing authorized operator endpoint on localhost with the host-local APP_API_KEY.
# Prints counts and markers only: never the key, never provider text, never order or account data.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
for f in runtime.env .env paper-compose.yml; do
  test -f "$root/$f" && test ! -L "$root/$f"
done
grep -Fq 'TRADING_MODE: PAPER' "$root/paper-compose.yml"
python3 - "$root" <<'PY'
import json,pathlib,re,stat,subprocess,sys,time
from urllib.request import Request,urlopen
from urllib.error import HTTPError
root=pathlib.Path(sys.argv[1])
INSTRUMENT_ID=417378
SYMBOL="RELIANCE-EQ"
EXCHANGE="NSE"
FROM="2026-11-03"
TO="2026-11-17"
INTERVALS=("FIVE_MINUTE","ONE_DAY")
BASE="http://127.0.0.1:8080"
def block(stage):
    print("MINTTICK_BACKFILL_BLOCKER:",stage,flush=True)
    raise SystemExit(2)
def operator_key(path):
    try:
        if stat.S_IMODE(path.stat().st_mode)!=0o600:
            return ""
        vals=[line[len("APP_API_KEY="):] for line in path.read_text().splitlines()
              if line.startswith("APP_API_KEY=")]
        return vals[0] if len(vals)==1 and re.fullmatch(r"[!-~]{16,256}",vals[0]) else ""
    except (OSError,ValueError):
        return ""
key=operator_key(root/"runtime.env")
if not key:
    block("OPERATOR_KEY")
base=["docker","compose","--env-file",str(root/".env"),"-f",str(root/"paper-compose.yml"),
      "exec","-T","postgres","psql","-X","-v","ON_ERROR_STOP=1","-U","aitrading","-d","aitrading","-Atq","-c"]
def db(query):
    try:
        p=subprocess.run(base+[query],capture_output=True,text=True,timeout=30)
        if p.returncode!=0:
            return None
        return p.stdout.strip()
    except (OSError,subprocess.TimeoutExpired):
        return None
def stats(interval,stage):
    q=("SELECT count(*)||'|'||coalesce(to_char(min(candle_time) AT TIME ZONE 'Asia/Kolkata','YYYY-MM-DD HH24:MI'),'-')"
       "||'|'||coalesce(to_char(max(candle_time) AT TIME ZONE 'Asia/Kolkata','YYYY-MM-DD HH24:MI'),'-')"
       "||'|'||count(DISTINCT candle_time) FROM market_candle WHERE instrument_id="+str(INSTRUMENT_ID)+
       " AND interval='"+interval+"';")
    raw=db(q)
    if raw is None or not re.fullmatch(r"[0-9]{1,8}\|[^|]*\|[^|]*\|[0-9]{1,8}",raw):
        block(stage)
    count,earliest,latest,distinct=raw.split("|")
    return {"count":int(count),"earliest":earliest,"latest":latest,"distinct":int(distinct)}
def call(method,path,body=None):
    data=None if body is None else json.dumps(body).encode()
    headers={"X-API-Key":key,"Accept":"application/json"}
    if data is not None:
        headers["Content-Type"]="application/json"
    request=Request(BASE+path,headers=headers,data=data,method=method)
    try:
        with urlopen(request,timeout=240) as response:
            if response.status!=200:
                block("BACKEND_HTTP")
            raw=response.read(65537)
    except HTTPError as exc:
        print("MINTTICK_BACKFILL_HTTP_STATUS: HTTP_"+str(exc.code),flush=True)
        block("BACKEND_HTTP")
    except Exception:
        block("BACKEND_HTTP")
    if len(raw)>65536:
        block("BACKEND_RESPONSE")
    try:
        result=json.loads(raw)
    except ValueError:
        block("BACKEND_RESPONSE")
    if not isinstance(result,dict):
        block("BACKEND_RESPONSE")
    return result
print("MINTTICK_BACKFILL_INSTRUMENT: "+SYMBOL+" "+EXCHANGE+" "+str(INSTRUMENT_ID),flush=True)
print("MINTTICK_BACKFILL_WINDOW: "+FROM+" .. "+TO,flush=True)
for interval in INTERVALS:
    before=stats(interval,"PREIMPORT_DB")
    run=call("POST","/api/market-data/backfills",{"instrumentId":INSTRUMENT_ID,"interval":interval,
                                                  "fromDate":FROM,"toDate":TO})
    run_id=run.get("id")
    status=run.get("status")
    for _ in range(40):
        if status in ("SUCCEEDED","PARTIAL","FAILED") or not isinstance(run_id,str):
            break
        time.sleep(5)
        run=call("GET","/api/market-data/backfills/"+run_id)
        status=run.get("status")
    after=stats(interval,"POSTIMPORT_DB")
    fetched=int(run.get("fetchedRows") or 0)
    persisted=int(run.get("persistedRows") or 0)
    print("MINTTICK_BACKFILL_INTERVAL: "+interval,flush=True)
    print("MINTTICK_BACKFILL_RUN_STATUS: "+str(status),flush=True)
    print("MINTTICK_BACKFILL_CHUNKS: "+str(int(run.get("chunksCompleted") or 0)),flush=True)
    print("MINTTICK_BACKFILL_FETCHED: "+str(fetched),flush=True)
    print("MINTTICK_BACKFILL_PERSISTED: "+str(persisted),flush=True)
    print("MINTTICK_BACKFILL_DB_BEFORE: "+str(before["count"]),flush=True)
    print("MINTTICK_BACKFILL_DB_AFTER: "+str(after["count"]),flush=True)
    print("MINTTICK_BACKFILL_DB_NEW_ROWS: "+str(after["count"]-before["count"]),flush=True)
    print("MINTTICK_BACKFILL_DB_DISTINCT_TIMESTAMPS: "+str(after["distinct"]),flush=True)
    print("MINTTICK_BACKFILL_DB_EARLIEST_IST: "+after["earliest"],flush=True)
    print("MINTTICK_BACKFILL_DB_LATEST_IST: "+after["latest"],flush=True)
    error_code=run.get("errorCode")
    if isinstance(error_code,str) and re.fullmatch(r"[A-Z][A-Z0-9_]{1,59}",error_code):
        print("MINTTICK_BACKFILL_ERROR_CODE: "+error_code,flush=True)
    if status!="SUCCEEDED":
        block("BACKFILL_NOT_SUCCEEDED")
print("MINTTICK_BACKFILL_RESULT: SUCCEEDED",flush=True)
PY
# No scheduler, no retries, no broker order path. Exits non-zero on any blocker.
