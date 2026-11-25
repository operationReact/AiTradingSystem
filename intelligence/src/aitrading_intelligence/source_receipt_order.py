"""Research-only guard against observations arriving after source receipt."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .source_coverage_gate import CoverageDecision, CoverageResult


@dataclass(frozen=True)
class SourceReceiptDecision:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_source_receipt_order(
    *,
    first_observation_at: datetime,
    last_observation_at: datetime,
    source_received_at: datetime,
    decision_at: datetime,
    coverage: CoverageResult,
) -> SourceReceiptDecision:
    """Fail closed unless the complete observation window was already received."""
    def abstain(reason: str) -> SourceReceiptDecision:
        return SourceReceiptDecision(False, reason)

    times = (
        first_observation_at, last_observation_at, source_received_at, decision_at
    )
    if any(t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("NAIVE_SOURCE_TIMESTAMP")
    if coverage.can_authorize_capital:
        return abstain("UNEXPECTED_EXECUTION_AUTHORITY")
    if coverage.decision != CoverageDecision.RESEARCH_ONLY or (
        coverage.reason != "COMPLETE_LICENSED_AS_OF_DECISION"
    ):
        return abstain("UPSTREAM_COVERAGE_NOT_READY")
    if first_observation_at > last_observation_at:
        return abstain("REVERSED_OBSERVATION_WINDOW")
    if last_observation_at > source_received_at:
        return abstain("OBSERVATION_AFTER_SOURCE_RECEIPT")
    if source_received_at > decision_at:
        return abstain("SOURCE_RECEIVED_AFTER_DECISION")
    return SourceReceiptDecision(True, "SOURCE_RECEIPT_RESEARCH_ONLY")
