"""Research-only composition: both coverage and causal timing must pass.

Neither component nor their conjunction authorizes orders or capital.
"""
from __future__ import annotations

from dataclasses import dataclass

from .next_bar_timing_gate import TimingDecision
from .source_coverage_gate import CoverageDecision, CoverageResult


@dataclass(frozen=True)
class ResearchReadiness:
    eligible_for_research: bool
    reason: str
    execution_authorized: bool = False


def assess_research_readiness(
    coverage: CoverageResult, timing: TimingDecision
) -> ResearchReadiness:
    """Fail closed if either independent evidence gate abstains."""
    if coverage.can_authorize_capital or timing.execution_authorized:
        return ResearchReadiness(False, "UNEXPECTED_EXECUTION_AUTHORITY")
    if coverage.decision != CoverageDecision.RESEARCH_ONLY:
        return ResearchReadiness(False, "COVERAGE_" + coverage.reason)
    if not timing.eligible_for_timing_research:
        return ResearchReadiness(False, "TIMING_" + timing.reason)
    if coverage.reason != "COMPLETE_LICENSED_AS_OF_DECISION":
        return ResearchReadiness(False, "UNRECOGNIZED_COVERAGE_EVIDENCE")
    if timing.reason != "TIMING_RESEARCH_ONLY_NOT_EXECUTABLE":
        return ResearchReadiness(False, "UNRECOGNIZED_TIMING_EVIDENCE")
    return ResearchReadiness(True, "RESEARCH_ONLY_BOTH_GATES_PASSED")
