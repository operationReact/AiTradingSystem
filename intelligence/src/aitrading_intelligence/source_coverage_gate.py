"""Fail-closed provenance and coverage gate for Mintick Mind research.

No market data retrieval, order authorization, performance claims or persistence.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum


class CoverageDecision(StrEnum):
    RESEARCH_ONLY = "RESEARCH_ONLY"
    ABSTAIN = "ABSTAIN"


@dataclass(frozen=True)
class CoverageResult:
    decision: CoverageDecision
    reason: str
    can_authorize_capital: bool = False


def assess_source_coverage(
    *,
    decision_at: datetime,
    first_observation_at: datetime | None,
    last_observation_at: datetime | None,
    source_received_at: datetime | None,
    source_licensed: bool,
    instrument_identified: bool,
    expected_bars: int,
    observed_bars: int,
    missing_bars: int,
) -> CoverageResult:
    """Require timestamped, complete, licensed evidence known at decision time."""
    def abstain(reason: str) -> CoverageResult:
        return CoverageResult(CoverageDecision.ABSTAIN, reason)
    times = (decision_at, first_observation_at, last_observation_at, source_received_at)
    if any(t is None or t.tzinfo is None or t.utcoffset() is None for t in times):
        return abstain("MISSING_OR_NAIVE_TIMESTAMP")
    if not source_licensed:
        return abstain("UNLICENSED_SOURCE")
    if not instrument_identified:
        return abstain("UNKNOWN_INSTRUMENT")
    if first_observation_at > last_observation_at:
        return abstain("REVERSED_OBSERVATION_WINDOW")
    if last_observation_at > decision_at or source_received_at > decision_at:
        return abstain("FUTURE_INFORMATION")
    if expected_bars <= 0 or observed_bars < 0 or missing_bars < 0:
        return abstain("INVALID_COVERAGE_COUNTS")
    if observed_bars + missing_bars != expected_bars:
        return abstain("INCONSISTENT_COVERAGE_COUNTS")
    if missing_bars != 0:
        return abstain("INCOMPLETE_COVERAGE")
    return CoverageResult(CoverageDecision.RESEARCH_ONLY, "COMPLETE_LICENSED_AS_OF_DECISION")
