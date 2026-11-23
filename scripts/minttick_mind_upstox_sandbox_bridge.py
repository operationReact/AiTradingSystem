#!/usr/bin/env python3
"""Explicit non-funded bridge: real Mintick Mind F&O research -> Upstox sandbox.

No real-money endpoint, no production OAuth or automated signal polling.
The caller must supply an authentic, freshly retrieved /api/customer/mind/v1/
fno-evaluation JSON response. The bridge validates it; source authenticity is
not proven from a standalone JSON file. Default CLI does not use AWS or broker.
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import json
import re
import sys
import urllib.request
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from zoneinfo import ZoneInfo

import upstox_fno_sandbox_lifecycle as broker
import upstox_fno_sandbox_ssm as ssm

IST = ZoneInfo("Asia/Kolkata")
BOD = "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz"
STRATEGIES = {
    "strategy.STR-181": ("BUY_CALL_RESEARCH", "CE"),
    "strategy.STR-182": ("BUY_PUT_RESEARCH", "PE"),
}
MAX_SECONDS = 15
MAX_PREMIUM_INR = Decimal("25000")
TICK_PAISE = Decimal("5")


class BridgeBlocked(ValueError):
    """Allowlisted, non-sensitive rejection category."""


@dataclass(frozen=True)
class SandboxIntent:
    strategy_id: str
    instrument_key: str
    expiry: dt.date
    quantity: int
    limit_price: Decimal
    tick: Decimal


def _time(value: object, label: str) -> dt.datetime:
    if not isinstance(value, str):
        raise BridgeBlocked("MIND_" + label + "_MISSING")
    try:
        t = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise BridgeBlocked("MIND_" + label + "_INVALID") from None
    if t.tzinfo is None:
        raise BridgeBlocked("MIND_" + label + "_NOT_OFFSET_AWARE")
    return t.astimezone(dt.timezone.utc)


def _decimal(value: object, label: str) -> Decimal:
    if value is None or isinstance(value, bool):
        raise BridgeBlocked("MIND_" + label + "_INVALID")
    try:
        d = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        raise BridgeBlocked("MIND_" + label + "_INVALID") from None
    if not d.is_finite() or d <= 0 or d > Decimal("1000000"):
        raise BridgeBlocked("MIND_" + label + "_INVALID")
    return d


def _validate_fresh(value: object, now: dt.datetime, label: str) -> None:
    timestamp = _time(value, label)
    seconds = (now - timestamp).total_seconds()
    if seconds < 0 or seconds > MAX_SECONDS:
        raise BridgeBlocked("MIND_" + label + "_STALE_OR_FUTURE")


def intent_from_research(research: object, master: object,
                         now: dt.datetime | None = None) -> SandboxIntent:
    """Validate one *research-only* signal against independently listed BOD."""
    now = now or dt.datetime.now(dt.timezone.utc)
    if now.tzinfo is None:
        raise ValueError("now must include timezone")
    now = now.astimezone(dt.timezone.utc)
    if not isinstance(research, dict) or not isinstance(master, list):
        raise BridgeBlocked("MIND_INPUT_INVALID")
    if research.get("contractVersion") != "mind.fno-research.v1":
        raise BridgeBlocked("MIND_CONTRACT_VERSION_UNSUPPORTED")
    if research.get("status") != "RESEARCH_CANDIDATE":
        raise BridgeBlocked("MIND_NO_RESEARCH_CANDIDATE")
    if research.get("executable") is not False or research.get("orderPlaced") is not False:
        raise BridgeBlocked("MIND_ORDER_AUTHORITY_FLAG_INVALID")
    strategy = research.get("strategyId")
    if strategy not in STRATEGIES:
        raise BridgeBlocked("MIND_STRATEGY_NOT_SUPPORTED_BY_BRIDGE")
    expected_signal, option_type = STRATEGIES[strategy]
    if research.get("signal") != expected_signal:
        raise BridgeBlocked("MIND_STRATEGY_SIGNAL_MISMATCH")
    if research.get("underlyingKey") != "NSE_INDEX|Nifty 50":
        raise BridgeBlocked("MIND_UNDERLYING_NOT_NIFTY_INDEX")
    if (research.get("quoteSource") != "UPSTOX_V3_EXCHANGE_TIMED"
            or research.get("candleSource") not in ("ANGEL_ONE_SMARTAPI", "UPSTOX_ANALYTICS")):
        raise BridgeBlocked("MIND_SOURCE_PROVENANCE_UNSUPPORTED")
    count = research.get("completedCandles")
    if type(count) is not int or count < 35:
        raise BridgeBlocked("MIND_HISTORY_INSUFFICIENT")
    for name in ("evaluatedAt", "exchangeAt", "observedAt"):
        _validate_fresh(research.get(name), now, name.upper())
    closed = _time(research.get("latestCompletedCloseAt"), "COMPLETED_CANDLE")
    if closed > now:
        raise BridgeBlocked("MIND_COMPLETED_CANDLE_IN_FUTURE")
    if _time(research.get("observedAt"), "OBSERVED_AT") < _time(research.get("exchangeAt"), "EXCHANGE_AT"):
        raise BridgeBlocked("MIND_QUOTE_TIME_INVERTED")
    key = research.get("instrumentKey")
    if not isinstance(key, str) or not broker.INSTRUMENT.fullmatch(key):
        raise BridgeBlocked("MIND_INSTRUMENT_KEY_INVALID")
    try:
        expiry = dt.date.fromisoformat(research.get("expiry"))
    except (ValueError, TypeError):
        raise BridgeBlocked("MIND_EXPIRY_INVALID") from None
    today = now.astimezone(IST).date()
    if not today < expiry <= today + dt.timedelta(days=90):
        raise BridgeBlocked("MIND_EXPIRY_NOT_FUTURE_LISTED")
    lotsize = research.get("lotSize")
    if type(lotsize) is not int or not 1 <= lotsize <= 10000:
        raise BridgeBlocked("MIND_LOT_SIZE_INVALID")
    strike = _decimal(research.get("strike"), "STRIKE")
    bid = _decimal(research.get("bid"), "BID")
    ask = _decimal(research.get("ask"), "ASK")
    if bid > ask:
        raise BridgeBlocked("MIND_CROSSED_BOOK")
    if ask * lotsize > MAX_PREMIUM_INR:
        raise BridgeBlocked("MIND_SANDBOX_PREMIUM_CAP_EXCEEDED")
    if (ask * 100 / TICK_PAISE) != (ask * 100 / TICK_PAISE).to_integral_value():
        raise BridgeBlocked("MIND_PRICE_NOT_ON_TICK")

    matches = []
    for contract in master:
        if not isinstance(contract, dict) or contract.get("instrument_key") != key:
            continue
        if (contract.get("segment") != "NSE_FO"
                or contract.get("instrument_type") != option_type
                or contract.get("underlying_symbol") not in ("NIFTY", "NIFTY 50")):
            continue
        try:
            epoch = int(contract["expiry"])
            d = dt.datetime.fromtimestamp(epoch / 1000, IST).date()
            size = int(contract["lot_size"])
            s = Decimal(str(contract["strike_price"]))
            tick = Decimal(str(contract["tick_size"]))
        except (TypeError, KeyError, ValueError, InvalidOperation, OverflowError, OSError):
            continue
        if d == expiry and size == lotsize and s == strike and tick == TICK_PAISE:
            matches.append(contract)
    if len(matches) != 1:
        raise BridgeBlocked("MIND_CURRENT_BOD_CONTRACT_UNVERIFIED")
    return SandboxIntent(strategy, key, expiry, lotsize, ask, Decimal("0.05"))


def current_nse_master() -> list:
    """Read public Upstox BOD, bounded size; never retrieve a broker token."""
    request = urllib.request.Request(
        BOD, headers={"User-Agent": "MintTick-Upstox-InstrumentAudit/1.0",
                      "Accept": "application/gzip"})
    with urllib.request.urlopen(request, timeout=25) as response:
        blob = response.read(55_000_001)
    if len(blob) > 55_000_000:
        raise BridgeBlocked("MIND_BOD_TOO_LARGE")
    try:
        data = json.loads(gzip.decompress(blob))
    except (OSError, ValueError, TypeError):
        raise BridgeBlocked("MIND_BOD_INVALID") from None
    if not isinstance(data, list):
        raise BridgeBlocked("MIND_BOD_INVALID")
    return data


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-json", required=True, type=Path)
    parser.add_argument("--execute-sandbox", action="store_true")
    parser.add_argument("--confirmation", default="DO_NOT_EXECUTE")
    args = parser.parse_args(argv)
    if args.execute_sandbox and args.confirmation != "SANDBOX_ONLY":
        print("MIND_SANDBOX_CONFIRMATION_REQUIRED")
        return 2
    try:
        if args.research_json.stat().st_size > 20000:
            raise BridgeBlocked("MIND_RESEARCH_FILE_TOO_LARGE")
        data = json.loads(args.research_json.read_text(encoding="utf-8"))
        intent = intent_from_research(data, current_nse_master())
        print("MIND_SANDBOX_VALIDATED_RESEARCH_ONLY:" + intent.strategy_id)
        if not args.execute_sandbox:
            print("MIND_SANDBOX_DRY_RUN:ZERO_BROKER_ORDERS")
            return 0
        # Dedicated sandbox role, token and host only. No live credentials.
        if broker.SANDBOX != "https://api-sandbox.upstox.com" or (
                broker.SANDBOX_USER_AGENT != "MintTick-Upstox-Sandbox/1.0"):
            raise BridgeBlocked("MIND_SANDBOX_HOST_OR_HEADER_MISMATCH")
        token = ssm.load_sandbox_token()
        try:
            result = broker.run_cycle(
                broker.sandbox_transport(token), intent.instrument_key,
                intent.quantity, str(intent.limit_price), str(intent.tick))
        finally:
            token = None
        print("MIND_SANDBOX_SIMULATED_LIFECYCLE:" +
              ("VERIFIED" if all(result.get(k) is True for k in ("placed", "modified", "cancelled"))
               else "INCOMPLETE"))
        return 0 if all(result.get(k) is True for k in ("placed", "modified", "cancelled")) else 1
    except (OSError, ValueError, TypeError, json.JSONDecodeError,
            broker.SandboxError, ssm.SandboxBootstrapError) as exc:
        # Errors are fixed categories, not raw broker/SSM response content.
        if isinstance(exc, (BridgeBlocked, broker.SandboxError, ssm.SandboxBootstrapError)):
            print("MIND_SANDBOX_BLOCKED:" + str(exc))
        else:
            print("MIND_SANDBOX_BLOCKED:UNAVAILABLE_OR_INVALID_INPUT")
        return 1


if __name__ == "__main__":
    sys.exit(main())
