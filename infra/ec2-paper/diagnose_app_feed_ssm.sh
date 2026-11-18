#!/usr/bin/env bash
# Read-only diagnostic of the application Angel One feed inputs on the PAPER host.
# Reads the four SSM parameters with the instance role and prints ONLY pass/fail per field.
# It never prints a value, a length, a digest or any account data. No writes, no broker calls.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
python3 - <<'PY'
import base64,re,subprocess,json
PREFIX="/minttick/dev/angelone/"
NAMES={"api-key","client-code","login-pin","totp-secret"}
def marker(name,value):
    print("MINTTICK_APP_FEED_SSM: "+name+"="+value,flush=True)
try:
    names=[PREFIX+n for n in sorted(NAMES)]
    p=subprocess.run(["aws","ssm","get-parameters","--region","ap-south-1","--with-decryption",
                      "--names"]+names+["--output","json"],
                     capture_output=True,text=True,timeout=60)
except (OSError,subprocess.TimeoutExpired):
    marker("ACCESS","UNAVAILABLE"); raise SystemExit(2)
if p.returncode!=0:
    marker("ACCESS","DENIED_OR_ERROR"); raise SystemExit(2)
try:
    body=json.loads(p.stdout)
except ValueError:
    marker("ACCESS","INVALID_RESPONSE"); raise SystemExit(2)
marker("ACCESS","OK")
found={}
for item in body.get("Parameters",[]) or []:
    name=str(item.get("Name","")).replace(PREFIX,"")
    if name in NAMES and isinstance(item.get("Value"),str):
        found[name]=item["Value"]
marker("INVALID_NAMES_COUNT",str(len(body.get("InvalidParameters",[]) or [])))
for name in sorted(NAMES):
    marker("PARAMETER_"+name.upper().replace("-","_"),"PRESENT" if name in found else "MISSING")
def ok(name,check):
    if name not in found: return False
    try: return bool(check(found[name]))
    except Exception: return False
api_key_ok=ok("api-key",lambda v: v.strip()!="" and len(v)<=256)
client_ok=ok("client-code",lambda v: re.fullmatch(r"[a-zA-Z0-9]{4,20}",v) is not None)
pin_ok=ok("login-pin",lambda v: re.fullmatch(r"[0-9]{4,8}",v) is not None)
def totp_ok(v):
    s=re.sub(r"[\s-]","",v).upper()
    s+="="*((8-len(s)%8)%8)
    base64.b32decode(s,casefold=True)
    return True
totp_format=ok("totp-secret",totp_ok)
marker("API_KEY_FORMAT","PASS" if api_key_ok else "FAIL")
marker("CLIENT_CODE_FORMAT","PASS" if client_ok else "FAIL")
marker("LOGIN_PIN_FORMAT","PASS" if pin_ok else "FAIL")
marker("TOTP_SECRET_BASE32","PASS" if totp_format else "FAIL")
marker("RESULT","COMPLETE")
PY
