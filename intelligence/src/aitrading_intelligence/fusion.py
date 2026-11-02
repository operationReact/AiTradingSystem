from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .regime import REGIME_LABELS, RegimeMethod, RegimeSnapshot

FUSION_VERSION = "signal-fusion.v1"
REGIME_EXPLAINABILITY_VERSION = "regime-explainability.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class FusionEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    family: Literal["DETERMINISTIC", "QUANT", "GEMINI"]
    provider: Annotated[str, Field(min_length=1, max_length=80)]
    version: Annotated[str, Field(min_length=1, max_length=120)]
    score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    observed_at: datetime
    fingerprint: Hash64
    reasons: list[str] = Field(default_factory=list, max_length=32)

    @field_validator("observed_at")
    @classmethod
    def timestamp_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("observed_at must be timezone-aware")
        return value


class RegimeExplainability(BaseModel):
    """UI/API-safe regime context that cannot participate in capital authorization."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["regime-explainability.v1"] = REGIME_EXPLAINABILITY_VERSION
    as_of: datetime
    data_cutoff: datetime
    method: RegimeMethod
    dominant_regime: str
    probabilities: dict[str, float]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    normalized_entropy: Annotated[float, Field(ge=0.0, le=1.0)]
    regime_snapshot_fingerprint: Hash64
    explainability_fingerprint: Hash64
    status: Literal["RESEARCH_EXPLAINABILITY_ONLY"] = "RESEARCH_EXPLAINABILITY_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("regime explainability timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_probabilities(self) -> RegimeExplainability:
        if tuple(self.probabilities) != REGIME_LABELS:
            raise ValueError("regime explainability probabilities must use canonical labels")
        if self.data_cutoff > self.as_of:
            raise ValueError("regime explainability data_cutoff cannot exceed as_of")
        return self


class FusionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fusion_version: Literal["signal-fusion.v1"] = FUSION_VERSION
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    max_age_seconds: Annotated[int, Field(ge=1, le=86_400)] = 1800
    deterministic: FusionEvidence
    quant: FusionEvidence
    gemini: FusionEvidence | None = None
    regime: RegimeSnapshot | None = None

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_families_and_causality(self) -> FusionRequest:
        if self.deterministic.family != "DETERMINISTIC":
            raise ValueError("deterministic evidence must use family DETERMINISTIC")
        if self.quant.family != "QUANT":
            raise ValueError("quant evidence must use family QUANT")
        if self.gemini is not None and self.gemini.family != "GEMINI":
            raise ValueError("gemini evidence must use family GEMINI")
        for evidence in (self.deterministic, self.quant, self.gemini):
            if evidence is not None and evidence.observed_at > self.as_of:
                raise ValueError("evidence observed_at cannot be later than as_of")
        if self.regime is not None:
            if self.regime.as_of > self.as_of:
                raise ValueError("regime as_of cannot be later than fusion as_of")
            if self.regime.data_cutoff > self.as_of:
                raise ValueError("regime data_cutoff cannot be later than fusion as_of")
            if self.regime.can_authorize_capital:
                raise ValueError("regime evidence cannot authorize capital")
        return self


class FusionContribution(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    family: Literal["DETERMINISTIC", "QUANT", "GEMINI"]
    configured_weight: Annotated[float, Field(ge=0.0, le=1.0)]
    effective_weight: Annotated[float, Field(ge=0.0, le=1.0)]
    score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    weighted_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    age_seconds: Annotated[int, Field(ge=0)]
    included: bool
    reason: str
    fingerprint: Hash64


class RankedOpportunity(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fusion_version: Literal["signal-fusion.v1"] = FUSION_VERSION
    symbol: str
    as_of: datetime
    data_cutoff: datetime
    action: Literal["BUY", "WATCH"]
    fused_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    rank_score: Annotated[float, Field(ge=0.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    contributions: list[FusionContribution]
    reason_codes: list[str]
    provenance: list[str]
    opportunity_fingerprint: Hash64
    regime_context: RegimeExplainability | None = None
    explainability_fingerprint: Hash64 | None = None
    status: Literal["CANDIDATE_NOT_EXECUTABLE"] = "CANDIDATE_NOT_EXECUTABLE"
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def explainability_is_all_or_nothing(self) -> RankedOpportunity:
        if (self.regime_context is None) != (self.explainability_fingerprint is None):
            raise ValueError("regime context and explainability fingerprint must appear together")
        if self.regime_context is not None:
            if self.regime_context.can_authorize_capital:
                raise ValueError("regime context cannot authorize capital")
            if self.regime_context.explainability_fingerprint != self.explainability_fingerprint:
                raise ValueError("regime explainability fingerprint mismatch")
        return self


_WEIGHTS = {
    "DETERMINISTIC": 0.45,
    "QUANT": 0.35,
    "GEMINI": 0.20,
}


def _age_seconds(as_of: datetime, observed_at: datetime) -> int:
    return max(0, int((as_of - observed_at).total_seconds()))


def _normalized_entropy(probabilities: dict[str, float]) -> float:
    entropy = -sum(
        probability * math.log(probability)
        for probability in probabilities.values()
        if probability > 0.0
    )
    return entropy / math.log(len(REGIME_LABELS))


def _regime_explainability(regime: RegimeSnapshot) -> RegimeExplainability:
    probabilities = {label: regime.probabilities[label] for label in REGIME_LABELS}
    payload = {
        "contract_version": REGIME_EXPLAINABILITY_VERSION,
        "as_of": regime.as_of.isoformat(),
        "data_cutoff": regime.data_cutoff.isoformat(),
        "method": regime.method,
        "dominant_regime": regime.dominant_regime,
        "probabilities": probabilities,
        "confidence": regime.confidence,
        "normalized_entropy": _normalized_entropy(probabilities),
        "regime_snapshot_fingerprint": regime.snapshot_fingerprint,
        "status": "RESEARCH_EXPLAINABILITY_ONLY",
        "can_authorize_capital": False,
    }
    fingerprint = canonical_fingerprint(payload)
    return RegimeExplainability(
        **payload,
        explainability_fingerprint=fingerprint,
    )


def fuse_signals(request: FusionRequest) -> RankedOpportunity:
    evidence_items = [request.deterministic, request.quant]
    if request.gemini is not None:
        evidence_items.append(request.gemini)

    contributions: list[FusionContribution] = []
    included: list[tuple[FusionEvidence, float]] = []
    required_stale = False
    reason_codes: list[str] = []

    for evidence in evidence_items:
        age_seconds = _age_seconds(request.as_of, evidence.observed_at)
        configured_weight = _WEIGHTS[evidence.family]
        is_fresh = age_seconds <= request.max_age_seconds
        if not is_fresh and evidence.family in {"DETERMINISTIC", "QUANT"}:
            required_stale = True
        effective_weight = configured_weight * evidence.confidence if is_fresh else 0.0
        weighted_score = effective_weight * evidence.score
        reason = "FRESH" if is_fresh else "STALE_EXCLUDED"
        contributions.append(
            FusionContribution(
                family=evidence.family,
                configured_weight=configured_weight,
                effective_weight=effective_weight,
                score=evidence.score,
                confidence=evidence.confidence,
                weighted_score=weighted_score,
                age_seconds=age_seconds,
                included=is_fresh,
                reason=reason,
                fingerprint=evidence.fingerprint,
            )
        )
        if is_fresh:
            included.append((evidence, effective_weight))
        else:
            reason_codes.append(f"{evidence.family}_STALE")

    denominator = sum(weight for _, weight in included)
    fused_score = (
        sum(evidence.score * weight for evidence, weight in included) / denominator
        if denominator > 0
        else 0.0
    )
    rank_score = (fused_score + 1.0) / 2.0
    confidence = min(1.0, denominator)

    deterministic_positive = request.deterministic.score > 0.0
    quant_positive = request.quant.score > 0.0
    action = "WATCH"
    if required_stale:
        reason_codes.append("REQUIRED_EVIDENCE_STALE")
    elif deterministic_positive and quant_positive and fused_score >= 0.35:
        action = "BUY"
        reason_codes.append("FUSION_BUY_THRESHOLD_MET")
    else:
        if not deterministic_positive:
            reason_codes.append("DETERMINISTIC_NOT_POSITIVE")
        if not quant_positive:
            reason_codes.append("QUANT_NOT_POSITIVE")
        if fused_score < 0.35:
            reason_codes.append("FUSION_SCORE_BELOW_BUY_THRESHOLD")

    included_evidence = [evidence for evidence, _ in included]
    data_cutoff = max(
        (evidence.observed_at for evidence in included_evidence),
        default=request.as_of,
    )
    provenance = [
        f"{evidence.family}:{evidence.provider}:{evidence.version}:{evidence.fingerprint}"
        for evidence in included_evidence
    ]
    payload = {
        "fusion_version": FUSION_VERSION,
        "symbol": request.symbol,
        "as_of": request.as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "action": action,
        "fused_score": fused_score,
        "rank_score": rank_score,
        "confidence": confidence,
        "contributions": [item.model_dump(mode="json") for item in contributions],
        "reason_codes": reason_codes,
        "provenance": provenance,
        "status": "CANDIDATE_NOT_EXECUTABLE",
        "can_authorize_capital": False,
    }
    opportunity_fingerprint = canonical_fingerprint(payload)
    regime_context = _regime_explainability(request.regime) if request.regime is not None else None
    return RankedOpportunity(
        fusion_version=FUSION_VERSION,
        symbol=request.symbol,
        as_of=request.as_of,
        data_cutoff=data_cutoff,
        action=action,
        fused_score=fused_score,
        rank_score=rank_score,
        confidence=confidence,
        contributions=contributions,
        reason_codes=reason_codes,
        provenance=provenance,
        opportunity_fingerprint=opportunity_fingerprint,
        regime_context=regime_context,
        explainability_fingerprint=(
            regime_context.explainability_fingerprint if regime_context is not None else None
        ),
    )
