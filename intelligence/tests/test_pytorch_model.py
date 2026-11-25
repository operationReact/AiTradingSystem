from datetime import UTC, datetime, timedelta

import pytest

pytest.importorskip("torch")

from aitrading_intelligence.baseline import DEFAULT_BASELINE_FEATURES
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.pytorch_model import (
    TorchTrainingConfig,
    score_torch_challenger,
    train_torch_challenger,
)
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


def _series(length: int = 61) -> list[FeatureRow]:
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
                    "market.range_pct": 0.015,
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


def test_training_is_reproducible_purged_and_never_promoted() -> None:
    rows = _series()
    spec = ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )
    examples = build_labeled_examples(rows, spec)
    config = TorchTrainingConfig(hidden_dim=8, epochs=60, learning_rate=0.05, seed=42)

    first = train_torch_challenger(examples, config=config)
    second = train_torch_challenger(list(reversed(examples)), config=config)

    assert first.model_fingerprint == second.model_fingerprint
    assert first.feature_names == list(DEFAULT_BASELINE_FEATURES)
    assert first.train_end < first.validation_start
    assert first.train_rows >= 20
    assert first.validation_rows >= 10
    assert 0.0 <= first.validation.roc_auc <= 1.0
    assert 0.0 <= first.validation.brier_score <= 1.0
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False
    assert first.target_spec_fingerprint == spec.fingerprint


def test_inference_is_bounded_explainable_and_evidence_only() -> None:
    rows = _series()
    examples = build_labeled_examples(
        rows,
        ForwardReturnTargetSpec(horizon_bars=1, minimum_edge_bps=25, round_trip_cost_bps=10),
    )
    artifact = train_torch_challenger(
        examples,
        config=TorchTrainingConfig(hidden_dim=8, epochs=40, learning_rate=0.05, seed=7),
    )

    evidence = score_torch_challenger(rows[-1], artifact)

    assert 0.0 <= evidence.probability_up <= 1.0
    assert -1.0 <= evidence.directional_score <= 1.0
    assert 0.0 <= evidence.confidence <= 1.0
    assert set(evidence.feature_attribution) == set(DEFAULT_BASELINE_FEATURES)
    assert len(evidence.inference_fingerprint) == 64
    assert evidence.status == "CHALLENGER_EVIDENCE_ONLY"
    assert evidence.promotion_allowed is False
    assert evidence.can_authorize_capital is False


def test_training_rejects_inconsistent_target_specs() -> None:
    rows = _series()
    first = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=1))
    second = build_labeled_examples(rows, ForwardReturnTargetSpec(horizon_bars=2))
    mixed = [*first[:30], *second[30:45]]

    with pytest.raises(ValueError, match="same target specification"):
        train_torch_challenger(mixed)
