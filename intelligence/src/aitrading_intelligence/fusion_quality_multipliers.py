from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime, timedelta
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .calibration_freshness import CalibrationFreshnessDomainDecision
from .fingerprints import canonical_fingerprint
from .fusion import FusionFamily

FUSION_QUALITY_MULTIPLIER_VERSION = "fusion-quality-multiplier.v1"
SHADOW_PERFORMANCE_OBSERVATION_VERSION = "shadow-performance-observation.v1"
SHADOW_PERFORMANCE_EVIDENCE_VERSION = "shadow-performance-evidence.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
PerformanceState = Literal["SUFFICIENT", "INSUFFICIENT"]


class FusionQualityMultiplierConfig(BaseModel):
    """Manual, bounded research challenger configuration for V3-06.3."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["fusion-quality-multiplier.v1"] = (
        FUSION_QUALITY_MULTIPLIER_VERSION
    )
    fresh_calibration_seconds: Annotated[int, Field(ge=60, le=31_536_000)] = 86_400
    maximum_calibration_age_seconds: Annotated[
        int,
        Field(ge=60, le=31_536_000),
    ] = 604_800
    downgraded_calibration_multiplier: Annotated[
        float,
        Field(ge=0.0, le=1.0),
    ] = 0.90
    stale_calibration_floor: Annotated[float, Field(ge=0.0, le=1.0)] = 0.75
    trailing_window_seconds: Annotated[int, Field(ge=3_600, le=31_536_000)] = 2_592_000
    minimum_resolved_shadow_observations: Annotated[
        int,
        Field(ge=2, le=100_000),
    ] = 20
    performance_scale_bps: Annotated[float, Field(gt=0.0, le=10_000.0)] = 500.0
    maximum_performance_adjustment: Annotated[
        float,
        Field(ge=0.0, le=0.50),
    ] = 0.10
    minimum_combined_multiplier: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0
    maximum_combined_multiplier: Annotated[float, Field(ge=1.0, le=2.0)] = 1.20

    @model_validator(mode="after")
    def validate_config(self) -> FusionQualityMultiplierConfig:
        if self.fresh_calibration_seconds > self.maximum_calibration_age_seconds:
            raise ValueError(
                "fresh_calibration_seconds cannot exceed maximum_calibration_age_seconds"
            )
        minimum_performance_multiplier = 1.0 - self.maximum_performance_adjustment
        maximum_performance_multiplier = 1.0 + self.maximum_performance_adjustment
        if self.minimum_combined_multiplier > minimum_performance_multiplier:
            raise ValueError(
                "minimum_combined_multiplier cannot exceed the minimum performance multiplier"
            )
        if self.maximum_combined_multiplier < maximum_performance_multiplier:
            raise ValueError(
                "maximum_combined_multiplier cannot be below the maximum performance multiplier"
            )
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class ShadowPerformanceObservationV1(BaseModel):
    """One resolved shadow outcome. It has no trading or capital authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["shadow-performance-observation.v1"] = (
        SHADOW_PERFORMANCE_OBSERVATION_VERSION
    )
    family: FusionFamily
    decision_as_of: datetime
    target_observed_at: datetime
    net_return: float
    source_fingerprint: Hash64
    observation_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_as_of", "target_observed_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("shadow performance timestamps must be timezone-aware")
        return value

    @field_validator("net_return")
    @classmethod
    def net_return_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("shadow performance net_return must be finite")
        return value

    @model_validator(mode="after")
    def target_must_follow_decision(self) -> ShadowPerformanceObservationV1:
        if self.target_observed_at < self.decision_as_of:
            raise ValueError("shadow outcome cannot be observed before its decision")
        return self


def build_shadow_performance_observation(
    *,
    family: FusionFamily,
    decision_as_of: datetime,
    target_observed_at: datetime,
    net_return: float,
    source_fingerprint: str,
) -> ShadowPerformanceObservationV1:
    payload = {
        "contract_version": SHADOW_PERFORMANCE_OBSERVATION_VERSION,
        "family": family,
        "decision_as_of": decision_as_of.isoformat(),
        "target_observed_at": target_observed_at.isoformat(),
        "net_return": net_return,
        "source_fingerprint": source_fingerprint,
        "can_authorize_capital": False,
    }
    return ShadowPerformanceObservationV1(
        **payload,
        observation_fingerprint=canonical_fingerprint(payload),
    )


class ShadowPerformanceEvidenceV1(BaseModel):
    """Causal trailing shadow-performance evidence, evaluated only on resolved outcomes."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["shadow-performance-evidence.v1"] = (
        SHADOW_PERFORMANCE_EVIDENCE_VERSION
    )
    family: FusionFamily
    evaluation_as_of: datetime
    window_start: datetime
    window_end: datetime
    eligible_observation_count: Annotated[int, Field(ge=0)]
    future_observation_count: Annotated[int, Field(ge=0)]
    resolved_observation_count: Annotated[int, Field(ge=0)]
    winning_observation_count: Annotated[int, Field(ge=0)]
    mean_net_return: float | None
    net_expectancy_bps: float | None
    state: PerformanceState
    reason_codes: list[str] = Field(min_length=1, max_length=16)
    source_observation_fingerprints: list[Hash64] = Field(default_factory=list)
    source_data_cutoff: datetime | None = None
    config_fingerprint: Hash64
    evidence_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator(
        "evaluation_as_of",
        "window_start",
        "window_end",
        "source_data_cutoff",
    )
    @classmethod
    def evidence_timestamps_must_be_aware(
        cls,
        value: datetime | None,
    ) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("shadow performance evidence timestamps must be timezone-aware")
        return value

    @field_validator("mean_net_return", "net_expectancy_bps")
    @classmethod
    def optional_metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("shadow performance evidence metrics must be finite")
        return value

    @model_validator(mode="after")
    def validate_evidence(self) -> ShadowPerformanceEvidenceV1:
        if self.window_start > self.window_end:
            raise ValueError("shadow performance window_start cannot exceed window_end")
        if self.window_end > self.evaluation_as_of:
            raise ValueError("shadow performance window_end cannot exceed evaluation_as_of")
        if (
            self.source_data_cutoff is not None
            and self.source_data_cutoff > self.evaluation_as_of
        ):
            raise ValueError(
                "shadow performance data cutoff cannot exceed evaluation_as_of"
            )
        if self.winning_observation_count > self.resolved_observation_count:
            raise ValueError("winning observations cannot exceed resolved observations")
        if len(self.source_observation_fingerprints) != self.resolved_observation_count:
            raise ValueError(
                "source fingerprint count must equal resolved observation count"
            )
        if len(set(self.source_observation_fingerprints)) != len(
            self.source_observation_fingerprints
        ):
            raise ValueError("shadow performance source observations must be unique")
        if self.resolved_observation_count == 0:
            if self.mean_net_return is not None or self.net_expectancy_bps is not None:
                raise ValueError("empty shadow evidence cannot carry performance metrics")
        elif self.mean_net_return is None or self.net_expectancy_bps is None:
            raise ValueError("resolved shadow evidence requires performance metrics")
        return self


def evaluate_shadow_performance(
    observations: Sequence[ShadowPerformanceObservationV1],
    *,
    family: FusionFamily,
    evaluation_as_of: datetime,
    config: FusionQualityMultiplierConfig | None = None,
) -> ShadowPerformanceEvidenceV1:
    cfg = config or FusionQualityMultiplierConfig()
    if evaluation_as_of.tzinfo is None or evaluation_as_of.utcoffset() is None:
        raise ValueError("evaluation_as_of must be timezone-aware")

    validated: list[ShadowPerformanceObservationV1] = []
    seen: set[str] = set()
    for item in observations:
        if item.family != family:
            continue
        checked = _validate_shadow_observation(item)
        if checked.observation_fingerprint in seen:
            raise ValueError(
                "shadow performance observations must have unique fingerprints"
            )
        seen.add(checked.observation_fingerprint)
        validated.append(checked)

    window_end = evaluation_as_of
    window_start = evaluation_as_of - timedelta(seconds=cfg.trailing_window_seconds)
    future = [item for item in validated if item.target_observed_at > evaluation_as_of]
    eligible = [
        item
        for item in validated
        if item.target_observed_at <= evaluation_as_of
        and item.target_observed_at >= window_start
        and item.decision_as_of <= evaluation_as_of
    ]
    eligible.sort(
        key=lambda item: (
            item.target_observed_at,
            item.decision_as_of,
            item.observation_fingerprint,
        )
    )

    returns = [item.net_return for item in eligible]
    mean_return = sum(returns) / len(returns) if returns else None
    expectancy_bps = None if mean_return is None else mean_return * 10_000.0
    reasons: list[str] = []
    if future:
        reasons.append("FUTURE_SHADOW_OUTCOMES_EXCLUDED")
    if len(eligible) < cfg.minimum_resolved_shadow_observations:
        state: PerformanceState = "INSUFFICIENT"
        reasons.append("INSUFFICIENT_RESOLVED_SHADOW_OBSERVATIONS")
    else:
        state = "SUFFICIENT"
        reasons.append("SHADOW_PERFORMANCE_EVIDENCE_SUFFICIENT")
    if not eligible:
        reasons.append("NO_RESOLVED_SHADOW_OUTCOMES_IN_WINDOW")

    source_data_cutoff = max(
        (item.target_observed_at for item in eligible),
        default=None,
    )
    payload = {
        "contract_version": SHADOW_PERFORMANCE_EVIDENCE_VERSION,
        "family": family,
        "evaluation_as_of": evaluation_as_of.isoformat(),
        "window_start": window_start.isoformat(),
        "window_end": window_end.isoformat(),
        "eligible_observation_count": len(eligible),
        "future_observation_count": len(future),
        "resolved_observation_count": len(eligible),
        "winning_observation_count": sum(value > 0.0 for value in returns),
        "mean_net_return": mean_return,
        "net_expectancy_bps": expectancy_bps,
        "state": state,
        "reason_codes": sorted(set(reasons)),
        "source_observation_fingerprints": [
            item.observation_fingerprint for item in eligible
        ],
        "source_data_cutoff": (
            source_data_cutoff.isoformat() if source_data_cutoff is not None else None
        ),
        "config_fingerprint": cfg.fingerprint,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ShadowPerformanceEvidenceV1(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


class FusionExpertQualityMultiplierV1(BaseModel):
    """Reconstructable multiplier evidence. It does not itself alter a fusion decision."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["fusion-quality-multiplier.v1"] = (
        FUSION_QUALITY_MULTIPLIER_VERSION
    )
    family: FusionFamily
    evaluated_at: datetime
    calibration_quality_multiplier: Annotated[float, Field(ge=0.0, le=1.0)]
    freshness_multiplier: Annotated[float, Field(ge=0.0, le=1.0)]
    performance_multiplier: Annotated[float, Field(ge=0.5, le=1.5)]
    raw_combined_multiplier: Annotated[float, Field(ge=0.0, le=1.5)]
    minimum_combined_multiplier: Annotated[float, Field(ge=0.0, le=1.0)]
    maximum_combined_multiplier: Annotated[float, Field(ge=1.0, le=2.0)]
    combined_multiplier: Annotated[float, Field(ge=0.0, le=2.0)]
    calibration_decision_fingerprint: Hash64 | None = None
    shadow_performance_evidence_fingerprint: Hash64 | None = None
    reason_codes: list[str] = Field(min_length=1, max_length=24)
    config_fingerprint: Hash64
    multiplier_fingerprint: Hash64
    status: Literal["RESEARCH_QUALITY_EVIDENCE_ONLY"] = (
        "RESEARCH_QUALITY_EVIDENCE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("evaluated_at")
    @classmethod
    def evaluated_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluated_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_multiplier_arithmetic(self) -> FusionExpertQualityMultiplierV1:
        if self.minimum_combined_multiplier > self.maximum_combined_multiplier:
            raise ValueError("combined multiplier bounds are invalid")
        expected_raw = (
            self.calibration_quality_multiplier
            * self.freshness_multiplier
            * self.performance_multiplier
        )
        if not math.isclose(
            self.raw_combined_multiplier,
            expected_raw,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("raw combined multiplier must equal component product")
        expected_combined = max(
            self.minimum_combined_multiplier,
            min(self.maximum_combined_multiplier, expected_raw),
        )
        if not math.isclose(
            self.combined_multiplier,
            expected_combined,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("combined multiplier must equal bounded component product")
        return self


def build_fusion_quality_multiplier(
    *,
    family: FusionFamily,
    evaluated_at: datetime,
    calibration: CalibrationFreshnessDomainDecision | None = None,
    shadow_performance: ShadowPerformanceEvidenceV1 | None = None,
    config: FusionQualityMultiplierConfig | None = None,
) -> FusionExpertQualityMultiplierV1:
    cfg = config or FusionQualityMultiplierConfig()
    if evaluated_at.tzinfo is None or evaluated_at.utcoffset() is None:
        raise ValueError("evaluated_at must be timezone-aware")

    calibration_quality_multiplier = 1.0
    freshness_multiplier = 1.0
    calibration_fingerprint: str | None = None
    reasons: list[str] = []

    if calibration is None:
        reasons.append("CALIBRATION_EVIDENCE_NOT_SUPPLIED_NEUTRAL")
    else:
        checked_calibration = _validate_calibration_decision(calibration)
        if checked_calibration.decision_at > evaluated_at:
            raise ValueError("calibration decision cannot postdate multiplier evaluation")
        calibration_fingerprint = checked_calibration.decision_fingerprint
        if checked_calibration.action == "USE_CALIBRATED":
            calibration_quality_multiplier = 1.0
            reasons.append("CALIBRATION_USABLE")
        elif checked_calibration.action == "DOWNGRADE_TO_RAW":
            calibration_quality_multiplier = cfg.downgraded_calibration_multiplier
            reasons.append("CALIBRATION_DOWNGRADED")
        else:
            calibration_quality_multiplier = 0.0
            reasons.append("CALIBRATION_ABSTAIN_ZERO_QUALITY")

        freshness_multiplier = _freshness_multiplier(
            checked_calibration.calibration_age_seconds,
            cfg,
        )
        if freshness_multiplier < 1.0:
            reasons.append("CALIBRATION_FRESHNESS_PENALTY_APPLIED")
        else:
            reasons.append("CALIBRATION_FRESHNESS_FULL_WEIGHT")

    performance_multiplier = 1.0
    performance_fingerprint: str | None = None
    if shadow_performance is None:
        reasons.append("SHADOW_PERFORMANCE_NOT_SUPPLIED_NEUTRAL")
    else:
        checked_performance = _validate_shadow_performance_evidence(
            shadow_performance
        )
        if checked_performance.family != family:
            raise ValueError("shadow performance family must match multiplier family")
        if checked_performance.evaluation_as_of > evaluated_at:
            raise ValueError(
                "shadow performance evidence cannot postdate multiplier evaluation"
            )
        if (
            checked_performance.source_data_cutoff is not None
            and checked_performance.source_data_cutoff > evaluated_at
        ):
            raise ValueError(
                "shadow performance cutoff cannot postdate multiplier evaluation"
            )
        performance_fingerprint = checked_performance.evidence_fingerprint
        if checked_performance.state == "SUFFICIENT":
            expectancy_bps = checked_performance.net_expectancy_bps
            if expectancy_bps is None:
                raise ValueError("sufficient shadow evidence requires expectancy")
            raw_adjustment = expectancy_bps / cfg.performance_scale_bps
            bounded_adjustment = max(
                -cfg.maximum_performance_adjustment,
                min(cfg.maximum_performance_adjustment, raw_adjustment),
            )
            performance_multiplier = 1.0 + bounded_adjustment
            reasons.append("SHADOW_PERFORMANCE_MULTIPLIER_APPLIED")
        else:
            reasons.append("SHADOW_PERFORMANCE_INSUFFICIENT_NEUTRAL")

    raw_combined = (
        calibration_quality_multiplier
        * freshness_multiplier
        * performance_multiplier
    )
    combined = max(
        cfg.minimum_combined_multiplier,
        min(cfg.maximum_combined_multiplier, raw_combined),
    )
    payload = {
        "contract_version": FUSION_QUALITY_MULTIPLIER_VERSION,
        "family": family,
        "evaluated_at": evaluated_at.isoformat(),
        "calibration_quality_multiplier": calibration_quality_multiplier,
        "freshness_multiplier": freshness_multiplier,
        "performance_multiplier": performance_multiplier,
        "raw_combined_multiplier": raw_combined,
        "minimum_combined_multiplier": cfg.minimum_combined_multiplier,
        "maximum_combined_multiplier": cfg.maximum_combined_multiplier,
        "combined_multiplier": combined,
        "calibration_decision_fingerprint": calibration_fingerprint,
        "shadow_performance_evidence_fingerprint": performance_fingerprint,
        "reason_codes": sorted(set(reasons)),
        "config_fingerprint": cfg.fingerprint,
        "status": "RESEARCH_QUALITY_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return FusionExpertQualityMultiplierV1(
        **payload,
        multiplier_fingerprint=canonical_fingerprint(payload),
    )


def _freshness_multiplier(
    age_seconds: int,
    config: FusionQualityMultiplierConfig,
) -> float:
    if age_seconds <= config.fresh_calibration_seconds:
        return 1.0
    if age_seconds >= config.maximum_calibration_age_seconds:
        return config.stale_calibration_floor
    span = config.maximum_calibration_age_seconds - config.fresh_calibration_seconds
    progress = (age_seconds - config.fresh_calibration_seconds) / span
    return 1.0 - (progress * (1.0 - config.stale_calibration_floor))


def _validate_calibration_decision(
    item: CalibrationFreshnessDomainDecision,
) -> CalibrationFreshnessDomainDecision:
    checked = CalibrationFreshnessDomainDecision.model_validate(
        item.model_dump(mode="python")
    )
    payload = checked.model_dump(mode="python", exclude={"decision_fingerprint"})
    payload["decision_at"] = checked.decision_at.isoformat()
    if checked.decision_fingerprint != canonical_fingerprint(payload):
        raise ValueError("calibration decision fingerprint mismatch")
    if checked.promotion_allowed or checked.can_authorize_capital:
        raise ValueError(
            "calibration decision cannot carry promotion or capital authority"
        )
    return checked


def _validate_shadow_observation(
    item: ShadowPerformanceObservationV1,
) -> ShadowPerformanceObservationV1:
    checked = ShadowPerformanceObservationV1.model_validate(
        item.model_dump(mode="python")
    )
    payload = checked.model_dump(mode="python", exclude={"observation_fingerprint"})
    payload["decision_as_of"] = checked.decision_as_of.isoformat()
    payload["target_observed_at"] = checked.target_observed_at.isoformat()
    if checked.observation_fingerprint != canonical_fingerprint(payload):
        raise ValueError("shadow performance observation fingerprint mismatch")
    if checked.can_authorize_capital:
        raise ValueError("shadow performance observation cannot carry capital authority")
    return checked


def _validate_shadow_performance_evidence(
    item: ShadowPerformanceEvidenceV1,
) -> ShadowPerformanceEvidenceV1:
    checked = ShadowPerformanceEvidenceV1.model_validate(
        item.model_dump(mode="python")
    )
    payload = checked.model_dump(mode="python", exclude={"evidence_fingerprint"})
    payload["evaluation_as_of"] = checked.evaluation_as_of.isoformat()
    payload["window_start"] = checked.window_start.isoformat()
    payload["window_end"] = checked.window_end.isoformat()
    payload["source_data_cutoff"] = (
        checked.source_data_cutoff.isoformat()
        if checked.source_data_cutoff is not None
        else None
    )
    if checked.evidence_fingerprint != canonical_fingerprint(payload):
        raise ValueError("shadow performance evidence fingerprint mismatch")
    if checked.promotion_allowed or checked.can_authorize_capital:
        raise ValueError(
            "shadow performance evidence cannot carry promotion or capital authority"
        )
    return checked
