from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from aitrading_intelligence.app import app
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.selective_evaluation import (
    CoverageExpectancyPoint,
    SelectivePolicyEvaluationReport,
)
from aitrading_intelligence.selective_policy import SelectiveDecisionV1
from aitrading_intelligence.selective_presentation import build_selective_presentation

NOW = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)
HASH_A = "a" * 64
HASH_B = "b" * 64
HASH_C = "c" * 64
HASH_D = "d" * 64
HASH_E = "e" * 64


def _decision(
    *,
    policy_decision: str = "TRADE",
    calibrated_probability: float | None = 0.82,
    uncertainty_score: float = 0.20,
    uncertainty_level: str = "LOW",
    evidence_quality: str = "COMPLETE",
) -> SelectiveDecisionV1:
    state = "CANDIDATE" if policy_decision == "TRADE" else "ABSTAIN"
    reason_codes = (
        ["SELECTIVE_POLICY_CANDIDATE"]
        if policy_decision == "TRADE"
        else ["HIGH_UNCERTAINTY"]
    )
    payload = {
        "contract_version": "selective-policy.v1",
        "symbol": "RELIANCE",
        "decision_as_of": NOW.isoformat(),
        "policy_config_fingerprint": HASH_A,
        "source_opportunity_fingerprint": HASH_B,
        "source_uncertainty_fingerprint": HASH_C,
        "source_uncertainty_age_seconds": 5,
        "calibrated_probability": calibrated_probability,
        "uncertainty_score": uncertainty_score,
        "uncertainty_level": uncertainty_level,
        "evidence_quality": evidence_quality,
        "expert_coverage": 1.0,
        "fused_score": 0.70,
        "expected_gross_edge_bps": 35.0,
        "estimated_round_trip_cost_bps": 10.0,
        "expected_net_edge_bps": 25.0,
        "policy_decision": policy_decision,
        "state": state,
        "reason_codes": reason_codes,
        "required_next_gate": "V3_05_COVERAGE_EXPECTANCY_EVALUATION",
        "status": "RESEARCH_ELIGIBILITY_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SelectiveDecisionV1(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )


def _evaluation(
    decision: SelectiveDecisionV1,
    *,
    assessment: str = "QUALITY_PRESERVED_OR_IMPROVED",
) -> SelectivePolicyEvaluationReport:
    candidate_count = 1 if decision.policy_decision == "TRADE" else 0
    abstain_count = 1 - candidate_count
    selective = CoverageExpectancyPoint(
        policy="SELECTIVE",
        coverage=float(candidate_count),
        trade_count=candidate_count,
        winning_trades=candidate_count,
        win_rate=float(candidate_count),
        total_net_return=0.01 if candidate_count else 0.0,
        mean_net_return=0.01 if candidate_count else None,
        expectancy_bps=100.0 if candidate_count else None,
    )
    always_trade = CoverageExpectancyPoint(
        policy="ALWAYS_TRADE",
        coverage=1.0,
        trade_count=1,
        winning_trades=1,
        win_rate=1.0,
        total_net_return=0.005,
        mean_net_return=0.005,
        expectancy_bps=50.0,
    )
    if assessment == "QUALITY_PRESERVED_OR_IMPROVED":
        next_gate = "V3_05_API_UI_SEMANTICS"
        reasons = ["SELECTIVE_EXPECTANCY_PRESERVED_OR_IMPROVED"]
        uplift = 50.0 if candidate_count else None
    elif assessment == "QUALITY_REGRESSED":
        next_gate = "V3_05_SELECTIVE_POLICY_RESEARCH_REVIEW"
        reasons = ["SELECTIVE_EXPECTANCY_REGRESSED"]
        uplift = -50.0
    else:
        next_gate = "V3_05_SELECTIVE_POLICY_RESEARCH_REVIEW"
        reasons = ["INSUFFICIENT_RESOLVED_DECISIONS"]
        uplift = None

    payload = {
        "contract_version": "selective-policy-evaluation.v1",
        "evaluation_as_of": (NOW + timedelta(minutes=30)).isoformat(),
        "config_fingerprint": HASH_D,
        "target_spec_fingerprint": HASH_E,
        "eligible_decision_count": 1,
        "resolved_decision_count": 1,
        "unresolved_decision_count": 0,
        "candidate_count": candidate_count,
        "abstain_count": abstain_count,
        "always_trade": always_trade.model_dump(mode="json"),
        "selective": selective.model_dump(mode="json"),
        "abstained_counterfactual_expectancy_bps": -25.0 if abstain_count else None,
        "expectancy_uplift_bps": uplift,
        "assessment": assessment,
        "reason_codes": reasons,
        "source_decision_fingerprints": [decision.decision_fingerprint],
        "source_opportunity_fingerprints": [decision.source_opportunity_fingerprint],
        "source_outcome_fingerprints": ["f" * 64],
        "required_next_gate": next_gate,
        "status": "RESEARCH_EVALUATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SelectivePolicyEvaluationReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


def test_quality_gate_exposes_calibrated_probability_separately_from_uncertainty() -> None:
    decision = _decision()
    presentation = build_selective_presentation(decision, _evaluation(decision))

    assert presentation.state == "CANDIDATE"
    assert presentation.calibrated_probability == pytest.approx(0.82)
    assert presentation.probability_semantics == "CALIBRATED_EVENT_PROBABILITY"
    assert presentation.uncertainty_score == pytest.approx(0.20)
    assert presentation.uncertainty_level == "LOW"
    assert presentation.evaluation_assessment == "QUALITY_PRESERVED_OR_IMPROVED"
    assert presentation.can_authorize_capital is False
    assert presentation.promotion_allowed is False


@pytest.mark.parametrize("assessment", ["QUALITY_REGRESSED", "INSUFFICIENT_EVIDENCE"])
def test_failed_or_insufficient_quality_gate_abstains_and_withholds_probability(
    assessment: str,
) -> None:
    decision = _decision()
    presentation = build_selective_presentation(
        decision,
        _evaluation(decision, assessment=assessment),
    )

    assert presentation.state == "ABSTAIN"
    assert presentation.calibrated_probability is None
    assert presentation.probability_semantics == "WITHHELD"
    assert presentation.uncertainty_score == pytest.approx(0.20)
    assert presentation.uncertainty_level == "LOW"


def test_abstaining_decision_never_leaks_probability_even_with_quality_preserved() -> None:
    decision = _decision(
        policy_decision="ABSTAIN",
        calibrated_probability=0.88,
        uncertainty_score=0.82,
        uncertainty_level="HIGH",
    )
    presentation = build_selective_presentation(decision, _evaluation(decision))

    assert presentation.state == "ABSTAIN"
    assert presentation.calibrated_probability is None
    assert presentation.probability_semantics == "WITHHELD"
    assert presentation.uncertainty_score == pytest.approx(0.82)
    assert "SELECTIVE_DECISION_ABSTAINED" in presentation.reason_codes


def test_missing_calibrated_probability_abstains_instead_of_synthesizing_confidence() -> None:
    decision = _decision(calibrated_probability=None)
    presentation = build_selective_presentation(decision, _evaluation(decision))

    assert presentation.state == "ABSTAIN"
    assert presentation.calibrated_probability is None
    assert "CALIBRATED_PROBABILITY_UNAVAILABLE" in presentation.reason_codes


def test_tampered_evaluation_fingerprint_fails_closed() -> None:
    decision = _decision()
    evaluation = _evaluation(decision).model_copy(update={"report_fingerprint": "0" * 64})

    with pytest.raises(ValueError, match="selective evaluation report fingerprint mismatch"):
        build_selective_presentation(decision, evaluation)


def test_selective_presentation_api_preserves_probability_semantics() -> None:
    decision = _decision()
    evaluation = _evaluation(decision)
    client = TestClient(app)

    response = client.post(
        "/v1/selective/presentation",
        json={
            "decision": decision.model_dump(mode="json"),
            "evaluation": evaluation.model_dump(mode="json"),
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["contract_version"] == "selective-presentation.v1"
    assert body["state"] == "CANDIDATE"
    assert body["calibrated_probability"] == pytest.approx(0.82)
    assert body["probability_semantics"] == "CALIBRATED_EVENT_PROBABILITY"
    assert body["uncertainty_score"] == pytest.approx(0.20)
    assert body["can_authorize_capital"] is False
