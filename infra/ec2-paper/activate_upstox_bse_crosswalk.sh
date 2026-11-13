#!/usr/bin/env bash
# Exact-host operator-only BSE secondary reference refresh. No order placement.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
for file in runtime.env .env paper-compose.yml; do
  test -f "$root/$file" && test ! -L "$root/$file"
done
grep -Fq 'TRADING_MODE: PAPER' "$root/paper-compose.yml"
python3 - "$root" <<'PY'
import hmac,json,pathlib,re,stat,subprocess,sys
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
root=pathlib.Path(sys.argv[1])
def block(reason):
    print("MINTTICK_BSE_BLOCKER:"+reason,flush=True)
    raise SystemExit(2)
def key(file):
    try:
        if stat.S_IMODE(file.stat().st_mode)!=0o600:return ""
        values=[x[12:] for x in file.read_text().splitlines() if x.startswith("APP_API_KEY=")]
        return values[0] if len(values)==1 and re.fullmatch(r"[!-~]{16,256}",values[0]) else ""
    except (OSError,ValueError):
        return ""
a,b=key(root/"runtime.env"),key(root/".env")
if not a or not b or not hmac.compare_digest(a,b):block("OPERATOR_KEY")
base=["docker","compose","--env-file",str(root/".env"),"-f",str(root/"paper-compose.yml"),
      "exec","-T","postgres","psql","-X","-v","ON_ERROR_STOP=1","-U","aitrading",
      "-d","aitrading","-Atq","-c"]
sql=("SELECT count(*) FROM instrument_master i JOIN instrument_catalogue_state s "
     "ON s.active_version=i.catalogue_version AND s.singleton_id=1 "
     "WHERE i.active AND i.exchange='BSE' AND i.exchange_segment='BSE_CM' "
     "AND upper(coalesce(i.instrument_type,''))='EQ';")
def count():
    try:
        p=subprocess.run(base+[sql],capture_output=True,text=True,timeout=18)
        if p.returncode==0 and re.fullmatch(r"[0-9]{1,6}",p.stdout.strip()):
            return int(p.stdout.strip())
    except (OSError,subprocess.TimeoutExpired):pass
    return -1
before=count()
if before<0:block("PREIMPORT_DB")
print("MINTTICK_BSE_EQ_BEFORE:"+str(before),flush=True)
try:
    req=Request("http://127.0.0.1:8080/api/instruments/upstox-bse-crosswalk/refresh",
       data=b"",method="POST",headers={"X-API-Key":a,"Accept":"application/json"})
    with urlopen(req,timeout=200) as response:
        if response.status!=200:block("REFRESH_HTTP")
        data=response.read(200001)
except HTTPError as error:
    # Never echo raw provider JSON, stack traces or credentials; only
    # operator-safe error codes and the HTTP category are emitted.
    print("MINTTICK_BSE_REFRESH_HTTP:HTTP_"+str(error.code),flush=True)
    try:
        payload=json.loads(error.read(16384))
        code=payload.get("code") if isinstance(payload,dict) else None
        if isinstance(code,str) and re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}",code):
            print("MINTTICK_BSE_REFRESH_CODE:"+code,flush=True)
    except (OSError,ValueError,UnicodeError):
        pass
    block("REFRESH_HTTP")
except (URLError,TimeoutError,OSError):
    block("REFRESH_NETWORK")
if len(data)>200000:block("REFRESH_RESPONSE")
try:
    report=json.loads(data)
    origin=report["source"]
    rec=report["reconciliation"]
    broker=report["brokerCatalogue"]
    if not isinstance(origin,dict) or not isinstance(rec,dict) or not isinstance(broker,dict):
        block("REFRESH_RESPONSE")
    eligible=origin["eligibleRows"]
    matched=rec["matched"]
    raw=rec["brokerBseRaw"]
    if any(type(v)!=int or v<0 or v>100000 for v in (eligible,matched,raw)):
        block("REFRESH_RESPONSE")
    ambiguous=rec["ambiguous"]
    if type(ambiguous)!=int or ambiguous<0 or ambiguous>100000:
        block("REFRESH_RESPONSE")
    # Capture only aggregate cross-broker identity overlap, not identifiers.
    overlap_sql=("""
    SELECT count(*),
      count(*) FILTER(WHERE upper(i.trading_symbol)=upper(u.trading_symbol)),
      count(*) FILTER(WHERE upper(regexp_replace(i.trading_symbol,'-(EQ|A)$',''))
        =upper(u.trading_symbol))
    FROM instrument_master i JOIN instrument_catalogue_state c
      ON c.active_version=i.catalogue_version AND c.singleton_id=1
    JOIN upstox_bse_reference_active a ON a.singleton_id=1
    JOIN upstox_bse_reference_equity u
      ON u.import_id=a.active_import AND u.exchange_token=i.symbol_token
    WHERE i.active AND i.exchange='BSE' AND i.exchange_segment='BSE_CM';
    """)
    try:
        probe=subprocess.run(base+[overlap_sql],capture_output=True,text=True,timeout=18)
        parts=probe.stdout.strip().split("|")
        if probe.returncode or len(parts)!=3 or any(
           not re.fullmatch(r"[0-9]{1,6}",v) for v in parts):
            block("SOURCE_OVERLAP_AUDIT")
    except (OSError,subprocess.TimeoutExpired):
        block("SOURCE_OVERLAP_AUDIT")
    print("MINTTICK_BSE_SOURCE_EQUITIES:"+str(eligible),flush=True)
    print("MINTTICK_BSE_RAW:"+str(raw),flush=True)
    print("MINTTICK_BSE_MATCHED:"+str(matched),flush=True)
    print("MINTTICK_BSE_SYMBOL_MISMATCH:"+str(ambiguous),flush=True)
    print("MINTTICK_BSE_TOKEN_OVERLAP:"+parts[0],flush=True)
    print("MINTTICK_BSE_EXACT_OVERLAP:"+parts[1],flush=True)
    print("MINTTICK_BSE_SUFFIX_OVERLAP:"+parts[2],flush=True)
    if eligible<100 or raw<100 or matched<1:
        block("NO_VALID_MATCHES")
except (TypeError,ValueError,KeyError,IndexError):
    block("REFRESH_RESPONSE")
after=count()
if after<1 or after<=before:block("NO_BSE_COVERAGE_GAIN")
print("MINTTICK_BSE_EQ_AFTER:"+str(after),flush=True)
print("MINTTICK_BSE_RESULT:VERIFIED",flush=True)
PY
