from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .calibration_artifact import CalibrationArtifact, CalibrationObservation
from .fingerprints import canonical_fingerprint

CALIBRATION_DOMAIN_PROFILE_VERSION = "calibration-domain-profile.v1"
CALIBRATION_USABILITY_VERSION = "calibration-usability.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelFamily = Literal["LIGHTGBM", "CATBOOST", "PYTORCH"]
CalibrationAction = Literal["USE_CALIBRATED", "DOWNGRADE_TO_RAW", "ABSTAIN"]


class CalibrationDomainProfile(BaseModel):
    """Frozen probability-domain evidence for one immutable calibration artifact."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["calibration-domain-profile.v1"] = (
        CALIBRATION_DOMAIN_PROFILE_VERSION
    )
    calibration_artifact_fingerprint: Hash64
    source_registry_fingerprint: Hash64
    model_family: ModelFamily
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    model_artifact_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    feature_schema_fingerprint: Hash64
    fit_as_of: datetime
    observation_count: Annotated[int, Field(ge=20)]
    positive_count: Annotated[int, Field(ge=1)]
    negative_count: Annotated[int, Field(ge=1)]
    raw_probability_min: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_probability_p05: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_probability_mean: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_probability_p95: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_probability_max: Annotated[float, Field(ge=0.0, le=1.0)]
    observation_fingerprints: list[Hash64] = Field(min_length=20, max_length=1_000_000)
    domain_dataset_fingerprint: Hash64
    profile_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("fit_as_of")
    @classmethod
    def fit_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("profile fit_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_profile(self) -> CalibrationDomainProfile:
        if self.positive_count + self.negative_count != self.observation_count:
            raise ValueError("class counts must equal observation_count")
        if len(self.observation_fingerprints) != self.observation_count:
            raise ValueError("observation fingerprint count must equal observation_count")
        if len(set(self.observation_fingerprints)) != self.observation_count:
            raise ValueError("domain profile observations must be unique")
        if not (
            self.raw_probability_min
            <= self.raw_probability_p05
            <= self.raw_probability_p95
            <= self.raw_probability_max
        ):
            raise ValueError("probability quantiles must be ordered within hard support")
        if not self.raw_probability_min <= self.raw_probability_mean <= self.raw_probability_max:
            raise ValueError("probability mean must lie within hard support")
        return self


class CalibrationFreshnessDomainConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    maximum_age_seconds: Annotated[int, Field(ge=60, le=31_536_000)] = 604_800
    minimum_observations: Annotated[int, Field(ge=20, le=1_000_000)] = 50
    minimum_positive_observations: Annotated[int, Field(ge=1, le=500_000)] = 10
    minimum_negative_observations: Annotated[int, Field(ge=1, le=500_000)] = 10
    central_domain_margin: Annotated[float, Field(ge=0.0, le=0.25)] = 0.02
    severe_domain_margin: Annotated[float, Field(gt=0.0, le=0.5)] = 0.10

    @model_validator(mode="after")
    def validate_margins(self) -> CalibrationFreshnessDomainConfig:
        if self.severe_domain_margin <= self.central_domain_margin:
            raise ValueError("severe_domain_margin must exceed central_domain_margin")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CalibrationFreshnessDomainDecision(BaseModel):
    """Advisory calibration usability evidence; never a trade or capital decision."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["calibration-usability.v1"] = CALIBRATION_USABILITY_VERSION
    decision_at: datetime
    raw_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    calibration_artifact_fingerprint: Hash64
    domain_profile_fingerprint: Hash64
    config_fingerprint: Hash64
    calibration_age_seconds: Annotated[int, Field(ge=0)]
    observation_count: Annotated[int, Field(ge=20)]
    positive_count: Annotated[int, Field(ge=1)]
    negative_count: Annotated[int, Field(ge=1)]
    central_domain_lower: Annotated[float, Field(ge=0.0, le=1.0)]
    central_domain_upper: Annotated[float, Field(ge=0.0, le=1.0)]
    hard_domain_lower: Annotated[float, Field(ge=0.0, le=1.0)]
    hard_domain_upper: Annotated[float, Field(ge=0.0, le=1.0)]
    action: CalibrationAction
    calibration_applied: bool
    reason_codes: list[str] = Field(min_length=1, max_length=16)
    required_next_gate: Literal["V3_05_UNCERTAINTY"] = "V3_05_UNCERTAINTY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    decision_fingerprint: Hash64

    @field_validator("decision_at")
    @classmethod
    def decision_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("decision_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_decision(self) -> CalibrationFreshnessDomainDecision:
        if self.central_domain_lower > self.central_domain_upper:
            raise ValueError("central domain lower bound cannot exceed upper bound")
        if self.hard_domain_lower > self.hard_domain_upper:
            raise ValueError("hard domain lower bound cannot exceed upper bound")
        if self.action == "USE_CALIBRATED" and not self.calibration_applied:
            raise ValueError("USE_CALIBRATED requires calibration_applied=true")
        if self.action != "USE_CALIBRATED" and self.calibration_applied:
            raise ValueError("downgrade/abstain cannot apply calibration")
        return self


def build_calibration_domain_profile(
    *,
    artifact: CalibrationArtifact,
    observations: Sequence[CalibrationObservation],
) -> CalibrationDomainProfile:
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("calibration artifact cannot carry promotion or capital authority")

    expected_fingerprints = set(artifact.observation_fingerprints)
    by_fingerprint: dict[str, CalibrationObservation] = {}
    for observation in observations:
        validated = CalibrationObservation.model_validate(observation.model_dump(mode="python"))
        if validated.observation_fingerprint in expected_fingerprints:
            if validated.observation_fingerprint in by_fingerprint:
                raise ValueError("duplicate frozen calibration observation")
            by_fingerprint[validated.observation_fingerprint] = validated

    missing = expected_fingerprints - set(by_fingerprint)
    if missing:
        raise ValueError("frozen calibration observations are incomplete")
    selected = [by_fingerprint[fingerprint] for fingerprint in artifact.observation_fingerprints]
    if {row.source_registry_fingerprint for row in selected} != {
        artifact.source_registry_fingerprint
    }:
        raise ValueError("domain observations do not match calibration registry lineage")
    if any(row.target_observed_at > artifact.fit_as_of for row in selected):
        raise ValueError("domain profile cannot include labels observed after artifact fit_as_of")

    probabilities = sorted(row.raw_probability for row in selected)
    positive_count = sum(row.actual_positive for row in selected)
    negative_count = len(selected) - positive_count
    if positive_count == 0 or negative_count == 0:
        raise ValueError("domain profile requires both outcome classes")

    observation_fingerprints = [row.observation_fingerprint for row in selected]
    dataset_fingerprint = canonical_fingerprint(
        {
            "calibration_artifact_fingerprint": artifact.artifact_fingerprint,
            "observation_fingerprints": observation_fingerprints,
        }
    )
    payload = {
        "contract_version": CALIBRATION_DOMAIN_PROFILE_VERSION,
        "calibration_artifact_fingerprint": artifact.artifact_fingerprint,
        "source_registry_fingerprint": artifact.source_registry_fingerprint,
        "model_family": artifact.model_family,
        "model_version": artifact.model_version,
        "model_artifact_fingerprint": artifact.model_artifact_fingerprint,
        "target_spec_fingerprint": artifact.target_spec_fingerprint,
        "feature_schema_fingerprint": artifact.feature_schema_fingerprint,
        "fit_as_of": artifact.fit_as_of.isoformat(),
        "observation_count": len(selected),
        "positive_count": positive_count,
        "negative_count": negative_count,
        "raw_probability_min": probabilities[0],
        "raw_probability_p05": _quantile(probabilities, 0.05),
        "raw_probability_mean": sum(probabilities) / len(probabilities),
        "raw_probability_p95": _quantile(probabilities, 0.95),
        "raw_probability_max": probabilities[-1],
        "observation_fingerprints": observation_fingerprints,
        "domain_dataset_fingerprint": dataset_fingerprint,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibrationDomainProfile(
        **payload,
        profile_fingerprint=canonical_fingerprint(payload),
    )


def evaluate_calibration_usability(
    *,
    artifact: CalibrationArtifact,
    profile: CalibrationDomainProfile,
    raw_probability: float,
    decision_at: datetime,
    config: CalibrationFreshnessDomainConfig | None = None,
) -> CalibrationFreshnessDomainDecision:
    cfg = config or CalibrationFreshnessDomainConfig()
    if decision_at.tzinfo is None or decision_at.utcoffset() is None:
        raise ValueError("decision_at must be timezone-aware")
    if not math.isfinite(raw_probability) or not 0.0 <= raw_probability <= 1.0:
        raise ValueError("raw_probability must be finite and in [0, 1]")
    _validate_lineage(artifact, profile)
    if decision_at < artifact.fit_as_of:
        raise ValueError("calibration usability decision cannot predate artifact fit")

    age_seconds = int((decision_at - artifact.fit_as_of).total_seconds())
    central_lower = max(0.0, profile.raw_probability_p05 - cfg.central_domain_margin)
    central_upper = min(1.0, profile.raw_probability_p95 + cfg.central_domain_margin)
    hard_lower = max(0.0, profile.raw_probability_min - cfg.severe_domain_margin)
    hard_upper = min(1.0, profile.raw_probability_max + cfg.severe_domain_margin)

    reasons: list[str] = []
    severe_ood = raw_probability < hard_lower or raw_probability > hard_upper
    if severe_ood:
        action: CalibrationAction = "ABSTAIN"
        reasons.append("CALIBRATION_SEVERE_OUT_OF_DOMAIN")
    else:
        if age_seconds > cfg.maximum_age_seconds:
            reasons.append("CALIBRATION_STALE")
        if profile.observation_count < cfg.minimum_observations:
            reasons.append("CALIBRATION_SAMPLE_INSUFFICIENT")
        if profile.positive_count < cfg.minimum_positive_observations:
            reasons.append("CALIBRATION_POSITIVE_CLASS_INSUFFICIENT")
        if profile.negative_count < cfg.minimum_negative_observations:
            reasons.append("CALIBRATION_NEGATIVE_CLASS_INSUFFICIENT")
        if raw_probability < central_lower or raw_probability > central_upper:
            reasons.append("CALIBRATION_OUTSIDE_CENTRAL_DOMAIN")
        action = "DOWNGRADE_TO_RAW" if reasons else "USE_CALIBRATED"
        if not reasons:
            reasons.append("CALIBRATION_FRESH_IN_DOMAIN_AND_SUFFICIENT")

    payload = {
        "contract_version": CALIBRATION_USABILITY_VERSION,
        "decision_at": decision_at.isoformat(),
        "raw_probability": raw_probability,
        "calibration_artifact_fingerprint": artifact.artifact_fingerprint,
        "domain_profile_fingerprint": profile.profile_fingerprint,
        "config_fingerprint": cfg.fingerprint,
        "calibration_age_seconds": age_seconds,
        "observation_count": profile.observation_count,
        "positive_count": profile.positive_count,
        "negative_count": profile.negative_count,
        "central_domain_lower": central_lower,
        "central_domain_upper": central_upper,
        "hard_domain_lower": hard_lower,
        "hard_domain_upper": hard_upper,
        "action": action,
        "calibration_applied": action == "USE_CALIBRATED",
        "reason_codes": sorted(reasons),
        "required_next_gate": "V3_05_UNCERTAINTY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibrationFreshnessDomainDecision(
        **payload,
        decision_fingerprint=canonical_fingerprint(payload),
    )


def _validate_lineage(
    artifact: CalibrationArtifact,
    profile: CalibrationDomainProfile,
) -> None:
    expected = {
        "calibration_artifact_fingerprint": artifact.artifact_fingerprint,
        "source_registry_fingerprint": artifact.source_registry_fingerprint,
        "model_family": artifact.model_family,
        "model_version": artifact.model_version,
        "model_artifact_fingerprint": artifact.model_artifact_fingerprint,
        "target_spec_fingerprint": artifact.target_spec_fingerprint,
        "feature_schema_fingerprint": artifact.feature_schema_fingerprint,
        "fit_as_of": artifact.fit_as_of,
    }
    actual = {
        "calibration_artifact_fingerprint": profile.calibration_artifact_fingerprint,
        "source_registry_fingerprint": profile.source_registry_fingerprint,
        "model_family": profile.model_family,
        "model_version": profile.model_version,
        "model_artifact_fingerprint": profile.model_artifact_fingerprint,
        "target_spec_fingerprint": profile.target_spec_fingerprint,
        "feature_schema_fingerprint": profile.feature_schema_fingerprint,
        "fit_as_of": profile.fit_as_of,
    }
    if expected != actual:
        raise ValueError("calibration domain profile lineage does not match artifact")
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("calibration artifact cannot carry promotion or capital authority")
    if profile.promotion_allowed or profile.can_authorize_capital:
        raise ValueError("calibration domain profile cannot carry promotion or capital authority")


def _quantile(values: Sequence[float], probability: float) -> float:
    if not values:
        raise ValueError("quantile requires at least one value")
    if not 0.0 <= probability <= 1.0:
        raise ValueError("quantile probability must be in [0, 1]")
    if len(values) == 1:
        return float(values[0])
    position = probability * (len(values) - 1)
    lower_index = int(math.floor(position))
    upper_index = int(math.ceil(position))
    if lower_index == upper_index:
        return float(values[lower_index])
    weight = position - lower_index
    return float(values[lower_index] + (weight * (values[upper_index] - values[lower_index])))
