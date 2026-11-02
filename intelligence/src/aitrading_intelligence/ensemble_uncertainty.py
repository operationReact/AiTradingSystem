from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .calibrated_prediction import CalibratedPredictionV1
from .fingerprints import canonical_fingerprint

ENSEMBLE_UNCERTAINTY_VERSION = "ensemble-uncertainty.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
UncertaintyLevel = Literal["LOW", "MEDIUM", "HIGH"]
EvidenceQuality = Literal["COMPLETE", "DEGRADED", "INSUFFICIENT"]


class EnsembleUncertaintyConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    expected_model_families: list[str] = Field(
        default_factory=lambda: ["CATBOOST", "LIGHTGBM", "PYTORCH"],
        min_length=2,
        max_length=16,
    )
    minimum_available_experts: Annotated[int, Field(ge=2, le=16)] = 2
    direction_threshold: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.5
    neutral_tolerance: Annotated[float, Field(ge=0.0, le=0.10)] = 1e-9
    medium_uncertainty_threshold: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.35
    high_uncertainty_threshold: Annotated[float, Field(gt=0.0, le=1.0)] = 0.65

    @model_validator(mode="after")
    def validate_config(self) -> EnsembleUncertaintyConfig:
        if len(set(self.expected_model_families)) != len(self.expected_model_families):
            raise ValueError("expected model families must be unique")
        if self.minimum_available_experts > len(self.expected_model_families):
            raise ValueError("minimum available experts cannot exceed expected experts")
        if self.medium_uncertainty_threshold >= self.high_uncertainty_threshold:
            raise ValueError("medium uncertainty threshold must be below high threshold")
        return self

    @property
    def fingerprint(self) -> str:
        payload = self.model_dump(mode="json")
        payload["expected_model_families"] = sorted(self.expected_model_families)
        return canonical_fingerprint(payload)


class EnsembleUncertaintyEvidence(BaseModel):
    """Research-only disagreement evidence across calibrated model experts."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["ensemble-uncertainty.v1"] = ENSEMBLE_UNCERTAINTY_VERSION
    symbol: Annotated[str, Field(min_length=1, max_length=64)]
    as_of: datetime
    config_fingerprint: Hash64
    expected_model_families: list[str] = Field(min_length=2, max_length=16)
    present_model_families: list[str] = Field(max_length=16)
    contributing_model_families: list[str] = Field(max_length=16)
    missing_model_families: list[str] = Field(max_length=16)
    abstaining_model_families: list[str] = Field(max_length=16)
    source_calibrated_prediction_fingerprints: list[Hash64] = Field(max_length=16)
    effective_probabilities: dict[str, Annotated[float, Field(ge=0.0, le=1.0)]] = Field(
        max_length=16
    )
    expected_expert_count: Annotated[int, Field(ge=2, le=16)]
    present_expert_count: Annotated[int, Field(ge=0, le=16)]
    contributing_expert_count: Annotated[int, Field(ge=0, le=16)]
    positive_direction_count: Annotated[int, Field(ge=0, le=16)]
    negative_direction_count: Annotated[int, Field(ge=0, le=16)]
    neutral_direction_count: Annotated[int, Field(ge=0, le=16)]
    directional_disagreement_count: Annotated[int, Field(ge=0, le=16)]
    probability_mean: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_min: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_max: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    probability_stddev: Annotated[float | None, Field(ge=0.0, le=0.5)] = None
    probability_dispersion: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    directional_entropy: Annotated[float, Field(ge=0.0, le=1.0)]
    mean_margin_uncertainty: Annotated[float, Field(ge=0.0, le=1.0)]
    normalized_disagreement_score: Annotated[float, Field(ge=0.0, le=1.0)]
    uncertainty_level: UncertaintyLevel
    evidence_quality: EvidenceQuality
    has_missing_expert: bool
    has_abstaining_expert: bool
    has_stale_expert: bool
    has_out_of_domain_expert: bool
    has_insufficient_calibration_expert: bool
    reason_codes: list[str] = Field(min_length=1, max_length=64)
    required_next_gate: Literal["V3_05_SELECTIVE_POLICY"] = "V3_05_SELECTIVE_POLICY"
    evidence_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("ensemble uncertainty as_of must be timezone-aware")
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
        return self


def evaluate_ensemble_uncertainty(
    predictions: Sequence[CalibratedPredictionV1],
    *,
    config: EnsembleUncertaintyConfig | None = None,
) -> EnsembleUncertaintyEvidence:
    cfg = config or EnsembleUncertaintyConfig()
    if not predictions:
        raise ValueError("ensemble uncertainty requires at least one calibrated prediction")

    validated = [_validate_source_prediction(item) for item in predictions]
    symbols = {item.symbol for item in validated}
    as_of_values = {item.as_of for item in validated}
    if len(symbols) != 1 or len(as_of_values) != 1:
        raise ValueError("ensemble experts must share one symbol and as_of timestamp")

    by_family: dict[str, CalibratedPredictionV1] = {}
    for item in validated:
        if item.model_family in by_family:
            raise ValueError("ensemble cannot contain duplicate model families")
        by_family[item.model_family] = item

    expected = set(cfg.expected_model_families)
    present = set(by_family)
    unexpected = present - expected
    if unexpected:
        raise ValueError(f"ensemble contains unexpected model families: {sorted(unexpected)}")

    expected_families = sorted(expected)
    present_families = sorted(present)
    missing_families = sorted(expected - present)
    abstaining_families = sorted(
        family
        for family, item in by_family.items()
        if item.calibration_action == "ABSTAIN" or item.effective_probability is None
    )
    effective_probabilities = {
        family: float(item.effective_probability)
        for family, item in sorted(by_family.items())
        if item.effective_probability is not None and item.calibration_action != "ABSTAIN"
    }
    contributing_families = sorted(effective_probabilities)
    values = [effective_probabilities[family] for family in contributing_families]

    metrics = _probability_metrics(values, cfg)
    stale = any(item.is_stale for item in validated)
    out_of_domain = any(item.is_out_of_domain for item in validated)
    insufficient = any(item.is_insufficient_calibration for item in validated)
    has_missing = bool(missing_families)
    has_abstaining = bool(abstaining_families)

    if len(values) < cfg.minimum_available_experts:
        evidence_quality: EvidenceQuality = "INSUFFICIENT"
    elif has_missing or has_abstaining or stale or out_of_domain or insufficient:
        evidence_quality = "DEGRADED"
    else:
        evidence_quality = "COMPLETE"

    uncertainty_score = metrics["normalized_disagreement_score"]
    if uncertainty_score >= cfg.high_uncertainty_threshold:
        uncertainty_level: UncertaintyLevel = "HIGH"
    elif uncertainty_score >= cfg.medium_uncertainty_threshold:
        uncertainty_level = "MEDIUM"
    else:
        uncertainty_level = "LOW"

    reasons: list[str] = []
    reasons.extend(f"MISSING_EXPERT_{family}" for family in missing_families)
    reasons.extend(f"EXPERT_ABSTAINED_{family}" for family in abstaining_families)
    if stale:
        reasons.append("STALE_CALIBRATION_PRESENT")
    if out_of_domain:
        reasons.append("OUT_OF_DOMAIN_EXPERT_PRESENT")
    if insufficient:
        reasons.append("INSUFFICIENT_CALIBRATION_EXPERT_PRESENT")
    if len(values) < cfg.minimum_available_experts:
        reasons.append("INSUFFICIENT_AVAILABLE_EXPERTS")
    if metrics["directional_disagreement_count"] > 0:
        reasons.append("DIRECTIONAL_DISAGREEMENT_PRESENT")
    if uncertainty_level == "HIGH":
        reasons.append("HIGH_NORMALIZED_DISAGREEMENT")
    elif uncertainty_level == "MEDIUM":
        reasons.append("MEDIUM_NORMALIZED_DISAGREEMENT")
    if not reasons:
        reasons.append("ENSEMBLE_COMPLETE_LOW_DISAGREEMENT")
    reasons = sorted(set(reasons))

    source_fingerprints = [
        by_family[family].evidence_fingerprint for family in present_families
    ]
    payload = {
        "contract_version": ENSEMBLE_UNCERTAINTY_VERSION,
        "symbol": next(iter(symbols)),
        "as_of": next(iter(as_of_values)).isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "expected_model_families": expected_families,
        "present_model_families": present_families,
        "contributing_model_families": contributing_families,
        "missing_model_families": missing_families,
        "abstaining_model_families": abstaining_families,
        "source_calibrated_prediction_fingerprints": source_fingerprints,
        "effective_probabilities": effective_probabilities,
        "expected_expert_count": len(expected_families),
        "present_expert_count": len(present_families),
        "contributing_expert_count": len(contributing_families),
        **metrics,
        "uncertainty_level": uncertainty_level,
        "evidence_quality": evidence_quality,
        "has_missing_expert": has_missing,
        "has_abstaining_expert": has_abstaining,
        "has_stale_expert": stale,
        "has_out_of_domain_expert": out_of_domain,
        "has_insufficient_calibration_expert": insufficient,
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
    payload = validated.model_dump(mode="json", exclude={"evidence_fingerprint"})
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
            "probability_mean": None,
            "probability_min": None,
            "probability_max": None,
            "probability_stddev": None,
            "probability_dispersion": None,
            "directional_entropy": 1.0,
            "mean_margin_uncertainty": 1.0,
            "normalized_disagreement_score": 1.0,
        }

    mean = sum(probabilities) / len(probabilities)
    variance = sum((value - mean) ** 2 for value in probabilities) / len(probabilities)
    stddev = math.sqrt(variance)
    dispersion = max(probabilities) - min(probabilities)
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
    normalized_stddev = min(1.0, 2.0 * stddev)
    normalized_score = min(
        1.0,
        max(normalized_stddev, directional_entropy, margin_uncertainty),
    )
    return {
        "positive_direction_count": positive,
        "negative_direction_count": negative,
        "neutral_direction_count": neutral,
        "directional_disagreement_count": min(positive, negative),
        "probability_mean": mean,
        "probability_min": min(probabilities),
        "probability_max": max(probabilities),
        "probability_stddev": stddev,
        "probability_dispersion": dispersion,
        "directional_entropy": directional_entropy,
        "mean_margin_uncertainty": margin_uncertainty,
        "normalized_disagreement_score": normalized_score,
    }
