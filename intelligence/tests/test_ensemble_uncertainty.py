from datetime import UTC, datetime, timedelta

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
    calibration_stale: bool = False,
    out_of_domain: bool = False,
    insufficient: bool = False,
    as_of: datetime = AS_OF,
    symbol: str = "RELIANCE",
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
        "symbol": symbol,
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
        "is_stale": calibration_stale,
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


def _aligned_predictions() -> list[CalibratedPredictionV1]:
    return [
        _prediction("CATBOOST", 0.90, fingerprint_seed=1),
        _prediction("LIGHTGBM", 0.88, fingerprint_seed=2),
        _prediction("PYTORCH", 0.86, fingerprint_seed=3),
    ]


def test_aligned_experts_produce_low_complete_uncertainty_order_invariant() -> None:
    predictions = _aligned_predictions()

    first = evaluate_ensemble_uncertainty(predictions, assessment_as_of=AS_OF)
    second = evaluate_ensemble_uncertainty(
        list(reversed(predictions)),
        assessment_as_of=AS_OF,
    )

    assert first == second
    assert first.uncertainty_level == "LOW"
    assert first.evidence_quality == "COMPLETE"
    assert first.directional_disagreement_count == 0
    assert first.directional_entropy == 0.0
    assert first.probability_dispersion == pytest.approx(0.04)
    assert first.expert_coverage == 1.0
    assert first.reason_codes == ["ENSEMBLE_COMPLETE_LOW_UNCERTAINTY"]
    assert first.required_next_gate == "V3_05_SELECTIVE_POLICY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_directional_split_produces_high_uncertainty() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.90, fingerprint_seed=11),
            _prediction("LIGHTGBM", 0.10, fingerprint_seed=12),
            _prediction("PYTORCH", 0.55, fingerprint_seed=13),
        ],
        assessment_as_of=AS_OF,
    )

    assert evidence.uncertainty_level == "HIGH"
    assert evidence.directional_disagreement_count == 1
    assert evidence.directional_entropy > 0.9
    assert evidence.normalized_disagreement_score > 0.9
    assert "DIRECTIONAL_DISAGREEMENT_PRESENT" in evidence.reason_codes
    assert "HIGH_UNCERTAINTY" in evidence.reason_codes


def test_missing_expert_increases_uncertainty_and_degrades_evidence() -> None:
    complete = evaluate_ensemble_uncertainty(
        _aligned_predictions(),
        assessment_as_of=AS_OF,
    )
    missing = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.90, fingerprint_seed=21),
            _prediction("LIGHTGBM", 0.88, fingerprint_seed=22),
        ],
        assessment_as_of=AS_OF,
    )

    assert missing.evidence_quality == "DEGRADED"
    assert missing.has_missing_expert is True
    assert missing.missing_model_families == ["PYTORCH"]
    assert missing.expert_coverage == pytest.approx(2 / 3)
    assert missing.missingness_penalty == pytest.approx(1 / 3)
    assert missing.uncertainty_score > complete.uncertainty_score
    assert "MISSING_EXPERT_PYTORCH" in missing.reason_codes


def test_source_stale_expert_is_excluded_and_recorded() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction(
                "CATBOOST",
                0.90,
                as_of=AS_OF - timedelta(minutes=30),
                fingerprint_seed=31,
            ),
            _prediction("LIGHTGBM", 0.88, fingerprint_seed=32),
            _prediction("PYTORCH", 0.86, fingerprint_seed=33),
        ],
        assessment_as_of=AS_OF,
    )

    assert evidence.has_stale_expert is True
    assert evidence.stale_model_families == ["CATBOOST"]
    assert evidence.contributing_model_families == ["LIGHTGBM", "PYTORCH"]
    assert "CATBOOST" not in evidence.effective_probabilities
    assert "STALE_EXPERT_CATBOOST" in evidence.reason_codes


def test_calibration_downgrade_remains_usable_but_is_visible() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction(
                "CATBOOST",
                0.85,
                calibration_stale=True,
                action="DOWNGRADE_TO_RAW",
                fingerprint_seed=41,
            ),
            _prediction("LIGHTGBM", 0.82, fingerprint_seed=42),
            _prediction("PYTORCH", 0.80, fingerprint_seed=43),
        ],
        assessment_as_of=AS_OF,
    )

    assert evidence.evidence_quality == "DEGRADED"
    assert evidence.calibration_degraded_families == ["CATBOOST"]
    assert evidence.effective_probabilities["CATBOOST"] == pytest.approx(0.85)
    assert "CALIBRATION_DOWNGRADED_CATBOOST" in evidence.reason_codes


def test_abstaining_and_out_of_domain_expert_is_excluded() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.90, fingerprint_seed=51),
            _prediction("LIGHTGBM", 0.88, fingerprint_seed=52),
            _prediction(
                "PYTORCH",
                0.99,
                action="ABSTAIN",
                out_of_domain=True,
                fingerprint_seed=53,
            ),
        ],
        assessment_as_of=AS_OF,
    )

    assert evidence.has_abstaining_expert is True
    assert evidence.has_out_of_domain_expert is True
    assert evidence.abstaining_model_families == ["PYTORCH"]
    assert evidence.out_of_domain_model_families == ["PYTORCH"]
    assert evidence.contributing_model_families == ["CATBOOST", "LIGHTGBM"]
    assert "PYTORCH" not in evidence.effective_probabilities


def test_one_usable_expert_is_maximum_uncertainty_not_false_consensus() -> None:
    evidence = evaluate_ensemble_uncertainty(
        [
            _prediction("CATBOOST", 0.95, fingerprint_seed=61),
            _prediction(
                "LIGHTGBM",
                0.10,
                action="ABSTAIN",
                fingerprint_seed=62,
            ),
        ],
        assessment_as_of=AS_OF,
    )

    assert evidence.contributing_expert_count == 1
    assert evidence.evidence_quality == "INSUFFICIENT"
    assert evidence.uncertainty_score == 1.0
    assert evidence.uncertainty_level == "HIGH"
    assert "INSUFFICIENT_USABLE_EXPERTS" in evidence.reason_codes


def test_duplicate_family_mixed_symbol_and_future_evidence_fail_closed() -> None:
    duplicate = [
        _prediction("CATBOOST", 0.80, fingerprint_seed=71),
        _prediction("CATBOOST", 0.82, fingerprint_seed=72),
    ]
    with pytest.raises(ValueError, match="duplicate model families"):
        evaluate_ensemble_uncertainty(duplicate, assessment_as_of=AS_OF)

    mixed_symbol = [
        _prediction("CATBOOST", 0.80, fingerprint_seed=73),
        _prediction("LIGHTGBM", 0.82, symbol="TCS", fingerprint_seed=74),
    ]
    with pytest.raises(ValueError, match="share one symbol"):
        evaluate_ensemble_uncertainty(mixed_symbol, assessment_as_of=AS_OF)

    future = [
        _prediction("CATBOOST", 0.80, fingerprint_seed=75),
        _prediction(
            "LIGHTGBM",
            0.82,
            as_of=AS_OF + timedelta(seconds=1),
            fingerprint_seed=76,
        ),
    ]
    with pytest.raises(ValueError, match="future prediction evidence"):
        evaluate_ensemble_uncertainty(future, assessment_as_of=AS_OF)


def test_tampered_calibrated_prediction_fingerprint_fails_closed() -> None:
    prediction = _prediction("CATBOOST", 0.80, fingerprint_seed=81).model_copy(
        update={"evidence_fingerprint": _hash(9999)}
    )

    with pytest.raises(ValueError, match="evidence fingerprint mismatch"):
        evaluate_ensemble_uncertainty([prediction], assessment_as_of=AS_OF)
