"""Research-only prospective next-bar timing audit, never a fill simulator."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .quote_window_order import QuoteWindowDecision


@dataclass(frozen=True)
class NextBarTimingDecision:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_next_bar_timing(
    *,
    decision_at: datetime,
    next_bar_open_at: datetime | None,
    next_bar_close_at: datetime | None,
    evaluated_at: datetime,
    quote_window: QuoteWindowDecision,
) -> NextBarTimingDecision:
    """Permit retrospective research only after the prospective bar closes."""
    def abstain(reason: str) -> NextBarTimingDecision:
        return NextBarTimingDecision(False, reason)

    if quote_window.execution_authorized:
        return abstain("UNEXPECTED_EXECUTION_AUTHORITY")
    if (
        not quote_window.eligible_for_research
        or quote_window.reason != "QUOTE_WINDOW_RESEARCH_ONLY"
    ):
        return abstain("UPSTREAM_QUOTE_WINDOW_NOT_READY")
    if next_bar_open_at is None or next_bar_close_at is None:
        return abstain("MISSING_NEXT_BAR")
    times = (decision_at, next_bar_open_at, next_bar_close_at, evaluated_at)
    if any(t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("NAIVE_TIMESTAMP")
    if next_bar_open_at <= decision_at:
        return abstain("NEXT_BAR_NOT_PROSPECTIVE")
    if next_bar_close_at <= next_bar_open_at:
        return abstain("INVALID_BAR_INTERVAL")
    if evaluated_at < next_bar_close_at:
        return abstain("BAR_NOT_YET_COMPLETE")
    return NextBarTimingDecision(True, "NEXT_BAR_RETROSPECTIVE_RESEARCH_ONLY")
