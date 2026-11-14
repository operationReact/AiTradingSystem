"""Worker 3: fail-closed, immutable Upstox historical coverage denominator contract.

Pure read-only evaluator. Callers must supply authorized, migrated database rows;
this module never queries a provider, infers NSE universe size, or writes receipts.
"""
from dataclasses import dataclass
from datetime import date
from typing import Optional, Sequence


@dataclass(frozen=True)
class Target:
    provider: str
    exchange: str
    universe_asof: date
    listing_id: str
    instrument_id: str
    timeframe: str
    start: date
    end_exclusive: date
    source_domain: str
    rights_receipt_id: Optional[str]


@dataclass(frozen=True)
class Receipt:
    listing_id: str
    instrument_id: str
    timeframe: str
    year: int
    month: int
    status: str
    rights_receipt_id: Optional[str]
    source_domain: str


def partition_keys(target: Target):
    """Year/month partitions intersecting the fixed [start,end) window."""
    if target.start >= target.end_exclusive:
        raise ValueError("invalid target window")
    if not all((target.provider == "UPSTOX", target.exchange == "NSE_EQ",
                target.listing_id, target.instrument_id, target.timeframe,
                target.source_domain)):
        raise ValueError("incomplete target identity")
    year, month = target.start.year, target.start.month
    while (year, month) <= ((target.end_exclusive.year, target.end_exclusive.month)
                           if target.end_exclusive.day > 1 else
                           (target.end_exclusive.year - (target.end_exclusive.month == 1),
                            12 if target.end_exclusive.month == 1 else target.end_exclusive.month - 1)):
        yield (target.listing_id, target.instrument_id, target.timeframe, year, month)
        month += 1
        if month == 13:
            year, month = year + 1, 1


def evaluate(targets: Optional[Sequence[Target]], receipts: Sequence[Receipt],
             rights_verified: bool):
    """Never turn unknown universe/rights into a misleading 0% market score."""
    if targets is None:
        return {"state": "UNKNOWN_TARGET", "target": None, "verified": None,
                "candidate": None, "missing": None}
    if not rights_verified or any(not t.rights_receipt_id for t in targets):
        return {"state": "RIGHTS_UNVERIFIED", "target": None, "verified": None,
                "candidate": None, "missing": None}
    expected = {}
    for target in targets:
        for key in partition_keys(target):
            if key in expected:
                raise ValueError("duplicate target partition")
            expected[key] = (target.rights_receipt_id, target.source_domain)
    seen = set()
    verified = candidate = 0
    for receipt in receipts:
        key = (receipt.listing_id, receipt.instrument_id, receipt.timeframe,
               receipt.year, receipt.month)
        if key not in expected or key in seen:
            continue
        seen.add(key)
        rights_id, source_domain = expected[key]
        if receipt.rights_receipt_id != rights_id or receipt.source_domain != source_domain:
            continue
        if receipt.status == "VERIFIED":
            verified += 1
        elif receipt.status == "CANDIDATE":
            candidate += 1
    return {"state": "SCOPED", "target": len(expected), "verified": verified,
            "candidate": candidate, "missing": len(expected) - verified - candidate}
