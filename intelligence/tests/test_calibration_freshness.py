from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.calibration_artifact import (
    PlattParameters,
    build_calibration_observation,
    create_calibration_artifact,
)
from aitrading_intelligence.calibration_freshness import (
    CalibrationFreshnessDomainConfig,
    build_calibration_domain_profile,
    evaluate_calibration_usability,
)
from aitrading_intelligence.model_registry import ModelLabRegistryEntry


def _hash(value: int) -> str:
    return f"{value:064x}"


def _registry() -> ModelLabRegistryEntry:
    evaluation_as_of = datetime(2026, 1, 1, 10, 0, tzinfo=UTC)
    return ModelLabRegistryEntry(
        model_family="LIGHTGBM",
        model_version="lightgbm-binary.v1",
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
    count: int = 60,
    start_offset_minutes: int = 10,
) -> list:
    start = registry.registered_at + timedelta(minutes=start_offset_minutes)
    rows = []
    for index in range(count):
        decision_at = start + timedelta(minutes=5 * index)
        probability = 0.20 + (0.60 * index / max(1, count - 1))
        rows.append(
            build_calibration_observation(
                symbol="RELIANCE",
                decision_at=decision_at,
                target_observed_at=decision_at + timedelta(minutes=2),
                source_registry_fingerprint=registry.registry_fingerprint,
                model_prediction_fingerprint=_hash(1000 + index),
                raw_probability=probability,
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
    return registry, artifact, observations


def test_fresh_in_domain_calibration_is_usable() -> None:
    _, artifact, observations = _artifact_and_observations()
    profile = build_calibration_domain_profile(
        artifact=artifact,
        observations=observations,
    )
    decision = evaluate_calibration_usability(
        artifact=artifact,
        profile=profile,
        raw_probability=0.50,
        decision_at=artifact.fit_as_of + timedelta(hours=1),
    )

    assert decision.action == "USE_CALIBRATED"
    assert decision.calibration_applied is True
    assert decision.reason_codes == ["CALIBRATION_FRESH_IN_DOMAIN_AND_SUFFICIENT"]
    assert decision.promotion_allowed is False
    assert decision.can_authorize_capital is False


def test_stale_calibration_downgrades_to_raw() -> None:
    _, artifact, observations = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)

    decision = evaluate_calibration_usability(
        artifact=artifact,
        profile=profile,
        raw_probability=0.50,
        decision_at=artifact.fit_as_of + timedelta(days=8),
    )

    assert decision.action == "DOWNGRADE_TO_RAW"
    assert decision.calibration_applied is False
    assert "CALIBRATION_STALE" in decision.reason_codes


def test_mild_out_of_domain_downgrades_but_severe_out_of_domain_abstains() -> None:
    _, artifact, observations = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    decision_at = artifact.fit_as_of + timedelta(hours=1)

    mild = evaluate_calibration_usability(
        artifact=artifact,
        profile=profile,
        raw_probability=0.82,
        decision_at=decision_at,
    )
    severe = evaluate_calibration_usability(
        artifact=artifact,
        profile=profile,
        raw_probability=0.95,
        decision_at=decision_at,
    )

    assert mild.action == "DOWNGRADE_TO_RAW"
    assert "CALIBRATION_OUTSIDE_CENTRAL_DOMAIN" in mild.reason_codes
    assert severe.action == "ABSTAIN"
    assert severe.reason_codes == ["CALIBRATION_SEVERE_OUT_OF_DOMAIN"]


def test_insufficient_sample_policy_downgrades_to_raw() -> None:
    _, artifact, observations = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    config = CalibrationFreshnessDomainConfig(minimum_observations=100)

    decision = evaluate_calibration_usability(
        artifact=artifact,
        profile=profile,
        raw_probability=0.50,
        decision_at=artifact.fit_as_of + timedelta(hours=1),
        config=config,
    )

    assert decision.action == "DOWNGRADE_TO_RAW"
    assert "CALIBRATION_SAMPLE_INSUFFICIENT" in decision.reason_codes


def test_profile_is_order_invariant_and_ignores_future_archive_growth() -> None:
    registry, artifact, observations = _artifact_and_observations()
    future = _observations(
        registry,
        count=10,
        start_offset_minutes=1000,
    )

    baseline = build_calibration_domain_profile(
        artifact=artifact,
        observations=observations,
    )
    after_growth = build_calibration_domain_profile(
        artifact=artifact,
        observations=[*reversed(observations), *future],
    )

    assert baseline == after_growth
    assert baseline.profile_fingerprint == after_growth.profile_fingerprint


def test_missing_frozen_observation_and_lineage_mismatch_fail_closed() -> None:
    _, artifact, observations = _artifact_and_observations()
    with pytest.raises(ValueError, match="incomplete"):
        build_calibration_domain_profile(
            artifact=artifact,
            observations=observations[:-1],
        )

    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)
    mismatched = profile.model_copy(update={"model_artifact_fingerprint": _hash(9999)})
    with pytest.raises(ValueError, match="lineage"):
        evaluate_calibration_usability(
            artifact=artifact,
            profile=mismatched,
            raw_probability=0.50,
            decision_at=artifact.fit_as_of + timedelta(hours=1),
        )


def test_usability_rejects_future_relative_to_artifact_timeline() -> None:
    _, artifact, observations = _artifact_and_observations()
    profile = build_calibration_domain_profile(artifact=artifact, observations=observations)

    with pytest.raises(ValueError, match="cannot predate"):
        evaluate_calibration_usability(
            artifact=artifact,
            profile=profile,
            raw_probability=0.50,
            decision_at=artifact.fit_as_of - timedelta(seconds=1),
        )
