"""Synthetic-only tests for research as-of availability safety."""

from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.research_asof_gate import evaluate_asof_features

NOW = datetime(2026, 1, 5, 9, 20, tzinfo=UTC)


@pytest.mark.parametrize(
    ("features", "source", "coverage", "decision", "reason"),
    [
        ((NOW,), True, True, NOW, "research_eligible_only"),
        ((NOW - timedelta(minutes=5),), True, True, NOW, "research_eligible_only"),
        ((NOW + timedelta(seconds=1),), True, True, NOW, "lookahead_feature"),
        ((), True, True, NOW, "missing_features"),
        ((NOW,), False, True, NOW, "unverified_source_or_coverage"),
        ((NOW,), True, False, NOW, "unverified_source_or_coverage"),
        ((NOW.replace(tzinfo=None),), True, True, NOW, "naive_timestamp"),
        ((NOW,), True, True, NOW.replace(tzinfo=None), "naive_timestamp"),
    ],
)
def test_asof_feature_gate(features, source, coverage, decision, reason):
    result = evaluate_asof_features(
        decision_at=decision,
        feature_available_at=features,
        source_verified=source,
        coverage_verified=coverage,
    )
    assert result.reason == reason
    assert result.eligible == (reason == "research_eligible_only")
    assert result.can_authorize_capital is False
