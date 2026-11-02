from datetime import UTC, datetime

import pytest

from aitrading_intelligence.model_monitoring import ModelMonitoringReport, MonitoringMetrics
from aitrading_intelligence.model_registry import ModelGovernanceCandidate
from aitrading_intelligence.promotion_governance import (
    PromotionGovernanceConfig,
    evaluate_promotion_governance,
    evaluate_rollback_governance,
)
from aitrading_intelligence.shadow_governance import (
    ShadowComparisonEvidence,
    ShadowMetrics,
)

CHAMPION = "1" * 64
CHALLENGER = "2" * 64
PREVIOUS = "3" * 64


def _candidate(*, challenger_of: str = CHAMPION) -> ModelGovernanceCandidate:
    return ModelGovernanceCandidate(
        model_family="PYTORCH",
        model_version="pytorch-challenger.v2",
        artifact_fingerprint="4" * 64,
        feature_schema_fingerprint="5" * 64,
        training_dataset_fingerprint="6" * 64,
        training_config_fingerprint="7" * 64,
        code_sha="8" * 40,
        evaluation_fingerprint="9" * 64,
        monte_carlo_evidence_fingerprint="a" * 64,
        registered_at=datetime(2026, 11, 2, 9, 45, tzinfo=UTC),
        challenger_of=challenger_of,
        registry_fingerprint=CHALLENGER,
    )


def _metrics(
    *,
    rows: int = 250,
    auc: float,
    brier: float,
    logloss: float,
    ece: float,
) -> ShadowMetrics:
    positive = rows // 2
    return ShadowMetrics(
        rows=rows,
        positive_rows=positive,
        negative_rows=rows - positive,
        roc_auc=auc,
        brier_score=brier,
        log_loss=logloss,
        expected_calibration_error=ece,
    )


def _shadow(*, rows: int = 250, assessment: str = "CHALLENGER_ELIGIBLE_FOR_GOVERNANCE_REVIEW") -> ShadowComparisonEvidence:
    return ShadowComparisonEvidence(
        config_fingerprint="b" * 64,
        champion_registry_fingerprint=CHAMPION,
        challenger_registry_fingerprint=CHALLENGER,
        record_fingerprints=[f"{index + 1:064x}" for index in range(rows)],
        champion=_metrics(rows=rows, auc=0.70, brier=0.20, logloss=0.60, ece=0.05),
        challenger=_metrics(rows=rows, auc=0.75, brier=0.17, logloss=0.55, ece=0.04),
        assessment=assessment,
        required_next_gate=(
            "V2_09_EXPLICIT_GOVERNANCE_DECISION"
            if assessment == "CHALLENGER_ELIGIBLE_FOR_GOVERNANCE_REVIEW"
            else "V2_09_CONTINUE_SHADOW"
        ),
        evidence_fingerprint="c" * 64,
    )


def _monitoring(
    *,
    registry: str = CHALLENGER,
    assessment: str = "STABLE",
) -> ModelMonitoringReport:
    metrics = MonitoringMetrics(
        records=100,
        positive_rate=0.5,
        mean_probability=0.5,
        brier_score=0.18,
        log_loss=0.55,
        ece=0.04,
    )
    retraining = assessment == "RETRAINING_RECOMMENDED"
    return ModelMonitoringReport(
        registry_fingerprint=registry,
        config_fingerprint="d" * 64,
        reference_fingerprint="e" * 64,
        current_fingerprint="f" * 64,
        reference_metrics=metrics,
        current_metrics=metrics,
        probability_psi=0.02,
        positive_rate_delta=0.01,
        brier_delta=0.0,
        log_loss_delta=0.0,
        ece_delta=0.0,
        assessment=assessment,
        reason_codes=["TEST_MONITORING_STATE"],
        required_next_gate=(
            "V2_09_CANDIDATE_RETRAINING"
            if retraining
            else "V2_09_CONTINUE_PAPER_MONITORING"
        ),
        candidate_training_allowed=retraining,
        report_fingerprint="0" * 64,
    )


def test_promotion_recommendation_is_deterministic_and_carries_exact_rollback_target() -> None:
    first = evaluate_promotion_governance(
        champion_registry_fingerprint=CHAMPION,
        challenger_candidate=_candidate(),
        shadow=_shadow(),
        challenger_monitoring=_monitoring(),
    )
    second = evaluate_promotion_governance(
        champion_registry_fingerprint=CHAMPION,
        challenger_candidate=_candidate(),
        shadow=_shadow(),
        challenger_monitoring=_monitoring(),
    )

    assert first == second
    assert first.decision == "PROMOTION_RECOMMENDED"
    assert first.recommended_registry_fingerprint == CHALLENGER
    assert first.rollback_registry_fingerprint == CHAMPION
    assert first.required_next_gate == "V2_09_EXPLICIT_ACTIVATION_APPROVAL"
    assert first.activation_requires_explicit_approval is True
    assert first.activation_allowed is False
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_promotion_holds_when_challenger_monitoring_is_not_stable() -> None:
    decision = evaluate_promotion_governance(
        champion_registry_fingerprint=CHAMPION,
        challenger_candidate=_candidate(),
        shadow=_shadow(),
        challenger_monitoring=_monitoring(assessment="WATCH"),
    )

    assert decision.decision == "HOLD_CHAMPION"
    assert decision.recommended_registry_fingerprint == CHAMPION
    assert "CHALLENGER_MONITORING_WATCH" in decision.reason_codes
    assert decision.activation_allowed is False


def test_promotion_holds_when_shadow_sample_is_below_stricter_governance_minimum() -> None:
    decision = evaluate_promotion_governance(
        champion_registry_fingerprint=CHAMPION,
        challenger_candidate=_candidate(),
        shadow=_shadow(rows=100),
        challenger_monitoring=_monitoring(),
        config=PromotionGovernanceConfig(minimum_shadow_records=200),
    )

    assert decision.decision == "HOLD_CHAMPION"
    assert "INSUFFICIENT_SHADOW_EVIDENCE" in decision.reason_codes


def test_promotion_rejects_candidate_lineage_mismatch() -> None:
    with pytest.raises(ValueError, match="exact champion"):
        evaluate_promotion_governance(
            champion_registry_fingerprint=CHAMPION,
            challenger_candidate=_candidate(challenger_of=PREVIOUS),
            shadow=_shadow(),
            challenger_monitoring=_monitoring(),
        )


def test_promotion_holds_when_shadow_itself_is_not_eligible() -> None:
    decision = evaluate_promotion_governance(
        champion_registry_fingerprint=CHAMPION,
        challenger_candidate=_candidate(),
        shadow=_shadow(assessment="CHAMPION_HOLDS"),
        challenger_monitoring=_monitoring(),
    )

    assert decision.decision == "HOLD_CHAMPION"
    assert "SHADOW_NOT_ELIGIBLE_FOR_PROMOTION_REVIEW" in decision.reason_codes


def test_severe_active_model_monitoring_recommends_exact_last_accepted_rollback() -> None:
    decision = evaluate_rollback_governance(
        active_registry_fingerprint=CHALLENGER,
        last_accepted_registry_fingerprint=CHAMPION,
        active_monitoring=_monitoring(assessment="RETRAINING_RECOMMENDED"),
    )

    assert decision.decision == "ROLLBACK_RECOMMENDED"
    assert decision.recommended_registry_fingerprint == CHAMPION
    assert decision.rollback_registry_fingerprint == CHAMPION
    assert decision.required_next_gate == "V2_09_EXPLICIT_ROLLBACK_APPROVAL"
    assert decision.activation_requires_explicit_approval is True
    assert decision.activation_allowed is False
    assert decision.promotion_allowed is False
    assert decision.can_authorize_capital is False


def test_stable_active_model_monitoring_holds_active_model() -> None:
    decision = evaluate_rollback_governance(
        active_registry_fingerprint=CHALLENGER,
        last_accepted_registry_fingerprint=CHAMPION,
        active_monitoring=_monitoring(),
    )

    assert decision.decision == "HOLD_ACTIVE"
    assert decision.recommended_registry_fingerprint == CHALLENGER
    assert decision.required_next_gate == "V2_09_CONTINUE_PAPER_MONITORING"


def test_rollback_rejects_monitoring_for_a_different_active_model() -> None:
    with pytest.raises(ValueError, match="must match the active model"):
        evaluate_rollback_governance(
            active_registry_fingerprint=CHALLENGER,
            last_accepted_registry_fingerprint=CHAMPION,
            active_monitoring=_monitoring(registry=PREVIOUS),
        )


def test_rollback_target_must_be_a_distinct_last_accepted_model() -> None:
    with pytest.raises(ValueError, match="must differ"):
        evaluate_rollback_governance(
            active_registry_fingerprint=CHALLENGER,
            last_accepted_registry_fingerprint=CHALLENGER,
            active_monitoring=_monitoring(),
        )
