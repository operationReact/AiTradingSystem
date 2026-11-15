"""Rights-safe Worker 4 receipt eligibility diagnostic; no provider data access.

Run: python3 -m unittest docs/upstox/test_worker4_receipt_gate.py
This is NOT a production adapter, authorization decision, or ingestion receipt.
"""
from datetime import datetime, timezone


REQUIRED = ("sourceId", "authorizationReference", "dataFamily", "listingEpisode",
            "partitionKey", "sourceRecordId", "revision", "receiptId",
            "publishedAt", "availableAt", "observedAt", "committedAt")


def _instant(value):
    if not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt.astimezone(timezone.utc) if dt.tzinfo else None


def evaluate(receipts, cutoff):
    """Return sanitized diagnostic, never inferred persisted counts.

    Caller must provide independently reviewed authorization and commit evidence.
    Unknown/malformed evidence fails closed. Synthetic inputs are tests only.
    """
    limit = _instant(cutoff)
    if limit is None:
        return {"status": "TIMESTAMP_UNKNOWN", "eligibleUniqueUnits": None}
    seen = set()
    count = 0
    rejected = {}
    for r in receipts:
        reason = None
        if not isinstance(r, dict) or any(not r.get(k) for k in REQUIRED):
            reason = "SCHEMA_UNKNOWN"
        elif r["dataFamily"] not in ("RATIO", "CORPORATE_EVENT"):
            reason = "SCHEMA_UNKNOWN"
        elif r.get("useAuthorized") is not True:
            reason = "RIGHTS_BLOCKED"
        elif r.get("persistenceVerified") is not True:
            reason = "PERSISTENCE_UNKNOWN"
        else:
            times = [_instant(r[k]) for k in ("publishedAt", "availableAt", "observedAt", "committedAt")]
            if any(t is None for t in times):
                reason = "TIMESTAMP_UNKNOWN"
            elif any(t > limit for t in times[:3]):
                reason = "FUTURE_KNOWLEDGE"
        if reason:
            rejected[reason] = rejected.get(reason, 0) + 1
            continue
        identity = tuple(r[k] for k in ("sourceId", "dataFamily", "listingEpisode",
                                        "partitionKey", "sourceRecordId", "revision"))
        if identity in seen:
            rejected["DUPLICATE"] = rejected.get("DUPLICATE", 0) + 1
            continue
        seen.add(identity)
        count += 1
    return {"status": "DIAGNOSTIC_ONLY", "eligibleUniqueUnits": count,
            "rejectedByReason": rejected}
