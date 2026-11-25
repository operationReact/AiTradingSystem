from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .calibration_artifact import CalibrationArtifact, apply_calibration
from .calibration_freshness import (
    CalibrationDomainProfile,
    CalibrationFreshnessDomainConfig,
    build_calibration_domain_profile,
    evaluate_calibration_usability,
)
from .fingerprints import canonical_fingerprint
from .model_prediction_v2 import ModelPredictionV2

CALIBRATED_PREDICTION_VERSION = "calibrated-prediction.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CalibrationAction = Literal["USE_CALIBRATED", "DOWNGRADE_TO_RAW", "ABSTAIN"]


class CalibratedPredictionV1(BaseModel):
    """Canonical calibrated probability with explicit freshness/domain eligibility."""

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
    effective_probability: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    calibration_sample_size: Annotated[int, Field(ge=20)]
    calibration_age_seconds: Annotated[int, Field(ge=0)]
    central_domain_lower: Annotated[float, Field(ge=0.0, le=1.0)]
    central_domain_upper: Annotated[float, Field(ge=0.0, le=1.0)]
    hard_domain_lower: Annotated[float, Field(ge=0.0, le=1.0)]
    hard_domain_upper: Annotated[float, Field(ge=0.0, le=1.0)]
    is_stale: bool
    is_out_of_domain: bool
    is_insufficient_calibration: bool
    calibration_action: CalibrationAction
    calibration_applied: bool
    reason_codes: list[str] = Field(min_length=1, max_length=16)
    uncertainty_score: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    uncertainty_status: Literal["NOT_ATTACHED"] = "NOT_ATTACHED"
    required_next_gate: Literal["V3_05_UNCERTAINTY"] = "V3_05_UNCERTAINTY"
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
    def validate_action_semantics(self) -> CalibratedPredictionV1:
        if self.central_domain_lower > self.central_domain_upper:
            raise ValueError("central calibration domain lower bound cannot exceed upper bound")
        if self.hard_domain_lower > self.hard_domain_upper:
            raise ValueError("hard calibration domain lower bound cannot exceed upper bound")
        if self.calibration_action == "USE_CALIBRATED":
            if not self.calibration_applied:
                raise ValueError("USE_CALIBRATED requires calibration_applied=true")
            if self.effective_probability is None or not math.isclose(
                self.effective_probability,
                self.calibrated_probability,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("usable calibration must expose calibrated probability")
        elif self.calibration_action == "DOWNGRADE_TO_RAW":
            if self.calibration_applied:
                raise ValueError("DOWNGRADE_TO_RAW cannot apply calibration")
            if self.effective_probability is None or not math.isclose(
                self.effective_probability,
                self.raw_probability,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("downgrade must expose raw probability")
        else:
            if self.calibration_applied or self.effective_probability is not None:
                raise ValueError("ABSTAIN cannot expose an effective probability")
        return self


def evaluate_calibrated_prediction(
    *,
    prediction: ModelPredictionV2,
    artifact: CalibrationArtifact,
    domain_profile: CalibrationDomainProfile,
    config: CalibrationFreshnessDomainConfig | None = None,
) -> CalibratedPredictionV1:
    cfg = config or CalibrationFreshnessDomainConfig()
    _validate_prediction_lineage(prediction, artifact, domain_profile)
    usability = evaluate_calibration_usability(
        artifact=artifact,
        profile=domain_profile,
        raw_probability=prediction.raw_probability,
        decision_at=prediction.as_of,
        config=cfg,
    )
    calibrated_probability = apply_calibration(prediction.raw_probability, artifact.parameters)
    if not math.isfinite(calibrated_probability):
        raise ValueError("calibrated probability must be finite")

    if usability.action == "USE_CALIBRATED":
        effective_probability: float | None = calibrated_probability
    elif usability.action == "DOWNGRADE_TO_RAW":
        effective_probability = prediction.raw_probability
    else:
        effective_probability = None

    reasons = set(usability.reason_codes)
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
        "calibration_policy_fingerprint": usability.config_fingerprint,
        "calibration_segment_type": "GLOBAL",
        "calibration_segment_key": "GLOBAL",
        "raw_probability": prediction.raw_probability,
        "calibrated_probability": calibrated_probability,
        "effective_probability": effective_probability,
        "calibration_sample_size": usability.observation_count,
        "calibration_age_seconds": usability.calibration_age_seconds,
        "central_domain_lower": usability.central_domain_lower,
        "central_domain_upper": usability.central_domain_upper,
        "hard_domain_lower": usability.hard_domain_lower,
        "hard_domain_upper": usability.hard_domain_upper,
        "is_stale": "CALIBRATION_STALE" in reasons,
        "is_out_of_domain": bool(
            reasons
            & {
                "CALIBRATION_OUTSIDE_CENTRAL_DOMAIN",
                "CALIBRATION_SEVERE_OUT_OF_DOMAIN",
            }
        ),
        "is_insufficient_calibration": bool(
            reasons
            & {
                "CALIBRATION_SAMPLE_INSUFFICIENT",
                "CALIBRATION_POSITIVE_CLASS_INSUFFICIENT",
                "CALIBRATION_NEGATIVE_CLASS_INSUFFICIENT",
            }
        ),
        "calibration_action": usability.action,
        "calibration_applied": usability.calibration_applied,
        "reason_codes": usability.reason_codes,
        "uncertainty_score": None,
        "uncertainty_status": "NOT_ATTACHED",
        "required_next_gate": "V3_05_UNCERTAINTY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibratedPredictionV1(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _validate_prediction_lineage(
    prediction: ModelPredictionV2,
    artifact: CalibrationArtifact,
    domain_profile: CalibrationDomainProfile,
) -> None:
    if prediction.promotion_allowed or prediction.can_authorize_capital:
        raise ValueError("model prediction cannot carry promotion or capital authority")
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
    if domain_profile.model_artifact_fingerprint != artifact.model_artifact_fingerprint:
        raise ValueError("calibration domain profile model artifact does not match artifact")
    if domain_profile.target_spec_fingerprint != artifact.target_spec_fingerprint:
        raise ValueError("calibration domain profile target does not match artifact")
    if domain_profile.feature_schema_fingerprint != artifact.feature_schema_fingerprint:
        raise ValueError("calibration domain profile feature schema does not match artifact")


__all__ = [
    "CALIBRATED_PREDICTION_VERSION",
    "CalibratedPredictionV1",
    "CalibrationDomainProfile",
    "CalibrationFreshnessDomainConfig",
    "build_calibration_domain_profile",
    "evaluate_calibrated_prediction",
]
