"""Research-only causal timing gate for a hypothetical next-bar entry.

A bar open is never evidence of an achievable fill. No order authority.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta


@dataclass(frozen=True)
class TimingEvidence:
    signal_bar_closed_at: datetime
    research_received_at: datetime
    proposed_entry_at: datetime
    quote_observed_at: datetime | None = None


@dataclass(frozen=True)
class TimingDecision:
    eligible_for_timing_research: bool
    reason: str
    execution_authorized: bool = False


def assess_next_bar_timing(
    evidence: TimingEvidence, *, minimum_delay: timedelta = timedelta(0),
    maximum_quote_age: timedelta | None = None,
) -> TimingDecision:
    """Reject ambiguous chronology and any hypothetical entry before receipt."""
    def abstain(reason: str) -> TimingDecision:
        return TimingDecision(False, reason)

    times = (
        evidence.signal_bar_closed_at,
        evidence.research_received_at,
        evidence.proposed_entry_at,
    )
    if any(t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("NAIVE_TIMESTAMP")
    if minimum_delay < timedelta(0) or (
        maximum_quote_age is not None and maximum_quote_age < timedelta(0)
    ):
        return abstain("INVALID_DELAY")
    if evidence.research_received_at < evidence.signal_bar_closed_at:
        return abstain("RECEIPT_BEFORE_SIGNAL_CLOSE")
    if evidence.proposed_entry_at <= evidence.research_received_at:
        return abstain("ENTRY_NOT_AFTER_RECEIPT")
    if evidence.proposed_entry_at - evidence.research_received_at < minimum_delay:
        return abstain("INSUFFICIENT_DECISION_DELAY")
    quote_at = evidence.quote_observed_at
    if quote_at is None:
        return abstain("MISSING_AS_OF_QUOTE")
    if quote_at.tzinfo is None or quote_at.utcoffset() is None:
        return abstain("NAIVE_QUOTE_TIMESTAMP")
    if quote_at > evidence.research_received_at:
        return abstain("FUTURE_QUOTE_INFORMATION")
    if maximum_quote_age is not None and (
        evidence.research_received_at - quote_at > maximum_quote_age
    ):
        return abstain("STALE_AS_OF_QUOTE")
    return TimingDecision(True, "TIMING_RESEARCH_ONLY_NOT_EXECUTABLE")
