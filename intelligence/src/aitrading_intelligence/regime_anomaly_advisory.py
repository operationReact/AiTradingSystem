"""V4-07.3 provenance-bound, research-only regime/anomaly eligibility overlay.

The V3 fusion candidate and Java capital/risk gate remain untouched. This
offline evidence may only *reduce* research eligibility; it cannot create a
BUY, promote a challenger, call a broker, or authorize real/PAPER execution.
"""
from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .fingerprints import canonical_fingerprint
from .fusion import FusionRequest, fuse_signals
from .regime import RegimeSnapshot
from .regime_anomaly import IsolationAnomalyObservation

CONTRACT = "v4-regime-anomaly-advisory.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class RegimeAnomalyAdvisoryConfig(BaseModel):
    """Frozen policy for an offline-only warning overlay, not broker risk."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    maximum_regime_age_seconds: Annotated[int, Field(ge=1, le=86_400)] = 1_800
    maximum_anomaly_age_seconds: Annotated[int, Field(ge=1, le=86_400)] = 1_800
    stress_probability_threshold: Annotated[float, Field(gt=0.0, le=1.0)] = 0.70
    reduce_on_regime_disagreement: bool = True

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class RegimeAnomalyAdvisoryV1(BaseModel):
    """Immutable recommendation preserving the underlying source action."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-regime-anomaly-advisory.v1"] = CONTRACT
    decision_at: datetime
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    original_fusion_action: Literal["BUY", "WATCH"]
    research_disposition: Literal[
        "RETAIN_UNCHANGED_RESEARCH_ONLY",
        "REDUCE_RESEARCH_ELIGIBILITY",
        "PRESERVE_NON_BUY",
    ]
    source_request_fingerprint: Hash64
    source_opportunity_fingerprint: Hash64
    source_heuristic_fingerprint: Hash64
    source_hmm_fingerprint: Hash64 | None
    source_anomaly_fingerprint: Hash64 | None
    source_market_context_fingerprint: Hash64
    policy_fingerprint: Hash64
    reason_codes: list[str] = Field(min_length=1, max_length=12)
    research_buy_candidate_eligible: bool
    production_risk_policy_unchanged: Literal[True] = True
    broker_or_paper_execution_enabled: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    advisory_fingerprint: Hash64

    @field_validator("decision_at")
    @classmethod
    def aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("decision_at must be timezone-aware")
        return value


def _verify_source_fingerprint(
    source: RegimeSnapshot | IsolationAnomalyObservation,
    field: str,
) -> None:
    payload = source.model_dump(mode="json", exclude={field})
    if canonical_fingerprint(payload) != getattr(source, field):
        raise ValueError(f"{field} mismatch: research source was modified")


def build_regime_anomaly_advisory(
    request: FusionRequest,
    *,
    heuristic: RegimeSnapshot,
    anomaly: IsolationAnomalyObservation | None,
    hmm: RegimeSnapshot | None = None,
    config: RegimeAnomalyAdvisoryConfig | None = None,
) -> RegimeAnomalyAdvisoryV1:
    """Do not modify fusion or Java risk: output a traceable reduction-only view.

    Require the exact V3 regime already bound to the fusion request; an
    unrelated stronger regime must not be substituted after a BUY was scored.
    HMM is an optional, separately identified challenger, never the baseline.
    """
    cfg = config or RegimeAnomalyAdvisoryConfig()
    checked_request = FusionRequest.model_validate(request.model_dump(mode="python"))
    checked_heuristic = RegimeSnapshot.model_validate(heuristic.model_dump(mode="python"))
    if checked_heuristic.method != "HEURISTIC_BASELINE":
        raise ValueError("V3 heuristic baseline must remain the anchor")
    _verify_source_fingerprint(checked_heuristic, "snapshot_fingerprint")
    if checked_request.regime is None:
        raise ValueError("fusion request must include its original V3 heuristic regime")
    if checked_request.regime.snapshot_fingerprint != checked_heuristic.snapshot_fingerprint:
        raise ValueError("fusion regime and heuristic source fingerprints mismatch")
    if checked_request.regime.model_dump(mode="json") != checked_heuristic.model_dump(mode="json"):
        raise ValueError("fusion regime is not the exact frozen heuristic evidence")
    if checked_request.regime.method != "HEURISTIC_BASELINE":
        raise ValueError("fusion regime cannot be substituted with a challenger")

    opportunity = fuse_signals(checked_request)
    decision_at = checked_request.as_of
    if checked_heuristic.as_of > decision_at:
        raise ValueError("future regime evidence cannot affect historical fusion")
    if checked_heuristic.data_cutoff > decision_at:
        raise ValueError("future market-context cutoff not permitted")

    checked_hmm: RegimeSnapshot | None = None
    if hmm is not None:
        checked_hmm = RegimeSnapshot.model_validate(hmm.model_dump(mode="python"))
        _verify_source_fingerprint(checked_hmm, "snapshot_fingerprint")
        if checked_hmm.method != "HMM_CHALLENGER":
            raise ValueError("optional challenger must be the actual V3 HMM")
        if checked_hmm.input_fingerprint != checked_heuristic.input_fingerprint:
            raise ValueError("HMM and heuristic source market context mismatch")
        if checked_hmm.as_of != checked_heuristic.as_of:
            raise ValueError("HMM and heuristic must assess the same market moment")
        if checked_hmm.data_cutoff > decision_at:
            raise ValueError("HMM cannot use future market data")

    checked_anomaly: IsolationAnomalyObservation | None = None
    if anomaly is not None:
        checked_anomaly = IsolationAnomalyObservation.model_validate(
            anomaly.model_dump(mode="python")
        )
        _verify_source_fingerprint(checked_anomaly, "evidence_fingerprint")
        if checked_anomaly.source_snapshot_fingerprint != checked_heuristic.input_fingerprint:
            raise ValueError("anomaly and regime source market context mismatch")
        if checked_anomaly.decision_at != checked_heuristic.as_of:
            raise ValueError("anomaly and regime must assess the same market moment")
        if checked_anomaly.data_cutoff > checked_anomaly.decision_at:
            raise ValueError("anomaly cannot use future market data")
        if checked_anomaly.training_data_cutoff >= checked_anomaly.decision_at:
            raise ValueError("anomaly scoring must be after model training")
        if not math.isfinite(checked_anomaly.anomaly_score) or not math.isfinite(
            checked_anomaly.threshold
        ):
            raise ValueError("anomaly score and threshold must be finite")
        if checked_anomaly.elevated_anomaly != (
            checked_anomaly.anomaly_score > checked_anomaly.threshold
        ) or checked_anomaly.elevated_anomaly != (
            checked_anomaly.action == "REDUCE_SIGNAL_ELIGIBILITY"
        ):
            raise ValueError("anomaly score and action are inconsistent")

    reasons: list[str] = []
    reduce = False
    if checked_heuristic.as_of < decision_at and (
        decision_at - checked_heuristic.as_of
    ).total_seconds() > cfg.maximum_regime_age_seconds:
        reasons.append("HEURISTIC_REGIME_EXPIRED")
        reduce = True
    if checked_hmm is not None:
        if (
            decision_at - checked_hmm.as_of
        ).total_seconds() > cfg.maximum_regime_age_seconds:
            reasons.append("HMM_REGIME_EXPIRED")
            reduce = True
        if (
            cfg.reduce_on_regime_disagreement
            and checked_hmm.dominant_regime != checked_heuristic.dominant_regime
        ):
            reasons.append("HMM_HEURISTIC_DISAGREEMENT")
            reduce = True

    for item in (checked_heuristic, checked_hmm):
        if item is not None and (
            item.probabilities["STRESS_HIGH_VOL"] >= cfg.stress_probability_threshold
        ):
            reasons.append("HIGH_STRESS_REGIME")
            reduce = True

    if checked_anomaly is None:
        reasons.append("ANOMALY_EVIDENCE_MISSING")
        reduce = True
    else:
        if (decision_at - checked_anomaly.decision_at).total_seconds() > (
            cfg.maximum_anomaly_age_seconds
        ):
            reasons.append("ANOMALY_EVIDENCE_EXPIRED")
            reduce = True
        if checked_anomaly.elevated_anomaly:
            reasons.append("ELEVATED_ANOMALY")
            reduce = True

    if opportunity.action == "WATCH":
        disposition = "PRESERVE_NON_BUY"
        reasons.append("FUSION_WATCH_CANNOT_BE_UPGRADED")
    elif reduce:
        disposition = "REDUCE_RESEARCH_ELIGIBILITY"
    else:
        disposition = "RETAIN_UNCHANGED_RESEARCH_ONLY"
        reasons.append("NO_RESEARCH_REDUCTION_NOT_TRADING_APPROVAL")

    payload = {
        "contract_version": CONTRACT,
        "decision_at": decision_at.isoformat(),
        "symbol": opportunity.symbol,
        "original_fusion_action": opportunity.action,
        "research_disposition": disposition,
        "source_request_fingerprint": canonical_fingerprint(
            checked_request.model_dump(mode="json")
        ),
        "source_opportunity_fingerprint": opportunity.opportunity_fingerprint,
        "source_heuristic_fingerprint": checked_heuristic.snapshot_fingerprint,
        "source_hmm_fingerprint": (
            checked_hmm.snapshot_fingerprint if checked_hmm is not None else None
        ),
        "source_anomaly_fingerprint": (
            checked_anomaly.evidence_fingerprint if checked_anomaly is not None else None
        ),
        "source_market_context_fingerprint": checked_heuristic.input_fingerprint,
        "policy_fingerprint": cfg.fingerprint,
        "reason_codes": sorted(set(reasons)),
        "research_buy_candidate_eligible": (
            opportunity.action == "BUY" and not reduce
        ),
        "production_risk_policy_unchanged": True,
        "broker_or_paper_execution_enabled": False,
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return RegimeAnomalyAdvisoryV1(
        **payload, advisory_fingerprint=canonical_fingerprint(payload)
    )
