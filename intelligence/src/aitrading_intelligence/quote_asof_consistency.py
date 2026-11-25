"""Research-only quote timestamp consistency across the causal decision snapshot."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .cross_gate_asof import AsOfDecision
from .next_bar_timing_gate import TimingEvidence


@dataclass(frozen=True)
class QuoteAsOfResult:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_quote_asof_consistency(
    *, decision_at: datetime, timing: TimingEvidence, cross_gate: AsOfDecision
) -> QuoteAsOfResult:
    """Never treat a future, missing, or unbounded quote as execution evidence."""
    def abstain(reason: str) -> QuoteAsOfResult:
        return QuoteAsOfResult(False, reason)

    if cross_gate.execution_authorized:
        return abstain("UNEXPECTED_EXECUTION_AUTHORITY")
    if not cross_gate.eligible_for_research or (
        cross_gate.reason != "CROSS_GATE_ASOF_RESEARCH_ONLY"
    ):
        return abstain("UPSTREAM_CROSS_GATE_NOT_READY")
    quote_at = timing.quote_observed_at
    if quote_at is None:
        return abstain("MISSING_QUOTE")
    times = (decision_at, quote_at, timing.signal_bar_closed_at,
             timing.research_received_at, timing.proposed_entry_at)
    if any(t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("NAIVE_TIMESTAMP")
    if quote_at > timing.research_received_at or quote_at > decision_at:
        return abstain("FUTURE_QUOTE")
    if timing.research_received_at > decision_at:
        return abstain("RESEARCH_RECEIVED_AFTER_DECISION")
    if timing.proposed_entry_at <= decision_at:
        return abstain("ENTRY_NOT_AFTER_DECISION")
    return QuoteAsOfResult(True, "QUOTE_ASOF_RESEARCH_ONLY_NOT_A_FILL")
