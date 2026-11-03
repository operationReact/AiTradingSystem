from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.calibration_freshness import (
    CalibrationFreshnessDomainDecision,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion_quality_multipliers import (
    FusionQualityMultiplierConfig,
    build_fusion_quality_multiplier,
    build_shadow_performance_observation,
    evaluate_shadow_performance,
)

NOW = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)
HASH_A = "a" * 64
HASH_B = "b" * 64
HASH_C = "c" * 64


def _calibration(
    *,
    action: str = "USE_CALIBRATED",
    age_seconds: int = 3_600,
    decision_at: datetime = NOW,
) -> CalibrationFreshnessDomainDecision:
    payload = {
        "contract_version": "calibration-usability.v1",
        "decision_at": decision_at.isoformat(),
        "raw_probability": 0.64,
        "calibration_artifact_fingerprint": HASH_A,
        "domain_profile_fingerprint": HASH_B,
        "config_fingerprint": HASH_C,
        "calibration_age_seconds": age_seconds,
        "observation_count": 120,
        "positive_count": 55,
        "negative_count": 65,
        "central_domain_lower": 0.10,
        "central_domain_upper": 0.90,
        "hard_domain_lower": 0.0,
        "hard_domain_upper": 1.0,
        "action": action,
        "calibration_applied": action == "USE_CALIBRATED",
        "reason_codes": [f"FIXTURE_{action}"],
        "required_next_gate": "V3_05_UNCERTAINTY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibrationFreshnessDomainDecision(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )


def _observations(
    *,
    count: int,
    net_return: float,
    family: str = "QUANT",
    future_count: int = 0,
):
    rows = []
    for index in range(count):
        target_at = NOW - timedelta(hours=index + 1)
        rows.append(
            build_shadow_performance_observation(
                family=family,
                decision_as_of=target_at - timedelta(hours=1),
                target_observed_at=target_at,
                net_return=net_return,
                source_fingerprint=f"{index + 1:064x}",
            )
        )
    for index in range(future_count):
        target_at = NOW + timedelta(hours=index + 1)
        rows.append(
            build_shadow_performance_observation(
                family=family,
                decision_as_of=NOW - timedelta(hours=1),
                target_observed_at=target_at,
                net_return=0.99,
                source_fingerprint=f"{10_000 + index:064x}",
            )
        )
    return rows


def test_future_shadow_outcomes_are_excluded_from_recent_performance() -> None:
    config = FusionQualityMultiplierConfig(minimum_resolved_shadow_observations=2)
    evidence = evaluate_shadow_performance(
        _observations(count=2, net_return=0.01, future_count=2),
        family="QUANT",
        evaluation_as_of=NOW,
        config=config,
    )

    assert evidence.state == "SUFFICIENT"
    assert evidence.resolved_observation_count == 2
    assert evidence.future_observation_count == 2
    assert evidence.net_expectancy_bps == pytest.approx(100.0)
    assert "FUTURE_SHADOW_OUTCOMES_EXCLUDED" in evidence.reason_codes
    assert evidence.source_data_cutoff is not None
    assert evidence.source_data_cutoff <= NOW


def test_insufficient_shadow_evidence_stays_neutral() -> None:
    config = FusionQualityMultiplierConfig(minimum_resolved_shadow_observations=5)
    evidence = evaluate_shadow_performance(
        _observations(count=2, net_return=-0.02),
        family="QUANT",
        evaluation_as_of=NOW,
        config=config,
    )
    multiplier = build_fusion_quality_multiplier(
        family="QUANT",
        evaluated_at=NOW,
        shadow_performance=evidence,
        config=config,
    )

    assert evidence.state == "INSUFFICIENT"
    assert multiplier.performance_multiplier == 1.0
    assert "SHADOW_PERFORMANCE_INSUFFICIENT_NEUTRAL" in multiplier.reason_codes
    assert multiplier.can_authorize_capital is False
    assert multiplier.promotion_allowed is False


def test_sufficient_shadow_expectancy_is_bounded_and_reconstructable() -> None:
    config = FusionQualityMultiplierConfig(
        minimum_resolved_shadow_observations=2,
        performance_scale_bps=500.0,
        maximum_performance_adjustment=0.20,
    )
    evidence = evaluate_shadow_performance(
        _observations(count=2, net_return=0.005),
        family="QUANT",
        evaluation_as_of=NOW,
        config=config,
    )
    multiplier = build_fusion_quality_multiplier(
        family="QUANT",
        evaluated_at=NOW,
        shadow_performance=evidence,
        config=config,
    )

    assert evidence.net_expectancy_bps == pytest.approx(50.0)
    assert multiplier.performance_multiplier == pytest.approx(1.10)
    assert multiplier.calibration_quality_multiplier == 1.0
    assert multiplier.freshness_multiplier == 1.0
    assert multiplier.combined_multiplier == pytest.approx(1.10)
    assert (
        multiplier.shadow_performance_evidence_fingerprint
        == evidence.evidence_fingerprint
    )


def test_calibration_quality_and_freshness_are_separate_components() -> None:
    config = FusionQualityMultiplierConfig(
        fresh_calibration_seconds=100,
        maximum_calibration_age_seconds=1_100,
        downgraded_calibration_multiplier=0.90,
        stale_calibration_floor=0.75,
    )
    calibration = _calibration(action="DOWNGRADE_TO_RAW", age_seconds=600)
    multiplier = build_fusion_quality_multiplier(
        family="QUANT",
        evaluated_at=NOW,
        calibration=calibration,
        config=config,
    )

    assert multiplier.calibration_quality_multiplier == pytest.approx(0.90)
    assert multiplier.freshness_multiplier == pytest.approx(0.875)
    assert multiplier.performance_multiplier == 1.0
    assert multiplier.combined_multiplier == pytest.approx(0.7875)
    assert "CALIBRATION_DOWNGRADED" in multiplier.reason_codes
    assert "CALIBRATION_FRESHNESS_PENALTY_APPLIED" in multiplier.reason_codes


def test_calibration_abstain_zeroes_quality_without_authority() -> None:
    calibration = _calibration(action="ABSTAIN", age_seconds=100)
    multiplier = build_fusion_quality_multiplier(
        family="QUANT",
        evaluated_at=NOW,
        calibration=calibration,
    )

    assert multiplier.calibration_quality_multiplier == 0.0
    assert multiplier.combined_multiplier == 0.0
    assert multiplier.status == "RESEARCH_QUALITY_EVIDENCE_ONLY"
    assert multiplier.can_authorize_capital is False


def test_missing_quality_inputs_are_neutral_not_guessed() -> None:
    multiplier = build_fusion_quality_multiplier(
        family="DETERMINISTIC",
        evaluated_at=NOW,
    )

    assert multiplier.calibration_quality_multiplier == 1.0
    assert multiplier.freshness_multiplier == 1.0
    assert multiplier.performance_multiplier == 1.0
    assert multiplier.combined_multiplier == 1.0
    assert "CALIBRATION_EVIDENCE_NOT_SUPPLIED_NEUTRAL" in multiplier.reason_codes
    assert "SHADOW_PERFORMANCE_NOT_SUPPLIED_NEUTRAL" in multiplier.reason_codes


def test_shadow_evidence_family_mismatch_fails_closed() -> None:
    config = FusionQualityMultiplierConfig(minimum_resolved_shadow_observations=2)
    evidence = evaluate_shadow_performance(
        _observations(count=2, net_return=0.01, family="QUANT"),
        family="QUANT",
        evaluation_as_of=NOW,
        config=config,
    )

    with pytest.raises(ValueError, match="family must match"):
        build_fusion_quality_multiplier(
            family="GEMINI",
            evaluated_at=NOW,
            shadow_performance=evidence,
            config=config,
        )


def test_tampered_shadow_performance_fingerprint_fails_closed() -> None:
    config = FusionQualityMultiplierConfig(minimum_resolved_shadow_observations=2)
    evidence = evaluate_shadow_performance(
        _observations(count=2, net_return=0.01),
        family="QUANT",
        evaluation_as_of=NOW,
        config=config,
    ).model_copy(update={"evidence_fingerprint": "0" * 64})

    with pytest.raises(
        ValueError,
        match="shadow performance evidence fingerprint mismatch",
    ):
        build_fusion_quality_multiplier(
            family="QUANT",
            evaluated_at=NOW,
            shadow_performance=evidence,
            config=config,
        )


def test_tampered_calibration_fingerprint_fails_closed() -> None:
    calibration = _calibration().model_copy(
        update={"decision_fingerprint": "0" * 64}
    )

    with pytest.raises(ValueError, match="calibration decision fingerprint mismatch"):
        build_fusion_quality_multiplier(
            family="QUANT",
            evaluated_at=NOW,
            calibration=calibration,
        )


def test_future_calibration_or_performance_evidence_is_rejected() -> None:
    calibration = _calibration(decision_at=NOW + timedelta(seconds=1))
    with pytest.raises(ValueError, match="calibration decision cannot postdate"):
        build_fusion_quality_multiplier(
            family="QUANT",
            evaluated_at=NOW,
            calibration=calibration,
        )

    config = FusionQualityMultiplierConfig(minimum_resolved_shadow_observations=2)
    future_evaluation_at = NOW + timedelta(minutes=1)
    evidence = evaluate_shadow_performance(
        _observations(count=2, net_return=0.01),
        family="QUANT",
        evaluation_as_of=future_evaluation_at,
        config=config,
    )
    with pytest.raises(ValueError, match="cannot postdate multiplier evaluation"):
        build_fusion_quality_multiplier(
            family="QUANT",
            evaluated_at=NOW,
            shadow_performance=evidence,
            config=config,
        )


def test_invalid_multiplier_config_fails_closed() -> None:
    with pytest.raises(ValidationError, match="cannot exceed"):
        FusionQualityMultiplierConfig(
            fresh_calibration_seconds=1_000,
            maximum_calibration_age_seconds=100,
        )
