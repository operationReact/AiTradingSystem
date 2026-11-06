"""MintTick read-only Angel One market-data Lambda.

Direct IAM invocation only. No HTTP gateway, order endpoints, trading authority or secrets in responses.
The Lambda execution role may read exactly four SSM SecureString parameters in ap-south-1.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import struct
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from threading import Lock
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

REGION = "ap-south-1"
API_ROOT = "https://apiconnect.angelone.in"
SSM_PREFIX = "/minttick/dev/angelone"
NAMES = {
    "api_key": SSM_PREFIX + "/api-key",
    "client_code": SSM_PREFIX + "/client-code",
    "pin": SSM_PREFIX + "/login-pin",
    "totp_secret": SSM_PREFIX + "/totp-secret",
}
LOGIN_PATH = "/rest/auth/angelbroking/user/v1/loginByPassword"
LTP_PATH = "/rest/secure/angelbroking/order/v1/getLtpData"
CANDLE_PATH = "/rest/secure/angelbroking/historical/v1/getCandleData"
REAUTH_CODES = {"AG8001", "AG8003", "AB8050", "AB8051", "AB1010"}
EXCHANGES = {"NSE", "BSE"}
INTERVALS = {
    "ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE", "TEN_MINUTE",
    "FIFTEEN_MINUTE", "THIRTY_MINUTE", "ONE_HOUR", "ONE_DAY",
}
_LOCK = Lock()
_CACHE = {"secrets": None, "secrets_loaded_at": 0.0, "jwt": None, "jwt_expires": 0.0}
_LAST_CALL = 0.0

class FeedError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code

def _secrets():
    now = time.monotonic()
    if _CACHE["secrets"] is not None and now - _CACHE["secrets_loaded_at"] < 300:
        return _CACHE["secrets"]
    # Imported only in the Lambda runtime; makes offline tests dependency-free.
    import boto3
    try:
        result = boto3.client("ssm", region_name=REGION).get_parameters(
            Names=list(NAMES.values()), WithDecryption=True)
        found = {item["Name"]: item["Value"] for item in result.get("Parameters", [])
                 if item.get("Type") == "SecureString"}
        if set(found) != set(NAMES.values()):
            raise FeedError("CREDENTIALS_NOT_CONFIGURED")
        values = {key: found[name] for key, name in NAMES.items()}
        if not all(isinstance(value, str) and value.strip() for value in values.values()):
            raise FeedError("CREDENTIALS_NOT_CONFIGURED")
        _CACHE["secrets"] = values
        _CACHE["secrets_loaded_at"] = now
        return values
    except FeedError:
        raise
    except Exception:
        raise FeedError("SSM_UNAVAILABLE") from None

def _totp(secret, now=None):
    clean = re.sub(r"\s+", "", secret).upper()
    if not re.fullmatch(r"[A-Z2-7]+=*", clean):
        raise FeedError("INVALID_TOTP_CONFIGURATION")
    try:
        raw = base64.b32decode(clean + "=" * ((-len(clean)) % 8))
        counter = int(time.time() if now is None else now) // 30
        digest = hmac.new(raw, struct.pack(">Q", counter), hashlib.sha1).digest()
        offset = digest[-1] & 0x0F
        number = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1000000
        return f"{number:06d}"
    except (ValueError, TypeError, base64.binascii.Error):
        raise FeedError("INVALID_TOTP_CONFIGURATION") from None

def _pace():
    global _LAST_CALL
    delta = time.monotonic() - _LAST_CALL
    if delta < 1.05:
        time.sleep(1.05 - delta)
    _LAST_CALL = time.monotonic()

def _post(path, payload, jwt, credentials):
    if path not in (LOGIN_PATH, LTP_PATH, CANDLE_PATH):
        raise FeedError("DISALLOWED_OPERATION")
    _pace()
    headers = {
        "Content-Type": "application/json", "Accept": "application/json",
        "X-PrivateKey": credentials["api_key"], "X-UserType": "USER",
        "X-SourceID": "WEB",
    }
    if jwt:
        headers["Authorization"] = "Bearer " + jwt
    req = Request(API_ROOT + path, data=json.dumps(payload).encode("utf-8"),
                  headers=headers, method="POST")
    try:
        with urlopen(req, timeout=7) as response:
            content = response.read(1_000_001)
            if len(content) > 1_000_000:
                raise FeedError("PROVIDER_INVALID_RESPONSE")
    except HTTPError as exc:
        if exc.code == 401 and jwt:
            raise FeedError("SESSION_EXPIRED") from None
        if exc.code in (403, 429):
            raise FeedError("PROVIDER_RATE_LIMITED") from None
        raise FeedError("PROVIDER_UNAVAILABLE") from None
    except (URLError, TimeoutError, OSError):
        raise FeedError("PROVIDER_UNAVAILABLE") from None
    try:
        result = json.loads(content)
        if not isinstance(result, dict) or not isinstance(result.get("status"), bool):
            raise FeedError("PROVIDER_INVALID_RESPONSE")
        if result["status"] is False:
            if str(result.get("errorcode", "")) in REAUTH_CODES and jwt:
                raise FeedError("SESSION_EXPIRED")
            raise FeedError("PROVIDER_REJECTED")
        data = result.get("data")
        if not isinstance(data, (dict, list)):
            raise FeedError("PROVIDER_INVALID_RESPONSE")
        return data
    except FeedError:
        raise
    except (ValueError, TypeError, UnicodeError):
        raise FeedError("PROVIDER_INVALID_RESPONSE") from None

def _login(credentials):
    # Token reuse never survives beyond the next Indian midnight.
    if _CACHE["jwt"] and time.time() < _CACHE["jwt_expires"]:
        return _CACHE["jwt"]
    payload = {
        "clientcode": credentials["client_code"],
        "password": credentials["pin"],
        "totp": _totp(credentials["totp_secret"]),
    }
    data = _post(LOGIN_PATH, payload, None, credentials)
    if not isinstance(data, dict) or not isinstance(data.get("jwtToken"), str) or not data["jwtToken"]:
        raise FeedError("PROVIDER_INVALID_RESPONSE")
    _CACHE["jwt"] = data["jwtToken"]
    india = datetime.now(ZoneInfo("Asia/Kolkata"))
    midnight = (india + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    _CACHE["jwt_expires"] = min(time.time() + 3600 * 4, midnight.timestamp() - 60)
    return _CACHE["jwt"]

def _read(path, payload, credentials):
    jwt = _login(credentials)
    try:
        return _post(path, payload, jwt, credentials)
    except FeedError as exc:
        if exc.code != "SESSION_EXPIRED":
            raise
        # Only one session recovery attempt, never retry arbitrary failures or orders.
        _CACHE["jwt"] = None
        _CACHE["jwt_expires"] = 0.0
        return _post(path, payload, _login(credentials), credentials)

def _quote(event, credentials):
    exchange = event.get("exchange")
    symbol = event.get("tradingSymbol")
    token = event.get("symbolToken")
    if (exchange not in EXCHANGES or not isinstance(symbol, str) or
        not re.fullmatch(r"[A-Za-z0-9._&-]{1,80}", symbol) or
        not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", token)):
        raise FeedError("INVALID_INSTRUMENT")
    data = _read(LTP_PATH,
                 {"exchange": exchange, "tradingsymbol": symbol, "symboltoken": token},
                 credentials)
    try:
        if not isinstance(data, dict):
            raise ValueError()
        price = Decimal(str(data["ltp"]))
        if not price.is_finite() or price <= 0:
            raise ValueError()
    except (KeyError, InvalidOperation, ValueError, TypeError):
        raise FeedError("PROVIDER_INVALID_RESPONSE") from None
    return {"ok": True, "source": "ANGEL_ONE_SMARTAPI", "ltp": str(price),
            "fetchedAt": datetime.now(timezone.utc).isoformat()}

def _candles(event, credentials):
    exchange, token = event.get("exchange"), event.get("symbolToken")
    interval, start, end = event.get("interval"), event.get("fromDate"), event.get("toDate")
    if (exchange not in EXCHANGES or not isinstance(token, str) or
        not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", token) or interval not in INTERVALS):
        raise FeedError("INVALID_CANDLE_REQUEST")
    try:
        if not all(isinstance(date, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}", date)
                   for date in (start, end)):
            raise ValueError()
        from_date = datetime.strptime(start, "%Y-%m-%d %H:%M")
        to_date = datetime.strptime(end, "%Y-%m-%d %H:%M")
        if to_date < from_date or to_date - from_date > timedelta(days=31):
            raise ValueError()
    except (ValueError, TypeError):
        raise FeedError("INVALID_CANDLE_REQUEST") from None
    rows = _read(CANDLE_PATH, {
        "exchange": exchange, "symboltoken": token, "interval": interval,
        "fromdate": start, "todate": end}, credentials)
    if not isinstance(rows, list) or len(rows) > 10000:
        raise FeedError("PROVIDER_INVALID_RESPONSE")
    return {"ok": True, "source": "ANGEL_ONE_SMARTAPI", "candles": rows}

def handler(event, context):
    # No event/secret/request-body logging. Only private IAM InvokeFunction is granted.
    with _LOCK:
        try:
            if not isinstance(event, dict):
                raise FeedError("INVALID_REQUEST")
            operation = event.get("operation")
            if operation not in ("status", "quote", "candles"):
                raise FeedError("DISALLOWED_OPERATION")
            credentials = _secrets()
            if operation == "status":
                return {"ok": True, "source": "ANGEL_ONE_SMARTAPI", "configured": True}
            if operation == "quote":
                return _quote(event, credentials)
            return _candles(event, credentials)
        except FeedError as exc:
            return {"ok": False, "code": exc.code}
        except Exception:
            # Fail closed. Never serialize raw errors containing API payload or credentials.
            return {"ok": False, "code": "INTERNAL_FAILURE"}
