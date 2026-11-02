from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.ensemble_uncertainty import EnsembleUncertaintyEvidence
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest, fuse_signals
from aitrading_intelligence.selective_policy import evaluate_selective_policy

AS_OF = datetime(2026, 1, 8, 5, 30, tzinfo=UTC)


def _hash(seed: int) -> str:
    return f"{seed:064x}"


def _opportunity(*, symbol: str = "RELIANCE", buy: bool = True):
    deterministic_score = 0.80 if buy else 0.10
    quant_score = 0.75 if buy else -0.20
    return fuse_signals(
        FusionRequest(
            symbol=symbol,
            as_of=AS_OF,
            deterministic=FusionEvidence(
                family="DETERMINISTIC",
                provider="rules",
                version="fixture.v1",
                score=deterministic_score,
                confidence=1.0,
                observed_at=AS_OF,
                fingerprint=_hash(10),
            ),
            quant=FusionEvidence(
                family="QUANT",
                provider="model-lab",
                version="fixture.v1",
                score=quant_score,
                confidence=1.0,
                observed_at=AS_OF,
                fingerprint=_hash(11),
            ),
        )
    )


def _uncertainty(
    *,
    symbol: str = "RELIANCE",
    assessment_as_of: datetime = AS_OF,
    probability_mean: float | None = 0.85,
    uncertainty_score: float = 0.20,
    uncertainty_level: str = "LOW",
    evidence_quality: str = "COMPLETE",
    expert_coverage: float = 1.0,
    insufficient_calibration: bool = False,
) -> EnsembleUncertaintyEvidence:
    effective_probabilities = {
        "CATBOOST": 0.83,
        "LIGHTGBM": 0.85,
        "PYTORCH": 0.87,
    }
    payload = {
        "contract_version": "ensemble-uncertainty.v1",
        "symbol": symbol,
        "assessment_as_of": assessment_as_of.isoformat(),
        "config_fingerprint": _hash(20),
        "expected_model_families": ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        "present_model_families": ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        "contributing_model_families": ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        "missing_model_families": [],
        "stale_model_families": [],
        "abstaining_model_families": [],
        "calibration_degraded_families": [],
        "out_of_domain_model_families": [],
        "source_calibrated_prediction_fingerprints": [_hash(21), _hash(22), _hash(23)],
        "effective_probabilities": effective_probabilities,
        "expected_expert_count": 3,
        "present_expert_count": 3,
        "contributing_expert_count": 3,
        "positive_direction_count": 3,
        "negative_direction_count": 0,
        "neutral_direction_count": 0,
        "directional_disagreement_count": 0,
        "directional_disagreement_ratio": 0.0,
        "probability_mean": probability_mean,
        "probability_min": 0.83,
        "probability_max": 0.87,
        "probability_stddev": 0.01633,
        "probability_dispersion": 0.04,
        "normalized_dispersion": 0.03266,
        "directional_entropy": 0.0,
        "mean_margin_uncertainty": 0.30,
        "expert_coverage": expert_coverage,
        "missingness_penalty": 1.0 - expert_coverage,
        "normalized_disagreement_score": 0.03266,
        "uncertainty_score": uncertainty_score,
        "uncertainty_level": uncertainty_level,
        "evidence_quality": evidence_quality,
        "has_missing_expert": False,
        "has_stale_expert": False,
        "has_abstaining_expert": False,
        "has_out_of_domain_expert": False,
        "has_insufficient_calibration_expert": insufficient_calibration,
        "reason_codes": [
            "INSUFFICIENT_CALIBRATION_EXPERT_PRESENT"
            if insufficient_calibration
            else "ENSEMBLE_COMPLETE_LOW_UNCERTAINTY"
        ],
        "required_next_gate": "V3_05_SELECTIVE_POLICY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return EnsembleUncertaintyEvidence(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def test_complete_low_uncertainty_positive_edge_becomes_research_candidate() -> None:
    first = evaluate_selective_policy(
        _opportunity(),
        _uncertainty(),
        expected_gross_edge_bps=35.0,
        estimated_round_trip_cost_bps=10.0,
    )
    second = evaluate_selective_policy(
        _opportunity(),
        _uncertainty(),
        expected_gross_edge_bps=35.0,
        estimated_round_trip_cost_bps=10.0,
    )

    assert first.policy_decision == "TRADE"
    assert first.state == "CANDIDATE"
    assert first.expected_net_edge_bps == 25.0
    assert first.calibrated_probability == 0.85
    assert first.reason_codes == ["SELECTIVE_POLICY_CANDIDATE"]
    assert first.required_next_gate == "V3_05_COVERAGE_EXPECTANCY_EVALUATION"
    assert first.status == "RESEARCH_ELIGIBILITY_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False
    assert first.decision_fingerprint == second.decision_fingerprint


def test_high_uncertainty_can_only_abstain() -> None:
    decision = evaluate_selective_policy(
        _opportunity(),
        _uncertainty(uncertainty_score=0.92, uncertainty_level="HIGH"),
        expected_gross_edge_bps=50.0,
        estimated_round_trip_cost_bps=5.0,
    )

    assert decision.policy_decision == "ABSTAIN"
    assert decision.state == "ABSTAIN"
    assert "HIGH_UNCERTAINTY" in decision.reason_codes
    assert "UNCERTAINTY_ABOVE_THRESHOLD" in decision.reason_codes
    assert decision.can_authorize_capital is False


def test_insufficient_or_degraded_ensemble_evidence_abstains() -> None:
    decision = evaluate_selective_policy(
        _opportunity(),
        _uncertainty(
            evidence_quality="INSUFFICIENT",
            expert_coverage=0.67,
            insufficient_calibration=True,
        ),
        expected_gross_edge_bps=40.0,
        estimated_round_trip_cost_bps=10.0,
    )

    assert decision.policy_decision == "ABSTAIN"
    assert "ENSEMBLE_EVIDENCE_INSUFFICIENT" in decision.reason_codes
    assert "EXPERT_COVERAGE_BELOW_THRESHOLD" in decision.reason_codes
    assert "ENSEMBLE_HAS_INSUFFICIENT_CALIBRATION" in decision.reason_codes


def test_watch_opportunity_never_becomes_trade_candidate() -> None:
    decision = evaluate_selective_policy(
        _opportunity(buy=False),
        _uncertainty(),
        expected_gross_edge_bps=60.0,
        estimated_round_trip_cost_bps=5.0,
    )

    assert decision.policy_decision == "ABSTAIN"
    assert "FUSION_NOT_BUY" in decision.reason_codes
    assert "FUSION_SCORE_BELOW_POLICY_THRESHOLD" in decision.reason_codes


def test_net_edge_is_after_cost_and_low_edge_abstains() -> None:
    decision = evaluate_selective_policy(
        _opportunity(),
        _uncertainty(),
        expected_gross_edge_bps=12.0,
        estimated_round_trip_cost_bps=8.0,
    )

    assert decision.expected_net_edge_bps == 4.0
    assert decision.policy_decision == "ABSTAIN"
    assert "NET_EDGE_BELOW_THRESHOLD" in decision.reason_codes


def test_stale_uncertainty_abstains_even_when_snapshot_itself_was_complete() -> None:
    decision = evaluate_selective_policy(
        _opportunity(),
        _uncertainty(assessment_as_of=AS_OF - timedelta(minutes=20)),
        expected_gross_edge_bps=35.0,
        estimated_round_trip_cost_bps=10.0,
        decision_as_of=AS_OF,
    )

    assert decision.source_uncertainty_age_seconds == 1_200
    assert decision.policy_decision == "ABSTAIN"
    assert "UNCERTAINTY_EVIDENCE_STALE" in decision.reason_codes


def test_future_or_mismatched_evidence_fails_closed() -> None:
    with pytest.raises(ValueError, match="future uncertainty"):
        evaluate_selective_policy(
            _opportunity(),
            _uncertainty(assessment_as_of=AS_OF + timedelta(seconds=1)),
            expected_gross_edge_bps=35.0,
            estimated_round_trip_cost_bps=10.0,
            decision_as_of=AS_OF,
        )

    with pytest.raises(ValueError, match="share one symbol"):
        evaluate_selective_policy(
            _opportunity(),
            _uncertainty(symbol="TCS"),
            expected_gross_edge_bps=35.0,
            estimated_round_trip_cost_bps=10.0,
        )


def test_tampered_source_fingerprints_fail_closed() -> None:
    opportunity = _opportunity().model_copy(update={"opportunity_fingerprint": "f" * 64})
    with pytest.raises(ValueError, match="opportunity fingerprint mismatch"):
        evaluate_selective_policy(
            opportunity,
            _uncertainty(),
            expected_gross_edge_bps=35.0,
            estimated_round_trip_cost_bps=10.0,
        )

    uncertainty = _uncertainty().model_copy(update={"evidence_fingerprint": "e" * 64})
    with pytest.raises(ValueError, match="uncertainty fingerprint mismatch"):
        evaluate_selective_policy(
            _opportunity(),
            uncertainty,
            expected_gross_edge_bps=35.0,
            estimated_round_trip_cost_bps=10.0,
        )


def test_non_finite_or_negative_cost_inputs_fail_closed() -> None:
    with pytest.raises(ValueError, match="gross_edge_bps must be finite"):
        evaluate_selective_policy(
            _opportunity(),
            _uncertainty(),
            expected_gross_edge_bps=float("nan"),
            estimated_round_trip_cost_bps=10.0,
        )

    with pytest.raises(ValueError, match="cannot be negative"):
        evaluate_selective_policy(
            _opportunity(),
            _uncertainty(),
            expected_gross_edge_bps=35.0,
            estimated_round_trip_cost_bps=-1.0,
        )
