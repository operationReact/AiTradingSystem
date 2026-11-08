#!/usr/bin/env bash
# Read-only, small SSM payload: market catalogue import diagnostics without secrets.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test -f "$root/.env" && test ! -L "$root/.env"
test -f "$root/paper-compose.yml" && test ! -L "$root/paper-compose.yml"
python3 - "$root" <<'PY'
import pathlib,re,subprocess,sys,hmac
from urllib.request import Request,urlopen
from urllib.error import HTTPError
root=pathlib.Path(sys.argv[1])
try:
    lines=(root/"runtime.env").read_text().splitlines()
    vals=[line[12:] for line in lines if line.startswith("APP_API_KEY=")]
    key_ok=(len(vals)==1 and re.fullmatch(r"[!-~]{16,256}",vals[0]) is not None
            and (root/"runtime.env").stat().st_mode & 0o077 == 0)
    runtime_key=vals[0] if key_ok else ""
except (OSError,ValueError):
    key_ok=False
    runtime_key=""
try:
    env=[line[12:] for line in (root/".env").read_text().splitlines()
         if line.startswith("APP_API_KEY=")]
    compose_key=env[0] if len(env)==1 and env[0] else ""
except (OSError,ValueError):
    compose_key=""
key_state=("MATCH" if runtime_key and compose_key and hmac.compare_digest(runtime_key,compose_key)
           else "MISMATCH" if runtime_key and compose_key else "MISSING")
# Owner allowlist is required for the verified Cognito account to see
# "Secure connect". Never reveal the subject, email or credential values.
owner=[line.split("=",1)[1].strip() for line in lines
       if line.startswith("MINTTICK_ANGEL_ONE_OWNER_SUB=")]
print("MINTTICK_OWNER_BINDING:",
      "PRESENT" if len(owner)==1 and re.fullmatch(r"[A-Za-z0-9_-]{10,128}",owner[0])
      else "MISSING")
try:
    compose=(root/"paper-compose.yml").read_text()
    enabled='MINTTICK_ANGEL_ONE_SSM_STREAM_ENABLED: "true"' in compose
except OSError:
    enabled=False
print("MINTTICK_PRIVATE_FEED_MODE:", "ENABLED" if enabled else "NOT_ENABLED")
print("MINTTICK_OPERATOR_ENV_KEYS:",key_state)
print("MINTTICK_OPERATOR_KEY:", "PRESENT" if key_ok else "MISSING_OR_INVALID")
try:
    with urlopen(Request("http://127.0.0.1:8080/api/instruments/status"),
                 timeout=5) as response:
        http=response.status
except HTTPError as exc:
    http=exc.code
except Exception:
    http=0
print("MINTTICK_LOCAL_IMPORT_API:", "REACHABLE" if 200<=http<500 else "UNREACHABLE")
try:
    if not runtime_key:
        auth="UNAVAILABLE"
    else:
        request=Request("http://127.0.0.1:8080/api/instruments/status",
                        headers={"X-API-Key":runtime_key})
        try:
            with urlopen(request,timeout=5) as response:
                auth="ACCEPTED" if response.status==200 else "REJECTED"
        except HTTPError as e:
            auth="REJECTED" if e.code in (401,403,404) else "UNAVAILABLE"
except Exception:
    auth="UNAVAILABLE"
print("MINTTICK_OPERATOR_AUTH:",auth)
base=["docker","compose","--env-file",str(root/".env"),"-f",str(root/"paper-compose.yml"),
      "exec","-T","postgres","psql","-X","-v","ON_ERROR_STOP=1","-U","aitrading",
      "-d","aitrading","-Atq","-c"]
def db(sql):
    try:
        result=subprocess.run(base+[sql],capture_output=True,text=True,timeout=15,check=False)
        return result.stdout.strip() if result.returncode==0 else None
    except (OSError,subprocess.TimeoutExpired):
        return None
value=db("SELECT status || '|' || COALESCE(error_code,'') FROM instrument_import_run ORDER BY started_at DESC LIMIT 1;")
if value is None or "\n" in value:
    status,err="UNKNOWN","OTHER"
elif not value:
    status,err="NONE","NONE"
else:
    parts=value.split("|")
    status,err=parts if len(parts)==2 else ("UNKNOWN","OTHER")
    if status not in {"RUNNING","FAILED","SUCCEEDED"}:
        status,err="UNKNOWN","OTHER"
allowed={"SOURCE_UNAVAILABLE","SOURCE_TIMEOUT","SOURCE_EMPTY","SOURCE_TOO_LARGE",
         "SOURCE_INTERRUPTED","NO_VALID_INSTRUMENTS","TOO_MANY_REJECTED_ROWS",
         "INCOMPLETE_CATALOGUE","CONFLICTING_DUPLICATE","IMPORT_FAILED"}
err=("NONE" if status!="FAILED" else
     err if err in allowed or re.fullmatch(r"SOURCE_HTTP_[1-5][0-9]{2}",err) else "OTHER")
print("MINTTICK_LAST_IMPORT_STATUS:",status)
print("MINTTICK_LAST_IMPORT_ERROR:",err)
n=db("SELECT count(*) FROM instrument_master i JOIN instrument_catalogue_state s ON s.active_version=i.catalogue_version WHERE s.singleton_id=1 AND i.active=TRUE AND i.exchange_segment IN ('NSE_CM','BSE_CM') AND i.trading_symbol LIKE '%-EQ';")
if n is not None and re.fullmatch(r"[0-9]{1,6}",n):
    print("MINTTICK_CASH_EQUITY_INSTRUMENTS:",n)
else:
    print("MINTTICK_CASH_EQUITY_INSTRUMENTS: UNAVAILABLE")
PY
