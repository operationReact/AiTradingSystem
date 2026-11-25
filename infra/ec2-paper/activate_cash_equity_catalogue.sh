#!/usr/bin/env bash
# Compact guarded one-time official cash-equity import. No broker trades.
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
import hmac,json,pathlib,re,stat,subprocess,sys
from urllib.request import Request,urlopen
from urllib.error import HTTPError
root=pathlib.Path(sys.argv[1])
def block(stage):
    print("MINTTICK_CATALOGUE_BLOCKER:",stage,flush=True)
    raise SystemExit(2)
def key(path):
    try:
        if stat.S_IMODE(path.stat().st_mode)!=0o600:
            return ""
        vals=[line[12:] for line in path.read_text().splitlines()
              if line.startswith("APP_API_KEY=")]
        return vals[0] if len(vals)==1 and re.fullmatch(r"[!-~]{16,256}",vals[0]) else ""
    except (OSError,ValueError):
        return ""
key_runtime=key(root/"runtime.env")
key_compose=key(root/".env")
if not key_runtime or not key_compose or not hmac.compare_digest(key_runtime,key_compose):
    block("OPERATOR_KEY")
base=["docker","compose","--env-file",str(root/".env"),
      "-f",str(root/"paper-compose.yml"),"exec","-T","postgres",
      "psql","-X","-v","ON_ERROR_STOP=1","-U","aitrading",
      "-d","aitrading","-Atq","-c"]
query=("SELECT count(*) FROM instrument_master i JOIN instrument_catalogue_state c "
       "ON c.active_version=i.catalogue_version WHERE c.singleton_id=1 "
       "AND i.active=TRUE AND i.exchange_segment IN ('NSE_CM','BSE_CM') "
       "AND i.trading_symbol LIKE '%-EQ';")
def cash_count():
    try:
        p=subprocess.run(base+[query],capture_output=True,text=True,timeout=16)
        raw=p.stdout.strip()
        if p.returncode==0 and re.fullmatch(r"[0-9]{1,6}",raw):
            return int(raw)
    except (OSError,subprocess.TimeoutExpired):
        pass
    return -1
before=cash_count()
if before<0:
    block("PREIMPORT_DB")
if before>0:
    print("MINTTICK_CATALOGUE_IMPORT: ALREADY_ACTIVE",flush=True)
    print("MINTTICK_CASH_EQUITY_INSTRUMENTS:",before,flush=True)
    raise SystemExit(0)
try:
    request=Request("http://127.0.0.1:8080/api/instruments/import",
                    headers={"X-API-Key":key_runtime,"Accept":"application/json"},
                    data=b"",method="POST")
    with urlopen(request,timeout=190) as response:
        code=response.status
        body=response.read(65537)
except HTTPError as exc:
    if 400<=exc.code<=599:
        print("MINTTICK_CATALOGUE_HTTP_STATUS: HTTP_"+str(exc.code),flush=True)
    block("BACKEND_HTTP")
except Exception:
    block("BACKEND_HTTP")
if code!=200 or len(body)>65536:
    block("BACKEND_HTTP")
try:
    result=json.loads(body)
    if not isinstance(result,dict):
        block("BACKEND_RESPONSE")
    if result.get("status")!="SUCCEEDED":
        error=result.get("errorCode")
        allowed={"SOURCE_UNAVAILABLE","SOURCE_TIMEOUT","SOURCE_EMPTY",
                 "SOURCE_TOO_LARGE","SOURCE_INTERRUPTED","NO_VALID_INSTRUMENTS",
                 "TOO_MANY_REJECTED_ROWS","INCOMPLETE_CATALOGUE",
                 "CONFLICTING_DUPLICATE","IMPORT_FAILED"}
        if isinstance(error,str) and (error in allowed or
               re.fullmatch(r"SOURCE_HTTP_[1-5][0-9]{2}",error)):
            print("MINTTICK_CATALOGUE_ERROR:",error,flush=True)
        block("BACKEND_RESPONSE")
    if type(result.get("activeCount")) is not int or result["activeCount"]<1:
        block("BACKEND_RESPONSE")
except (TypeError,ValueError,UnicodeError):
    block("BACKEND_RESPONSE")
after=cash_count()
if after<1:
    block("POSTIMPORT_DB")
print("MINTTICK_CATALOGUE_IMPORT: SUCCESS",flush=True)
print("MINTTICK_CASH_EQUITY_INSTRUMENTS:",after,flush=True)
PY
