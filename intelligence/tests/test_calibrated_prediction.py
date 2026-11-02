from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.calibrated_prediction import (
    CalibrationDomainConfig,
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


def _observations(registry: ModelLabRegistryEntry, count: int = 40) -> list:
    start = registry.registered_at + timedelta(minutes=10)
    rows = []
    for index in range(count):
        raw_probability = 0.2 + (0.1 * (index % 7))
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
    fit_as_of = max(row.target_observed_at for row in observations)
    artifact = create_calibration_artifact(
        source_registry=registry,
        observations=observations,
        fit_as_of=fit_as_of,
        parameters=PlattParameters(slope=1.0, intercept=0.0),
        calibration_bins=5,
    )
    return registry, observations, artifact


def _prediction(artifact, *, probability: float = 0.6, age_days: int = 1):
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


def test_domain_profile_is_deterministic_and_requires_exact_fit_evidence() -> None:
    _, observations, artifact = _artifact_and_observations()

    first = build_calibration_domain_profile(artifact, observations)
    second = build_calibration_domain_profile(artifact, list(reversed(observations)))

    assert first == second
    assert first.segment_type == "GLOBAL"
    assert first.sample_count == 40
    assert first.raw_probability_min == pytest.approx(0.2)
    assert first.raw_probability_max == pytest.approx(0.8)
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False

    with pytest.raises(ValueError, match="exact calibration artifact observations"):
        build_calibration_domain_profile(artifact, observations[:-1])


def test_fresh_in_domain_calibration_is_usable_without_capital_authority() -> None:
    _, observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact, observations)
    prediction = _prediction(artifact, probability=0.6)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
        config=CalibrationDomainConfig(minimum_calibration_rows=30),
    )

    assert result.contract_version == "calibrated-prediction.v1"
    assert result.calibration_usable is True
    assert result.eligibility == "CALIBRATION_USABLE"
    assert result.reason_codes == ["CALIBRATION_FRESH_IN_DOMAIN"]
    assert result.calibrated_probability == pytest.approx(prediction.raw_probability)
    assert result.calibration_sample_size == 40
    assert result.is_stale is False
    assert result.is_out_of_domain is False
    assert result.is_insufficient_calibration is False
    assert result.uncertainty_score is None
    assert result.uncertainty_status == "NOT_ATTACHED"
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_stale_calibrator_downgrades_or_abstains() -> None:
    _, observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact, observations)
    prediction = _prediction(artifact, probability=0.6, age_days=8)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
        config=CalibrationDomainConfig(minimum_calibration_rows=30),
    )

    assert result.calibration_usable is False
    assert result.eligibility == "DOWNGRADE_OR_ABSTAIN"
    assert result.is_stale is True
    assert "CALIBRATION_STALE" in result.reason_codes


def test_out_of_domain_probability_downgrades_or_abstains() -> None:
    _, observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact, observations)
    prediction = _prediction(artifact, probability=0.95)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
        config=CalibrationDomainConfig(
            minimum_calibration_rows=30,
            raw_probability_margin=0.01,
        ),
    )

    assert result.calibration_usable is False
    assert result.is_out_of_domain is True
    assert "RAW_PROBABILITY_OUT_OF_CALIBRATION_DOMAIN" in result.reason_codes


def test_insufficient_calibration_sample_downgrades_or_abstains() -> None:
    _, observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact, observations)
    prediction = _prediction(artifact, probability=0.6)

    result = evaluate_calibrated_prediction(
        prediction=prediction,
        artifact=artifact,
        domain_profile=profile,
        config=CalibrationDomainConfig(minimum_calibration_rows=50),
    )

    assert result.calibration_usable is False
    assert result.is_insufficient_calibration is True
    assert "INSUFFICIENT_CALIBRATION_SAMPLE" in result.reason_codes


def test_future_calibrator_cannot_be_applied_to_historical_prediction() -> None:
    _, observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact, observations)
    prediction = _prediction(artifact).model_copy(
        update={
            "as_of": artifact.fit_as_of - timedelta(seconds=1),
            "data_cutoff": artifact.fit_as_of - timedelta(seconds=1),
        }
    )

    with pytest.raises(ValueError, match="future calibration artifact"):
        evaluate_calibrated_prediction(
            prediction=prediction,
            artifact=artifact,
            domain_profile=profile,
        )


def test_calibration_guard_fails_closed_on_model_lineage_mismatch() -> None:
    _, observations, artifact = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact, observations)
    prediction = _prediction(artifact).model_copy(
        update={"model_artifact_fingerprint": _hash(9999)}
    )

    with pytest.raises(ValueError, match="model artifact fingerprint"):
        evaluate_calibrated_prediction(
            prediction=prediction,
            artifact=artifact,
            domain_profile=profile,
        )
