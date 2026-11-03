from __future__ import annotations

import math
from collections.abc import Mapping
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .adaptive_fusion import (
    STATIC_CHAMPION_WEIGHTS,
    AdaptiveFusionDecisionV1,
    AdaptiveFusionPolicy,
    fusion_request_fingerprint,
)
from .fingerprints import canonical_fingerprint
from .fusion import (
    FUSION_VERSION,
    V2_CHAMPION_FUSION_POLICY,
    FusionEvidence,
    FusionFamily,
    FusionRequest,
    RankedOpportunity,
    fuse_signals,
)
from .regime_fusion_policy import FUSION_FAMILIES

FUSION_DISAGREEMENT_CONTROL_VERSION = "fusion-disagreement-control.v1"
DISAGREEMENT_CONTROLLED_OPPORTUNITY_VERSION = "disagreement-controlled-opportunity.v1"
DISAGREEMENT_CONTROLLED_POLICY_ID = "v3-disagreement-controlled-policy-v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
GuardedAction = Literal["BUY", "WATCH"]


class FusionDisagreementControlConfig(BaseModel):
    """Bounded manual research controls for expert disagreement."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["fusion-disagreement-control.v1"] = (
        FUSION_DISAGREEMENT_CONTROL_VERSION
    )
    required_positive_families: tuple[FusionFamily, ...] = (
        "DETERMINISTIC",
        "QUANT",
    )
    minimum_fresh_families: Annotated[int, Field(ge=2, le=3)] = 2
    neutral_score_tolerance: Annotated[float, Field(ge=0.0, le=0.10)] = 1e-9
    penalty_threshold: Annotated[float, Field(ge=0.0, lt=1.0)] = 0.30
    veto_threshold: Annotated[float, Field(gt=0.0, le=1.0)] = 0.70
    maximum_weight_penalty: Annotated[float, Field(ge=0.0, le=0.75)] = 0.50

    @model_validator(mode="after")
    def validate_config(self) -> FusionDisagreementControlConfig:
        if tuple(dict.fromkeys(self.required_positive_families)) != (
            self.required_positive_families
        ):
            raise ValueError("required disagreement families must be unique")
        if any(family not in FUSION_FAMILIES for family in self.required_positive_families):
            raise ValueError("required disagreement families must be supported fusion families")
        if self.penalty_threshold >= self.veto_threshold:
            raise ValueError("disagreement penalty threshold must be below veto threshold")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class FusionDisagreementControlV1(BaseModel):
    """Request-bound disagreement penalty/veto evidence; never capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["fusion-disagreement-control.v1"] = (
        FUSION_DISAGREEMENT_CONTROL_VERSION
    )
    fusion_version: Literal["signal-fusion.v1"] = FUSION_VERSION
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    assessed_at: datetime
    source_request_fingerprint: Hash64
    source_adaptive_decision_fingerprint: Hash64
    config_fingerprint: Hash64
    present_families: list[FusionFamily]
    fresh_families: list[FusionFamily]
    stale_families: list[FusionFamily]
    missing_families: list[FusionFamily]
    score_by_family: dict[str, float]
    confidence_by_family: dict[str, float]
    consensus_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    normalized_score_spread: Annotated[float, Field(ge=0.0, le=1.0)]
    normalized_family_deviation: dict[str, float]
    normalized_disagreement_score: Annotated[float, Field(ge=0.0, le=1.0)]
    required_family_sign_conflict: bool
    insufficient_fresh_evidence: bool
    required_family_stale: bool
    family_penalty_multipliers: dict[str, float]
    adaptive_weights: dict[str, float]
    penalized_unnormalized_weights: dict[str, float]
    controlled_weights: dict[str, float]
    penalty_applied: bool
    veto_buy: bool
    fallback_to_static: bool
    reason_codes: list[str] = Field(min_length=1, max_length=32)
    control_fingerprint: Hash64
    status: Literal["RESEARCH_DISAGREEMENT_CONTROL_ONLY"] = (
        "RESEARCH_DISAGREEMENT_CONTROL_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("assessed_at")
    @classmethod
    def assessed_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("disagreement assessed_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_control_arithmetic(self) -> FusionDisagreementControlV1:
        expected = tuple(FUSION_FAMILIES)
        for label, values in (
            ("family penalty", self.family_penalty_multipliers),
            ("adaptive", self.adaptive_weights),
            ("penalized", self.penalized_unnormalized_weights),
            ("controlled", self.controlled_weights),
        ):
            if tuple(values) != expected:
                raise ValueError(f"{label} weights must use canonical fusion family order")
            if any(not math.isfinite(value) or value < 0.0 for value in values.values()):
                raise ValueError(f"{label} weights must be finite and non-negative")

        expected_penalized = {
            family: self.adaptive_weights[family]
            * self.family_penalty_multipliers[family]
            for family in FUSION_FAMILIES
        }
        if any(
            not math.isclose(
                self.penalized_unnormalized_weights[family],
                expected_penalized[family],
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for family in FUSION_FAMILIES
        ):
            raise ValueError(
                "penalized weights must equal adaptive weight times family penalty"
            )

        total = sum(self.penalized_unnormalized_weights.values())
        if self.fallback_to_static:
            if total > 1e-12:
                raise ValueError("static disagreement fallback requires zero penalized weight")
            if any(
                not math.isclose(
                    self.controlled_weights[family],
                    STATIC_CHAMPION_WEIGHTS[family],
                    rel_tol=0.0,
                    abs_tol=1e-12,
                )
                for family in FUSION_FAMILIES
            ):
                raise ValueError("disagreement fallback must equal static champion weights")
        else:
            if total <= 0.0:
                raise ValueError("non-fallback disagreement control requires positive weight")
            if any(
                not math.isclose(
                    self.controlled_weights[family],
                    self.penalized_unnormalized_weights[family] / total,
                    rel_tol=0.0,
                    abs_tol=1e-12,
                )
                for family in FUSION_FAMILIES
            ):
                raise ValueError("controlled weights must normalize penalized weights")
        return self


class DisagreementControlledOpportunityV1(BaseModel):
    """Research-only opportunity after disagreement penalty/veto application."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["disagreement-controlled-opportunity.v1"] = (
        DISAGREEMENT_CONTROLLED_OPPORTUNITY_VERSION
    )
    symbol: str
    as_of: datetime
    weighted_action: GuardedAction
    guarded_action: GuardedAction
    veto_applied: bool
    fused_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    rank_score: Annotated[float, Field(ge=0.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    source_opportunity_fingerprint: Hash64
    source_control_fingerprint: Hash64
    reason_codes: list[str] = Field(min_length=1, max_length=64)
    guarded_fingerprint: Hash64
    status: Literal["RESEARCH_DISAGREEMENT_GUARDED_ONLY"] = (
        "RESEARCH_DISAGREEMENT_GUARDED_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("guarded opportunity as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_veto_semantics(self) -> DisagreementControlledOpportunityV1:
        if self.veto_applied and self.guarded_action != "WATCH":
            raise ValueError("disagreement veto must force WATCH")
        if not self.veto_applied and self.guarded_action != self.weighted_action:
            raise ValueError("non-veto guarded action must equal weighted action")
        return self


def build_fusion_disagreement_control(
    request: FusionRequest,
    adaptive_decision: AdaptiveFusionDecisionV1,
    *,
    config: FusionDisagreementControlConfig | None = None,
) -> FusionDisagreementControlV1:
    cfg = config or FusionDisagreementControlConfig()
    adaptive_policy = AdaptiveFusionPolicy(adaptive_decision)
    adaptive_weights = dict(adaptive_policy.configured_weights(request))

    evidence_by_family: dict[FusionFamily, FusionEvidence | None] = {
        "DETERMINISTIC": request.deterministic,
        "QUANT": request.quant,
        "GEMINI": request.gemini,
    }
    present_families = [
        family for family in FUSION_FAMILIES if evidence_by_family[family] is not None
    ]
    missing_families = [
        family for family in FUSION_FAMILIES if evidence_by_family[family] is None
    ]
    stale_families = [
        family
        for family in present_families
        if _age_seconds(request, evidence_by_family[family]) > request.max_age_seconds
    ]
    fresh_families = [
        family for family in present_families if family not in stale_families
    ]

    score_by_family = {
        family: float(evidence_by_family[family].score)
        for family in present_families
        if evidence_by_family[family] is not None
    }
    confidence_by_family = {
        family: float(evidence_by_family[family].confidence)
        for family in present_families
        if evidence_by_family[family] is not None
    }

    consensus_denominator = sum(
        adaptive_weights[family] * confidence_by_family[family]
        for family in fresh_families
    )
    consensus_score = (
        sum(
            score_by_family[family]
            * adaptive_weights[family]
            * confidence_by_family[family]
            for family in fresh_families
        )
        / consensus_denominator
        if consensus_denominator > 0.0
        else 0.0
    )
    deviations = {
        family: (
            min(1.0, abs(score_by_family[family] - consensus_score) / 2.0)
            if family in fresh_families
            else 1.0
        )
        for family in FUSION_FAMILIES
    }
    fresh_scores = [score_by_family[family] for family in fresh_families]
    normalized_spread = (
        (max(fresh_scores) - min(fresh_scores)) / 2.0
        if len(fresh_scores) >= 2
        else 1.0
    )
    disagreement_score = max(
        normalized_spread,
        max((deviations[family] for family in fresh_families), default=1.0),
    )

    required_signs = {
        family: _direction(
            score_by_family.get(family),
            cfg.neutral_score_tolerance,
        )
        for family in cfg.required_positive_families
        if family in fresh_families
    }
    required_sign_conflict = (
        1 in required_signs.values() and -1 in required_signs.values()
    )
    required_stale = any(
        family in stale_families for family in cfg.required_positive_families
    )
    insufficient_fresh = len(fresh_families) < cfg.minimum_fresh_families

    penalty_applied = disagreement_score >= cfg.penalty_threshold
    family_penalties: dict[FusionFamily, float] = {}
    for family in FUSION_FAMILIES:
        if family not in fresh_families:
            family_penalties[family] = 0.0
        elif penalty_applied:
            family_penalties[family] = max(
                0.0,
                1.0 - cfg.maximum_weight_penalty * deviations[family],
            )
        else:
            family_penalties[family] = 1.0

    penalized = {
        family: adaptive_weights[family] * family_penalties[family]
        for family in FUSION_FAMILIES
    }
    penalized_total = sum(penalized.values())
    fallback_to_static = penalized_total <= 0.0
    controlled_weights = (
        dict(STATIC_CHAMPION_WEIGHTS)
        if fallback_to_static
        else {
            family: penalized[family] / penalized_total
            for family in FUSION_FAMILIES
        }
    )

    severe_disagreement = disagreement_score >= cfg.veto_threshold
    veto_buy = bool(
        required_sign_conflict
        or required_stale
        or insufficient_fresh
        or severe_disagreement
        or fallback_to_static
    )

    reasons: list[str] = []
    reasons.extend(f"MISSING_FAMILY_{family}" for family in missing_families)
    reasons.extend(f"STALE_FAMILY_{family}" for family in stale_families)
    if penalty_applied:
        reasons.append("DISAGREEMENT_WEIGHT_PENALTY_APPLIED")
    else:
        reasons.append("DISAGREEMENT_BELOW_PENALTY_THRESHOLD")
    if required_sign_conflict:
        reasons.append("REQUIRED_FAMILY_DIRECTION_CONFLICT")
    if required_stale:
        reasons.append("REQUIRED_FAMILY_STALE_VETO")
    if insufficient_fresh:
        reasons.append("INSUFFICIENT_FRESH_FUSION_EVIDENCE")
    if severe_disagreement:
        reasons.append("SEVERE_DISAGREEMENT_VETO")
    if fallback_to_static:
        reasons.append("DISAGREEMENT_CONTROL_STATIC_FALLBACK")
    if veto_buy:
        reasons.append("DISAGREEMENT_BUY_VETO")
    if not reasons:
        reasons.append("DISAGREEMENT_CONTROL_CLEAR")

    payload = {
        "contract_version": FUSION_DISAGREEMENT_CONTROL_VERSION,
        "fusion_version": FUSION_VERSION,
        "symbol": request.symbol,
        "assessed_at": request.as_of.isoformat(),
        "source_request_fingerprint": fusion_request_fingerprint(request),
        "source_adaptive_decision_fingerprint": adaptive_decision.decision_fingerprint,
        "config_fingerprint": cfg.fingerprint,
        "present_families": present_families,
        "fresh_families": fresh_families,
        "stale_families": stale_families,
        "missing_families": missing_families,
        "score_by_family": score_by_family,
        "confidence_by_family": confidence_by_family,
        "consensus_score": consensus_score,
        "normalized_score_spread": normalized_spread,
        "normalized_family_deviation": deviations,
        "normalized_disagreement_score": disagreement_score,
        "required_family_sign_conflict": required_sign_conflict,
        "insufficient_fresh_evidence": insufficient_fresh,
        "required_family_stale": required_stale,
        "family_penalty_multipliers": family_penalties,
        "adaptive_weights": adaptive_weights,
        "penalized_unnormalized_weights": penalized,
        "controlled_weights": controlled_weights,
        "penalty_applied": penalty_applied,
        "veto_buy": veto_buy,
        "fallback_to_static": fallback_to_static,
        "reason_codes": sorted(set(reasons)),
        "status": "RESEARCH_DISAGREEMENT_CONTROL_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return FusionDisagreementControlV1(
        **payload,
        control_fingerprint=canonical_fingerprint(payload),
    )


class DisagreementControlledFusionPolicy:
    """Request-bound research policy using disagreement-controlled weights."""

    policy_id = DISAGREEMENT_CONTROLLED_POLICY_ID

    def __init__(
        self,
        adaptive_decision: AdaptiveFusionDecisionV1,
        control: FusionDisagreementControlV1,
    ) -> None:
        self.adaptive_decision = AdaptiveFusionPolicy(adaptive_decision).decision
        self.control = _validate_control(control)

    def configured_weights(self, request: FusionRequest) -> Mapping[FusionFamily, float]:
        self._validate_binding(request)
        return {
            family: self.control.controlled_weights[family]
            for family in FUSION_FAMILIES
        }

    def required_positive_families(
        self,
        request: FusionRequest,
    ) -> tuple[FusionFamily, ...]:
        self._validate_binding(request)
        return V2_CHAMPION_FUSION_POLICY.required_positive_families(request)

    def buy_threshold(self, request: FusionRequest) -> float:
        self._validate_binding(request)
        return V2_CHAMPION_FUSION_POLICY.buy_threshold(request)

    def _validate_binding(self, request: FusionRequest) -> None:
        request_fingerprint = fusion_request_fingerprint(request)
        if request_fingerprint != self.control.source_request_fingerprint:
            raise ValueError("disagreement control request fingerprint mismatch")
        if (
            self.adaptive_decision.decision_fingerprint
            != self.control.source_adaptive_decision_fingerprint
        ):
            raise ValueError("disagreement control adaptive decision fingerprint mismatch")


def fuse_with_disagreement_control(
    request: FusionRequest,
    adaptive_decision: AdaptiveFusionDecisionV1,
    control: FusionDisagreementControlV1,
) -> DisagreementControlledOpportunityV1:
    policy = DisagreementControlledFusionPolicy(adaptive_decision, control)
    opportunity = fuse_signals(request, policy=policy)
    guarded_action: GuardedAction = (
        "WATCH" if control.veto_buy else opportunity.action
    )
    reasons = sorted(
        set(
            opportunity.reason_codes
            + control.reason_codes
            + (["DISAGREEMENT_BUY_VETO_APPLIED"] if control.veto_buy else [])
        )
    )
    payload = {
        "contract_version": DISAGREEMENT_CONTROLLED_OPPORTUNITY_VERSION,
        "symbol": opportunity.symbol,
        "as_of": opportunity.as_of.isoformat(),
        "weighted_action": opportunity.action,
        "guarded_action": guarded_action,
        "veto_applied": control.veto_buy,
        "fused_score": opportunity.fused_score,
        "rank_score": opportunity.rank_score,
        "confidence": opportunity.confidence,
        "source_opportunity_fingerprint": opportunity.opportunity_fingerprint,
        "source_control_fingerprint": control.control_fingerprint,
        "reason_codes": reasons,
        "status": "RESEARCH_DISAGREEMENT_GUARDED_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return DisagreementControlledOpportunityV1(
        **payload,
        guarded_fingerprint=canonical_fingerprint(payload),
    )


def _age_seconds(
    request: FusionRequest,
    evidence: FusionEvidence | None,
) -> int:
    if evidence is None:
        return request.max_age_seconds + 1
    return max(0, int((request.as_of - evidence.observed_at).total_seconds()))


def _direction(score: float | None, tolerance: float) -> int:
    if score is None or abs(score) <= tolerance:
        return 0
    return 1 if score > 0.0 else -1


def _validate_control(
    item: FusionDisagreementControlV1,
) -> FusionDisagreementControlV1:
    checked = FusionDisagreementControlV1.model_validate(item.model_dump(mode="python"))
    payload = checked.model_dump(mode="python", exclude={"control_fingerprint"})
    payload["assessed_at"] = checked.assessed_at.isoformat()
    if checked.control_fingerprint != canonical_fingerprint(payload):
        raise ValueError("fusion disagreement control fingerprint mismatch")
    if checked.promotion_allowed or checked.can_authorize_capital:
        raise ValueError("fusion disagreement control cannot carry promotion or capital authority")
    return checked
