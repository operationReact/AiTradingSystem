from __future__ import annotations

import json
import math
from bisect import bisect_right
from collections.abc import Sequence
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss

from .fingerprints import canonical_fingerprint
from .model_registry import ModelLabRegistryEntry

CALIBRATION_ARTIFACT_VERSION = "calibration-artifact.v1"
CALIBRATION_OBSERVATION_VERSION = "calibration-observation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CalibrationMethod = Literal["PLATT", "ISOTONIC", "TEMPERATURE"]


class PlattParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    method: Literal["PLATT"] = "PLATT"
    slope: float
    intercept: float

    @field_validator("slope", "intercept")
    @classmethod
    def parameters_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("Platt parameters must be finite")
        return value


class IsotonicParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    method: Literal["ISOTONIC"] = "ISOTONIC"
    x_thresholds: list[Annotated[float, Field(ge=0.0, le=1.0)]] = Field(
        min_length=2,
        max_length=256,
    )
    y_thresholds: list[Annotated[float, Field(ge=0.0, le=1.0)]] = Field(
        min_length=2,
        max_length=256,
    )

    @model_validator(mode="after")
    def validate_thresholds(self) -> IsotonicParameters:
        if len(self.x_thresholds) != len(self.y_thresholds):
            raise ValueError("isotonic x/y thresholds must have equal length")
        if any(
            right <= left
            for left, right in zip(
                self.x_thresholds,
                self.x_thresholds[1:],
                strict=False,
            )
        ):
            raise ValueError("isotonic x thresholds must be strictly increasing")
        if any(
            right < left
            for left, right in zip(
                self.y_thresholds,
                self.y_thresholds[1:],
                strict=False,
            )
        ):
            raise ValueError("isotonic y thresholds must be nondecreasing")
        return self


class TemperatureParameters(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    method: Literal["TEMPERATURE"] = "TEMPERATURE"
    temperature: Annotated[float, Field(gt=0.0, le=100.0)]

    @field_validator("temperature")
    @classmethod
    def temperature_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("temperature must be finite")
        return value


CalibrationParameters = Annotated[
    PlattParameters | IsotonicParameters | TemperatureParameters,
    Field(discriminator="method"),
]


class CalibrationObservation(BaseModel):
    """Closed-label evidence used only for offline calibration fitting/evaluation."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["calibration-observation.v1"] = CALIBRATION_OBSERVATION_VERSION
    symbol: Annotated[str, Field(min_length=1, max_length=64)]
    decision_at: datetime
    target_observed_at: datetime
    source_registry_fingerprint: Hash64
    model_prediction_fingerprint: Hash64
    raw_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    actual_positive: bool
    observation_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_at", "target_observed_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("calibration observation timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_observation(self) -> CalibrationObservation:
        if self.target_observed_at <= self.decision_at:
            raise ValueError("calibration target must be observed after decision time")
        payload = self.model_dump(mode="json", exclude={"observation_fingerprint"})
        payload["decision_at"] = self.decision_at.isoformat()
        payload["target_observed_at"] = self.target_observed_at.isoformat()
        expected = canonical_fingerprint(payload)
        if self.observation_fingerprint != expected:
            raise ValueError("calibration observation fingerprint mismatch")
        return self


class CalibrationMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    rows: Annotated[int, Field(ge=20)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    expected_calibration_error: Annotated[float, Field(ge=0.0, le=1.0)]


class CalibrationArtifact(BaseModel):
    """Immutable research calibration evidence; never an activation decision."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["calibration-artifact.v1"] = CALIBRATION_ARTIFACT_VERSION
    source_registry_fingerprint: Hash64
    model_family: Literal["LIGHTGBM", "CATBOOST"]
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    model_artifact_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    feature_schema_fingerprint: Hash64
    fit_as_of: datetime
    fit_start: datetime
    fit_end: datetime
    calibration_bins: Annotated[int, Field(ge=5, le=50)]
    method: CalibrationMethod
    parameters: CalibrationParameters
    observation_fingerprints: list[Hash64] = Field(min_length=20, max_length=1_000_000)
    calibration_dataset_fingerprint: Hash64
    raw_probability_fingerprint: Hash64
    calibrated_probability_fingerprint: Hash64
    raw_metrics: CalibrationMetrics
    calibrated_metrics: CalibrationMetrics
    status: Literal["CALIBRATION_RESEARCH_ONLY"] = "CALIBRATION_RESEARCH_ONLY"
    required_next_gate: Literal["V3_05_CALIBRATION_BENCHMARK"] = (
        "V3_05_CALIBRATION_BENCHMARK"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    artifact_fingerprint: Hash64

    @field_validator("fit_as_of", "fit_start", "fit_end")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("calibration artifact timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_artifact(self) -> CalibrationArtifact:
        if self.fit_start > self.fit_end:
            raise ValueError("fit_start cannot exceed fit_end")
        if self.fit_end > self.fit_as_of:
            raise ValueError("fit_end cannot exceed fit_as_of")
        if self.parameters.method != self.method:
            raise ValueError("calibration method must match parameter type")
        if len(set(self.observation_fingerprints)) != len(self.observation_fingerprints):
            raise ValueError("calibration observations must be unique")
        payload = self.model_dump(mode="json", exclude={"artifact_fingerprint"})
        payload["fit_as_of"] = self.fit_as_of.isoformat()
        payload["fit_start"] = self.fit_start.isoformat()
        payload["fit_end"] = self.fit_end.isoformat()
        expected = canonical_fingerprint(payload)
        if self.artifact_fingerprint != expected:
            raise ValueError("calibration artifact fingerprint mismatch")
        return self


def build_calibration_observation(
    *,
    symbol: str,
    decision_at: datetime,
    target_observed_at: datetime,
    source_registry_fingerprint: str,
    model_prediction_fingerprint: str,
    raw_probability: float,
    actual_positive: bool,
) -> CalibrationObservation:
    payload = {
        "contract_version": CALIBRATION_OBSERVATION_VERSION,
        "symbol": symbol,
        "decision_at": decision_at.isoformat(),
        "target_observed_at": target_observed_at.isoformat(),
        "source_registry_fingerprint": source_registry_fingerprint,
        "model_prediction_fingerprint": model_prediction_fingerprint,
        "raw_probability": raw_probability,
        "actual_positive": actual_positive,
        "can_authorize_capital": False,
    }
    return CalibrationObservation(
        symbol=symbol,
        decision_at=decision_at,
        target_observed_at=target_observed_at,
        source_registry_fingerprint=source_registry_fingerprint,
        model_prediction_fingerprint=model_prediction_fingerprint,
        raw_probability=raw_probability,
        actual_positive=actual_positive,
        observation_fingerprint=canonical_fingerprint(payload),
    )


def apply_calibration(
    probability: float,
    parameters: CalibrationParameters,
) -> float:
    if not math.isfinite(probability) or probability < 0.0 or probability > 1.0:
        raise ValueError("raw probability must be finite and in [0, 1]")

    if parameters.method == "ISOTONIC":
        return _apply_isotonic(probability, parameters)

    clipped = min(max(probability, 1e-12), 1.0 - 1e-12)
    logit = math.log(clipped / (1.0 - clipped))
    if parameters.method == "PLATT":
        calibrated_logit = (parameters.slope * logit) + parameters.intercept
    else:
        calibrated_logit = logit / parameters.temperature
    return _sigmoid(calibrated_logit)


def create_calibration_artifact(
    *,
    source_registry: ModelLabRegistryEntry,
    observations: Sequence[CalibrationObservation],
    fit_as_of: datetime,
    parameters: CalibrationParameters,
    calibration_bins: int = 10,
) -> CalibrationArtifact:
    if source_registry.promotion_allowed or source_registry.can_authorize_capital:
        raise ValueError("source registry entry cannot carry promotion or capital authority")
    if source_registry.required_next_gate != "V3_05_CALIBRATION":
        raise ValueError("source registry entry is not routed to V3-05 calibration")
    if fit_as_of.tzinfo is None or fit_as_of.utcoffset() is None:
        raise ValueError("fit_as_of must be timezone-aware")
    if fit_as_of < source_registry.evaluation_as_of:
        raise ValueError("calibration fit cannot predate source model evaluation")
    if not 5 <= calibration_bins <= 50:
        raise ValueError("calibration_bins must be between 5 and 50")
    if len(observations) < 20:
        raise ValueError("at least 20 closed calibration observations are required")

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
    if len({row.observation_fingerprint for row in ordered}) != len(ordered):
        raise ValueError("calibration observations must be unique")
    if {row.source_registry_fingerprint for row in ordered} != {
        source_registry.registry_fingerprint
    }:
        raise ValueError("calibration observations must match the exact source registry entry")
    if any(row.target_observed_at > fit_as_of for row in ordered):
        raise ValueError("calibration cannot use targets observed after fit_as_of")

    labels = [int(row.actual_positive) for row in ordered]
    if set(labels) != {0, 1}:
        raise ValueError("calibration observations must contain both outcome classes")
    raw_probabilities = [row.raw_probability for row in ordered]
    calibrated_probabilities = [
        apply_calibration(probability, parameters) for probability in raw_probabilities
    ]
    raw_metrics = _metrics(labels, raw_probabilities, calibration_bins)
    calibrated_metrics = _metrics(labels, calibrated_probabilities, calibration_bins)
    observation_fingerprints = [row.observation_fingerprint for row in ordered]
    calibration_dataset_fingerprint = canonical_fingerprint(
        {
            "source_registry_fingerprint": source_registry.registry_fingerprint,
            "observation_fingerprints": observation_fingerprints,
        }
    )
    payload = {
        "contract_version": CALIBRATION_ARTIFACT_VERSION,
        "source_registry_fingerprint": source_registry.registry_fingerprint,
        "model_family": source_registry.model_family,
        "model_version": source_registry.model_version,
        "model_artifact_fingerprint": source_registry.artifact_fingerprint,
        "target_spec_fingerprint": source_registry.target_spec_fingerprint,
        "feature_schema_fingerprint": source_registry.feature_schema_fingerprint,
        "fit_as_of": fit_as_of.isoformat(),
        "fit_start": ordered[0].decision_at.isoformat(),
        "fit_end": ordered[-1].target_observed_at.isoformat(),
        "calibration_bins": calibration_bins,
        "method": parameters.method,
        "parameters": parameters.model_dump(mode="json"),
        "observation_fingerprints": observation_fingerprints,
        "calibration_dataset_fingerprint": calibration_dataset_fingerprint,
        "raw_probability_fingerprint": canonical_fingerprint(
            [round(value, 12) for value in raw_probabilities]
        ),
        "calibrated_probability_fingerprint": canonical_fingerprint(
            [round(value, 12) for value in calibrated_probabilities]
        ),
        "raw_metrics": raw_metrics.model_dump(mode="json"),
        "calibrated_metrics": calibrated_metrics.model_dump(mode="json"),
        "status": "CALIBRATION_RESEARCH_ONLY",
        "required_next_gate": "V3_05_CALIBRATION_BENCHMARK",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibrationArtifact(
        **payload,
        artifact_fingerprint=canonical_fingerprint(payload),
    )


def write_calibration_artifact(path: Path, artifact: CalibrationArtifact) -> Path:
    """Persist once; identical retries are idempotent and conflicting writes fail closed."""

    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = _canonical_json(artifact)
    try:
        with path.open("x", encoding="utf-8", newline="\n") as handle:
            handle.write(serialized)
            handle.write("\n")
    except FileExistsError as exc:
        existing = load_calibration_artifact(path)
        if existing != artifact:
            raise FileExistsError(
                "calibration artifact path already contains different evidence"
            ) from exc
    return path


def load_calibration_artifact(path: Path) -> CalibrationArtifact:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return CalibrationArtifact.model_validate(payload)


def _canonical_json(artifact: CalibrationArtifact) -> str:
    return json.dumps(
        artifact.model_dump(mode="json"),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )


def _sigmoid(value: float) -> float:
    if value >= 0.0:
        return 1.0 / (1.0 + math.exp(-value))
    exp_value = math.exp(value)
    return exp_value / (1.0 + exp_value)


def _apply_isotonic(probability: float, parameters: IsotonicParameters) -> float:
    x_values = parameters.x_thresholds
    y_values = parameters.y_thresholds
    if probability <= x_values[0]:
        return y_values[0]
    if probability >= x_values[-1]:
        return y_values[-1]
    right = bisect_right(x_values, probability)
    left = right - 1
    weight = (probability - x_values[left]) / (x_values[right] - x_values[left])
    return y_values[left] + (weight * (y_values[right] - y_values[left]))


def _metrics(
    labels: Sequence[int],
    probabilities: Sequence[float],
    bins: int,
) -> CalibrationMetrics:
    return CalibrationMetrics(
        rows=len(labels),
        positive_rows=sum(labels),
        negative_rows=len(labels) - sum(labels),
        brier_score=float(brier_score_loss(labels, probabilities)),
        log_loss=float(log_loss(labels, probabilities, labels=[0, 1])),
        expected_calibration_error=_expected_calibration_error(labels, probabilities, bins),
    )


def _expected_calibration_error(
    labels: Sequence[int],
    probabilities: Sequence[float],
    bins: int,
) -> float:
    total = len(labels)
    error = 0.0
    for bin_index in range(bins):
        lower = bin_index / bins
        upper = (bin_index + 1) / bins
        members = [
            index
            for index, probability in enumerate(probabilities)
            if probability >= lower
            and (probability < upper or (bin_index == bins - 1 and probability <= upper))
        ]
        if not members:
            continue
        mean_probability = sum(probabilities[index] for index in members) / len(members)
        observed_rate = sum(labels[index] for index in members) / len(members)
        error += (len(members) / total) * abs(mean_probability - observed_rate)
    return float(error)
