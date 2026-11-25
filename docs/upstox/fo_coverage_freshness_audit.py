"""Offline, read-only NSE_FO coverage/freshness assessment. No source calls or persistence.

Input is an operator-sanitized receipt JSON, not a provider quote. Never pass tokens,
premiums, positions, customer identifiers, or unredacted market data.
"""
from datetime import datetime, timezone, timedelta
import json
import sys

CONTRACT = "minttick.upstox.fo-coverage.v1"


def _instant(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc) if parsed.tzinfo else None
    except ValueError:
        return None


def assess(receipt, now, max_age_seconds=15):
    """Fail closed: UNKNOWN is not zero; STALE is never trade-time ready."""
    if not isinstance(receipt, dict) or now.tzinfo is None or max_age_seconds <= 0:
        raise ValueError("invalid assessment input")
    result = {"contract": CONTRACT, "status": "UNKNOWN", "tradeTimeReady": False,
              "reason": "MISSING_EVIDENCE", "verifiedContracts": None,
              "freshContracts": None}
    if receipt.get("source") != "UPSTOX" or receipt.get("exchange") != "NSE_FO":
        return result
    contracts = receipt.get("contracts")
    if not isinstance(contracts, list):
        return result
    if not receipt.get("readRightsVerified") or not receipt.get("displayRightsVerified"):
        result["reason"] = "RIGHTS_UNVERIFIED"
        return result
    if receipt.get("sessionState") != "OPEN" or receipt.get("sessionVerified") is not True:
        result["reason"] = "SESSION_UNVERIFIED_OR_CLOSED"
        return result
    if not contracts:
        result.update(status="MISSING", reason="NO_CONTRACT_RECEIPTS",
                      verifiedContracts=0, freshContracts=0)
        return result
    # NSE contract expiry is a local exchange date, not a UTC date.
    exchange_today = (now.astimezone(timezone.utc) + timedelta(hours=5, minutes=30)).date()
    verified = 0
    fresh = 0
    seen = set()
    # Duplicate receipts cannot increase coverage or freshness counts.
    for item in contracts:
        if not isinstance(item, dict):
            continue
        if (item.get("underlying") != "NIFTY" or item.get("optionType") not in ("CE", "PE")
                or item.get("listingVerified") is not True
                or not isinstance(item.get("instrumentKey"), str)
                or not item["instrumentKey"].startswith("NSE_FO|")
                or len(item["instrumentKey"]) <= len("NSE_FO|")
                or not isinstance(item.get("tradingSymbol"), str)
                or not item["tradingSymbol"].strip()
                or not isinstance(item.get("strikePrice"), (int, float))
                or isinstance(item.get("strikePrice"), bool)
                or item["strikePrice"] <= 0
                or not isinstance(item.get("lotSize"), int)
                or isinstance(item.get("lotSize"), bool)
                or item["lotSize"] <= 0):
            continue
        expiry = item.get("expiry")
        try:
            expiry_day = datetime.strptime(expiry, "%Y-%m-%d").date()
        except (TypeError, ValueError):
            continue
        if expiry_day < exchange_today:
            continue
        identity = item["instrumentKey"]
        if identity in seen:
            result.update(status="UNKNOWN", reason="DUPLICATE_CONTRACT_RECEIPT",
                          verifiedContracts=None, freshContracts=None)
            return result
        seen.add(identity)
        verified += 1
        stamp = _instant(item.get("exchangeLtt"))
        if stamp is None:
            continue
        age = (now.astimezone(timezone.utc) - stamp).total_seconds()
        if 0 <= age <= max_age_seconds:
            fresh += 1
    result.update(verifiedContracts=verified, freshContracts=fresh)
    if verified == 0:
        result.update(status="MISSING", reason="NO_VERIFIED_ACTIVE_CONTRACT")
    elif fresh != verified:
        result.update(status="STALE", reason="QUOTE_TIME_MISSING_OR_STALE")
    else:
        result.update(status="FRESH", reason="QUOTE_TIME_FRESH",
                      tradeTimeReady=True)
    return result


def assess_cli(receipt, clock):
    """Receipt timestamps must not serve as their own independent freshness clock."""
    observation = _instant(receipt.get("observedAt"))
    if observation is None or receipt.get("observationVerified") is not True:
        return {"contract": CONTRACT, "status": "UNKNOWN", "tradeTimeReady": False,
                "reason": "OBSERVATION_UNVERIFIED", "verifiedContracts": None,
                "freshContracts": None}
    delay = (clock - observation).total_seconds()
    if delay < 0 or delay > 15:
        return {"contract": CONTRACT, "status": "STALE", "tradeTimeReady": False,
                "reason": "OBSERVATION_STALE_OR_FUTURE", "verifiedContracts": None,
                "freshContracts": None}
    # stdin is an untrusted assertion surface: booleans do not authenticate
    # exchange listings, source rights, or exchange-session provenance.
    # Only an independently authenticated runtime adapter may assess trusted
    # evidence. Until wired, CLI must never report FRESH or trade readiness.
    return {"contract": CONTRACT, "status": "UNKNOWN", "tradeTimeReady": False,
            "reason": "PROVENANCE_UNVERIFIED", "verifiedContracts": None,
            "freshContracts": None}


if __name__ == "__main__":
    # No network, DB, or filesystem writes. Read a sanitized receipt from stdin.
    payload = json.load(sys.stdin)
    print(json.dumps(assess_cli(payload, datetime.now(timezone.utc)), sort_keys=True))
