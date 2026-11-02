from datetime import UTC, datetime, timedelta

import pytest

pytest.importorskip("torch")

from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.pytorch_evaluation import evaluate_pytorch_challenger
from aitrading_intelligence.pytorch_model import TorchTrainingConfig
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


def _series(length: int = 81) -> list[FeatureRow]:
    closes = [100.0]
    for index in range(length - 1):
        direction = 1.0 if index % 2 == 0 else -1.0
        closes.append(closes[-1] * (1.0 + (0.01 * direction)))

    rows: list[FeatureRow] = []
    start = datetime(2026, 1, 1, 3, 45, tzinfo=UTC)
    for index, close in enumerate(closes):
        direction = 1.0 if index % 2 == 0 else -1.0
        as_of = start + timedelta(minutes=5 * index)
        rows.append(
            FeatureRow(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                features={
                    "market.close": close,
                    "market.return_1": direction * 0.01,
                    "market.range_pct": 0.015 + ((index % 3) * 0.001),
                    "market.volume_change_1": direction * 0.20,
                    "event.AI_NEWS_EVIDENCE.direction_score": direction * 0.70,
                    "event.AI_NEWS_EVIDENCE.confidence": 0.80,
                    "event.AI_NEWS_EVIDENCE.materiality": 0.75,
                },
                provenance=[f"fixture:{index}"],
                fingerprint=f"{index + 1:064x}",
            )
        )
    return rows


def _inputs() -> tuple[list[FeatureRow], list]:
    rows = _series()
    examples = build_labeled_examples(
        rows,
        ForwardReturnTargetSpec(
            horizon_bars=1,
            minimum_edge_bps=25,
            round_trip_cost_bps=10,
        ),
    )
    return rows, examples


def test_evaluation_is_deterministic_calibrated_and_never_promotes() -> None:
    rows, examples = _inputs()
    config = TorchTrainingConfig(
        hidden_dim=8,
        epochs=60,
        learning_rate=0.05,
        train_fraction=0.70,
        seed=42,
    )

    first = evaluate_pytorch_challenger(rows, examples, config=config)
    second = evaluate_pytorch_challenger(
        list(reversed(rows)),
        list(reversed(examples)),
        config=config,
    )

    assert first.evaluation_fingerprint == second.evaluation_fingerprint
    assert first.pytorch_model_fingerprint == second.pytorch_model_fingerprint
    assert first.train_rows >= 20
    assert first.test_rows >= 10
    assert first.comparison in {
        "PYTORCH_ADVANCES_TO_REPLAY",
        "BASELINES_HOLD",
        "INCONCLUSIVE",
    }
    assert first.replay_candidate is (first.comparison == "PYTORCH_ADVANCES_TO_REPLAY")
    assert first.required_next_gate == "V2_08_REPLAY_WALK_FORWARD"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False

    for metrics in (first.logistic, first.tree, first.pytorch):
        assert 0.0 <= metrics.roc_auc <= 1.0
        assert 0.0 <= metrics.brier_score <= 1.0
        assert 0.0 <= metrics.expected_calibration_error <= 1.0
        assert sum(bucket.rows for bucket in metrics.calibration_buckets) == first.test_rows


def test_evaluation_rejects_missing_frozen_feature_rows() -> None:
    rows, examples = _inputs()

    with pytest.raises(ValueError, match="every labeled example"):
        evaluate_pytorch_challenger(rows[:-5], examples)


def test_evaluation_rejects_mixed_target_specs() -> None:
    rows = _series()
    first = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=1))
    second = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=2))
    mixed = [*first[:45], *second[45:70]]

    with pytest.raises(ValueError, match="same target specification"):
        evaluate_pytorch_challenger(rows, mixed)
