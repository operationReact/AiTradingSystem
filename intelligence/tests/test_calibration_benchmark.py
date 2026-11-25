from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.calibration_artifact import build_calibration_observation
from aitrading_intelligence.calibration_benchmark import (
    CalibrationBenchmarkConfig,
    benchmark_platt_and_isotonic,
)
from aitrading_intelligence.model_registry import ModelLabRegistryEntry


def _hash(value: int) -> str:
    return f"{value:064x}"


def _registry() -> ModelLabRegistryEntry:
    evaluation_as_of = datetime(2026, 1, 5, 10, 0, tzinfo=UTC)
    return ModelLabRegistryEntry(
        model_family="LIGHTGBM",
        model_version="lightgbm-binary.v1",
        artifact_fingerprint=_hash(1),
        feature_schema_fingerprint=_hash(2),
        training_dataset_fingerprint=_hash(3),
        target_spec_fingerprint=_hash(4),
        training_config_fingerprint=_hash(5),
        code_sha="a" * 40,
        training_as_of=evaluation_as_of - timedelta(days=1),
        evaluation_as_of=evaluation_as_of,
        evaluation_report_fingerprint=_hash(6),
        evaluation_metrics_fingerprint=_hash(7),
        registered_at=evaluation_as_of + timedelta(minutes=1),
        registry_fingerprint=_hash(8),
    )


def _observations(
    registry: ModelLabRegistryEntry,
    *,
    count: int = 80,
    perfectly_calibrated: bool = False,
    target_delay_minutes: int = 15,
):
    start = registry.registered_at + timedelta(minutes=10)
    rows = []
    for index in range(count):
        positive = index % 2 == 0
        if perfectly_calibrated:
            probability = 1.0 if positive else 0.0
        else:
            probability = 0.65 if positive else 0.55
        decision_at = start + timedelta(minutes=10 * index)
        rows.append(
            build_calibration_observation(
                symbol="RELIANCE",
                decision_at=decision_at,
                target_observed_at=decision_at
                + timedelta(minutes=target_delay_minutes),
                source_registry_fingerprint=registry.registry_fingerprint,
                model_prediction_fingerprint=_hash(100 + index),
                raw_probability=probability,
                actual_positive=positive,
            )
        )
    return rows


def _config() -> CalibrationBenchmarkConfig:
    return CalibrationBenchmarkConfig(
        fit_fraction=0.7,
        minimum_fit_rows=30,
        minimum_evaluation_rows=20,
        calibration_bins=5,
    )


def test_benchmark_uses_purged_holdout_and_selects_improving_calibrator() -> None:
    registry = _registry()
    observations = _observations(registry)
    benchmark_as_of = max(row.target_observed_at for row in observations)

    report = benchmark_platt_and_isotonic(
        source_registry=registry,
        observations=observations,
        benchmark_as_of=benchmark_as_of,
        config=_config(),
    )

    assert report.selected_method == "ISOTONIC"
    assert report.selected_artifact_fingerprint == report.isotonic_artifact_fingerprint
    assert report.purged_observation_fingerprints
    assert not (
        set(report.fit_observation_fingerprints)
        & set(report.evaluation_observation_fingerprints)
    )
    candidates = {candidate.method: candidate for candidate in report.candidates}
    assert candidates["ISOTONIC"].metrics.brier_score < candidates["RAW"].metrics.brier_score
    assert candidates["ISOTONIC"].metrics.log_loss < candidates["RAW"].metrics.log_loss
    assert candidates["ISOTONIC"].eligible_for_selection is True
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_benchmark_retains_raw_when_calibrator_does_not_improve_reference() -> None:
    registry = _registry()
    observations = _observations(
        registry,
        perfectly_calibrated=True,
        target_delay_minutes=5,
    )
    benchmark_as_of = max(row.target_observed_at for row in observations)

    report = benchmark_platt_and_isotonic(
        source_registry=registry,
        observations=observations,
        benchmark_as_of=benchmark_as_of,
        config=_config(),
    )

    assert report.selected_method == "RAW"
    assert report.selected_artifact_fingerprint is None
    assert report.selection_reason_codes == ["CALIBRATOR_TIED_RAW_REFERENCE"]


def test_future_closed_observation_cannot_rewrite_historical_benchmark() -> None:
    registry = _registry()
    observations = _observations(registry)
    benchmark_as_of = max(row.target_observed_at for row in observations)
    first = benchmark_platt_and_isotonic(
        source_registry=registry,
        observations=observations,
        benchmark_as_of=benchmark_as_of,
        config=_config(),
    )

    future_decision = benchmark_as_of + timedelta(minutes=10)
    future = build_calibration_observation(
        symbol="RELIANCE",
        decision_at=future_decision,
        target_observed_at=future_decision + timedelta(minutes=15),
        source_registry_fingerprint=registry.registry_fingerprint,
        model_prediction_fingerprint=_hash(999),
        raw_probability=0.99,
        actual_positive=False,
    )
    repeated = benchmark_platt_and_isotonic(
        source_registry=registry,
        observations=[*observations, future],
        benchmark_as_of=benchmark_as_of,
        config=_config(),
    )

    assert repeated == first
    assert repeated.report_fingerprint == first.report_fingerprint


def test_benchmark_is_deterministic_and_lineage_sensitive() -> None:
    registry = _registry()
    observations = _observations(registry)
    benchmark_as_of = max(row.target_observed_at for row in observations)
    kwargs = {
        "source_registry": registry,
        "observations": observations,
        "benchmark_as_of": benchmark_as_of,
        "config": _config(),
    }

    first = benchmark_platt_and_isotonic(**kwargs)
    repeated = benchmark_platt_and_isotonic(**kwargs)
    assert repeated == first

    wrong_registry = registry.model_copy(update={"registry_fingerprint": _hash(77)})
    with pytest.raises(ValueError, match="must match source registry"):
        benchmark_platt_and_isotonic(
            source_registry=wrong_registry,
            observations=observations,
            benchmark_as_of=benchmark_as_of,
            config=_config(),
        )
