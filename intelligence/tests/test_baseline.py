from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.baseline import (
    DEFAULT_BASELINE_FEATURES,
    score_logistic_baseline,
    train_logistic_baseline,
)
from aitrading_intelligence.features import FeatureRow


def _row(index: int, positive: bool) -> FeatureRow:
    as_of = datetime(2026, 1, 1, tzinfo=UTC) + timedelta(minutes=index)
    sign = 1.0 if positive else -1.0
    return FeatureRow(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        features={
            "market.return_1": sign * 0.01,
            "market.range_pct": 0.02,
            "market.volume_change_1": sign * 0.20,
            "event.AI_NEWS_EVIDENCE.direction_score": sign * 0.70,
            "event.AI_NEWS_EVIDENCE.confidence": 0.80,
            "event.AI_NEWS_EVIDENCE.materiality": 0.75,
        },
        provenance=[f"fixture:{index}"],
        fingerprint=f"{index + 1:064x}",
    )


def test_logistic_baseline_is_reproducible_and_evidence_only() -> None:
    rows = [_row(index, index % 2 == 0) for index in range(30)]
    labels = [1 if index % 2 == 0 else 0 for index in range(30)]

    first = train_logistic_baseline(rows, labels)
    second = train_logistic_baseline(list(reversed(rows)), list(reversed(labels)))

    assert first.artifact_fingerprint == second.artifact_fingerprint
    assert first.feature_names == list(DEFAULT_BASELINE_FEATURES)
    assert first.row_count == 30
    assert first.positive_count == 15
    assert first.negative_count == 15
    assert first.can_authorize_capital is False

    score = score_logistic_baseline(_row(31, True), first)
    assert 0.0 <= score.probability_up <= 1.0
    assert score.directional_score > 0.0
    assert score.confidence > 0.0
    assert score.can_authorize_capital is False
    assert set(score.feature_contributions) == set(DEFAULT_BASELINE_FEATURES)


def test_logistic_baseline_requires_enough_two_class_history() -> None:
    rows = [_row(index, True) for index in range(20)]

    with pytest.raises(ValueError, match="both positive and negative labels"):
        train_logistic_baseline(rows, [1] * 20)

    with pytest.raises(ValueError, match="at least 20 labeled rows"):
        train_logistic_baseline(rows[:10], [1, 0] * 5)
