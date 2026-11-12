"""Synthetic tests for research-only bar continuity safety."""

from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.bar_continuity_gate import evaluate_bar_continuity


START = datetime(2026, 1, 5, 9, 15, tzinfo=UTC)
STEP = timedelta(minutes=5)


@pytest.mark.parametrize(
    ("timestamps", "interval", "verified", "reason"),
    [
        ((START, START + STEP), STEP, True, "research_eligible_only"),
        ((START, START + STEP * 2), STEP, True, "missing_or_irregular_bar"),
        ((START, START), STEP, True, "non_monotonic_timestamps"),
        ((START + STEP, START), STEP, True, "non_monotonic_timestamps"),
        ((START,), STEP, True, "insufficient_observations"),
        ((START, START + STEP), timedelta(0), True, "invalid_interval"),
        ((START, START + STEP), STEP, False, "source_unverified"),
        (
            (START.replace(tzinfo=None), (START + STEP).replace(tzinfo=None)),
            STEP,
            True,
            "naive_timestamp",
        ),
    ],
)
def test_bar_continuity(timestamps, interval, verified, reason):
    decision = evaluate_bar_continuity(
        timestamps=timestamps,
        expected_interval=interval,
        source_verified=verified,
    )
    assert decision.reason == reason
    assert decision.eligible == (reason == "research_eligible_only")
    assert decision.can_authorize_capital is False
