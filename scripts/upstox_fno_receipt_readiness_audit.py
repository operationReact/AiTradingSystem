#!/usr/bin/env python3
"""Offline, read-only NSE_FO receipt readiness audit. No source API, credentials or orders.

Input is a sanitized JSON object with keys rights, session, contracts.
Each contract must contain symbol, exchange, expiry (YYYY-MM-DD), lot_size,
exchange_ltt (offset-aware ISO8601), bid, ask and source_receipt_id.
Only counts and reason codes are emitted; never echo market data or identities.
A prior REST contract discovery or market-closed snapshot cannot pass.
"""
import argparse
import datetime as dt
import json
from collections import Counter


def audit(payload, now=None, max_age_seconds=30):
    now = now or dt.datetime.now(dt.timezone.utc)
    reasons = Counter()
    if not isinstance(payload, dict):
        return {"ready": False, "verified": 0, "total": None, "reasons": ["INVALID_INPUT"]}
    rights = payload.get("rights")
    session = payload.get("session")
    contracts = payload.get("contracts")
    if not isinstance(contracts, list):
        return {"ready": False, "verified": 0, "total": None, "reasons": ["CONTRACTS_UNKNOWN"]}
    rights_ok = isinstance(rights, dict) and all(rights.get(k) is True for k in
        ("reviewed", "ephemeral_read", "private_display", "paper_simulation"))
    session_ok = isinstance(session, dict) and session.get("exchange") == "NSE_FO" and session.get("state") == "OPEN" and session.get("verified") is True
    if not rights_ok:
        reasons["SOURCE_RIGHTS_UNKNOWN"] += 1
    if not session_ok:
        reasons["TRADE_SESSION_NOT_VERIFIED"] += 1
    verified = 0
    for contract in contracts:
        if not isinstance(contract, dict):
            reasons["INVALID_CONTRACT"] += 1
            continue
        if contract.get("exchange") != "NSE_FO" or not isinstance(contract.get("symbol"), str) or not contract["symbol"].strip():
            reasons["CONTRACT_IDENTITY_UNKNOWN"] += 1
            continue
        try:
            expiry = dt.date.fromisoformat(contract["expiry"])
            lot = contract["lot_size"]
            if isinstance(lot, bool) or not isinstance(lot, int) or lot <= 0 or expiry < now.date():
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            reasons["EXPIRY_OR_LOT_INVALID"] += 1
            continue
        try:
            stamp = dt.datetime.fromisoformat(contract["exchange_ltt"].replace("Z", "+00:00"))
            if stamp.tzinfo is None or not 0 <= (now - stamp).total_seconds() <= max_age_seconds:
                raise ValueError()
            bid, ask = contract["bid"], contract["ask"]
            if any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in (bid, ask)) or not (0 < bid <= ask):
                raise ValueError()
        except (ValueError, KeyError, TypeError, AttributeError):
            reasons["QUOTE_STALE_OR_UNKNOWN"] += 1
            continue
        if not contract.get("source_receipt_id"):
            reasons["SOURCE_RECEIPT_UNKNOWN"] += 1
            continue
        if rights_ok and session_ok:
            verified += 1
    return {"ready": rights_ok and session_ok and verified > 0 and verified == len(contracts),
            "verified": verified, "total": len(contracts), "reasons": sorted(reasons)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("receipt_json", help="local sanitized receipt JSON; never credentials")
    args = parser.parse_args()
    with open(args.receipt_json, encoding="utf-8") as handle:
        result = audit(json.load(handle))
    print(json.dumps(result, sort_keys=True))
    raise SystemExit(0 if result["ready"] else 2)


if __name__ == "__main__":
    main()
