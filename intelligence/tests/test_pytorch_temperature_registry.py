from datetime import UTC, datetime, timedelta

import pytest

pytest.importorskip("torch")

from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.model_fair_comparison import (
    FairComparisonConfig,
    evaluate_model_families,
)
from aitrading_intelligence.model_registry import register_pytorch_model_lab_entry
from aitrading_intelligence.pytorch_model import TorchTrainingConfig, train_torch_challenger
from aitrading_intelligence.pytorch_target import (
    ForwardReturnTargetSpec,
    build_labeled_examples,
)


def _series(length: int) -> list[FeatureRow]:
    closes = [100.0]
    rows: list[FeatureRow] = []
    start = datetime(2026, 1, 1, 3, 45, tzinfo=UTC)
    for index in range(length):
        if index > 0:
            direction = 1.0 if (index % 4) in (1, 2) else -1.0
            magnitude = 0.012 if index % 5 else 0.007
            closes.append(closes[-1] * (1.0 + (direction * magnitude)))
        direction = 1.0 if (index % 4) in (1, 2) else -1.0
        as_of = start + timedelta(minutes=5 * index)
        rows.append(
            FeatureRow(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                features={
                    "market.close": closes[index],
                    "market.return_1": direction * (0.008 + (0.001 * (index % 3))),
                    "market.range_pct": 0.012 + (0.001 * (index % 4)),
                    "market.volume_change_1": direction * (0.15 + 0.02 * (index % 5)),
                    "event.AI_NEWS_EVIDENCE.direction_score": direction * 0.65,
                    "event.AI_NEWS_EVIDENCE.confidence": 0.70 + (0.02 * (index % 4)),
                    "event.AI_NEWS_EVIDENCE.materiality": 0.68 + (0.01 * (index % 5)),
                },
                provenance=[f"pytorch-temperature-registry:{index}"],
                fingerprint=f"{index + 1:064x}",
            )
        )
    return rows


def _spec() -> ForwardReturnTargetSpec:
    return ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=25,
        round_trip_cost_bps=10,
    )


def _comparison_config() -> FairComparisonConfig:
    return FairComparisonConfig(
        fold_count=3,
        test_rows_per_fold=10,
        minimum_train_rows=20,
        calibration_bins=5,
        seed=42,
    )


def test_pytorch_artifact_is_causal_and_registers_in_model_lab() -> None:
    spec = _spec()
    examples = build_labeled_examples(_series(90), spec)
    comparison_config = _comparison_config()
    evaluation_as_of = examples[-1].target_observed_at
    comparison = evaluate_model_families(
        examples,
        spec,
        evaluation_as_of=evaluation_as_of,
        config=comparison_config,
        include_pytorch=True,
    )
    artifact = train_torch_challenger(
        examples,
        config=TorchTrainingConfig(seed=comparison_config.seed),
        training_as_of=evaluation_as_of,
    )

    entry = register_pytorch_model_lab_entry(
        artifact=artifact,
        comparison=comparison,
        comparison_config=comparison_config,
        code_sha="a" * 40,
        registered_at=evaluation_as_of + timedelta(minutes=1),
    )

    assert entry.model_family == "PYTORCH"
    assert entry.artifact_fingerprint == artifact.model_fingerprint
    assert entry.target_spec_fingerprint == spec.fingerprint
    assert entry.training_config_fingerprint == TorchTrainingConfig(seed=42).fingerprint
    assert artifact.latest_label_observed_at <= artifact.training_as_of
    assert len(artifact.training_example_fingerprints) == artifact.train_rows
    assert len(artifact.validation_example_fingerprints) == artifact.validation_rows
    assert entry.required_next_gate == "V3_05_CALIBRATION"
    assert entry.promotion_allowed is False
    assert entry.can_authorize_capital is False


def test_future_archive_growth_cannot_rewrite_historical_pytorch_artifact() -> None:
    spec = _spec()
    historical = build_labeled_examples(_series(90), spec)
    extended = build_labeled_examples(_series(120), spec)
    cutoff = historical[-1].target_observed_at
    config = TorchTrainingConfig(hidden_dim=8, epochs=40, learning_rate=0.05, seed=7)

    before_growth = train_torch_challenger(
        historical,
        config=config,
        training_as_of=cutoff,
    )
    after_growth = train_torch_challenger(
        extended,
        config=config,
        training_as_of=cutoff,
    )

    assert before_growth == after_growth
    assert before_growth.model_fingerprint == after_growth.model_fingerprint
    assert before_growth.training_example_fingerprints == (
        after_growth.training_example_fingerprints
    )
    assert before_growth.validation_example_fingerprints == (
        after_growth.validation_example_fingerprints
    )
