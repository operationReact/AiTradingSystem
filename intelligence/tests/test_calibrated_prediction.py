from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.calibrated_prediction import (
    CalibrationFreshnessDomainConfig,
    build_calibration_domain_profile,
    evaluate_calibrated_prediction,
)
from aitrading_intelligence.calibration_artifact import (
    PlattParameters,
    build_calibration_observation,
    create_calibration_artifact,
)
from aitrading_intelligence.model_prediction_v2 import ModelPredictionV2
from aitrading_intelligence.model_registry import ModelLabRegistryEntry


def _hash(value: int) -> str:
    return f"{value:064x}"


def _registry() -> ModelLabRegistryEntry:
    training_as_of = datetime(2026, 1, 1, 8, 0, tzinfo=UTC)
    evaluation_as_of = datetime(2026, 1, 1, 9, 0, tzinfo=UTC)
    return ModelLabRegistryEntry(
        model_family="LIGHTGBM",
        model_version="lightgbm-binary.v1",
        artifact_fingerprint=_hash(1),
        feature_schema_fingerprint=_hash(2),
        training_dataset_fingerprint=_hash(3),
        target_spec_fingerprint=_hash(4),
        training_config_fingerprint=_hash(5),
        code_sha="a" * 40,
        training_as_of=training_as_of,
        evaluation_as_of=evaluation_as_of,
        evaluation_report_fingerprint=_hash(6),
        evaluation_metrics_fingerprint=_hash(7),
        registered_at=evaluation_as_of + timedelta(minutes=1),
        registry_fingerprint=_hash(8),
    )


def _observations(registry: ModelLabRegistryEntry, count: int = 60) -> list:
    start = registry.registered_at + timedelta(minutes=10)
    rows = []
    for index in range(count):
        raw_probability = 0.20 + (0.60 * index / max(1, count - 1))
        decision_at = start + timedelta(minutes=index)
        rows.append(
            build_calibration_observation(
                symbol="RELIANCE",
                decision_at=decision_at,
                target_observed_at=decision_at + timedelta(seconds=30),
                source_registry_fingerprint=registry.registry_fingerprint,
                model_prediction_fingerprint=_hash(1000 + index),
                raw_probability=raw_probability,
                actual_positive=index % 2 == 0,
            )
        )
    return rows


def _artifact_and_observations():
    registry = _registry()
    observations = _observations(registry)
    artifact = create_calibration_artifact(
        source_registry=registry,
        observations=observations,
        fit_as_of=observations[-1].target_observed_at,
        parameters=PlattParameters(slope=1.0, intercept=0.0),
        calibration_bins=5,
    )
    return observations, artifact


def _prediction(artifact, *, probability: float = 0.50, age_days: int = 1):
    as_of = artifact.fit_as_of + timedelta(days=age_days)
    return ModelPredictionV2(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        model_family="LIGHTGBM",
        model_name="LGBMClassifier",
        model_version=artifact.model_version,
        model_artifact_fingerprint=artifact.model_artifact_fingerprint,
        target_definition_fingerprint=artifact.target_spec_fingerprint,
        raw_probability=probability,
        raw_score=(2.0 * probability) - 1.0,
        raw_confidence=None,
        feature_row_fingerprint=_hash(5000),
        attribution_status="NOT_ATTACHED",
        inference_latency_micros=10,
        inference_fingerprint=_hash(5001),
    )


def test_fresh_in_domain_prediction_uses_calibrated_probability() -> None:
    observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(
        artifact=artifact,
        observations=observations,
    )
    prediction = _prediction(artifact)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
    )

    assert result.contract_version == "calibrated-prediction.v1"
    assert result.calibration_action == "USE_CALIBRATED"
    assert result.calibration_applied is True
    assert result.calibrated_probability == pytest.approx(prediction.raw_probability)
    assert result.effective_probability == pytest.approx(result.calibrated_probability)
    assert result.calibration_sample_size == 60
    assert result.is_stale is False
    assert result.is_out_of_domain is False
    assert result.is_insufficient_calibration is False
    assert result.uncertainty_score is None
    assert result.uncertainty_status == "NOT_ATTACHED"
    assert result.required_next_gate == "V3_05_UNCERTAINTY"
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_stale_calibrator_downgrades_to_raw_probability() -> None:
    observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    prediction = _prediction(artifact, age_days=8)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
    )

    assert result.calibration_action == "DOWNGRADE_TO_RAW"
    assert result.calibration_applied is False
    assert result.effective_probability == pytest.approx(prediction.raw_probability)
    assert result.is_stale is True
    assert "CALIBRATION_STALE" in result.reason_codes


def test_mild_and_severe_out_of_domain_have_distinct_fail_closed_actions() -> None:
    observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)

    mild = evaluate_calibrated_prediction(
        prediction=_prediction(artifact, probability=0.82),
        artifact=artifact,
        domain_profile=profile,
    )
    severe = evaluate_calibrated_prediction(
        prediction=_prediction(artifact, probability=0.95),
        artifact=artifact,
        domain_profile=profile,
    )

    assert mild.calibration_action == "DOWNGRADE_TO_RAW"
    assert mild.is_out_of_domain is True
    assert mild.effective_probability == pytest.approx(mild.raw_probability)
    assert "CALIBRATION_OUTSIDE_CENTRAL_DOMAIN" in mild.reason_codes

    assert severe.calibration_action == "ABSTAIN"
    assert severe.is_out_of_domain is True
    assert severe.effective_probability is None
    assert severe.reason_codes == ["CALIBRATION_SEVERE_OUT_OF_DOMAIN"]


def test_insufficient_calibration_sample_downgrades_to_raw() -> None:
    observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    prediction = _prediction(artifact)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
        config=CalibrationFreshnessDomainConfig(minimum_observations=100),
    )

    assert result.calibration_action == "DOWNGRADE_TO_RAW"
    assert result.is_insufficient_calibration is True
    assert result.effective_probability == pytest.approx(prediction.raw_probability)
    assert "CALIBRATION_SAMPLE_INSUFFICIENT" in result.reason_codes


def test_future_calibrator_cannot_be_applied_to_historical_prediction() -> None:
    observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    prediction = _prediction(artifact).model_copy(
        update={
            "as_of": artifact.fit_as_of - timedelta(seconds=1),
            "data_cutoff": artifact.fit_as_of - timedelta(seconds=1),
        }
    )

    with pytest.raises(ValueError, match="cannot predate artifact fit"):
        evaluate_calibrated_prediction(
            prediction=prediction,
            artifact=artifact,
            domain_profile=profile,
        )


def test_calibrated_prediction_fails_closed_on_model_lineage_mismatch() -> None:
    observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    prediction = _prediction(artifact).model_copy(
        update={"model_artifact_fingerprint": _hash(9999)}
    )

    with pytest.raises(ValueError, match="model artifact fingerprint"):
        evaluate_calibrated_prediction(
            prediction=prediction,
            artifact=artifact,
            domain_profile=profile,
        )
