import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from pydantic import ValidationError

from aitrading_intelligence.calibration_artifact import (
    IsotonicParameters,
    PlattParameters,
    TemperatureParameters,
    build_calibration_observation,
    create_calibration_artifact,
    load_calibration_artifact,
    write_calibration_artifact,
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


def _observations(registry: ModelLabRegistryEntry, count: int = 24):
    start = registry.registered_at + timedelta(minutes=1)
    rows = []
    for index in range(count):
        decision_at = start + timedelta(minutes=5 * index)
        rows.append(
            build_calibration_observation(
                symbol="RELIANCE",
                decision_at=decision_at,
                target_observed_at=decision_at + timedelta(minutes=5),
                source_registry_fingerprint=registry.registry_fingerprint,
                model_prediction_fingerprint=_hash(100 + index),
                raw_probability=0.25 + (0.5 * ((index % 6) / 5)),
                actual_positive=index % 2 == 0,
            )
        )
    return rows


def test_calibration_artifact_is_order_invariant_and_research_only() -> None:
    registry = _registry()
    observations = _observations(registry)
    fit_as_of = max(row.target_observed_at for row in observations)
    parameters = PlattParameters(slope=1.0, intercept=0.0)

    first = create_calibration_artifact(
        source_registry=registry,
        observations=observations,
        fit_as_of=fit_as_of,
        parameters=parameters,
        calibration_bins=6,
    )
    reversed_input = create_calibration_artifact(
        source_registry=registry,
        observations=list(reversed(observations)),
        fit_as_of=fit_as_of,
        parameters=parameters,
        calibration_bins=6,
    )

    assert first == reversed_input
    assert first.artifact_fingerprint == reversed_input.artifact_fingerprint
    assert first.calibration_dataset_fingerprint == reversed_input.calibration_dataset_fingerprint
    assert first.raw_probability_fingerprint == first.calibrated_probability_fingerprint
    assert first.raw_metrics.brier_score == pytest.approx(first.calibrated_metrics.brier_score)
    assert first.raw_metrics.log_loss == pytest.approx(first.calibrated_metrics.log_loss)
    assert first.status == "CALIBRATION_RESEARCH_ONLY"
    assert first.required_next_gate == "V3_05_CALIBRATION_BENCHMARK"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_calibration_fails_closed_on_future_label_or_registry_mismatch() -> None:
    registry = _registry()
    observations = _observations(registry)
    fit_as_of = max(row.target_observed_at for row in observations)

    future = observations[-1].model_copy(
        update={"target_observed_at": fit_as_of + timedelta(seconds=1)}
    )
    with pytest.raises(ValueError, match="targets observed after fit_as_of"):
        create_calibration_artifact(
            source_registry=registry,
            observations=[*observations[:-1], future],
            fit_as_of=fit_as_of,
            parameters=PlattParameters(slope=1.0, intercept=0.0),
        )

    wrong_registry = observations[-1].model_copy(
        update={"source_registry_fingerprint": _hash(999)}
    )
    with pytest.raises(ValueError, match="exact source registry entry"):
        create_calibration_artifact(
            source_registry=registry,
            observations=[*observations[:-1], wrong_registry],
            fit_as_of=fit_as_of,
            parameters=PlattParameters(slope=1.0, intercept=0.0),
        )


def test_calibration_parameter_contracts_reject_invalid_shapes() -> None:
    with pytest.raises(ValidationError, match="strictly increasing"):
        IsotonicParameters(
            x_thresholds=[0.1, 0.1, 0.9],
            y_thresholds=[0.2, 0.5, 0.8],
        )
    with pytest.raises(ValidationError, match="nondecreasing"):
        IsotonicParameters(
            x_thresholds=[0.1, 0.5, 0.9],
            y_thresholds=[0.2, 0.8, 0.7],
        )
    with pytest.raises(ValidationError):
        TemperatureParameters(temperature=0.0)


def test_calibration_artifact_storage_is_immutable_and_idempotent(tmp_path: Path) -> None:
    registry = _registry()
    observations = _observations(registry)
    fit_as_of = max(row.target_observed_at for row in observations)
    artifact = create_calibration_artifact(
        source_registry=registry,
        observations=observations,
        fit_as_of=fit_as_of,
        parameters=PlattParameters(slope=1.0, intercept=0.0),
    )
    path = tmp_path / "calibration.json"

    assert write_calibration_artifact(path, artifact) == path
    assert write_calibration_artifact(path, artifact) == path
    assert load_calibration_artifact(path) == artifact

    changed = create_calibration_artifact(
        source_registry=registry,
        observations=observations,
        fit_as_of=fit_as_of,
        parameters=TemperatureParameters(temperature=1.5),
    )
    with pytest.raises(FileExistsError, match="different evidence"):
        write_calibration_artifact(path, changed)


def test_calibration_artifact_load_rejects_tampered_fingerprint(tmp_path: Path) -> None:
    registry = _registry()
    observations = _observations(registry)
    fit_as_of = max(row.target_observed_at for row in observations)
    artifact = create_calibration_artifact(
        source_registry=registry,
        observations=observations,
        fit_as_of=fit_as_of,
        parameters=IsotonicParameters(
            x_thresholds=[0.0, 0.5, 1.0],
            y_thresholds=[0.1, 0.5, 0.9],
        ),
    )
    payload = artifact.model_dump(mode="json")
    payload["artifact_fingerprint"] = _hash(9999)
    path = tmp_path / "tampered.json"
    path.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValidationError, match="artifact fingerprint mismatch"):
        load_calibration_artifact(path)
