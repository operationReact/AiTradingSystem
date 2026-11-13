"""Deterministic tests for Phase 4D5 research-only acceptance."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.dataset_acceptance_gate import (
    DatasetEvidence,
    accept_dataset,
)

NOW = datetime(2026, 11, 11, 3, 0, tzinfo=UTC)


def sample() -> DatasetEvidence:
    return DatasetEvidence(
        source_id="licensed-feed-a",
        dataset_version="v1",
        instrument_id="NSE:TEST",
        decision_at=NOW,
        first_observation_at=NOW - timedelta(hours=2),
        last_observation_at=NOW - timedelta(minutes=1),
        received_at=NOW,
        licensed_for_research=True,
        expected_bars=20,
        observed_bars=20,
        missing_bars=0,
    )


def assess(evidence: DatasetEvidence):
    return accept_dataset(
        evidence, max_age=timedelta(minutes=5),
        max_reference_skew=timedelta(seconds=30),
    )


def test_accepts_only_research_evidence():
    result = assess(sample())
    assert result.accepted_for_research
    assert result.reason == "RESEARCH_ONLY_PROVENANCE_ACCEPTED"
    assert not result.can_authorize_capital


@pytest.mark.parametrize(
    ("changes", "reason"),
    [
        ({"source_id": ""}, "MISSING_PROVENANCE"),
        ({"dataset_version": ""}, "MISSING_PROVENANCE"),
        ({"instrument_id": ""}, "UNKNOWN_INSTRUMENT"),
        ({"licensed_for_research": False}, "UNLICENSED_SOURCE"),
        ({"missing_bars": 1, "observed_bars": 19}, "INCOMPLETE_COVERAGE"),
        ({"observed_bars": 19}, "INCONSISTENT_COVERAGE_COUNTS"),
        ({"last_observation_at": NOW - timedelta(minutes=6)}, "STALE_OBSERVATIONS"),
        ({"received_at": NOW + timedelta(seconds=1)}, "FUTURE_INFORMATION"),
        ({"reference_source_id": "licensed-feed-a",
          "reference_last_observation_at": NOW}, "NON_INDEPENDENT_REFERENCE"),
        ({"reference_source_id": "licensed-feed-b"}, "INVALID_REFERENCE_TIMESTAMP"),
        ({"reference_last_observation_at": NOW}, "MISSING_REFERENCE_SOURCE"),
        ({"reference_source_id": "licensed-feed-b",
          "reference_last_observation_at": NOW + timedelta(seconds=1)}, "FUTURE_REFERENCE"),
        ({"reference_source_id": "licensed-feed-b",
          "reference_last_observation_at": NOW - timedelta(minutes=2)}, "REFERENCE_TIME_SKEW"),
    ],
)
def test_fail_closed(changes, reason):
    result = assess(replace(sample(), **changes))
    assert not result.accepted_for_research
    assert result.reason == reason
    assert not result.can_authorize_capital


def test_independent_reference_within_skew():
    evidence = replace(
        sample(),
        reference_source_id="licensed-feed-b",
        reference_last_observation_at=NOW - timedelta(seconds=45),
    )
    assert assess(evidence).accepted_for_research


def test_invalid_policy_abstains():
    evidence = sample()
    result = accept_dataset(
        evidence, max_age=timedelta(0), max_reference_skew=timedelta(0),
    )
    assert result.reason == "INVALID_ACCEPTANCE_POLICY"
    assert not result.accepted_for_research
