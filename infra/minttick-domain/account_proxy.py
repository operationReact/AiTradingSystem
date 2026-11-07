"""Minimal API Gateway to private MintTick PAPER Spring Boot reverse proxy.

Private TCP 10.87.0.10:8081 is reachable only from the dedicated Lambda
security group; the API Gateway JWT authorizer gates customer paths.
No management, research, admin, LIVE trading, or broker credentials exposed.
"""
import base64
import binascii
import json
import re
import urllib.error
import urllib.request

BACKEND = "http://10.87.0.10:8081"
ISSUER = "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_jz2T8qbiY"
CLIENT_ID = "6jm18v2mdpcfraip92hrjhqjnd"
EXACT = {
    ("POST", "/api/auth/cognito/provision"),
    ("GET", "/api/auth/cognito/me"),
    ("GET", "/api/customer/trading-rooms"),
    ("POST", "/api/customer/trading-rooms"),
    ("GET", "/api/customer/brokers/angel-one/status"),
    ("GET", "/api/customer/brokers/angel-one/configured-login"),
    ("POST", "/api/customer/brokers/angel-one/connect-configured"),
    ("POST", "/api/customer/brokers/angel-one/disconnect"),
    ("GET", "/api/customer/brokers/angel-one/funds"),
    ("GET", "/api/customer/brokers/angel-one/holdings"),
    ("GET", "/api/customer/brokers/angel-one/positions"),
    ("GET", "/api/customer/brokers/angel-one/orders"),
}
ROOM = re.compile(r"^/api/customer/trading-rooms/[0-9a-fA-F-]{36}$")
PAPER = re.compile(r"^/api/customer/trading-rooms/[0-9a-fA-F-]{36}/paper/(account|ledger)$")
ALLOWED_RESPONSES = {200, 201, 202, 204, 400, 401, 403, 404, 409, 422, 429, 500, 502, 503}
MAX_BODY = 65536

def reply(status, obj):
    return {"statusCode": status, "headers": {
        "content-type": "application/json",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
    }, "body": json.dumps(obj, separators=(",", ":"))}

def allowed(method, path):
    return ((method, path) in EXACT
            or (method == "GET" and (ROOM.fullmatch(path) or PAPER.fullmatch(path))))

def authorized(event):
    jwt = event.get("requestContext", {}).get("authorizer", {}).get("jwt", {})
    claims = jwt.get("claims", {})
    return (isinstance(claims, dict)
            and claims.get("token_use") == "access"
            and claims.get("iss") == ISSUER
            and claims.get("client_id") == CLIENT_ID
            and isinstance(claims.get("sub"), str)
            and len(claims["sub"]) > 0)

def handler(event, context):
    method = event.get("requestContext", {}).get("http", {}).get("method", "")
    path = event.get("rawPath", "")
    is_health = method == "GET" and path == "/actuator/health/readiness"
    if not is_health and not allowed(method, path):
        return reply(404, {"error": "NOT_FOUND"})
    if not is_health and not authorized(event):
        return reply(401, {"error": "AUTHENTICATION_REQUIRED"})
    query = event.get("rawQueryString", "")
    if not isinstance(query, str) or len(query) > 300:
        return reply(400, {"error": "INVALID_QUERY"})
    if query and is_health:
        return reply(400, {"error": "INVALID_QUERY"})
    headers = event.get("headers", {})
    bearer = headers.get("authorization", "")
    if not is_health and (not isinstance(bearer, str)
                          or not bearer.startswith("Bearer ")
                          or len(bearer) > 10000):
        return reply(401, {"error": "AUTHENTICATION_REQUIRED"})
    raw = event.get("body") or ""
    try:
        if not isinstance(raw, str):
            raise ValueError("bad body")
        body = base64.b64decode(raw, validate=True) if event.get("isBase64Encoded") else raw.encode()
    except (ValueError, UnicodeError, binascii.Error):
        return reply(400, {"error": "INVALID_BODY"})
    if len(body) > MAX_BODY:
        return reply(413, {"error": "BODY_TOO_LARGE"})
    if method == "GET" and body:
        return reply(400, {"error": "INVALID_BODY"})
    if method == "POST" and path in {
        "/api/auth/cognito/provision",
        "/api/customer/brokers/angel-one/connect-configured",
        "/api/customer/brokers/angel-one/disconnect",
    } and body:
        return reply(400, {"error": "INVALID_BODY"})
    internal_path = "/api/health" if is_health else path
    if query:
        internal_path += "?" + query
    upstream_headers = {"Accept": "application/json"}
    if not is_health:
        upstream_headers["Authorization"] = bearer
    if body:
        upstream_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        BACKEND + internal_path,
        data=body if method == "POST" else None,
        headers=upstream_headers,
        method=method)
    try:
        with urllib.request.urlopen(request, timeout=7) as upstream:
            status = upstream.status
            payload = upstream.read(131073)
    except urllib.error.HTTPError as exc:
        status = exc.code
        payload = exc.read(131073)
    except (urllib.error.URLError, TimeoutError, OSError):
        return reply(503, {"error": "PRIVATE_BACKEND_UNAVAILABLE"})
    if len(payload) > 131072 or status not in ALLOWED_RESPONSES:
        return reply(502, {"error": "INVALID_UPSTREAM_RESPONSE"})
    if is_health:
        return reply(200 if status == 200 else 503,
                     {"status": "UP" if status == 200 else "DOWN"})
    try:
        decoded = json.loads(payload) if payload else {}
        if not isinstance(decoded, (dict, list)):
            raise ValueError("non-object response")
    except (ValueError, UnicodeError):
        return reply(502, {"error": "INVALID_UPSTREAM_RESPONSE"})
    return reply(status, decoded)
