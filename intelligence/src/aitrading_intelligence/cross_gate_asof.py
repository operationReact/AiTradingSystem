"""Fail-closed cross-gate as-of chronology for prospective research.

No fills, broker operations, capital authority, or market data redistribution.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .next_bar_timing_gate import TimingEvidence
from .research_readiness_gate import ResearchReadiness
from .source_coverage_gate import CoverageResult


@dataclass(frozen=True)
class AsOfDecision:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_cross_gate_asof(
    *,
    decision_at: datetime,
    source_received_at: datetime,
    timing: TimingEvidence,
    coverage: CoverageResult,
    readiness: ResearchReadiness,
) -> AsOfDecision:
    """Require a single causally consistent decision snapshot across both gates."""
    def abstain(reason: str) -> AsOfDecision:
        return AsOfDecision(False, reason)

    times = (decision_at, source_received_at, timing.signal_bar_closed_at,
             timing.research_received_at, timing.proposed_entry_at)
    if any(t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("NAIVE_ASOF_TIMESTAMP")
    if coverage.can_authorize_capital or readiness.execution_authorized:
        return abstain("UNEXPECTED_EXECUTION_AUTHORITY")
    if not readiness.eligible_for_research or (
        readiness.reason != "RESEARCH_ONLY_BOTH_GATES_PASSED"
    ):
        return abstain("UPSTREAM_RESEARCH_NOT_READY")
    if source_received_at > decision_at:
        return abstain("SOURCE_RECEIVED_AFTER_DECISION")
    if timing.signal_bar_closed_at > source_received_at:
        return abstain("SOURCE_RECEIVED_BEFORE_SIGNAL_CLOSE")
    if timing.research_received_at < source_received_at:
        return abstain("TIMING_RECEIPT_PRECEDES_SOURCE")
    if timing.research_received_at > decision_at:
        return abstain("TIMING_RECEIPT_AFTER_DECISION")
    if timing.proposed_entry_at <= decision_at:
        return abstain("ENTRY_NOT_AFTER_DECISION")
    return AsOfDecision(True, "CROSS_GATE_ASOF_RESEARCH_ONLY")
