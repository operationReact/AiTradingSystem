#!/usr/bin/env python3
"""Explicitly opt-in, sandbox-only Upstox V3 F&O place/modify/cancel probe.

This NEVER uses production OAuth, analytics tokens, AWS credentials or a broker
account. Default mode performs NO network calls. One uncertain acknowledgement
is never re-sent. Do not claim real-market, margin or fill validation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import sys
import urllib.error
import urllib.request
from decimal import Decimal, InvalidOperation

SANDBOX = "https://api-sandbox.upstox.com"
INSTRUMENT = re.compile(r"NSE_FO\|[A-Za-z0-9._-]{1,64}\Z")
ORDER_ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
_ALLOWED = {("POST", "/v3/order/place"), ("PUT", "/v3/order/modify")}
_MAX_RESPONSE_BYTES = 16384


class SandboxError(Exception):
    """Fixed non-secret failure category; never contain provider bodies."""


def _decimal(text: str) -> Decimal:
    try:
        val = Decimal(str(text))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise SandboxError("INVALID_SANDBOX_LIMIT_OR_TICK") from exc
    if not val.is_finite() or val <= 0 or val > 1000000:
        raise SandboxError("INVALID_SANDBOX_LIMIT_OR_TICK")
    return val


def _ack(response: object, field: str) -> str:
    if not isinstance(response, dict) or response.get("status") != "success":
        raise SandboxError("SANDBOX_ACK_UNKNOWN")
    data = response.get("data")
    if not isinstance(data, dict):
        raise SandboxError("SANDBOX_ACK_UNKNOWN")
    if field == "order_ids":
        raw = data.get(field)
        if not isinstance(raw, list) or len(raw) != 1:
            raise SandboxError("SANDBOX_ACK_UNKNOWN")
        value = raw[0]
    else:
        value = data.get(field)
    if not isinstance(value, str) or not ORDER_ID.fullmatch(value):
        raise SandboxError("SANDBOX_ACK_UNKNOWN")
    return value


def run_cycle(call, instrument: str, quantity: int, price: str, tick: str) -> dict:
    """One bounded sandbox order; change & cancel only after confirmed ACKs.

    call(method,path,payload) is injectable for zero-network contract tests.
    No broker order retry occurs after any ambiguous or negative response.
    """
    if not isinstance(instrument, str) or not INSTRUMENT.fullmatch(instrument):
        raise SandboxError("SANDBOX_NFO_INSTRUMENT_REQUIRED")
    if type(quantity) is not int or not 1 <= quantity <= 100000:
        raise SandboxError("SANDBOX_QUANTITY_INVALID")
    p, step = _decimal(price), _decimal(tick)
    if (p / step) != (p / step).to_integral_value():
        raise SandboxError("SANDBOX_PRICE_TICK_MISMATCH")
    next_price = p + step
    if next_price > 1000000:
        raise SandboxError("SANDBOX_PRICE_INVALID")
    tag = "mtsb" + secrets.token_hex(7)
    placed = call("POST", "/v3/order/place", {
        "quantity": quantity, "product": "I", "validity": "DAY",
        "price": float(p), "tag": tag, "instrument_token": instrument,
        "order_type": "LIMIT", "transaction_type": "BUY",
        "disclosed_quantity": 0, "trigger_price": 0,
        "is_amo": False, "slice": False,
    })
    order = _ack(placed, "order_ids")
    result = {"environment": "UPSTOX_SANDBOX_ONLY", "placed": True,
              "modified": False, "cancelled": False,
              "orderFingerprint": hashlib.sha256(order.encode()).hexdigest()[:12]}
    # A rejected/unknown modification leaves the sandbox order in uncertain
    # state; never infer success or automatically send an unrelated order.
    changed = call("PUT", "/v3/order/modify", {
        "order_id": order, "quantity": quantity, "validity": "DAY",
        "price": float(next_price), "order_type": "LIMIT",
        "trigger_price": 0,
    })
    if _ack(changed, "order_id") != order:
        raise SandboxError("SANDBOX_MODIFY_ID_MISMATCH")
    result["modified"] = True
    cancelled = call("DELETE", "/v3/order/cancel?order_id=" + order, None)
    if _ack(cancelled, "order_id") != order:
        raise SandboxError("SANDBOX_CANCEL_ID_MISMATCH")
    result["cancelled"] = True
    return result


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def sandbox_transport(token: str):
    if not token or len(token) > 4096 or "\r" in token or "\n" in token:
        raise SandboxError("SANDBOX_TOKEN_MISSING_OR_INVALID")
    opener = urllib.request.build_opener(_NoRedirects())

    def call(method: str, path: str, body: dict | None):
        # Never accept a caller-defined scheme/host, including via redirects.
        if (method, path) not in _ALLOWED and not (
            method == "DELETE" and path.startswith("/v3/order/cancel?order_id=")
            and ORDER_ID.fullmatch(path.split("=", 1)[1])
        ):
            raise SandboxError("SANDBOX_ENDPOINT_NOT_ALLOWED")
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        req = urllib.request.Request(
            SANDBOX + path, data=data, method=method,
            headers={"Authorization": "Bearer " + token,
                     "Accept": "application/json", "Content-Type": "application/json"})
        try:
            with opener.open(req, timeout=6) as response:
                raw = response.read(_MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            raise SandboxError("SANDBOX_HTTP_ERROR_" + str(exc.code)) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise SandboxError("SANDBOX_TRANSPORT_OUTCOME_UNKNOWN") from None
        if len(raw) > _MAX_RESPONSE_BYTES:
            raise SandboxError("SANDBOX_RESPONSE_TOO_LARGE")
        try:
            return json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise SandboxError("SANDBOX_RESPONSE_INVALID") from None
    return call


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-sandbox", action="store_true")
    parser.add_argument("--instrument-key")
    parser.add_argument("--quantity", type=int)
    parser.add_argument("--price")
    parser.add_argument("--tick")
    args = parser.parse_args(argv)
    if not args.execute_sandbox:
        print("SANDBOX_DRY_RUN_ONLY: no network request")
        return 0
    token = os.environ.get("UPSTOX_SANDBOX_TOKEN", "")
    if not token:
        print("SANDBOX_TOKEN_NOT_CONFIGURED")
        return 2
    try:
        result = run_cycle(sandbox_transport(token), args.instrument_key,
                           args.quantity, args.price, args.tick)
        print(json.dumps(result, sort_keys=True))
        return 0
    except SandboxError as exc:
        print("SANDBOX_BLOCKED: " + str(exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())
