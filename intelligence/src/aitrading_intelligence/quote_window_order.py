"""Research-only quote/observation window chronology check."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .quote_asof_consistency import QuoteAsOfResult
from .source_coverage_gate import CoverageDecision, CoverageResult


@dataclass(frozen=True)
class QuoteWindowDecision:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_quote_window_order(
    *,
    last_observation_at: datetime,
    quote_observed_at: datetime | None,
    decision_at: datetime,
    coverage: CoverageResult,
    quote_asof: QuoteAsOfResult,
) -> QuoteWindowDecision:
    """Reject quotes preceding the last observation in the claimed window."""
    def abstain(reason: str) -> QuoteWindowDecision:
        return QuoteWindowDecision(False, reason)

    if coverage.can_authorize_capital or quote_asof.execution_authorized:
        return abstain("UNEXPECTED_EXECUTION_AUTHORITY")
    if coverage.decision != CoverageDecision.RESEARCH_ONLY or (
        coverage.reason != "COMPLETE_LICENSED_AS_OF_DECISION"
    ):
        return abstain("UPSTREAM_COVERAGE_NOT_READY")
    if not quote_asof.eligible_for_research or (
        quote_asof.reason != "QUOTE_ASOF_RESEARCH_ONLY_NOT_A_FILL"
    ):
        return abstain("UPSTREAM_QUOTE_NOT_READY")
    if quote_observed_at is None:
        return abstain("MISSING_QUOTE")
    times = (last_observation_at, quote_observed_at, decision_at)
    if any(t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("NAIVE_TIMESTAMP")
    if quote_observed_at < last_observation_at:
        return abstain("QUOTE_PRECEDES_LAST_OBSERVATION")
    if quote_observed_at > decision_at:
        return abstain("QUOTE_AFTER_DECISION")
    return QuoteWindowDecision(True, "QUOTE_WINDOW_RESEARCH_ONLY")
