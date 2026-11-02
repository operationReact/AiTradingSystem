from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from statistics import pstdev
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .calibrated_prediction import CalibratedPredictionV1
from .fingerprints import canonical_fingerprint

ENSEMBLE_UNCERTAINTY_VERSION = "ensemble-uncertainty.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelFamily = Literal["CATBOOST", "LIGHTGBM", "PYTORCH"]
UncertaintyLevel = Literal["LOW", "MEDIUM", "HIGH"]
EvidenceQuality = Literal["COMPLETE", "DEGRADED", "INSUFFICIENT"]


class EnsembleUncertaintyConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    expected_model_families: list[ModelFamily] = Field(
        default_factory=lambda: ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        min_length=2,
        max_length=16,
    )
    minimum_available_experts: Annotated[int, Field(ge=2, le=16)] = 2
    maximum_expert_age_seconds: Annotated[int, Field(ge=1, le=86_400)] = 900
    direction_threshold: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.5
    neutral_tolerance: Annotated[float, Field(ge=0.0, le=0.10)] = 1e-9
    medium_uncertainty_threshold: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.35
    high_uncertainty_threshold: Annotated[float, Field(gt=0.0, le=1.0)] = 0.65

    @model_validator(mode="after")
    def validate_config(self) -> EnsembleUncertaintyConfig:
        if len(set(self.expected_model_families)) != len(self.expected_model_families):
            raise ValueError("expected model families must be unique")
        if self.expected_model_families != sorted(self.expected_model_families):
            raise ValueError("expected model families must use canonical order")
        if self.minimum_available_experts > len(self.expected_model_families):
            raise ValueError("minimum available experts cannot exceed expected experts")
        if self.medium_uncertainty_threshold >= self.high_uncertainty_threshold:
            raise ValueError("medium uncertainty threshold must be below high threshold")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class EnsembleUncertaintyEvidence(BaseModel):
    """Research-only disagreement evidence across calibrated model experts."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["ensemble-uncertainty.v1"] = ENSEMBLE_UNCERTAINTY_VERSION
    symbol: Annotated[str, Field(min_length=1, max_length=64)]
    assessment_as_of: datetime
    config_fingerprint: Hash64
    expected_model_families: list[ModelFamily] = Field(min_length=2, max_length=16)
    present_model_families: list[ModelFamily] = Field(max_length=16)
    contributing_model_families: list[ModelFamily] = Field(max_length=16)
    missing_model_families: list[ModelFamily] = Field(max_length=16)
    stale_model_families: list[ModelFamily] = Field(max_length=16)
    abstaining_model_families: list[ModelFamily] = Field(max_length=16)
    calibration_degraded_families: list[ModelFamily] = Field(max_length=16)
    out_of_domain_model_families: list[ModelFamily] = Field(max_length=16)
    source_calibrated_prediction_fingerprints: list[Hash64] = Field(max_length=16)
    effective_probabilities: dict[ModelFamily, Annotated[float, Field(ge=0.0, le=1.0)]] = (
        Field(max_length=16)
    )
    expected_expert_count: Annotated[int, Field(ge=2, le=16)]
    present_expert_count: Annotated[int, Field(ge=0, le=16)]
    contributing_expert_count: Annotated[int, Field(ge=0, le=16)]
    positive_direction_count: Annotated[int, Field(ge=0, le=16)]
    negative_direction_count: Annotated[int, Field(ge=0, le=16)]
    neutral_direction_count: Annotated[int, Field(ge=0, le=16)]
    directional_disagreement_count: Annotated[int, Field(ge=0, le=16)]
    directional_disagreement_ratio: Annotated[float, Field(ge=0.0, le=1.0)]
    probability_mean: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_min: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_max: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_stddev: Annotated[float | None, Field(ge=0.0, le=0.5)] = None
    probability_dispersion: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    normalized_dispersion: Annotated[float, Field(ge=0.0, le=1.0)]
    directional_entropy: Annotated[float, Field(ge=0.0, le=1.0)]
    mean_margin_uncertainty: Annotated[float, Field(ge=0.0, le=1.0)]
    expert_coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    missingness_penalty: Annotated[float, Field(ge=0.0, le=1.0)]
    normalized_disagreement_score: Annotated[float, Field(ge=0.0, le=1.0)]
    uncertainty_score: Annotated[float, Field(ge=0.0, le=1.0)]
    uncertainty_level: UncertaintyLevel
    evidence_quality: EvidenceQuality
    has_missing_expert: bool
    has_stale_expert: bool
    has_abstaining_expert: bool
    has_out_of_domain_expert: bool
    has_insufficient_calibration_expert: bool
    reason_codes: list[str] = Field(min_length=1, max_length=64)
    required_next_gate: Literal["V3_05_SELECTIVE_POLICY"] = "V3_05_SELECTIVE_POLICY"
    evidence_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("assessment_as_of")
    @classmethod
    def assessment_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("ensemble uncertainty assessment_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_counts(self) -> EnsembleUncertaintyEvidence:
        if self.expected_expert_count != len(self.expected_model_families):
            raise ValueError("expected expert count must match expected families")
        if self.present_expert_count != len(self.present_model_families):
            raise ValueError("present expert count must match present families")
        if self.contributing_expert_count != len(self.contributing_model_families):
            raise ValueError("contributing expert count must match contributing families")
        direction_total = (
            self.positive_direction_count
            + self.negative_direction_count
            + self.neutral_direction_count
        )
        if direction_total != self.contributing_expert_count:
            raise ValueError("direction counts must equal contributing expert count")
        if set(self.effective_probabilities) != set(self.contributing_model_families):
            raise ValueError("effective probabilities must match contributing families")
        if self.has_missing_expert != bool(self.missing_model_families):
            raise ValueError("missing-expert flag must match family evidence")
        if self.has_stale_expert != bool(self.stale_model_families):
            raise ValueError("stale-expert flag must match family evidence")
        if self.has_abstaining_expert != bool(self.abstaining_model_families):
            raise ValueError("abstaining-expert flag must match family evidence")
        if self.has_out_of_domain_expert != bool(self.out_of_domain_model_families):
            raise ValueError("out-of-domain flag must match family evidence")
        return self


def evaluate_ensemble_uncertainty(
    predictions: Sequence[CalibratedPredictionV1],
    *,
    assessment_as_of: datetime | None = None,
    config: EnsembleUncertaintyConfig | None = None,
) -> EnsembleUncertaintyEvidence:
    cfg = config or EnsembleUncertaintyConfig()
    if not predictions:
        raise ValueError("ensemble uncertainty requires at least one calibrated prediction")

    validated = [_validate_source_prediction(item) for item in predictions]
    symbols = {item.symbol for item in validated}
    if len(symbols) != 1:
        raise ValueError("ensemble experts must share one symbol")
    effective_assessment_as_of = assessment_as_of or max(item.as_of for item in validated)
    if (
        effective_assessment_as_of.tzinfo is None
        or effective_assessment_as_of.utcoffset() is None
    ):
        raise ValueError("assessment_as_of must be timezone-aware")

    by_family: dict[ModelFamily, CalibratedPredictionV1] = {}
    expected = set(cfg.expected_model_families)
    for item in validated:
        if item.model_family not in expected:
            raise ValueError("ensemble contains an unexpected model family")
        if item.as_of > effective_assessment_as_of:
            raise ValueError("ensemble cannot include future prediction evidence")
        family = item.model_family
        if family in by_family:
            raise ValueError("ensemble cannot contain duplicate model families")
        by_family[family] = item

    expected_families = sorted(expected)
    present_families = sorted(by_family)
    missing_families = sorted(expected - set(by_family))
    stale_families = sorted(
        family
        for family, item in by_family.items()
        if (effective_assessment_as_of - item.as_of).total_seconds()
        > cfg.maximum_expert_age_seconds
    )
    abstaining_families = sorted(
        family
        for family, item in by_family.items()
        if item.calibration_action == "ABSTAIN" or item.effective_probability is None
    )
    degraded_families = sorted(
        family
        for family, item in by_family.items()
        if item.calibration_action == "DOWNGRADE_TO_RAW"
    )
    out_of_domain_families = sorted(
        family for family, item in by_family.items() if item.is_out_of_domain
    )
    insufficient_calibration = any(
        item.is_insufficient_calibration for item in validated
    )

    excluded = set(stale_families) | set(abstaining_families)
    effective_probabilities = {
        family: float(item.effective_probability)
        for family, item in sorted(by_family.items())
        if family not in excluded and item.effective_probability is not None
    }
    contributing_families = sorted(effective_probabilities)
    values = [effective_probabilities[family] for family in contributing_families]

    metrics = _probability_metrics(values, cfg)
    usable_count = len(values)
    expected_count = len(expected_families)
    coverage = usable_count / expected_count
    missingness_penalty = 1.0 - coverage
    disagreement_score = max(
        metrics["normalized_dispersion"],
        metrics["directional_disagreement_ratio"],
        metrics["directional_entropy"],
    )
    if usable_count < cfg.minimum_available_experts:
        uncertainty_score = 1.0
        uncertainty_level: UncertaintyLevel = "HIGH"
        evidence_quality: EvidenceQuality = "INSUFFICIENT"
    else:
        uncertainty_score = max(
            disagreement_score,
            metrics["directional_entropy"],
            metrics["mean_margin_uncertainty"],
            missingness_penalty,
        )
        if uncertainty_score >= cfg.high_uncertainty_threshold:
            uncertainty_level = "HIGH"
        elif uncertainty_score >= cfg.medium_uncertainty_threshold:
            uncertainty_level = "MEDIUM"
        else:
            uncertainty_level = "LOW"
        degraded = bool(
            missing_families
            or stale_families
            or abstaining_families
            or degraded_families
            or out_of_domain_families
            or insufficient_calibration
        )
        evidence_quality = "DEGRADED" if degraded else "COMPLETE"

    reasons: list[str] = []
    reasons.extend(f"MISSING_EXPERT_{family}" for family in missing_families)
    reasons.extend(f"STALE_EXPERT_{family}" for family in stale_families)
    reasons.extend(f"EXPERT_ABSTAINED_{family}" for family in abstaining_families)
    reasons.extend(f"CALIBRATION_DOWNGRADED_{family}" for family in degraded_families)
    reasons.extend(f"OUT_OF_DOMAIN_EXPERT_{family}" for family in out_of_domain_families)
    if insufficient_calibration:
        reasons.append("INSUFFICIENT_CALIBRATION_EXPERT_PRESENT")
    if usable_count < cfg.minimum_available_experts:
        reasons.append("INSUFFICIENT_USABLE_EXPERTS")
    elif metrics["directional_disagreement_count"] > 0:
        reasons.append("DIRECTIONAL_DISAGREEMENT_PRESENT")
    if uncertainty_level == "HIGH":
        reasons.append("HIGH_UNCERTAINTY")
    elif uncertainty_level == "MEDIUM":
        reasons.append("MEDIUM_UNCERTAINTY")
    if not reasons:
        reasons.append("ENSEMBLE_COMPLETE_LOW_UNCERTAINTY")
    reasons = sorted(set(reasons))

    source_fingerprints = [
        by_family[family].evidence_fingerprint for family in present_families
    ]
    payload = {
        "contract_version": ENSEMBLE_UNCERTAINTY_VERSION,
        "symbol": next(iter(symbols)),
        "assessment_as_of": effective_assessment_as_of.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "expected_model_families": expected_families,
        "present_model_families": present_families,
        "contributing_model_families": contributing_families,
        "missing_model_families": missing_families,
        "stale_model_families": stale_families,
        "abstaining_model_families": abstaining_families,
        "calibration_degraded_families": degraded_families,
        "out_of_domain_model_families": out_of_domain_families,
        "source_calibrated_prediction_fingerprints": source_fingerprints,
        "effective_probabilities": effective_probabilities,
        "expected_expert_count": expected_count,
        "present_expert_count": len(present_families),
        "contributing_expert_count": usable_count,
        **metrics,
        "expert_coverage": coverage,
        "missingness_penalty": missingness_penalty,
        "normalized_disagreement_score": disagreement_score,
        "uncertainty_score": uncertainty_score,
        "uncertainty_level": uncertainty_level,
        "evidence_quality": evidence_quality,
        "has_missing_expert": bool(missing_families),
        "has_stale_expert": bool(stale_families),
        "has_abstaining_expert": bool(abstaining_families),
        "has_out_of_domain_expert": bool(out_of_domain_families),
        "has_insufficient_calibration_expert": insufficient_calibration,
        "reason_codes": reasons,
        "required_next_gate": "V3_05_SELECTIVE_POLICY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return EnsembleUncertaintyEvidence(
        **payload,
        evidence_fingerprint=canonical_fingerprint(payload),
    )


def _validate_source_prediction(item: CalibratedPredictionV1) -> CalibratedPredictionV1:
    validated = CalibratedPredictionV1.model_validate(item.model_dump(mode="python"))
    payload = validated.model_dump(mode="python", exclude={"evidence_fingerprint"})
    payload["as_of"] = validated.as_of.isoformat()
    expected = canonical_fingerprint(payload)
    if validated.evidence_fingerprint != expected:
        raise ValueError("calibrated prediction evidence fingerprint mismatch")
    if validated.promotion_allowed or validated.can_authorize_capital:
        raise ValueError("calibrated prediction cannot carry promotion or capital authority")
    return validated


def _probability_metrics(
    probabilities: Sequence[float],
    config: EnsembleUncertaintyConfig,
) -> dict[str, float | int | None]:
    if not probabilities:
        return {
            "positive_direction_count": 0,
            "negative_direction_count": 0,
            "neutral_direction_count": 0,
            "directional_disagreement_count": 0,
            "directional_disagreement_ratio": 1.0,
            "probability_mean": None,
            "probability_min": None,
            "probability_max": None,
            "probability_stddev": None,
            "probability_dispersion": None,
            "normalized_dispersion": 0.0,
            "directional_entropy": 1.0,
            "mean_margin_uncertainty": 1.0,
        }

    mean = sum(probabilities) / len(probabilities)
    stddev = pstdev(probabilities) if len(probabilities) >= 2 else 0.0
    dispersion = max(probabilities) - min(probabilities)
    normalized_dispersion = min(1.0, stddev / 0.5)
    positive = sum(
        value > config.direction_threshold + config.neutral_tolerance
        for value in probabilities
    )
    negative = sum(
        value < config.direction_threshold - config.neutral_tolerance
        for value in probabilities
    )
    neutral = len(probabilities) - positive - negative
    directional = positive + negative
    if directional == 0:
        entropy = 1.0
    elif positive == 0 or negative == 0:
        entropy = 0.0
    else:
        positive_fraction = positive / directional
        negative_fraction = negative / directional
        entropy = -(
            positive_fraction * math.log2(positive_fraction)
            + negative_fraction * math.log2(negative_fraction)
        )
    neutral_fraction = neutral / len(probabilities)
    directional_entropy = max(entropy, neutral_fraction)
    margin_uncertainty = 1.0 - min(
        1.0,
        2.0 * abs(mean - config.direction_threshold),
    )
    majority = max(positive, negative, neutral)
    disagreement_count = len(probabilities) - majority
    disagreement_ratio = disagreement_count / len(probabilities)
    return {
        "positive_direction_count": positive,
        "negative_direction_count": negative,
        "neutral_direction_count": neutral,
        "directional_disagreement_count": disagreement_count,
        "directional_disagreement_ratio": disagreement_ratio,
        "probability_mean": mean,
        "probability_min": min(probabilities),
        "probability_max": max(probabilities),
        "probability_stddev": stddev,
        "probability_dispersion": dispersion,
        "normalized_dispersion": normalized_dispersion,
        "directional_entropy": directional_entropy,
        "mean_margin_uncertainty": margin_uncertainty,
    }