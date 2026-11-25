from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .model_monitoring import ModelMonitoringReport
from .model_registry import ModelGovernanceCandidate
from .shadow_governance import ShadowComparisonEvidence

PROMOTION_GOVERNANCE_VERSION = "model-promotion-governance.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class PromotionGovernanceConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_shadow_records: Annotated[int, Field(ge=50, le=100_000)] = 200
    minimum_auc_improvement: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0
    minimum_brier_improvement: Annotated[float, Field(ge=0.0, le=1.0)] = 0.01
    maximum_log_loss_regression: Annotated[float, Field(ge=0.0, le=10.0)] = 0.0
    maximum_ece_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.01

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PromotionGovernanceDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-promotion-governance.v1"] = (
        PROMOTION_GOVERNANCE_VERSION
    )
    config_fingerprint: Hash64
    champion_registry_fingerprint: Hash64
    challenger_registry_fingerprint: Hash64
    shadow_evidence_fingerprint: Hash64
    monitoring_report_fingerprint: Hash64
    decision: Literal["PROMOTION_RECOMMENDED", "HOLD_CHAMPION"]
    reason_codes: list[str]
    recommended_registry_fingerprint: Hash64
    rollback_registry_fingerprint: Hash64
    required_next_gate: Literal[
        "V2_09_EXPLICIT_ACTIVATION_APPROVAL",
        "V2_09_CONTINUE_PAPER_EVALUATION",
    ]
    activation_requires_explicit_approval: Literal[True] = True
    activation_allowed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    decision_fingerprint: Hash64


class RollbackGovernanceDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-promotion-governance.v1"] = (
        PROMOTION_GOVERNANCE_VERSION
    )
    active_registry_fingerprint: Hash64
    rollback_registry_fingerprint: Hash64
    monitoring_report_fingerprint: Hash64
    decision: Literal["ROLLBACK_RECOMMENDED", "HOLD_ACTIVE"]
    reason_codes: list[str]
    recommended_registry_fingerprint: Hash64
    required_next_gate: Literal[
        "V2_09_EXPLICIT_ROLLBACK_APPROVAL",
        "V2_09_CONTINUE_PAPER_MONITORING",
    ]
    activation_requires_explicit_approval: Literal[True] = True
    activation_allowed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    decision_fingerprint: Hash64


class PromotionInputs(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    champion_registry_fingerprint: Hash64
    challenger_candidate: ModelGovernanceCandidate
    shadow: ShadowComparisonEvidence
    challenger_monitoring: ModelMonitoringReport

    @model_validator(mode="after")
    def lineage_must_match_exactly(self) -> PromotionInputs:
        challenger_id = self.challenger_candidate.registry_fingerprint
        if self.challenger_candidate.challenger_of != self.champion_registry_fingerprint:
            raise ValueError("challenger registry lineage must identify the exact champion")
        if self.shadow.champion_registry_fingerprint != self.champion_registry_fingerprint:
            raise ValueError("shadow evidence champion does not match governance input")
        if self.shadow.challenger_registry_fingerprint != challenger_id:
            raise ValueError("shadow evidence challenger does not match registered candidate")
        if self.challenger_monitoring.registry_fingerprint != challenger_id:
            raise ValueError("monitoring evidence does not match registered challenger")
        if self.shadow.promotion_allowed or self.shadow.can_authorize_capital:
            raise ValueError("shadow evidence cannot carry promotion or capital authority")
        if (
            self.challenger_monitoring.promotion_allowed
            or self.challenger_monitoring.can_authorize_capital
        ):
            raise ValueError("monitoring evidence cannot carry promotion or capital authority")
        if (
            self.challenger_candidate.promotion_allowed
            or self.challenger_candidate.can_authorize_capital
        ):
            raise ValueError("registered candidate cannot carry promotion or capital authority")
        return self


def evaluate_promotion_governance(
    *,
    champion_registry_fingerprint: str,
    challenger_candidate: ModelGovernanceCandidate,
    shadow: ShadowComparisonEvidence,
    challenger_monitoring: ModelMonitoringReport,
    config: PromotionGovernanceConfig | None = None,
) -> PromotionGovernanceDecision:
    cfg = config or PromotionGovernanceConfig()
    inputs = PromotionInputs(
        champion_registry_fingerprint=champion_registry_fingerprint,
        challenger_candidate=challenger_candidate,
        shadow=shadow,
        challenger_monitoring=challenger_monitoring,
    )

    champion = shadow.champion
    challenger = shadow.challenger
    reasons: list[str] = []

    if champion.rows < cfg.minimum_shadow_records or challenger.rows < cfg.minimum_shadow_records:
        reasons.append("INSUFFICIENT_SHADOW_EVIDENCE")
    if shadow.assessment != "CHALLENGER_ELIGIBLE_FOR_GOVERNANCE_REVIEW":
        reasons.append("SHADOW_NOT_ELIGIBLE_FOR_PROMOTION_REVIEW")
    if challenger_monitoring.assessment != "STABLE":
        reasons.append(f"CHALLENGER_MONITORING_{challenger_monitoring.assessment}")

    auc_improvement = challenger.roc_auc - champion.roc_auc
    brier_improvement = champion.brier_score - challenger.brier_score
    log_loss_regression = challenger.log_loss - champion.log_loss
    ece_regression = (
        challenger.expected_calibration_error - champion.expected_calibration_error
    )

    if auc_improvement < cfg.minimum_auc_improvement:
        reasons.append("AUC_IMPROVEMENT_GATE_FAILED")
    if brier_improvement < cfg.minimum_brier_improvement:
        reasons.append("BRIER_IMPROVEMENT_GATE_FAILED")
    if log_loss_regression > cfg.maximum_log_loss_regression:
        reasons.append("LOG_LOSS_REGRESSION_GATE_FAILED")
    if ece_regression > cfg.maximum_ece_regression:
        reasons.append("ECE_REGRESSION_GATE_FAILED")

    if reasons:
        decision = "HOLD_CHAMPION"
        recommended_registry_fingerprint = inputs.champion_registry_fingerprint
        required_next_gate = "V2_09_CONTINUE_PAPER_EVALUATION"
        reason_codes = sorted(set(reasons))
    else:
        decision = "PROMOTION_RECOMMENDED"
        recommended_registry_fingerprint = challenger_candidate.registry_fingerprint
        required_next_gate = "V2_09_EXPLICIT_ACTIVATION_APPROVAL"
        reason_codes = ["ALL_OBJECTIVE_PROMOTION_GATES_PASSED"]

    payload = {
        "contract_version": PROMOTION_GOVERNANCE_VERSION,
        "config_fingerprint": cfg.fingerprint,
        "champion_registry_fingerprint": inputs.champion_registry_fingerprint,
        "challenger_registry_fingerprint": challenger_candidate.registry_fingerprint,
        "shadow_evidence_fingerprint": shadow.evidence_fingerprint,
        "monitoring_report_fingerprint": challenger_monitoring.report_fingerprint,
        "decision": decision,
        "reason_codes": reason_codes,
        "recommended_registry_fingerprint": recommended_registry_fingerprint,
        "rollback_registry_fingerprint": inputs.champion_registry_fingerprint,
        "required_next_gate": required_next_gate,
        "activation_requires_explicit_approval": True,
        "activation_allowed": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return PromotionGovernanceDecision(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )


def evaluate_rollback_governance(
    *,
    active_registry_fingerprint: str,
    last_accepted_registry_fingerprint: str,
    active_monitoring: ModelMonitoringReport,
) -> RollbackGovernanceDecision:
    if active_registry_fingerprint == last_accepted_registry_fingerprint:
        raise ValueError("rollback target must differ from the active model")
    if active_monitoring.registry_fingerprint != active_registry_fingerprint:
        raise ValueError("rollback monitoring evidence must match the active model")
    if active_monitoring.promotion_allowed or active_monitoring.can_authorize_capital:
        raise ValueError("monitoring evidence cannot carry promotion or capital authority")

    if active_monitoring.assessment == "RETRAINING_RECOMMENDED":
        decision = "ROLLBACK_RECOMMENDED"
        reason_codes = ["ACTIVE_MODEL_SEVERE_MONITORING_DEGRADATION"]
        recommended_registry_fingerprint = last_accepted_registry_fingerprint
        required_next_gate = "V2_09_EXPLICIT_ROLLBACK_APPROVAL"
    else:
        decision = "HOLD_ACTIVE"
        reason_codes = [f"ACTIVE_MODEL_MONITORING_{active_monitoring.assessment}"]
        recommended_registry_fingerprint = active_registry_fingerprint
        required_next_gate = "V2_09_CONTINUE_PAPER_MONITORING"

    payload = {
        "contract_version": PROMOTION_GOVERNANCE_VERSION,
        "active_registry_fingerprint": active_registry_fingerprint,
        "rollback_registry_fingerprint": last_accepted_registry_fingerprint,
        "monitoring_report_fingerprint": active_monitoring.report_fingerprint,
        "decision": decision,
        "reason_codes": reason_codes,
        "recommended_registry_fingerprint": recommended_registry_fingerprint,
        "required_next_gate": required_next_gate,
        "activation_requires_explicit_approval": True,
        "activation_allowed": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return RollbackGovernanceDecision(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )
