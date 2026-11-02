from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.baseline_evaluation import evaluate_baselines
from aitrading_intelligence.features import FeatureRow


def _row(index: int, label: int) -> FeatureRow:
    as_of = datetime(2026, 1, 1, tzinfo=UTC) + timedelta(minutes=index)
    sign = 1.0 if label == 1 else -1.0
    return FeatureRow(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        features={
            "market.return_1": sign * (0.004 + ((index % 5) * 0.001)),
            "market.range_pct": 0.01 + ((index % 3) * 0.002),
            "market.volume_change_1": sign * (0.10 + ((index % 4) * 0.03)),
            "event.AI_NEWS_EVIDENCE.direction_score": sign * 0.65,
            "event.AI_NEWS_EVIDENCE.confidence": 0.75 + ((index % 4) * 0.03),
            "event.AI_NEWS_EVIDENCE.materiality": 0.60 + ((index % 3) * 0.04),
        },
        provenance=[f"fixture:{index}"],
        fingerprint=f"{index + 1:064x}",
    )


def test_chronological_evaluation_is_reproducible_and_never_promotes() -> None:
    labels = [index % 2 for index in range(60)]
    rows = [_row(index, label) for index, label in enumerate(labels)]

    first = evaluate_baselines(rows, labels)
    second = evaluate_baselines(list(reversed(rows)), list(reversed(labels)))

    assert first.evaluation_fingerprint == second.evaluation_fingerprint
    assert first.train_rows == 42
    assert first.test_rows == 18
    assert first.train_end < first.test_start
    assert first.logistic.test_rows == 18
    assert first.tree_challenger.test_rows == 18
    assert 0.0 <= first.logistic.roc_auc <= 1.0
    assert 0.0 <= first.tree_challenger.roc_auc <= 1.0
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False
    assert first.comparison in {
        "TREE_OUTPERFORMS_LOGISTIC",
        "LOGISTIC_HOLDS",
        "INCONCLUSIVE",
    }


def test_evaluation_rejects_weak_or_noncausal_partitions() -> None:
    labels = [index % 2 for index in range(30)]
    rows = [_row(index, label) for index, label in enumerate(labels)]
    with pytest.raises(ValueError, match="at least 40 labeled rows"):
        evaluate_baselines(rows, labels)

    labels = [0] * 42 + [1] * 18
    rows = [_row(index, label) for index, label in enumerate(labels)]
    with pytest.raises(ValueError, match="both train and test partitions"):
        evaluate_baselines(rows, labels)
