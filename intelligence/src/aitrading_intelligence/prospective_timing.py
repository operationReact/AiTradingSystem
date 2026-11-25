"""Fail-closed, research-only prospective next-bar timing eligibility.

This module does not fetch prices, produce returns, or authorize orders.
The source's publication/receipt time must be known before the decision,
and the next bar must begin strictly after the decision.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum


class TimingStatus(StrEnum):
    ELIGIBLE_RESEARCH_ONLY = "ELIGIBLE_RESEARCH_ONLY"
    ABSTAIN = "ABSTAIN"


@dataclass(frozen=True)
class TimingAssessment:
    status: TimingStatus
    reason: str
    can_authorize_capital: bool = False


def assess_prospective_next_bar(
    *,
    decision_at: datetime,
    next_bar_open_at: datetime | None,
    source_published_at: datetime | None,
    source_received_at: datetime | None,
    source_licensed: bool,
    coverage_complete: bool,
) -> TimingAssessment:
    """Check evidence custody and causal entry timing; never calculate P&L."""
    times = (decision_at, next_bar_open_at, source_published_at, source_received_at)
    if any(t is None or t.tzinfo is None or t.utcoffset() is None for t in times):
        return TimingAssessment(TimingStatus.ABSTAIN, "MISSING_OR_NAIVE_TIMESTAMP")
    if not source_licensed:
        return TimingAssessment(TimingStatus.ABSTAIN, "SOURCE_RIGHTS_UNVERIFIED")
    if not coverage_complete:
        return TimingAssessment(TimingStatus.ABSTAIN, "COVERAGE_INCOMPLETE")
    assert next_bar_open_at is not None
    assert source_published_at is not None
    assert source_received_at is not None
    if source_published_at > source_received_at:
        return TimingAssessment(TimingStatus.ABSTAIN, "SOURCE_CLOCK_INCONSISTENT")
    if source_published_at > decision_at or source_received_at > decision_at:
        return TimingAssessment(TimingStatus.ABSTAIN, "EVIDENCE_NOT_AVAILABLE_AT_DECISION")
    if next_bar_open_at <= decision_at:
        return TimingAssessment(TimingStatus.ABSTAIN, "NEXT_BAR_NOT_PROSPECTIVE")
    return TimingAssessment(TimingStatus.ELIGIBLE_RESEARCH_ONLY, "CAUSAL_TIMING_ONLY")
