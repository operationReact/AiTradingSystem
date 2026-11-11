"""Deterministic research-only next-bar causality tests."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.next_bar_timing_gate import (
    TimingEvidence,
    assess_next_bar_timing,
)

NOW = datetime(2026, 11, 11, 9, 0, tzinfo=UTC)


def sample() -> TimingEvidence:
    return TimingEvidence(
        signal_bar_closed_at=NOW,
        research_received_at=NOW + timedelta(seconds=10),
        proposed_entry_at=NOW + timedelta(minutes=5),
        quote_observed_at=NOW + timedelta(seconds=5),
    )


def test_timing_research_does_not_authorize_execution():
    result = assess_next_bar_timing(sample())
    assert result.eligible_for_timing_research
    assert result.reason == "TIMING_RESEARCH_ONLY_NOT_EXECUTABLE"
    assert not result.execution_authorized


@pytest.mark.parametrize(
    ("changes", "reason"),
    [
        ({"research_received_at": NOW - timedelta(seconds=1)},
         "RECEIPT_BEFORE_SIGNAL_CLOSE"),
        ({"proposed_entry_at": NOW + timedelta(seconds=10)},
         "ENTRY_NOT_AFTER_RECEIPT"),
        ({"proposed_entry_at": NOW + timedelta(seconds=9)},
         "ENTRY_NOT_AFTER_RECEIPT"),
        ({"quote_observed_at": None}, "MISSING_AS_OF_QUOTE"),
        ({"quote_observed_at": NOW + timedelta(seconds=11)},
         "FUTURE_QUOTE_INFORMATION"),
        ({"quote_observed_at": datetime(2026, 11, 11, 9, 0)},
         "NAIVE_QUOTE_TIMESTAMP"),
        ({"research_received_at": datetime(2026, 11, 11, 9, 0)},
         "NAIVE_TIMESTAMP"),
    ],
)
def test_fail_closed(changes, reason):
    result = assess_next_bar_timing(replace(sample(), **changes))
    assert not result.eligible_for_timing_research
    assert result.reason == reason
    assert not result.execution_authorized


def test_delay_policy_abstains():
    result = assess_next_bar_timing(
        sample(), minimum_delay=timedelta(minutes=6)
    )
    assert result.reason == "INSUFFICIENT_DECISION_DELAY"
    assert not result.execution_authorized


def test_negative_delay_policy_abstains():
    result = assess_next_bar_timing(
        sample(), minimum_delay=timedelta(seconds=-1)
    )
    assert result.reason == "INVALID_DELAY"
    assert not result.execution_authorized
