from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.calibration_artifact import build_calibration_observation
from aitrading_intelligence.model_registry import ModelLabRegistryEntry
from aitrading_intelligence.neural_temperature_scaling import (
    NeuralTemperatureConfig,
    benchmark_neural_temperature_scaling,
)


def _hash(value: int) -> str:
    return f"{value:064x}"


def _registry() -> ModelLabRegistryEntry:
    evaluation_as_of = datetime(2026, 1, 1, 10, 0, tzinfo=UTC)
    return ModelLabRegistryEntry(
        model_family="PYTORCH",
        model_version="pytorch-mlp.v1",
        artifact_fingerprint=_hash(1),
        feature_schema_fingerprint=_hash(2),
        training_dataset_fingerprint=_hash(3),
        target_spec_fingerprint=_hash(4),
        training_config_fingerprint=_hash(5),
        code_sha="a" * 40,
        training_as_of=evaluation_as_of - timedelta(minutes=5),
        evaluation_as_of=evaluation_as_of,
        evaluation_report_fingerprint=_hash(6),
        evaluation_metrics_fingerprint=_hash(7),
        registered_at=evaluation_as_of + timedelta(minutes=1),
        registry_fingerprint=_hash(8),
    )


def _observations(
    registry: ModelLabRegistryEntry,
    *,
    count: int = 70,
    start_offset_minutes: int = 10,
) -> list:
    start = registry.registered_at + timedelta(minutes=start_offset_minutes)
    rows = []
    for index in range(count):
        predicted_positive = index % 2 == 0
        actual_positive = predicted_positive if index % 5 else not predicted_positive
        probability = 0.99 if predicted_positive else 0.01
        decision_at = start + timedelta(minutes=5 * index)
        rows.append(
            build_calibration_observation(
                symbol="RELIANCE",
                decision_at=decision_at,
                target_observed_at=decision_at + timedelta(minutes=2),
                source_registry_fingerprint=registry.registry_fingerprint,
                model_prediction_fingerprint=_hash(1000 + index),
                raw_probability=probability,
                actual_positive=actual_positive,
            )
        )
    return rows


def test_neural_temperature_scaling_is_deterministic_and_improves_overconfidence() -> None:
    registry = _registry()
    observations = _observations(registry)
    benchmark_as_of = observations[-1].target_observed_at
    config = NeuralTemperatureConfig(calibration_bins=5)

    first = benchmark_neural_temperature_scaling(
        source_registry=registry,
        observations=observations,
        benchmark_as_of=benchmark_as_of,
        config=config,
    )
    second = benchmark_neural_temperature_scaling(
        source_registry=registry,
        observations=list(reversed(observations)),
        benchmark_as_of=benchmark_as_of,
        config=config,
    )

    assert first == second
    assert first.model_family == "PYTORCH"
    assert first.temperature > 1.0
    assert first.calibrated_metrics.log_loss < first.raw_metrics.log_loss
    assert first.calibrated_metrics.brier_score < first.raw_metrics.brier_score
    assert first.selected_method == "TEMPERATURE"
    assert first.selected_artifact_fingerprint == first.temperature_artifact_fingerprint
    assert first.required_next_gate == "V3_05_CALIBRATION_FRESHNESS"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_future_closed_observations_cannot_rewrite_historical_temperature_report() -> None:
    registry = _registry()
    historical = _observations(registry)
    benchmark_as_of = historical[-1].target_observed_at
    future_start = (
        int((historical[-1].decision_at - registry.registered_at).total_seconds() / 60)
        + 60
    )
    future = _observations(
        registry,
        count=10,
        start_offset_minutes=future_start,
    )

    before_growth = benchmark_neural_temperature_scaling(
        source_registry=registry,
        observations=historical,
        benchmark_as_of=benchmark_as_of,
    )
    after_growth = benchmark_neural_temperature_scaling(
        source_registry=registry,
        observations=[*historical, *future],
        benchmark_as_of=benchmark_as_of,
    )

    assert before_growth == after_growth
    assert before_growth.report_fingerprint == after_growth.report_fingerprint


def test_temperature_scaling_rejects_non_neural_registry() -> None:
    registry = _registry().model_copy(update={"model_family": "LIGHTGBM"})
    observations = _observations(registry)

    with pytest.raises(ValueError, match="restricted to the PyTorch neural model"):
        benchmark_neural_temperature_scaling(
            source_registry=registry,
            observations=observations,
            benchmark_as_of=observations[-1].target_observed_at,
        )


def test_temperature_scaling_rejects_pre_registration_observations() -> None:
    registry = _registry()
    decision_at = registry.evaluation_as_of
    pre_registration = build_calibration_observation(
        symbol="RELIANCE",
        decision_at=decision_at,
        target_observed_at=decision_at + timedelta(seconds=30),
        source_registry_fingerprint=registry.registry_fingerprint,
        model_prediction_fingerprint=_hash(9000),
        raw_probability=0.80,
        actual_positive=True,
    )
    valid = _observations(registry)

    with pytest.raises(ValueError, match="after model registration"):
        benchmark_neural_temperature_scaling(
            source_registry=registry,
            observations=[pre_registration, *valid],
            benchmark_as_of=valid[-1].target_observed_at,
        )
