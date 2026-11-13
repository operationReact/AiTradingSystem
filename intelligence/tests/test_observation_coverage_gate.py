"""Synthetic tests for research-only observation coverage."""

from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.observation_coverage_gate import evaluate_observation_coverage

BASE = datetime(2026, 1, 1, tzinfo=UTC)
TIMES = tuple(BASE + timedelta(minutes=i) for i in range(3))


def check(**changes):
    values = dict(
        expected_bars=3,
        observed_bars=3,
        min_coverage=1.0,
        source_verified=True,
        timestamps=TIMES,
    )
    values.update(changes)
    return evaluate_observation_coverage(**values)


def test_verified_source_is_research_only():
    decision = check()
    assert decision.eligible
    assert decision.reason == "research_eligible_only"
    assert decision.can_authorize_capital is False


@pytest.mark.parametrize(
    ("changes", "reason"),
    [
        ({"source_verified": False}, "source_unverified"),
        ({"expected_bars": 0}, "invalid_counts"),
        ({"observed_bars": 4}, "invalid_counts"),
        ({"min_coverage": float("nan")}, "invalid_threshold"),
        ({"min_coverage": 1.1}, "invalid_threshold"),
        ({"timestamps": TIMES[:2]}, "timestamp_count_mismatch"),
        ({"timestamps": tuple(t.replace(tzinfo=None) for t in TIMES)}, "naive_timestamp"),
        ({"timestamps": (TIMES[0], TIMES[0], TIMES[2])}, "non_monotonic_timestamps"),
        ({"observed_bars": 2, "timestamps": TIMES[:2]}, "insufficient_coverage"),
    ],
)
def test_fail_closed(changes, reason):
    result = check(**changes)
    assert not result.eligible
    assert result.reason == reason
    assert result.can_authorize_capital is False
