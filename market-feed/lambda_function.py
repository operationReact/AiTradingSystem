"""MintTick read-only Angel One market-data Lambda.

Direct IAM invocation only. No HTTP gateway, order endpoints, trading authority or secrets in responses.
Dev-only owner pilot: the IAM-scoped Lambda accepts String or SecureString at exactly four SSM paths in ap-south-1. Migrate sensitive credentials to SecureString before broader use.
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
PROFILE_PATH = "/rest/secure/angelbroking/user/v1/getProfile"
ACCOUNT_PATHS = {
    "funds": "/rest/secure/angelbroking/user/v1/getRMS",
    "holdings": "/rest/secure/angelbroking/portfolio/v1/getHolding",
    "positions": "/rest/secure/angelbroking/order/v1/getPosition",
    "orders": "/rest/secure/angelbroking/order/v1/getOrderBook",
}
# No trade mutation or order execution endpoint is reachable.
MAX_ACCOUNT_RESPONSE_BYTES = 110_000
REAUTH_CODES = {"AG8001", "AG8003", "AB8050", "AB8051", "AB1010"}
EXCHANGES = {"NSE", "BSE"}
# Must match backend HistoricalCandleInterval maxDaysPerRequest.
INTERVALS = {
    "ONE_MINUTE": 30, "THREE_MINUTE": 60, "FIVE_MINUTE": 100,
    "TEN_MINUTE": 100, "FIFTEEN_MINUTE": 200, "THIRTY_MINUTE": 200,
    "ONE_HOUR": 400, "ONE_DAY": 2000,
}
_LOCK = Lock()
_CACHE = {"secrets": None, "secrets_loaded_at": 0.0, "jwt": None, "jwt_expires": 0.0}
_LAST_CALL = 0.0

class FeedError(Exception):
    def __init__(self, code, unavailable_fields=()):
        super().__init__(code)
        self.code = code
        # Field identifiers only; never store or return the SSM secret values.
        self.unavailable_fields = tuple(unavailable_fields)

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
                 if item.get("Type") in ("String", "SecureString") and
                 isinstance(item.get("Name"), str)}
        unavailable = tuple(
            field for field, name in NAMES.items()
            if not isinstance(found.get(name), str) or not found[name].strip()
        )
        if unavailable:
            raise FeedError("CREDENTIALS_NOT_CONFIGURED", unavailable)
        values = {key: found[name] for key, name in NAMES.items()}
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

def _get(path, jwt, credentials):
    # Only broker-account reads or profile verification.
    if path not in {*ACCOUNT_PATHS.values(), PROFILE_PATH} or not jwt:
        raise FeedError("DISALLOWED_OPERATION")
    _pace()
    headers = {
        "Accept": "application/json",
        "X-PrivateKey": credentials["api_key"],
        "X-UserType": "USER",
        "X-SourceID": "WEB",
        "Authorization": "Bearer " + jwt,
    }
    try:
        with urlopen(Request(API_ROOT + path, headers=headers, method="GET"), timeout=7) as response:
            payload = response.read(MAX_ACCOUNT_RESPONSE_BYTES + 1)
        if len(payload) > MAX_ACCOUNT_RESPONSE_BYTES:
            raise FeedError("PROVIDER_INVALID_RESPONSE")
    except HTTPError as exc:
        if exc.code == 401:
            raise FeedError("SESSION_EXPIRED") from None
        if exc.code in (403, 429):
            raise FeedError("PROVIDER_RATE_LIMITED") from None
        raise FeedError("PROVIDER_UNAVAILABLE") from None
    except (URLError, TimeoutError, OSError):
        raise FeedError("PROVIDER_UNAVAILABLE") from None
    try:
        result = json.loads(payload)
        if not isinstance(result, dict) or result.get("status") is not True:
            if isinstance(result, dict) and str(result.get("errorcode", "")) in REAUTH_CODES:
                raise FeedError("SESSION_EXPIRED")
            raise FeedError("PROVIDER_REJECTED")
        return result.get("data")
    except FeedError:
        raise
    except (ValueError, TypeError, UnicodeError):
        raise FeedError("PROVIDER_INVALID_RESPONSE") from None

def _read_account(path, credentials):
    jwt = _login(credentials)
    try:
        return _get(path, jwt, credentials)
    except FeedError as exc:
        if exc.code != "SESSION_EXPIRED":
            raise
        _CACHE["jwt"] = None
        _CACHE["jwt_expires"] = 0.0
        return _get(path, _login(credentials), credentials)


def _connect_broker(credentials):
    # Independently compare the broker-verified identity with the one bound
    # to the four SecureString parameters. Never expose profile PII or tokens.
    data = _read_account(PROFILE_PATH, credentials)
    if not isinstance(data, dict):
        raise FeedError("PROVIDER_INVALID_RESPONSE")
    expected = credentials["client_code"].strip().upper()
    verified = str(data.get("clientcode", data.get("clientCode", ""))).strip().upper()
    if not expected or verified != expected:
        raise FeedError("BROKER_ACCOUNT_MISMATCH")
    return {"ok": True, "broker": "ANGEL_ONE",
            "clientCodeMasked": "***" + expected[-3:],
            "orderExecutionEnabled": False}


def _broker_read(event, credentials):
    resource = event.get("resource")
    if not isinstance(resource, str) or resource not in ACCOUNT_PATHS:
        raise FeedError("DISALLOWED_OPERATION")
    data = _read_account(ACCOUNT_PATHS[resource], credentials)
    if not isinstance(data, (dict, list)):
        raise FeedError("PROVIDER_INVALID_RESPONSE")
    # No customer JWT, PIN, API key, TOTP or broker tokens are forwarded.
    return {"ok": True, "provider": "ANGEL_ONE",
            "fetchedAt": datetime.now(timezone.utc).isoformat(), "data": data}


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
        if to_date < from_date or to_date - from_date > timedelta(days=INTERVALS[interval]):
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
            if operation not in ("status", "quote", "candles", "broker-connect", "broker-read"):
                raise FeedError("DISALLOWED_OPERATION")
            if operation == "broker-read" and event.get("resource") not in ACCOUNT_PATHS:
                raise FeedError("DISALLOWED_OPERATION")
            credentials = _secrets()
            if operation == "status":
                return {"ok": True, "source": "ANGEL_ONE_SMARTAPI", "configured": True}
            if operation == "quote":
                return _quote(event, credentials)
            if operation == "candles":
                return _candles(event, credentials)
            if operation == "broker-connect":
                return _connect_broker(credentials)
            return _broker_read(event, credentials)
        except FeedError as exc:
            response = {"ok": False, "code": exc.code}
            # Status is IAM-invoked privately. Report only fixed symbolic field
            # identifiers so the operator can repair exact SSM names/types.
            if isinstance(event, dict) and event.get("operation") == "status" and (
                    exc.code == "CREDENTIALS_NOT_CONFIGURED" and exc.unavailable_fields):
                response["unavailableFields"] = list(exc.unavailable_fields)
            return response
        except Exception:
            # Fail closed. Never serialize raw errors containing API payload or credentials.
            return {"ok": False, "code": "INTERNAL_FAILURE"}
