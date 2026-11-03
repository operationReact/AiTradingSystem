from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .fusion import (
    FUSION_VERSION,
    V2_CHAMPION_FUSION_POLICY,
    FusionFamily,
    FusionRequest,
)
from .fusion_quality_multipliers import FusionExpertQualityMultiplierV1
from .regime_fusion_policy import (
    FUSION_FAMILIES,
    RegimeConditionedManualFusionPolicy,
)

ADAPTIVE_FUSION_DECISION_VERSION = "adaptive-fusion-decision.v1"
ADAPTIVE_FUSION_POLICY_ID = "v3-adaptive-fusion-decision-v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
FallbackReason = Literal["NONE", "QUALITY_COLLAPSE_STATIC_FALLBACK"]
STATIC_CHAMPION_WEIGHTS: dict[FusionFamily, float] = {
    "DETERMINISTIC": 0.45,
    "QUANT": 0.35,
    "GEMINI": 0.20,
}


class AdaptiveFusionDecisionV1(BaseModel):
    """Reconstructable research weight decision; never execution or capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["adaptive-fusion-decision.v1"] = (
        ADAPTIVE_FUSION_DECISION_VERSION
    )
    fusion_version: Literal["signal-fusion.v1"] = FUSION_VERSION
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    decision_at: datetime
    source_request_fingerprint: Hash64
    base_policy_id: str
    base_policy_config_fingerprint: Hash64
    source_regime_fingerprint: Hash64 | None = None
    base_weights: dict[str, float]
    quality_multipliers: dict[str, float]
    unnormalized_weights: dict[str, float]
    effective_weights: dict[str, float]
    source_quality_multiplier_fingerprints: dict[str, Hash64]
    fallback_to_static: bool
    fallback_reason: FallbackReason
    reason_codes: list[str] = Field(min_length=1, max_length=24)
    decision_fingerprint: Hash64
    status: Literal["RESEARCH_ADAPTIVE_FUSION_ONLY"] = (
        "RESEARCH_ADAPTIVE_FUSION_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_at")
    @classmethod
    def decision_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("adaptive fusion decision_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_weight_contract(self) -> AdaptiveFusionDecisionV1:
        expected = tuple(FUSION_FAMILIES)
        for label, values in (
            ("base", self.base_weights),
            ("quality multiplier", self.quality_multipliers),
            ("unnormalized", self.unnormalized_weights),
            ("effective", self.effective_weights),
        ):
            if tuple(values) != expected:
                raise ValueError(
                    f"{label} weights must use canonical ordered fusion families"
                )
            if any(
                not math.isfinite(value) or value < 0.0
                for value in values.values()
            ):
                raise ValueError(f"{label} weights must be finite and non-negative")

        if not math.isclose(
            sum(self.base_weights.values()),
            1.0,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("adaptive base weights must sum to 1")
        if not math.isclose(
            sum(self.effective_weights.values()),
            1.0,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("adaptive effective weights must sum to 1")

        expected_unnormalized = {
            family: self.base_weights[family] * self.quality_multipliers[family]
            for family in FUSION_FAMILIES
        }
        if any(
            not math.isclose(
                self.unnormalized_weights[family],
                expected_unnormalized[family],
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for family in FUSION_FAMILIES
        ):
            raise ValueError(
                "unnormalized weights must equal base weight times quality multiplier"
            )

        source_families = set(self.source_quality_multiplier_fingerprints)
        if not source_families.issubset(set(FUSION_FAMILIES)):
            raise ValueError("quality multiplier fingerprints contain unsupported families")
        for family in FUSION_FAMILIES:
            if (
                not math.isclose(
                    self.quality_multipliers[family],
                    1.0,
                    rel_tol=0.0,
                    abs_tol=1e-12,
                )
                and family not in source_families
            ):
                raise ValueError(
                    "non-neutral quality multipliers require a source fingerprint"
                )

        total = sum(self.unnormalized_weights.values())
        if self.fallback_to_static:
            if self.fallback_reason != "QUALITY_COLLAPSE_STATIC_FALLBACK":
                raise ValueError("static fallback must identify quality collapse")
            if total > 1e-12:
                raise ValueError("quality-collapse fallback requires zero adaptive weight")
            if any(
                not math.isclose(
                    self.effective_weights[family],
                    STATIC_CHAMPION_WEIGHTS[family],
                    rel_tol=0.0,
                    abs_tol=1e-12,
                )
                for family in FUSION_FAMILIES
            ):
                raise ValueError("fallback effective weights must equal static champion")
        else:
            if self.fallback_reason != "NONE":
                raise ValueError("non-fallback adaptive decision must use NONE reason")
            if total <= 0.0:
                raise ValueError("adaptive decision requires positive total weight")
            if any(
                not math.isclose(
                    self.effective_weights[family],
                    self.unnormalized_weights[family] / total,
                    rel_tol=0.0,
                    abs_tol=1e-12,
                )
                for family in FUSION_FAMILIES
            ):
                raise ValueError(
                    "effective weights must normalize the unnormalized adaptive weights"
                )
        return self


def fusion_request_fingerprint(request: FusionRequest) -> str:
    return canonical_fingerprint(request.model_dump(mode="json"))


def build_adaptive_fusion_decision(
    request: FusionRequest,
    quality_evidence: Sequence[FusionExpertQualityMultiplierV1],
    *,
    base_policy: RegimeConditionedManualFusionPolicy | None = None,
) -> AdaptiveFusionDecisionV1:
    """Combine manual regime weights with exact V3-06.3 multiplier evidence."""

    policy = base_policy or RegimeConditionedManualFusionPolicy()
    explanation = policy.explain(request)
    base_weights = {
        family: explanation.configured_weights[family] for family in FUSION_FAMILIES
    }

    by_family: dict[FusionFamily, FusionExpertQualityMultiplierV1] = {}
    for item in quality_evidence:
        checked = _validate_quality_multiplier(item)
        if checked.family in by_family:
            raise ValueError("adaptive fusion quality evidence must be unique by family")
        if checked.evaluated_at > request.as_of:
            raise ValueError("quality multiplier evidence cannot postdate fusion request")
        by_family[checked.family] = checked

    quality_multipliers: dict[FusionFamily, float] = {}
    source_fingerprints: dict[str, str] = {}
    reasons: list[str] = []
    for family in FUSION_FAMILIES:
        evidence = by_family.get(family)
        if evidence is None:
            quality_multipliers[family] = 1.0
            reasons.append(f"{family}_QUALITY_EVIDENCE_MISSING_NEUTRAL")
        else:
            quality_multipliers[family] = evidence.combined_multiplier
            source_fingerprints[family] = evidence.multiplier_fingerprint
            reasons.append(f"{family}_QUALITY_MULTIPLIER_APPLIED")

    unnormalized = {
        family: base_weights[family] * quality_multipliers[family]
        for family in FUSION_FAMILIES
    }
    total = sum(unnormalized.values())
    fallback_to_static = total <= 0.0
    if fallback_to_static:
        effective_weights = dict(STATIC_CHAMPION_WEIGHTS)
        fallback_reason: FallbackReason = "QUALITY_COLLAPSE_STATIC_FALLBACK"
        reasons.append("QUALITY_COLLAPSE_STATIC_FALLBACK")
    else:
        effective_weights = {
            family: unnormalized[family] / total for family in FUSION_FAMILIES
        }
        fallback_reason = "NONE"
        reasons.append("ADAPTIVE_WEIGHTS_RECONSTRUCTED")

    payload = {
        "contract_version": ADAPTIVE_FUSION_DECISION_VERSION,
        "fusion_version": FUSION_VERSION,
        "symbol": request.symbol,
        "decision_at": request.as_of.isoformat(),
        "source_request_fingerprint": fusion_request_fingerprint(request),
        "base_policy_id": policy.policy_id,
        "base_policy_config_fingerprint": explanation.config_fingerprint,
        "source_regime_fingerprint": explanation.source_regime_fingerprint,
        "base_weights": base_weights,
        "quality_multipliers": quality_multipliers,
        "unnormalized_weights": unnormalized,
        "effective_weights": effective_weights,
        "source_quality_multiplier_fingerprints": source_fingerprints,
        "fallback_to_static": fallback_to_static,
        "fallback_reason": fallback_reason,
        "reason_codes": sorted(set(reasons)),
        "status": "RESEARCH_ADAPTIVE_FUSION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return AdaptiveFusionDecisionV1(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )


class AdaptiveFusionPolicy:
    """Request-bound policy adapter backed by immutable adaptive decision evidence."""

    policy_id = ADAPTIVE_FUSION_POLICY_ID

    def __init__(self, decision: AdaptiveFusionDecisionV1) -> None:
        self.decision = _validate_adaptive_decision(decision)

    def configured_weights(self, request: FusionRequest) -> Mapping[FusionFamily, float]:
        self._validate_request_binding(request)
        return {
            family: self.decision.effective_weights[family]
            for family in FUSION_FAMILIES
        }

    def required_positive_families(
        self,
        request: FusionRequest,
    ) -> tuple[FusionFamily, ...]:
        self._validate_request_binding(request)
        return V2_CHAMPION_FUSION_POLICY.required_positive_families(request)

    def buy_threshold(self, request: FusionRequest) -> float:
        self._validate_request_binding(request)
        return V2_CHAMPION_FUSION_POLICY.buy_threshold(request)

    def _validate_request_binding(self, request: FusionRequest) -> None:
        if request.symbol != self.decision.symbol:
            raise ValueError("adaptive fusion decision symbol does not match request")
        if request.as_of != self.decision.decision_at:
            raise ValueError("adaptive fusion decision timestamp does not match request")
        if fusion_request_fingerprint(request) != self.decision.source_request_fingerprint:
            raise ValueError("adaptive fusion decision request fingerprint mismatch")


def _validate_quality_multiplier(
    item: FusionExpertQualityMultiplierV1,
) -> FusionExpertQualityMultiplierV1:
    checked = FusionExpertQualityMultiplierV1.model_validate(
        item.model_dump(mode="python")
    )
    payload = checked.model_dump(mode="python", exclude={"multiplier_fingerprint"})
    payload["evaluated_at"] = checked.evaluated_at.isoformat()
    if checked.multiplier_fingerprint != canonical_fingerprint(payload):
        raise ValueError("fusion quality multiplier fingerprint mismatch")
    if checked.promotion_allowed or checked.can_authorize_capital:
        raise ValueError(
            "fusion quality multiplier cannot carry promotion or capital authority"
        )
    return checked


def _validate_adaptive_decision(
    item: AdaptiveFusionDecisionV1,
) -> AdaptiveFusionDecisionV1:
    checked = AdaptiveFusionDecisionV1.model_validate(item.model_dump(mode="python"))
    payload = checked.model_dump(mode="python", exclude={"decision_fingerprint"})
    payload["decision_at"] = checked.decision_at.isoformat()
    if checked.decision_fingerprint != canonical_fingerprint(payload):
        raise ValueError("adaptive fusion decision fingerprint mismatch")
    if checked.promotion_allowed or checked.can_authorize_capital:
        raise ValueError(
            "adaptive fusion decision cannot carry promotion or capital authority"
        )
    return checked
