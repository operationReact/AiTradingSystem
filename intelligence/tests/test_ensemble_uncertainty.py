from datetime import UTC, datetime

import pytest

from aitrading_intelligence.calibrated_prediction import CalibratedPredictionV1
from aitrading_intelligence.ensemble_uncertainty import evaluate_ensemble_uncertainty
from aitrading_intelligence.fingerprints import canonical_fingerprint

AS_OF = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)


def _hash(value: int) -> str:
    return f"{value:064x}"


def _prediction(
    family: str,
    probability: float,
    *,
    action: str = "USE_CALIBRATED",
    stale: bool = False,
    out_of_domain: bool = False,
    insufficient: bool = False,
    as_of: datetime = AS_OF,
    fingerprint_seed: int = 1,
) -> CalibratedPredictionV1:
    raw_probability = probability
    calibrated_probability = probability
    if action == "USE_CALIBRATED":
        effective_probability = calibrated_probability
        calibration_applied = True
        reasons = ["CALIBRATION_FRESH_IN_DOMAIN_AND_SUFFICIENT"]
    elif action == "DOWNGRADE_TO_RAW":
        effective_probability = raw_probability
        calibration_applied = False
        reasons = ["CALIBRATION_STALE"]
    else:
        effective_probability = None
        calibration_applied = False
        reasons = ["CALIBRATION_SEVERE_OUT_OF_DOMAIN"]

    payload = {
        "contract_version": "calibrated-prediction.v1",
        "symbol": "RELIANCE",
        "as_of": as_of.isoformat(),
        "source_model_prediction_fingerprint": _hash(100 + fingerprint_seed),
        "source_registry_fingerprint": _hash(200 + fingerprint_seed),
        "model_family": family,
        "model_version": f"{family.lower()}.v1",
        "calibration_method": "PLATT",
        "calibration_artifact_fingerprint": _hash(300 + fingerprint_seed),
        "calibration_domain_profile_fingerprint": _hash(400 + fingerprint_seed),
        "calibration_policy_fingerprint": _hash(500 + fingerprint_seed),
        "calibration_segment_type": "GLOBAL",
        "calibration_segment_key": "GLOBAL",
        "raw_probability": raw_probability,
        "calibrated_probability": calibrated_probability,
        "effective_probability": effective_probability,
        "calibration_sample_size": 100,
        "calibration_age_seconds": 3600,
        "central_domain_lower": 0.10,
        "central_domain_upper": 0.90,
        "hard_domain_lower": 0.05,
        "hard_domain_upper": 0.95,
        "is_stale": stale,
        "is_out_of_domain": out_of_domain,
        "is_insufficient_calibration": insufficient,
        "calibration_action": action,
        "calibration_applied": calibration_applied,
        "reason_codes": reasons,
        "uncertainty_score": None,
        "uncertainty_status": "NOT_ATTACHED",
        "required_next_gate": "V3_05_UNCERTAINTY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibratedPredictionV1(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def test_aligned_experts_produce_low_complete_uncertainty_order_invariant() -> None:
    predictions = [
        _prediction("CATBOOST", 0.90, fingerprint_seed=1),
        _prediction("LIGHTGBM", 0.88, fingerprint_seed=2),
        _prediction("PYTORCH", 0.86, fingerprint_seed=3),
    ]

    first = evaluate_ensemble_uncertainty(predictions)
    second = evaluate_ensemble_uncertainty(list(reversed(predictions)))

    assert first == second
    assert first.uncertainty_level == "LOW"
    assert first.evidence_quality == "COMPLETE"
    assert first.directional_disagreement_count == 0
    assert first.directional_entropy == 0.0
    assert first.probability_dispersion == pytest.approx(0.04)
    assert first.reason_codes == ["ENSEMBLE_COMPLETE_LOW_DISAGREEMENT"]
    assert first.required_next_gate == "V3_05_SELECTIVE_POLICY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_directional_split_produces_high_uncertainty() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.90, fingerprint_seed=11),
            _prediction("LIGHTGBM", 0.10, fingerprint_seed=12),
            _prediction("PYTORCH", 0.55, fingerprint_seed=13),
        ]
    )

    assert evidence.uncertainty_level == "HIGH"
    assert evidence.directional_disagreement_count == 1
    assert evidence.directional_entropy > 0.9
    assert evidence.normalized_disagreement_score > 0.9
    assert "DIRECTIONAL_DISAGREEMENT_PRESENT" in evidence.reason_codes
    assert "HIGH_NORMALIZED_DISAGREEMENT" in evidence.reason_codes


def test_missing_expert_is_explicit_and_degrades_evidence_quality() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.88, fingerprint_seed=21),
            _prediction("LIGHTGBM", 0.86, fingerprint_seed=22),
        ]
    )

    assert evidence.evidence_quality == "DEGRADED"
    assert evidence.has_missing_expert is True
    assert evidence.missing_model_families == ["PYTORCH"]
    assert "MISSING_EXPERT_PYTORCH" in evidence.reason_codes


def test_stale_ood_and_insufficient_experts_are_visible_without_increasing_authority() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction(
                "CATBOOST",
                0.85,
                stale=True,
                action="DOWNGRADE_TO_RAW",
                fingerprint_seed=31,
            ),
            _prediction(
                "LIGHTGBM",
                0.82,
                out_of_domain=True,
                action="DOWNGRADE_TO_RAW",
                fingerprint_seed=32,
            ),
            _prediction(
                "PYTORCH",
                0.80,
                insufficient=True,
                action="DOWNGRADE_TO_RAW",
                fingerprint_seed=33,
            ),
        ]
    )

    assert evidence.evidence_quality == "DEGRADED"
    assert evidence.has_stale_expert is True
    assert evidence.has_out_of_domain_expert is True
    assert evidence.has_insufficient_calibration_expert is True
    assert evidence.promotion_allowed is False
    assert evidence.can_authorize_capital is False


def test_abstaining_expert_is_excluded_from_effective_probability_set() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.90, fingerprint_seed=41),
            _prediction("LIGHTGBM", 0.88, fingerprint_seed=42),
            _prediction(
                "PYTORCH",
                0.99,
                action="ABSTAIN",
                out_of_domain=True,
                fingerprint_seed=43,
            ),
        ]
    )

    assert evidence.has_abstaining_expert is True
    assert evidence.abstaining_model_families == ["PYTORCH"]
    assert evidence.contributing_model_families == ["CATBOOST", "LIGHTGBM"]
    assert "PYTORCH" not in evidence.effective_probabilities
    assert "EXPERT_ABSTAINED_PYTORCH" in evidence.reason_codes


def test_duplicate_family_and_mismatched_timestamp_fail_closed() -> None:
    duplicate = [
        _prediction("CATBOOST", 0.80, fingerprint_seed=51),
        _prediction("CATBOOST", 0.82, fingerprint_seed=52),
    ]
    with pytest.raises(ValueError, match="duplicate model families"):
        evaluate_ensemble_uncertainty(duplicate)

    mismatched_time = [
        _prediction("CATBOOST", 0.80, fingerprint_seed=53),
        _prediction(
            "LIGHTGBM",
            0.82,
            as_of=datetime(2026, 1, 2, 10, 1, tzinfo=UTC),
            fingerprint_seed=54,
        ),
    ]
    with pytest.raises(ValueError, match="one symbol and as_of"):
        evaluate_ensemble_uncertainty(mismatched_time)


def test_tampered_calibrated_prediction_fingerprint_fails_closed() -> None:
    prediction = _prediction("CATBOOST", 0.80, fingerprint_seed=61).model_copy(
        update={"evidence_fingerprint": _hash(9999)}
    )

    with pytest.raises(ValueError, match="evidence fingerprint mismatch"):
        evaluate_ensemble_uncertainty([prediction])
