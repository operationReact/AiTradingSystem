"""Offline NSE_FO receipt gate. No API, credentials, database writes or orders."""
import datetime as dt
import json
import sys

def parse(value):
    try:
        result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        return result if result.tzinfo else None
    except (ValueError, TypeError, AttributeError):
        return None

def audit(data, max_age_seconds=15):
    session = data.get("session") or {}
    now = parse(session.get("as_of"))
    if not now or session.get("exchange") != "NSE_FO" or session.get("open") is not True:
        return {"status": "BLOCKED", "reason": "SESSION_UNVERIFIED"}
    contracts, rights, quotes = (data.get(k) for k in ("contracts", "rights", "quotes"))
    if not all(isinstance(x, list) for x in (contracts, rights, quotes)):
        return {"status": "BLOCKED", "reason": "RECEIPTS_MISSING"}
    accepted = set()
    blocked = {}
    def reject(reason):
        blocked[reason] = blocked.get(reason, 0) + 1
    for c in contracts:
        if not isinstance(c, dict):
            reject("CONTRACT_UNVERIFIED"); continue
        key = c.get("instrument_key")
        try:
            expiry = dt.date.fromisoformat(c["expiry"])
        except (ValueError, TypeError, KeyError):
            expiry = None
        lot = c.get("lot_size")
        if (c.get("exchange") != "NSE_FO" or c.get("underlying") != "NIFTY"
            or not isinstance(key, str) or not key or not c.get("symbol")
            or not c.get("receipt_id") or c.get("side") not in ("CE", "PE")
            or type(lot) is not int or lot <= 0 or not expiry or expiry < now.date()):
            reject("CONTRACT_UNVERIFIED"); continue
        authorized = any(isinstance(r, dict) and r.get("instrument_key") == key
            and r.get("receipt_id") and r.get("ephemeral_read") is True
            and r.get("private_display") is True and r.get("paper_simulation") is True
            and parse(r.get("valid_from")) and parse(r.get("valid_until"))
            and parse(r["valid_from"]) <= now <= parse(r["valid_until"]) for r in rights)
        if not authorized:
            reject("RIGHTS_UNVERIFIED"); continue
        fresh = False
        for q in quotes:
            if not isinstance(q, dict) or q.get("instrument_key") != key or not q.get("receipt_id"):
                continue
            exchange_time, acquired = parse(q.get("exchange_time")), parse(q.get("acquired_at"))
            if exchange_time and acquired and exchange_time <= acquired <= now:
                age = (now - exchange_time).total_seconds()
                if 0 <= age <= max_age_seconds:
                    fresh = True
                    break
        if not fresh:
            reject("QUOTE_STALE_OR_UNKNOWN"); continue
        accepted.add((key, c["expiry"], lot, c["side"]))
    return {"status": "VERIFIED" if accepted else "BLOCKED",
            "verified_unique_contracts": len(accepted),
            "observed_contract_rows": len(contracts),
            "blocked_reasons": dict(sorted(blocked.items()))}

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: python scripts/upstox_fo_receipt_gate.py authorized_sanitized_receipts.json")
    with open(sys.argv[1], encoding="utf-8") as handle:
        print(json.dumps(audit(json.load(handle)), sort_keys=True))
