"""Synthetic timezone-equivalence and fail-closed as-of regression cases.

These tests exercise research eligibility only; they never authorize orders.
"""

from datetime import datetime, timedelta, timezone, UTC

import pytest

from aitrading_intelligence.research_asof_gate import evaluate_asof_features


DECISION = datetime(2026, 1, 5, 9, 20, tzinfo=UTC)
IST = timezone(timedelta(hours=5, minutes=30))


@pytest.mark.parametrize(
    ("available", "expected_reason"),
    [
        (datetime(2026, 1, 5, 14, 50, tzinfo=IST), "research_eligible_only"),
        (datetime(2026, 1, 5, 14, 49, 59, tzinfo=IST), "research_eligible_only"),
        (datetime(2026, 1, 5, 14, 50, 1, tzinfo=IST), "lookahead_feature"),
        (datetime(2026, 1, 5, 9, 20, 1, tzinfo=UTC), "lookahead_feature"),
    ],
)
def test_asof_compares_absolute_instants_across_offsets(available, expected_reason):
    result = evaluate_asof_features(
        decision_at=DECISION,
        feature_available_at=(available,),
        source_verified=True,
        coverage_verified=True,
    )
    assert result.reason == expected_reason
    assert result.eligible is (expected_reason == "research_eligible_only")
    assert result.can_authorize_capital is False


def test_asof_rejects_any_future_feature_in_mixed_offset_batch():
    result = evaluate_asof_features(
        decision_at=DECISION,
        feature_available_at=(
            DECISION - timedelta(minutes=5),
            datetime(2026, 1, 5, 14, 50, 1, tzinfo=IST),
        ),
        source_verified=True,
        coverage_verified=True,
    )
    assert result.reason == "lookahead_feature"
    assert not result.eligible
    assert not result.can_authorize_capital


def test_asof_rejects_any_naive_feature_in_mixed_batch():
    result = evaluate_asof_features(
        decision_at=DECISION,
        feature_available_at=(DECISION, DECISION.replace(tzinfo=None)),
        source_verified=True,
        coverage_verified=True,
    )
    assert result.reason == "naive_timestamp"
    assert not result.eligible
    assert not result.can_authorize_capital
