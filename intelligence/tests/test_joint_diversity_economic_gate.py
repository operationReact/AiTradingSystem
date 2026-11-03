from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.incremental_economic_value import (
    create_net_return_series,
)
from aitrading_intelligence.joint_diversity_economic_gate import (
    JointDiversityEconomicReport,
    evaluate_joint_diversity_economic_gate,
)
from aitrading_intelligence.model_diversity import build_prediction_series


def _sha(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _evidence(
    *,
    count: int = 100,
    near_duplicate: bool = False,
    same_returns: bool = False,
    constant: bool = False,
):
    observations = [_sha(f"observation-{i}") for i in range(count)]
    times = [
        datetime(2025, 1, 1, 4, 0, tzinfo=UTC) + timedelta(minutes=5 * i)
        for i in range(count)
    ]
    baseline_probs = (
        [0.5] * count
        if constant
        else [0.2 + 0.6 * (i % 5) / 4 for i in range(count)]
    )
    challenger_probs = (
        [min(1.0, x + 0.01) for x in baseline_probs]
        if near_duplicate
        else [0.2 + 0.6 * ((i * 7) % 11) / 10 for i in range(count)]
    )

    def prediction(name: str, probabilities: list[float]):
        return build_prediction_series(
            candidate_id=name,
            model_version_ref=f"{name}:v1",
            source_result_fingerprint=_sha(f"result-{name}"),
            observation_fingerprints=observations,
            probabilities=probabilities,
        )

    def returns(name: str, value: float):
        return create_net_return_series(
            candidate_id=name,
            model_version_ref=f"{name}:v1",
            source_result_fingerprint=_sha(f"result-{name}"),
            target_binding_fingerprint=_sha("target"),
            fold_binding_fingerprint=_sha("purged-folds"),
            economic_cost_binding_fingerprint=_sha("india-after-cost"),
            observation_fingerprints=observations,
            decision_times=times,
            net_returns_after_costs=[value] * count,
        )

    return (
        prediction("catboost", baseline_probs),
        prediction("lightgbm", challenger_probs),
        returns("catboost", 0.001),
        returns("lightgbm", 0.001 if same_returns else 0.004),
    )


def test_nonredundant_positive_paired_value_is_research_only() -> None:
    report = evaluate_joint_diversity_economic_gate(*_evidence())

    assert report.status == "ADDITIVE_RESEARCH_ONLY"
    assert report.economic_assessment == "PROMISING_RESEARCH"
    assert report.redundancy_risk is False
    assert report.correlation_defined is True
    assert report.reason_codes == []
    assert report.multiple_testing_adjusted is False
    assert report.independent_indian_market_validation_complete is False
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_highly_correlated_challenger_with_uplift_is_not_additive() -> None:
    report = evaluate_joint_diversity_economic_gate(*_evidence(near_duplicate=True))

    assert report.economic_assessment == "PROMISING_RESEARCH"
    assert report.redundancy_risk is True
    assert report.status == "NON_ADDITIVE"
    assert "PROBABILITY_SIGNAL_REDUNDANT" in report.reason_codes


def test_no_incremental_economic_uplift_is_non_additive() -> None:
    report = evaluate_joint_diversity_economic_gate(*_evidence(same_returns=True))

    assert report.status == "NON_ADDITIVE"
    assert report.economic_assessment == "NOT_IMPROVED"
    assert "ECONOMIC_NO_MEAN_NET_UPLIFT" in report.reason_codes


def test_insufficient_causal_rows_are_inconclusive() -> None:
    report = evaluate_joint_diversity_economic_gate(*_evidence(count=30))

    assert report.status == "INCONCLUSIVE"
    assert report.economic_assessment == "INCONCLUSIVE"
    assert "ECONOMIC_INSUFFICIENT_CAUSAL_OBSERVATIONS" in report.reason_codes


def test_undefined_diversity_is_inconclusive_even_with_positive_return() -> None:
    report = evaluate_joint_diversity_economic_gate(*_evidence(constant=True))

    assert report.status == "INCONCLUSIVE"
    assert report.correlation_defined is False
    assert "DIVERSITY_CORRELATION_UNDEFINED" in report.reason_codes


def test_prediction_and_returns_must_have_same_source_result() -> None:
    base_prediction, challenge_prediction, base_returns, challenge_returns = _evidence()
    bad_challenge_prediction = build_prediction_series(
        candidate_id="lightgbm",
        model_version_ref="lightgbm:v1",
        source_result_fingerprint=_sha("different-result"),
        observation_fingerprints=challenge_prediction.observation_fingerprints,
        probabilities=challenge_prediction.probabilities,
    )
    with pytest.raises(ValueError, match="source_result_fingerprint"):
        evaluate_joint_diversity_economic_gate(
            base_prediction, bad_challenge_prediction, base_returns, challenge_returns
        )


def test_prediction_and_return_observations_cannot_disagree() -> None:
    base_prediction, challenge_prediction, base_returns, challenge_returns = _evidence()
    mutated_prediction = build_prediction_series(
        candidate_id="lightgbm",
        model_version_ref="lightgbm:v1",
        source_result_fingerprint=challenge_prediction.source_result_fingerprint,
        observation_fingerprints=list(reversed(challenge_prediction.observation_fingerprints)),
        probabilities=challenge_prediction.probabilities,
    )
    with pytest.raises(ValueError, match="observation_fingerprints"):
        evaluate_joint_diversity_economic_gate(
            base_prediction, mutated_prediction, base_returns, challenge_returns
        )


def test_repeatability_and_fingerprint_tamper_rejection() -> None:
    inputs = _evidence()
    first = evaluate_joint_diversity_economic_gate(*inputs)
    second = evaluate_joint_diversity_economic_gate(*inputs)

    assert first.report_fingerprint == second.report_fingerprint
    with pytest.raises(ValidationError, match="joint diversity-economic fingerprint"):
        JointDiversityEconomicReport.model_validate(
            {**first.model_dump(), "report_fingerprint": _sha("forged")}
        )
