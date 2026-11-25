#!/usr/bin/env python3
"""Sanitize one authenticated F&O research response from the deployed Mind API.

Only fixed status/reason categories and bounded summary counts enter CI logs.
The customer response is never printed, persisted, or interpreted as an
execution authorization. No broker API and no trading-side effects.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import sys

MARKER = "MIND_FNO_RESEARCH_RUNTIME:"
ALLOWED_STATES = frozenset({"BLOCKED", "NO_TRADE", "RESEARCH_CANDIDATE"})
SAFE_REASON = re.compile(r"[A-Z][A-Z0-9_]{0,79}\Z")
UTC = dt.timezone.utc


def _time(value):
    if not isinstance(value, str) or len(value) > 64:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed.astimezone(UTC) if parsed.tzinfo is not None else None


def _recent(value, now, seconds):
    observed = _time(value)
    return observed is not None and 0 <= (now - observed).total_seconds() <= seconds


def _safe_reason(value):
    return value if isinstance(value, str) and SAFE_REASON.fullmatch(value) else "UNCLASSIFIED"


def classify(status: int, raw: bytes, now=None):
    """Return (bounded summary, pass) without disclosing any customer fields."""
    if status in (401, 403):
        return MARKER + "route=AUTH_DENIED;execution=false", True
    if status == 404:
        return MARKER + "route=NOT_DEPLOYED;execution=false", True
    if status >= 500:
        return MARKER + "route=BACKEND_ERROR;execution=false", False
    if status != 200:
        return MARKER + "route=UNEXPECTED_HTTP;execution=false", False
    if not isinstance(raw, bytes) or len(raw) > 20000:
        return MARKER + "route=INVALID_BODY;execution=false", False
    try:
        data = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        return MARKER + "route=INVALID_JSON;execution=false", False
    if not isinstance(data, dict):
        return MARKER + "route=INVALID_CONTRACT;execution=false", False
    if (data.get("contractVersion") != "mind.fno-research.v1" or
            data.get("underlyingKey") != "NSE_INDEX|Nifty 50" or
            data.get("status") not in ALLOWED_STATES or
            data.get("executable") is not False or
            data.get("orderPlaced") is not False):
        return MARKER + "route=INVALID_CONTRACT;execution=false", False
    now = now or dt.datetime.now(UTC)
    if now.tzinfo is None or not _recent(data.get("evaluatedAt"), now.astimezone(UTC), 90):
        return MARKER + "route=STALE_EVALUATION;execution=false", False
    bars = data.get("completedCandles")
    if type(bars) is not int or bars < 0 or bars > 100000:
        return MARKER + "route=INVALID_BAR_COUNT;execution=false", False
    state = data["status"]
    signal = data.get("signal")
    reason = _safe_reason(data.get("reasonCode"))
    strategy = data.get("strategyId")
    if state == "RESEARCH_CANDIDATE":
        if (strategy not in ("strategy.STR-181", "strategy.STR-182") or
                signal != ("BUY_CALL_RESEARCH" if strategy == "strategy.STR-181"
                           else "BUY_PUT_RESEARCH") or
                bars < 35 or
                data.get("quoteSource") != "UPSTOX_V3_EXCHANGE_TIMED" or
                data.get("candleSource") not in ("UPSTOX_ANALYTICS", "ANGEL_ONE_SMARTAPI") or
                not _recent(data.get("exchangeAt"), now, 15) or
                not _recent(data.get("observedAt"), now, 15)):
            return MARKER + "route=CANDIDATE_PROVENANCE_INVALID;execution=false", False
        strategy_class = "STR_181" if strategy.endswith("181") else "STR_182"
    else:
        if signal != "NO_TRADE":
            return MARKER + "route=NON_CANDIDATE_SIGNAL_INVALID;execution=false", False
        strategy_class = "NONE"
    return (MARKER + "route=HTTP_200;state=" + state +
            ";reason=" + reason + ";strategy=" + strategy_class +
            ";bars=" + str(bars) +
            ";brokerOrders=false;execution=false;independentE2E=false"), True


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        print(MARKER + "route=INVALID_ARGUMENTS;execution=false")
        return 2
    try:
        status = int(argv[0])
    except (TypeError, ValueError):
        print(MARKER + "route=INVALID_HTTP_STATUS;execution=false")
        return 2
    try:
        source = Path(argv[1])
        if not source.is_file() or source.stat().st_size > 20000:
            raise ValueError("BOUNDED_LOCAL_FILE_REQUIRED")
        raw = source.read_bytes()
    except (OSError, ValueError):
        print(MARKER + "route=INVALID_RESPONSE_FILE;execution=false")
        return 1
    result, valid = classify(status, raw)
    print(result)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
