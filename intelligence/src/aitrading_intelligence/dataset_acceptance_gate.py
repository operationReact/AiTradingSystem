"""Research-only acceptance gate for reproducible, as-of dataset provenance.

Never authorizes orders, capital allocation, or redistribution of vendor data.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from aitrading_intelligence.source_coverage_gate import (
    CoverageDecision,
    assess_source_coverage,
)


@dataclass(frozen=True)
class DatasetEvidence:
    source_id: str
    dataset_version: str
    instrument_id: str
    decision_at: datetime
    first_observation_at: datetime
    last_observation_at: datetime
    received_at: datetime
    licensed_for_research: bool
    expected_bars: int
    observed_bars: int
    missing_bars: int
    reference_source_id: str | None = None
    reference_last_observation_at: datetime | None = None


@dataclass(frozen=True)
class Acceptance:
    accepted_for_research: bool
    reason: str
    can_authorize_capital: bool = False


def accept_dataset(
    evidence: DatasetEvidence,
    *,
    max_age: timedelta,
    max_reference_skew: timedelta,
) -> Acceptance:
    """Fail closed on provenance, freshness and independently timed references."""
    def abstain(reason: str) -> Acceptance:
        return Acceptance(False, reason)

    if not evidence.source_id.strip() or not evidence.dataset_version.strip():
        return abstain("MISSING_PROVENANCE")
    if not evidence.instrument_id.strip():
        return abstain("UNKNOWN_INSTRUMENT")
    if max_age <= timedelta(0) or max_reference_skew < timedelta(0):
        return abstain("INVALID_ACCEPTANCE_POLICY")
    base = assess_source_coverage(
        decision_at=evidence.decision_at,
        first_observation_at=evidence.first_observation_at,
        last_observation_at=evidence.last_observation_at,
        source_received_at=evidence.received_at,
        source_licensed=evidence.licensed_for_research,
        instrument_identified=True,
        expected_bars=evidence.expected_bars,
        observed_bars=evidence.observed_bars,
        missing_bars=evidence.missing_bars,
    )
    if base.decision != CoverageDecision.RESEARCH_ONLY:
        return abstain(base.reason)
    if evidence.decision_at - evidence.last_observation_at > max_age:
        return abstain("STALE_OBSERVATIONS")
    if evidence.reference_source_id is None and evidence.reference_last_observation_at is not None:
        return abstain("MISSING_REFERENCE_SOURCE")
    if evidence.reference_source_id is not None:
        if (
            not evidence.reference_source_id.strip()
            or evidence.reference_source_id == evidence.source_id
        ):
            return abstain("NON_INDEPENDENT_REFERENCE")
        reference_time = evidence.reference_last_observation_at
        if (
            reference_time is None
            or reference_time.tzinfo is None
            or reference_time.utcoffset() is None
        ):
            return abstain("INVALID_REFERENCE_TIMESTAMP")
        if reference_time > evidence.decision_at:
            return abstain("FUTURE_REFERENCE")
        if abs(reference_time - evidence.last_observation_at) > max_reference_skew:
            return abstain("REFERENCE_TIME_SKEW")
    return Acceptance(True, "RESEARCH_ONLY_PROVENANCE_ACCEPTED")
