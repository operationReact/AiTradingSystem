"""Bound quote age at decision time for research, never for order authorization."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from .quote_asof_consistency import QuoteAsOfResult


@dataclass(frozen=True)
class QuoteAgeDecision:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_quote_age_boundary(
    *,
    quote_observed_at: datetime | None,
    decision_at: datetime,
    maximum_age: timedelta,
    quote_consistency: QuoteAsOfResult,
) -> QuoteAgeDecision:
    """Require explicit nonnegative freshness bound and prior consistency."""

    def abstain(reason: str) -> QuoteAgeDecision:
        return QuoteAgeDecision(False, reason)

    if quote_consistency.execution_authorized:
        return abstain("UNEXPECTED_EXECUTION_AUTHORITY")
    if not quote_consistency.eligible_for_research or (
        quote_consistency.reason != "QUOTE_ASOF_RESEARCH_ONLY_NOT_A_FILL"
    ):
        return abstain("UPSTREAM_QUOTE_NOT_READY")
    if maximum_age < timedelta(0):
        return abstain("NEGATIVE_MAXIMUM_AGE")
    if quote_observed_at is None:
        return abstain("MISSING_QUOTE")
    if any(
        t.tzinfo is None or t.utcoffset() is None
        for t in (quote_observed_at, decision_at)
    ):
        return abstain("NAIVE_TIMESTAMP")
    if quote_observed_at > decision_at:
        return abstain("FUTURE_QUOTE")
    if decision_at - quote_observed_at > maximum_age:
        return abstain("STALE_QUOTE")
    return QuoteAgeDecision(True, "FRESH_QUOTE_RESEARCH_ONLY_NOT_A_FILL")
