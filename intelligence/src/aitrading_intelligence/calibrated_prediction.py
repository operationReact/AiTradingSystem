from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .calibration_artifact import (
    CalibrationArtifact,
    CalibrationObservation,
    apply_calibration,
)
from .fingerprints import canonical_fingerprint
from .model_prediction_v2 import ModelPredictionV2

CALIBRATION_DOMAIN_PROFILE_VERSION = "calibration-domain-profile.v1"
CALIBRATED_PREDICTION_VERSION = "calibrated-prediction.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CalibrationEligibility = Literal["CALIBRATION_USABLE", "DOWNGRADE_OR_ABSTAIN"]


class CalibrationDomainConfig(BaseModel):
    """Fail-closed freshness/domain policy for one fitted calibrator."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    maximum_calibrator_age_seconds: Annotated[int, Field(ge=60, le=31_536_000)] = 604_800
    minimum_calibration_rows: Annotated[int, Field(ge=20, le=1_000_000)] = 50
    raw_probability_margin: Annotated[float, Field(ge=0.0, le=0.20)] = 0.02

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CalibrationDomainProfile(BaseModel):
    """Immutable raw-probability support derived from exact calibrator fit evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["calibration-domain-profile.v1"] = (
        CALIBRATION_DOMAIN_PROFILE_VERSION
    )
    calibration_artifact_fingerprint: Hash64
    source_registry_fingerprint: Hash64
    model_family: str
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    segment_type: Literal["GLOBAL"] = "GLOBAL"
    segment_key: Literal["GLOBAL"] = "GLOBAL"
    profile_as_of: datetime
    sample_count: Annotated[int, Field(ge=20)]
    raw_probability_min: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_probability_max: Annotated[float, Field(ge=0.0, le=1.0)]
    observation_fingerprints: list[Hash64] = Field(min_length=20, max_length=1_000_000)
    profile_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("profile_as_of")
    @classmethod
    def profile_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("profile_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_profile(self) -> CalibrationDomainProfile:
        if self.raw_probability_min > self.raw_probability_max:
            raise ValueError("raw probability domain minimum cannot exceed maximum")
        if len(self.observation_fingerprints) != self.sample_count:
            raise ValueError("domain profile observation count must match sample_count")
        if len(set(self.observation_fingerprints)) != self.sample_count:
            raise ValueError("domain profile observations must be unique")
        return self


class CalibratedPredictionV1(BaseModel):
    """Calibrated probability plus explicit freshness/domain eligibility evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["calibrated-prediction.v1"] = CALIBRATED_PREDICTION_VERSION
    symbol: Annotated[str, Field(min_length=1, max_length=64)]
    as_of: datetime
    source_model_prediction_fingerprint: Hash64
    source_registry_fingerprint: Hash64
    model_family: str
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    calibration_method: Literal["PLATT", "ISOTONIC", "TEMPERATURE"]
    calibration_artifact_fingerprint: Hash64
    calibration_domain_profile_fingerprint: Hash64
    calibration_policy_fingerprint: Hash64
    calibration_segment_type: Literal["GLOBAL"] = "GLOBAL"
    calibration_segment_key: Literal["GLOBAL"] = "GLOBAL"
    raw_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    calibrated_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    calibration_sample_size: Annotated[int, Field(ge=20)]
    calibration_age_seconds: Annotated[int, Field(ge=0)]
    raw_probability_domain_min: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_probability_domain_max: Annotated[float, Field(ge=0.0, le=1.0)]
    is_stale: bool
    is_out_of_domain: bool
    is_insufficient_calibration: bool
    calibration_usable: bool
    eligibility: CalibrationEligibility
    reason_codes: list[str]
    uncertainty_score: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    uncertainty_status: Literal["NOT_ATTACHED"] = "NOT_ATTACHED"
    evidence_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("calibrated prediction as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_eligibility(self) -> CalibratedPredictionV1:
        expected_usable = not (
            self.is_stale
            or self.is_out_of_domain
            or self.is_insufficient_calibration
        )
        if self.calibration_usable != expected_usable:
            raise ValueError("calibration_usable must match freshness/domain flags")
        expected_eligibility = (
            "CALIBRATION_USABLE" if expected_usable else "DOWNGRADE_OR_ABSTAIN"
        )
        if self.eligibility != expected_eligibility:
            raise ValueError("eligibility must match calibration usability")
        if expected_usable and self.reason_codes != ["CALIBRATION_FRESH_IN_DOMAIN"]:
            raise ValueError("usable calibration must carry the canonical success reason")
        if not expected_usable and not self.reason_codes:
            raise ValueError("downgraded calibration must include at least one reason")
        if self.raw_probability_domain_min > self.raw_probability_domain_max:
            raise ValueError("raw probability domain minimum cannot exceed maximum")
        return self


def build_calibration_domain_profile(
    artifact: CalibrationArtifact,
    observations: Sequence[CalibrationObservation],
) -> CalibrationDomainProfile:
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("calibration artifact cannot carry promotion or capital authority")
    validated = [
        CalibrationObservation.model_validate(row.model_dump(mode="python"))
        for row in observations
    ]
    ordered = sorted(
        validated,
        key=lambda row: (
            row.decision_at,
            row.symbol,
            row.model_prediction_fingerprint,
            row.observation_fingerprint,
        ),
    )
    fingerprints = [row.observation_fingerprint for row in ordered]
    if fingerprints != artifact.observation_fingerprints:
        raise ValueError("domain profile requires the exact calibration artifact observations")
    if {row.source_registry_fingerprint for row in ordered} != {
        artifact.source_registry_fingerprint
    }:
        raise ValueError("domain profile observations must match source registry")
    if artifact.raw_metrics.rows != len(ordered):
        raise ValueError("calibration artifact sample count does not match observations")
    probabilities = [row.raw_probability for row in ordered]
    probability_fingerprint = canonical_fingerprint(
        [round(value, 12) for value in probabilities]
    )
    if probability_fingerprint != artifact.raw_probability_fingerprint:
        raise ValueError("domain profile raw probabilities do not match calibration artifact")
    dataset_fingerprint = canonical_fingerprint(
        {
            "source_registry_fingerprint": artifact.source_registry_fingerprint,
            "observation_fingerprints": fingerprints,
        }
    )
    if dataset_fingerprint != artifact.calibration_dataset_fingerprint:
        raise ValueError("domain profile dataset fingerprint does not match calibration artifact")

    payload = {
        "contract_version": CALIBRATION_DOMAIN_PROFILE_VERSION,
        "calibration_artifact_fingerprint": artifact.artifact_fingerprint,
        "source_registry_fingerprint": artifact.source_registry_fingerprint,
        "model_family": artifact.model_family,
        "model_version": artifact.model_version,
        "segment_type": "GLOBAL",
        "segment_key": "GLOBAL",
        "profile_as_of": artifact.fit_as_of.isoformat(),
        "sample_count": len(ordered),
        "raw_probability_min": min(probabilities),
        "raw_probability_max": max(probabilities),
        "observation_fingerprints": fingerprints,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibrationDomainProfile(
        **payload,
        profile_fingerprint=canonical_fingerprint(payload),
    )


def evaluate_calibrated_prediction(
    *,
    prediction: ModelPredictionV2,
    artifact: CalibrationArtifact,
    domain_profile: CalibrationDomainProfile,
    config: CalibrationDomainConfig | None = None,
) -> CalibratedPredictionV1:
    cfg = config or CalibrationDomainConfig()
    _validate_lineage(prediction, artifact, domain_profile)
    if prediction.as_of < artifact.fit_as_of:
        raise ValueError("cannot apply a future calibration artifact to a historical prediction")

    age_seconds = int((prediction.as_of - artifact.fit_as_of).total_seconds())
    is_stale = age_seconds > cfg.maximum_calibrator_age_seconds
    is_insufficient = domain_profile.sample_count < cfg.minimum_calibration_rows
    lower = max(0.0, domain_profile.raw_probability_min - cfg.raw_probability_margin)
    upper = min(1.0, domain_profile.raw_probability_max + cfg.raw_probability_margin)
    is_out_of_domain = prediction.raw_probability < lower or prediction.raw_probability > upper
    calibrated_probability = apply_calibration(prediction.raw_probability, artifact.parameters)
    if not math.isfinite(calibrated_probability):
        raise ValueError("calibrated probability must be finite")

    reasons: list[str] = []
    if is_stale:
        reasons.append("CALIBRATION_STALE")
    if is_out_of_domain:
        reasons.append("RAW_PROBABILITY_OUT_OF_CALIBRATION_DOMAIN")
    if is_insufficient:
        reasons.append("INSUFFICIENT_CALIBRATION_SAMPLE")
    usable = not reasons
    if usable:
        reasons = ["CALIBRATION_FRESH_IN_DOMAIN"]
    else:
        reasons = sorted(reasons)

    payload = {
        "contract_version": CALIBRATED_PREDICTION_VERSION,
        "symbol": prediction.symbol,
        "as_of": prediction.as_of.isoformat(),
        "source_model_prediction_fingerprint": prediction.inference_fingerprint,
        "source_registry_fingerprint": artifact.source_registry_fingerprint,
        "model_family": prediction.model_family,
        "model_version": prediction.model_version,
        "calibration_method": artifact.method,
        "calibration_artifact_fingerprint": artifact.artifact_fingerprint,
        "calibration_domain_profile_fingerprint": domain_profile.profile_fingerprint,
        "calibration_policy_fingerprint": cfg.fingerprint,
        "calibration_segment_type": "GLOBAL",
        "calibration_segment_key": "GLOBAL",
        "raw_probability": prediction.raw_probability,
        "calibrated_probability": calibrated_probability,
        "calibration_sample_size": domain_profile.sample_count,
        "calibration_age_seconds": age_seconds,
        "raw_probability_domain_min": domain_profile.raw_probability_min,
        "raw_probability_domain_max": domain_profile.raw_probability_max,
        "is_stale": is_stale,
        "is_out_of_domain": is_out_of_domain,
        "is_insufficient_calibration": is_insufficient,
        "calibration_usable": usable,
        "eligibility": "CALIBRATION_USABLE" if usable else "DOWNGRADE_OR_ABSTAIN",
        "reason_codes": reasons,
        "uncertainty_score": None,
        "uncertainty_status": "NOT_ATTACHED",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibratedPredictionV1(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _validate_lineage(
    prediction: ModelPredictionV2,
    artifact: CalibrationArtifact,
    domain_profile: CalibrationDomainProfile,
) -> None:
    if prediction.promotion_allowed or prediction.can_authorize_capital:
        raise ValueError("model prediction cannot carry promotion or capital authority")
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("calibration artifact cannot carry promotion or capital authority")
    if prediction.model_family != artifact.model_family:
        raise ValueError("model prediction family does not match calibration artifact")
    if prediction.model_version != artifact.model_version:
        raise ValueError("model prediction version does not match calibration artifact")
    if prediction.model_artifact_fingerprint != artifact.model_artifact_fingerprint:
        raise ValueError("model artifact fingerprint does not match calibration artifact")
    if prediction.target_definition_fingerprint != artifact.target_spec_fingerprint:
        raise ValueError("model target fingerprint does not match calibration artifact")
    if domain_profile.calibration_artifact_fingerprint != artifact.artifact_fingerprint:
        raise ValueError("calibration domain profile does not match calibration artifact")
    if domain_profile.source_registry_fingerprint != artifact.source_registry_fingerprint:
        raise ValueError("calibration domain profile registry lineage does not match artifact")
    if domain_profile.model_family != artifact.model_family:
        raise ValueError("calibration domain profile model family does not match artifact")
    if domain_profile.model_version != artifact.model_version:
        raise ValueError("calibration domain profile model version does not match artifact")
