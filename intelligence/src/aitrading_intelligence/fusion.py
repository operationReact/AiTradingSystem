from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .regime import RegimeLabel, RegimeMethod, RegimeSnapshot

FUSION_VERSION = "signal-fusion.v1"
REGIME_FUSION_VIEW_VERSION = "regime-fusion-view.v1"
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


class RegimeEvidenceView(BaseModel):
    """UI/fusion view of regime evidence. It is deliberately zero-weight advisory context."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    view_version: Literal["regime-fusion-view.v1"] = REGIME_FUSION_VIEW_VERSION
    as_of: datetime
    data_cutoff: datetime
    method: RegimeMethod
    dominant_regime: RegimeLabel
    probabilities: dict[str, float]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    snapshot_fingerprint: Hash64
    model_fingerprint: Hash64 | None = None
    training_data_cutoff: datetime | None = None
    age_seconds: Annotated[int, Field(ge=0)]
    included: bool
    reason: Literal["FRESH_ADVISORY_ONLY", "STALE_ADVISORY_ONLY"]
    influences_fusion_score: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff", "training_data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("regime view timestamps must be timezone-aware")
        return value


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
    regime_evidence: RegimeEvidenceView | None = None
    reason_codes: list[str]
    provenance: list[str]
    opportunity_fingerprint: Hash64
    status: Literal["CANDIDATE_NOT_EXECUTABLE"] = "CANDIDATE_NOT_EXECUTABLE"
    can_authorize_capital: Literal[False] = False


_WEIGHTS = {
    "DETERMINISTIC": 0.45,
    "QUANT": 0.35,
    "GEMINI": 0.20,
}


def _age_seconds(as_of: datetime, observed_at: datetime) -> int:
    return max(0, int((as_of - observed_at).total_seconds()))


def _regime_view(request: FusionRequest) -> RegimeEvidenceView | None:
    if request.regime is None:
        return None
    age_seconds = _age_seconds(request.as_of, request.regime.as_of)
    included = age_seconds <= request.max_age_seconds
    return RegimeEvidenceView(
        as_of=request.regime.as_of,
        data_cutoff=request.regime.data_cutoff,
        method=request.regime.method,
        dominant_regime=request.regime.dominant_regime,
        probabilities=request.regime.probabilities,
        confidence=request.regime.confidence,
        snapshot_fingerprint=request.regime.snapshot_fingerprint,
        model_fingerprint=request.regime.model_fingerprint,
        training_data_cutoff=request.regime.training_data_cutoff,
        age_seconds=age_seconds,
        included=included,
        reason="FRESH_ADVISORY_ONLY" if included else "STALE_ADVISORY_ONLY",
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

    regime_evidence = _regime_view(request)
    if regime_evidence is not None:
        reason_codes.append("REGIME_ADVISORY_ONLY")
        if not regime_evidence.included:
            reason_codes.append("REGIME_STALE")
        provenance.append(
            "REGIME:"
            f"{regime_evidence.method}:"
            f"{regime_evidence.snapshot_fingerprint}"
        )

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
        "regime_evidence": (
            regime_evidence.model_dump(mode="json") if regime_evidence is not None else None
        ),
        "reason_codes": reason_codes,
        "provenance": provenance,
        "status": "CANDIDATE_NOT_EXECUTABLE",
        "can_authorize_capital": False,
    }
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
        regime_evidence=regime_evidence,
        reason_codes=reason_codes,
        provenance=provenance,
        opportunity_fingerprint=canonical_fingerprint(payload),
    )
