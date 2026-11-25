from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .ensemble_uncertainty import EnsembleUncertaintyEvidence
from .fingerprints import canonical_fingerprint
from .fusion import RankedOpportunity

SELECTIVE_POLICY_VERSION = "selective-policy.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
PolicyDecision = Literal["TRADE", "ABSTAIN"]
SelectiveState = Literal["CANDIDATE", "ABSTAIN"]


class SelectivePolicyConfig(BaseModel):
    """Conservative eligibility thresholds; this policy never authorizes an order."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["selective-policy.v1"] = SELECTIVE_POLICY_VERSION
    minimum_calibrated_probability: Annotated[float, Field(gt=0.5, lt=1.0)] = 0.55
    maximum_uncertainty_score: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.35
    minimum_expert_coverage: Annotated[float, Field(gt=0.0, le=1.0)] = 1.0
    minimum_fusion_score: Annotated[float, Field(ge=-1.0, le=1.0)] = 0.35
    minimum_net_edge_bps: Annotated[float, Field(ge=0.0, le=10_000.0)] = 10.0
    maximum_uncertainty_age_seconds: Annotated[int, Field(ge=1, le=86_400)] = 900
    require_complete_ensemble_evidence: bool = True

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class SelectiveDecisionV1(BaseModel):
    """Research eligibility only: TRADE means candidate-for-next-gate, never executable."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["selective-policy.v1"] = SELECTIVE_POLICY_VERSION
    symbol: Annotated[str, Field(min_length=1, max_length=64)]
    decision_as_of: datetime
    policy_config_fingerprint: Hash64
    source_opportunity_fingerprint: Hash64
    source_uncertainty_fingerprint: Hash64
    source_uncertainty_age_seconds: Annotated[int, Field(ge=0)]
    calibrated_probability: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    uncertainty_score: Annotated[float, Field(ge=0.0, le=1.0)]
    uncertainty_level: Literal["LOW", "MEDIUM", "HIGH"]
    evidence_quality: Literal["COMPLETE", "DEGRADED", "INSUFFICIENT"]
    expert_coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    fused_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    expected_gross_edge_bps: float
    estimated_round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=10_000.0)]
    expected_net_edge_bps: float
    policy_decision: PolicyDecision
    state: SelectiveState
    reason_codes: list[str] = Field(min_length=1, max_length=64)
    required_next_gate: Literal["V3_05_COVERAGE_EXPECTANCY_EVALUATION"] = (
        "V3_05_COVERAGE_EXPECTANCY_EVALUATION"
    )
    status: Literal["RESEARCH_ELIGIBILITY_ONLY"] = "RESEARCH_ELIGIBILITY_ONLY"
    decision_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_as_of")
    @classmethod
    def decision_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("selective policy decision_as_of must be timezone-aware")
        return value

    @field_validator("expected_gross_edge_bps", "expected_net_edge_bps")
    @classmethod
    def edge_values_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("selective policy edge values must be finite")
        return value

    @model_validator(mode="after")
    def decision_and_state_must_agree(self) -> SelectiveDecisionV1:
        expected_state = "CANDIDATE" if self.policy_decision == "TRADE" else "ABSTAIN"
        if self.state != expected_state:
            raise ValueError("selective decision state must match policy_decision")
        expected_net_edge = self.expected_gross_edge_bps - self.estimated_round_trip_cost_bps
        if not math.isclose(
            self.expected_net_edge_bps,
            expected_net_edge,
            rel_tol=0.0,
            abs_tol=1e-9,
        ):
            raise ValueError("expected_net_edge_bps must equal gross edge minus costs")
        return self


def evaluate_selective_policy(
    opportunity: RankedOpportunity,
    uncertainty: EnsembleUncertaintyEvidence,
    *,
    expected_gross_edge_bps: float,
    estimated_round_trip_cost_bps: float,
    decision_as_of: datetime | None = None,
    config: SelectivePolicyConfig | None = None,
) -> SelectiveDecisionV1:
    cfg = config or SelectivePolicyConfig()
    validated_opportunity = _validate_source_opportunity(opportunity)
    validated_uncertainty = _validate_source_uncertainty(uncertainty)

    if not math.isfinite(expected_gross_edge_bps):
        raise ValueError("expected_gross_edge_bps must be finite")
    if not math.isfinite(estimated_round_trip_cost_bps):
        raise ValueError("estimated_round_trip_cost_bps must be finite")
    if estimated_round_trip_cost_bps < 0.0:
        raise ValueError("estimated_round_trip_cost_bps cannot be negative")
    if estimated_round_trip_cost_bps > 10_000.0:
        raise ValueError("estimated_round_trip_cost_bps exceeds bounded policy input")
    if validated_opportunity.symbol != validated_uncertainty.symbol:
        raise ValueError("opportunity and uncertainty evidence must share one symbol")

    effective_as_of = decision_as_of or max(
        validated_opportunity.as_of,
        validated_uncertainty.assessment_as_of,
    )
    if effective_as_of.tzinfo is None or effective_as_of.utcoffset() is None:
        raise ValueError("decision_as_of must be timezone-aware")
    if validated_opportunity.as_of > effective_as_of:
        raise ValueError("selective policy cannot consume future opportunity evidence")
    if validated_opportunity.data_cutoff > effective_as_of:
        raise ValueError("selective policy cannot consume future opportunity data cutoff")
    if validated_uncertainty.assessment_as_of > effective_as_of:
        raise ValueError("selective policy cannot consume future uncertainty evidence")

    uncertainty_age_seconds = max(
        0,
        int((effective_as_of - validated_uncertainty.assessment_as_of).total_seconds()),
    )
    expected_net_edge_bps = expected_gross_edge_bps - estimated_round_trip_cost_bps
    probability = validated_uncertainty.probability_mean

    blockers: list[str] = []
    if validated_opportunity.action != "BUY":
        blockers.append("FUSION_NOT_BUY")
    if validated_opportunity.fused_score < cfg.minimum_fusion_score:
        blockers.append("FUSION_SCORE_BELOW_POLICY_THRESHOLD")
    if probability is None:
        blockers.append("CALIBRATED_PROBABILITY_UNAVAILABLE")
    elif probability < cfg.minimum_calibrated_probability:
        blockers.append("CALIBRATED_PROBABILITY_BELOW_THRESHOLD")
    if uncertainty_age_seconds > cfg.maximum_uncertainty_age_seconds:
        blockers.append("UNCERTAINTY_EVIDENCE_STALE")
    if validated_uncertainty.uncertainty_score >= cfg.maximum_uncertainty_score:
        blockers.append("UNCERTAINTY_ABOVE_THRESHOLD")
    if validated_uncertainty.uncertainty_level == "HIGH":
        blockers.append("HIGH_UNCERTAINTY")
    if validated_uncertainty.expert_coverage < cfg.minimum_expert_coverage:
        blockers.append("EXPERT_COVERAGE_BELOW_THRESHOLD")
    if (
        cfg.require_complete_ensemble_evidence
        and validated_uncertainty.evidence_quality != "COMPLETE"
    ):
        blockers.append(f"ENSEMBLE_EVIDENCE_{validated_uncertainty.evidence_quality}")
    if validated_uncertainty.has_missing_expert:
        blockers.append("ENSEMBLE_HAS_MISSING_EXPERT")
    if validated_uncertainty.has_stale_expert:
        blockers.append("ENSEMBLE_HAS_STALE_EXPERT")
    if validated_uncertainty.has_abstaining_expert:
        blockers.append("ENSEMBLE_HAS_ABSTAINING_EXPERT")
    if validated_uncertainty.has_out_of_domain_expert:
        blockers.append("ENSEMBLE_HAS_OUT_OF_DOMAIN_EXPERT")
    if validated_uncertainty.has_insufficient_calibration_expert:
        blockers.append("ENSEMBLE_HAS_INSUFFICIENT_CALIBRATION")
    if expected_net_edge_bps < cfg.minimum_net_edge_bps:
        blockers.append("NET_EDGE_BELOW_THRESHOLD")

    reason_codes = sorted(set(blockers))
    if reason_codes:
        policy_decision: PolicyDecision = "ABSTAIN"
        state: SelectiveState = "ABSTAIN"
    else:
        policy_decision = "TRADE"
        state = "CANDIDATE"
        reason_codes = ["SELECTIVE_POLICY_CANDIDATE"]

    payload = {
        "contract_version": SELECTIVE_POLICY_VERSION,
        "symbol": validated_opportunity.symbol,
        "decision_as_of": effective_as_of.isoformat(),
        "policy_config_fingerprint": cfg.fingerprint,
        "source_opportunity_fingerprint": validated_opportunity.opportunity_fingerprint,
        "source_uncertainty_fingerprint": validated_uncertainty.evidence_fingerprint,
        "source_uncertainty_age_seconds": uncertainty_age_seconds,
        "calibrated_probability": probability,
        "uncertainty_score": validated_uncertainty.uncertainty_score,
        "uncertainty_level": validated_uncertainty.uncertainty_level,
        "evidence_quality": validated_uncertainty.evidence_quality,
        "expert_coverage": validated_uncertainty.expert_coverage,
        "fused_score": validated_opportunity.fused_score,
        "expected_gross_edge_bps": expected_gross_edge_bps,
        "estimated_round_trip_cost_bps": estimated_round_trip_cost_bps,
        "expected_net_edge_bps": expected_net_edge_bps,
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


def _validate_source_opportunity(item: RankedOpportunity) -> RankedOpportunity:
    validated = RankedOpportunity.model_validate(item.model_dump(mode="python"))
    payload = validated.model_dump(mode="json", exclude={"opportunity_fingerprint"})
    payload["as_of"] = validated.as_of.isoformat()
    payload["data_cutoff"] = validated.data_cutoff.isoformat()
    expected = canonical_fingerprint(payload)
    if validated.opportunity_fingerprint != expected:
        raise ValueError("ranked opportunity fingerprint mismatch")
    if validated.can_authorize_capital:
        raise ValueError("ranked opportunity cannot carry capital authority")
    return validated


def _validate_source_uncertainty(
    item: EnsembleUncertaintyEvidence,
) -> EnsembleUncertaintyEvidence:
    validated = EnsembleUncertaintyEvidence.model_validate(item.model_dump(mode="python"))
    payload = validated.model_dump(mode="python", exclude={"evidence_fingerprint"})
    payload["assessment_as_of"] = validated.assessment_as_of.isoformat()
    expected = canonical_fingerprint(payload)
    if validated.evidence_fingerprint != expected:
        raise ValueError("ensemble uncertainty fingerprint mismatch")
    if validated.promotion_allowed or validated.can_authorize_capital:
        raise ValueError("uncertainty evidence cannot carry promotion or capital authority")
    return validated
