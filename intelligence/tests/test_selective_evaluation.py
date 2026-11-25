from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.ensemble_uncertainty import EnsembleUncertaintyEvidence
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import FusionEvidence, FusionRequest, fuse_signals
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.selective_evaluation import (
    SelectiveEvaluationConfig,
    evaluate_selective_coverage_expectancy,
)
from aitrading_intelligence.selective_policy import evaluate_selective_policy

START = datetime(2026, 1, 12, 4, 0, tzinfo=UTC)
TARGET_FINGERPRINT = "a" * 64


def _hash(seed: int) -> str:
    return f"{seed:064x}"


def _opportunity(index: int):
    as_of = START + timedelta(minutes=5 * index)
    return fuse_signals(
        FusionRequest(
            symbol="RELIANCE",
            as_of=as_of,
            deterministic=FusionEvidence(
                family="DETERMINISTIC",
                provider="rules",
                version="fixture.v1",
                score=0.80,
                confidence=1.0,
                observed_at=as_of,
                fingerprint=_hash(10_000 + (index * 2)),
            ),
            quant=FusionEvidence(
                family="QUANT",
                provider="model-lab",
                version="fixture.v1",
                score=0.75,
                confidence=1.0,
                observed_at=as_of,
                fingerprint=_hash(10_001 + (index * 2)),
            ),
        )
    )


def _uncertainty(index: int, *, candidate: bool) -> EnsembleUncertaintyEvidence:
    as_of = START + timedelta(minutes=5 * index)
    uncertainty_score = 0.20 if candidate else 0.90
    uncertainty_level = "LOW" if candidate else "HIGH"
    payload = {
        "contract_version": "ensemble-uncertainty.v1",
        "symbol": "RELIANCE",
        "assessment_as_of": as_of.isoformat(),
        "config_fingerprint": _hash(20_000 + index),
        "expected_model_families": ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        "present_model_families": ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        "contributing_model_families": ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        "missing_model_families": [],
        "stale_model_families": [],
        "abstaining_model_families": [],
        "calibration_degraded_families": [],
        "out_of_domain_model_families": [],
        "source_calibrated_prediction_fingerprints": [
            _hash(30_000 + (index * 3)),
            _hash(30_001 + (index * 3)),
            _hash(30_002 + (index * 3)),
        ],
        "effective_probabilities": {
            "CATBOOST": 0.83,
            "LIGHTGBM": 0.85,
            "PYTORCH": 0.87,
        },
        "expected_expert_count": 3,
        "present_expert_count": 3,
        "contributing_expert_count": 3,
        "positive_direction_count": 3,
        "negative_direction_count": 0,
        "neutral_direction_count": 0,
        "directional_disagreement_count": 0,
        "directional_disagreement_ratio": 0.0,
        "probability_mean": 0.85,
        "probability_min": 0.83,
        "probability_max": 0.87,
        "probability_stddev": 0.01633,
        "probability_dispersion": 0.04,
        "normalized_dispersion": 0.03266,
        "directional_entropy": 0.0,
        "mean_margin_uncertainty": 0.30,
        "expert_coverage": 1.0,
        "missingness_penalty": 0.0,
        "normalized_disagreement_score": 0.03266,
        "uncertainty_score": uncertainty_score,
        "uncertainty_level": uncertainty_level,
        "evidence_quality": "COMPLETE",
        "has_missing_expert": False,
        "has_stale_expert": False,
        "has_abstaining_expert": False,
        "has_out_of_domain_expert": False,
        "has_insufficient_calibration_expert": False,
        "reason_codes": [
            "ENSEMBLE_COMPLETE_LOW_UNCERTAINTY" if candidate else "HIGH_UNCERTAINTY"
        ],
        "required_next_gate": "V3_05_SELECTIVE_POLICY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return EnsembleUncertaintyEvidence(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _decision(index: int, *, candidate: bool):
    return evaluate_selective_policy(
        _opportunity(index),
        _uncertainty(index, candidate=candidate),
        expected_gross_edge_bps=35.0,
        estimated_round_trip_cost_bps=10.0,
    )


def _outcome(index: int, *, forward_return: float, observed_after_minutes: int = 30):
    as_of = START + timedelta(minutes=5 * index)
    observed_at = as_of + timedelta(minutes=observed_after_minutes)
    payload = {
        "target_version": "forward-return-after-costs.v1",
        "symbol": "RELIANCE",
        "as_of": as_of.isoformat(),
        "target_observed_at": observed_at.isoformat(),
        "feature_row_fingerprint": _hash(40_000 + index),
        "target_spec_fingerprint": TARGET_FINGERPRINT,
        "label": 1 if forward_return > 0.0025 else 0,
        "forward_return": forward_return,
        "threshold_return": 0.0025,
        "features": {"market.close": 100.0 + index},
        "provenance": [f"fixture:{index}"],
    }
    return LabeledExample(
        **payload,
        example_fingerprint=canonical_fingerprint(payload),
    )


def _dataset(*, candidate_return: float, abstain_return: float, count: int = 20):
    decisions = []
    opportunities = []
    outcomes = []
    for index in range(count):
        candidate = index % 2 == 0
        opportunities.append(_opportunity(index))
        decisions.append(_decision(index, candidate=candidate))
        outcomes.append(
            _outcome(
                index,
                forward_return=candidate_return if candidate else abstain_return,
            )
        )
    return decisions, opportunities, outcomes


def test_selective_policy_improves_expectancy_at_lower_coverage() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    evaluation_as_of = max(item.target_observed_at for item in outcomes)

    report = evaluate_selective_coverage_expectancy(
        decisions,
        opportunities,
        outcomes,
        evaluation_as_of=evaluation_as_of,
    )

    assert report.resolved_decision_count == 20
    assert report.candidate_count == 10
    assert report.abstain_count == 10
    assert report.always_trade.coverage == 1.0
    assert report.selective.coverage == 0.5
    assert report.selective.expectancy_bps == pytest.approx(190.0)
    assert report.always_trade.expectancy_bps == pytest.approx(40.0)
    assert report.expectancy_uplift_bps == pytest.approx(150.0)
    assert report.abstained_counterfactual_expectancy_bps == pytest.approx(-110.0)
    assert report.assessment == "QUALITY_PRESERVED_OR_IMPROVED"
    assert report.required_next_gate == "V3_05_API_UI_SEMANTICS"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_selective_policy_regression_routes_to_research_review() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=-0.010,
        abstain_return=0.020,
    )
    report = evaluate_selective_coverage_expectancy(
        decisions,
        opportunities,
        outcomes,
        evaluation_as_of=max(item.target_observed_at for item in outcomes),
    )

    assert report.expectancy_uplift_bps == pytest.approx(-150.0)
    assert report.assessment == "QUALITY_REGRESSED"
    assert "SELECTIVE_EXPECTANCY_REGRESSED" in report.reason_codes
    assert report.required_next_gate == "V3_05_SELECTIVE_POLICY_RESEARCH_REVIEW"


def test_small_sample_is_insufficient_even_when_expectancy_is_positive() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
        count=6,
    )
    report = evaluate_selective_coverage_expectancy(
        decisions,
        opportunities,
        outcomes,
        evaluation_as_of=max(item.target_observed_at for item in outcomes),
    )

    assert report.assessment == "INSUFFICIENT_EVIDENCE"
    assert "INSUFFICIENT_RESOLVED_DECISIONS" in report.reason_codes
    assert "INSUFFICIENT_CANDIDATE_DECISIONS" in report.reason_codes
    assert "INSUFFICIENT_ABSTAINED_DECISIONS" in report.reason_codes


def test_future_only_archive_growth_cannot_rewrite_historical_report() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    evaluation_as_of = max(item.target_observed_at for item in outcomes)
    baseline = evaluate_selective_coverage_expectancy(
        decisions,
        opportunities,
        outcomes,
        evaluation_as_of=evaluation_as_of,
    )

    future_index = 100
    future_opportunity = _opportunity(future_index)
    future_decision = _decision(future_index, candidate=True)
    future_outcome = _outcome(future_index, forward_return=0.50)
    expanded = evaluate_selective_coverage_expectancy(
        [*decisions, future_decision],
        [*opportunities, future_opportunity],
        [*outcomes, future_outcome],
        evaluation_as_of=evaluation_as_of,
    )

    assert future_decision.decision_as_of > evaluation_as_of
    assert expanded == baseline


def test_outcome_observed_after_cutoff_stays_unresolved_and_is_future_invariant() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    pending_index = 30
    pending_opportunity = _opportunity(pending_index)
    pending_decision = _decision(pending_index, candidate=True)
    cutoff = pending_decision.decision_as_of + timedelta(minutes=5)
    pending_outcome = _outcome(
        pending_index,
        forward_return=0.30,
        observed_after_minutes=30,
    )

    without_future_outcome = evaluate_selective_coverage_expectancy(
        [*decisions, pending_decision],
        [*opportunities, pending_opportunity],
        outcomes,
        evaluation_as_of=cutoff,
        config=SelectiveEvaluationConfig(minimum_resolved_decisions=10),
    )
    with_future_outcome = evaluate_selective_coverage_expectancy(
        [*decisions, pending_decision],
        [*opportunities, pending_opportunity],
        [*outcomes, pending_outcome],
        evaluation_as_of=cutoff,
        config=SelectiveEvaluationConfig(minimum_resolved_decisions=10),
    )

    assert pending_outcome.target_observed_at > cutoff
    assert without_future_outcome.unresolved_decision_count == 1
    assert with_future_outcome == without_future_outcome


def test_input_order_does_not_change_report_identity() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    evaluation_as_of = max(item.target_observed_at for item in outcomes)
    first = evaluate_selective_coverage_expectancy(
        decisions,
        opportunities,
        outcomes,
        evaluation_as_of=evaluation_as_of,
    )
    second = evaluate_selective_coverage_expectancy(
        list(reversed(decisions)),
        list(reversed(opportunities)),
        list(reversed(outcomes)),
        evaluation_as_of=evaluation_as_of,
    )

    assert first.report_fingerprint == second.report_fingerprint
    assert first == second


def test_tampered_decision_or_outcome_fingerprint_fails_closed() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    evaluation_as_of = max(item.target_observed_at for item in outcomes)
    decisions[0] = decisions[0].model_copy(update={"decision_fingerprint": "f" * 64})
    with pytest.raises(ValueError, match="selective decision fingerprint mismatch"):
        evaluate_selective_coverage_expectancy(
            decisions,
            opportunities,
            outcomes,
            evaluation_as_of=evaluation_as_of,
        )

    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    outcomes[0] = outcomes[0].model_copy(update={"example_fingerprint": "e" * 64})
    with pytest.raises(ValueError, match="labeled outcome fingerprint mismatch"):
        evaluate_selective_coverage_expectancy(
            decisions,
            opportunities,
            outcomes,
            evaluation_as_of=evaluation_as_of,
        )


def test_mixed_target_specifications_fail_closed() -> None:
    decisions, opportunities, outcomes = _dataset(
        candidate_return=0.020,
        abstain_return=-0.010,
    )
    changed = outcomes[0].model_copy(update={"target_spec_fingerprint": "b" * 64})
    payload = {
        "target_version": changed.target_version,
        "symbol": changed.symbol,
        "as_of": changed.as_of.isoformat(),
        "target_observed_at": changed.target_observed_at.isoformat(),
        "feature_row_fingerprint": changed.feature_row_fingerprint,
        "target_spec_fingerprint": changed.target_spec_fingerprint,
        "label": changed.label,
        "forward_return": changed.forward_return,
        "threshold_return": changed.threshold_return,
        "features": changed.features,
        "provenance": changed.provenance,
    }
    outcomes[0] = changed.model_copy(
        update={"example_fingerprint": canonical_fingerprint(payload)}
    )

    with pytest.raises(ValueError, match="one target specification"):
        evaluate_selective_coverage_expectancy(
            decisions,
            opportunities,
            outcomes,
            evaluation_as_of=max(item.target_observed_at for item in outcomes),
        )
