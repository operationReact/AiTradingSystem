from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss

from .calibration_artifact import (
    CALIBRATION_ARTIFACT_VERSION,
    CalibrationArtifact,
    CalibrationMetrics,
    CalibrationObservation,
    TemperatureParameters,
    apply_calibration,
)
from .fingerprints import canonical_fingerprint
from .model_registry import ModelLabRegistryEntry

NEURAL_TEMPERATURE_BENCHMARK_VERSION = "neural-temperature-benchmark.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
SelectedMethod = Literal["RAW", "TEMPERATURE"]


class NeuralTemperatureConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    benchmark_version: Literal["neural-temperature-benchmark.v1"] = (
        NEURAL_TEMPERATURE_BENCHMARK_VERSION
    )
    fit_fraction: Annotated[float, Field(ge=0.5, le=0.8)] = 0.7
    minimum_fit_rows: Annotated[int, Field(ge=20, le=100_000)] = 30
    minimum_evaluation_rows: Annotated[int, Field(ge=20, le=100_000)] = 20
    calibration_bins: Annotated[int, Field(ge=5, le=50)] = 10
    minimum_temperature: Annotated[float, Field(gt=0.0, le=1.0)] = 0.05
    maximum_temperature: Annotated[float, Field(ge=1.0, le=100.0)] = 20.0
    search_iterations: Annotated[int, Field(ge=32, le=256)] = 96
    maximum_brier_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0
    maximum_log_loss_regression: Annotated[float, Field(ge=0.0, le=10.0)] = 0.0
    maximum_ece_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0

    @model_validator(mode="after")
    def validate_temperature_range(self) -> NeuralTemperatureConfig:
        if self.maximum_temperature <= self.minimum_temperature:
            raise ValueError("maximum_temperature must exceed minimum_temperature")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class NeuralTemperatureCalibrationArtifact(CalibrationArtifact):
    """Shared calibration-artifact.v1 specialized for neural temperature scaling."""

    model_family: Literal["PYTORCH"] = "PYTORCH"
    method: Literal["TEMPERATURE"] = "TEMPERATURE"
    parameters: TemperatureParameters


class NeuralTemperatureBenchmarkReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    benchmark_version: Literal["neural-temperature-benchmark.v1"] = (
        NEURAL_TEMPERATURE_BENCHMARK_VERSION
    )
    source_registry_fingerprint: Hash64
    model_family: Literal["PYTORCH"] = "PYTORCH"
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    benchmark_as_of: datetime
    config_fingerprint: Hash64
    fit_observation_fingerprints: list[Hash64] = Field(min_length=20)
    evaluation_observation_fingerprints: list[Hash64] = Field(min_length=20)
    purged_observation_fingerprints: list[Hash64]
    fit_dataset_fingerprint: Hash64
    evaluation_dataset_fingerprint: Hash64
    temperature_artifact_fingerprint: Hash64
    temperature: Annotated[float, Field(gt=0.0, le=100.0)]
    raw_probability_fingerprint: Hash64
    calibrated_probability_fingerprint: Hash64
    raw_metrics: CalibrationMetrics
    calibrated_metrics: CalibrationMetrics
    brier_delta_vs_raw: float
    log_loss_delta_vs_raw: float
    ece_delta_vs_raw: float
    selected_method: SelectedMethod
    selected_artifact_fingerprint: Hash64 | None = None
    selection_reason_codes: list[str]
    status: Literal["NEURAL_TEMPERATURE_BENCHMARK_ONLY"] = (
        "NEURAL_TEMPERATURE_BENCHMARK_ONLY"
    )
    required_next_gate: Literal["V3_05_CALIBRATION_FRESHNESS"] = (
        "V3_05_CALIBRATION_FRESHNESS"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @field_validator("benchmark_as_of")
    @classmethod
    def benchmark_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("benchmark_as_of must be timezone-aware")
        return value

    @field_validator(
        "brier_delta_vs_raw",
        "log_loss_delta_vs_raw",
        "ece_delta_vs_raw",
    )
    @classmethod
    def deltas_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("temperature benchmark deltas must be finite")
        return value

    @model_validator(mode="after")
    def validate_selection(self) -> NeuralTemperatureBenchmarkReport:
        if set(self.fit_observation_fingerprints) & set(
            self.evaluation_observation_fingerprints
        ):
            raise ValueError("temperature fit and evaluation observations must be disjoint")
        if self.selected_method == "RAW" and self.selected_artifact_fingerprint is not None:
            raise ValueError("RAW selection cannot reference a temperature artifact")
        if (
            self.selected_method == "TEMPERATURE"
            and self.selected_artifact_fingerprint != self.temperature_artifact_fingerprint
        ):
            raise ValueError("temperature selection must reference the temperature artifact")
        return self


def benchmark_neural_temperature_scaling(
    *,
    source_registry: ModelLabRegistryEntry,
    observations: Sequence[CalibrationObservation],
    benchmark_as_of: datetime,
    config: NeuralTemperatureConfig | None = None,
) -> NeuralTemperatureBenchmarkReport:
    cfg = config or NeuralTemperatureConfig()
    _validate_source_registry(source_registry, benchmark_as_of)
    eligible = _validated_observations(
        source_registry=source_registry,
        observations=observations,
        benchmark_as_of=benchmark_as_of,
    )

    required_rows = cfg.minimum_fit_rows + cfg.minimum_evaluation_rows
    if len(eligible) < required_rows:
        raise ValueError("insufficient closed observations for temperature benchmark")

    proposed_split = max(cfg.minimum_fit_rows, int(len(eligible) * cfg.fit_fraction))
    split_index = min(proposed_split, len(eligible) - cfg.minimum_evaluation_rows)
    evaluation_rows = list(eligible[split_index:])
    if len(evaluation_rows) < cfg.minimum_evaluation_rows:
        raise ValueError("insufficient held-out observations for temperature benchmark")
    evaluation_start = evaluation_rows[0].decision_at

    pre_evaluation_rows = list(eligible[:split_index])
    fit_rows = [
        row for row in pre_evaluation_rows if row.target_observed_at < evaluation_start
    ]
    purged_rows = [
        row for row in pre_evaluation_rows if row.target_observed_at >= evaluation_start
    ]
    if len(fit_rows) < cfg.minimum_fit_rows:
        raise ValueError("purged temperature fit set has fewer than minimum rows")
    _require_both_classes(fit_rows, "temperature fit")
    _require_both_classes(evaluation_rows, "temperature evaluation")

    fit_as_of = max(row.target_observed_at for row in fit_rows)
    if fit_as_of >= evaluation_start:
        raise ValueError("temperature fit labels must be observed before evaluation starts")

    temperature = _fit_temperature(fit_rows, cfg)
    parameters = TemperatureParameters(temperature=temperature)
    artifact = _create_temperature_artifact(
        source_registry=source_registry,
        observations=fit_rows,
        fit_as_of=fit_as_of,
        parameters=parameters,
        calibration_bins=cfg.calibration_bins,
    )

    labels = [int(row.actual_positive) for row in evaluation_rows]
    raw_probabilities = [row.raw_probability for row in evaluation_rows]
    calibrated_probabilities = [
        apply_calibration(probability, parameters) for probability in raw_probabilities
    ]
    raw_metrics = _metrics(labels, raw_probabilities, cfg.calibration_bins)
    calibrated_metrics = _metrics(labels, calibrated_probabilities, cfg.calibration_bins)
    brier_delta = calibrated_metrics.brier_score - raw_metrics.brier_score
    log_loss_delta = calibrated_metrics.log_loss - raw_metrics.log_loss
    ece_delta = (
        calibrated_metrics.expected_calibration_error
        - raw_metrics.expected_calibration_error
    )

    regressions: list[str] = []
    if brier_delta > cfg.maximum_brier_regression:
        regressions.append("BRIER_REGRESSION")
    if log_loss_delta > cfg.maximum_log_loss_regression:
        regressions.append("LOG_LOSS_REGRESSION")
    if ece_delta > cfg.maximum_ece_regression:
        regressions.append("ECE_REGRESSION")
    strictly_improves = min(brier_delta, log_loss_delta, ece_delta) < -1e-12
    if regressions:
        selected_method: SelectedMethod = "RAW"
        selection_reason_codes = sorted(regressions)
        selected_artifact_fingerprint = None
    elif strictly_improves:
        selected_method = "TEMPERATURE"
        selection_reason_codes = ["TEMPERATURE_NO_REGRESSION_AND_STRICT_IMPROVEMENT"]
        selected_artifact_fingerprint = artifact.artifact_fingerprint
    else:
        selected_method = "RAW"
        selection_reason_codes = ["RAW_RETAINED_ON_TIE"]
        selected_artifact_fingerprint = None

    fit_fingerprints = [row.observation_fingerprint for row in fit_rows]
    evaluation_fingerprints = [row.observation_fingerprint for row in evaluation_rows]
    purged_fingerprints = [row.observation_fingerprint for row in purged_rows]
    raw_probability_fingerprint = canonical_fingerprint(
        [round(value, 12) for value in raw_probabilities]
    )
    calibrated_probability_fingerprint = canonical_fingerprint(
        [round(value, 12) for value in calibrated_probabilities]
    )
    payload = {
        "benchmark_version": NEURAL_TEMPERATURE_BENCHMARK_VERSION,
        "source_registry_fingerprint": source_registry.registry_fingerprint,
        "model_family": "PYTORCH",
        "model_version": source_registry.model_version,
        "benchmark_as_of": benchmark_as_of.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "fit_observation_fingerprints": fit_fingerprints,
        "evaluation_observation_fingerprints": evaluation_fingerprints,
        "purged_observation_fingerprints": purged_fingerprints,
        "fit_dataset_fingerprint": _dataset_fingerprint(source_registry, fit_fingerprints),
        "evaluation_dataset_fingerprint": _dataset_fingerprint(
            source_registry,
            evaluation_fingerprints,
        ),
        "temperature_artifact_fingerprint": artifact.artifact_fingerprint,
        "temperature": temperature,
        "raw_probability_fingerprint": raw_probability_fingerprint,
        "calibrated_probability_fingerprint": calibrated_probability_fingerprint,
        "raw_metrics": raw_metrics.model_dump(mode="json"),
        "calibrated_metrics": calibrated_metrics.model_dump(mode="json"),
        "brier_delta_vs_raw": brier_delta,
        "log_loss_delta_vs_raw": log_loss_delta,
        "ece_delta_vs_raw": ece_delta,
        "selected_method": selected_method,
        "selected_artifact_fingerprint": selected_artifact_fingerprint,
        "selection_reason_codes": selection_reason_codes,
        "status": "NEURAL_TEMPERATURE_BENCHMARK_ONLY",
        "required_next_gate": "V3_05_CALIBRATION_FRESHNESS",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return NeuralTemperatureBenchmarkReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


def _validate_source_registry(
    source_registry: ModelLabRegistryEntry,
    benchmark_as_of: datetime,
) -> None:
    if benchmark_as_of.tzinfo is None or benchmark_as_of.utcoffset() is None:
        raise ValueError("benchmark_as_of must be timezone-aware")
    if source_registry.model_family != "PYTORCH":
        raise ValueError("temperature scaling is restricted to the PyTorch neural model")
    if source_registry.required_next_gate != "V3_05_CALIBRATION":
        raise ValueError("source registry entry is not routed to V3-05 calibration")
    if source_registry.promotion_allowed or source_registry.can_authorize_capital:
        raise ValueError("source registry entry cannot carry promotion or capital authority")
    if benchmark_as_of < source_registry.registered_at:
        raise ValueError("temperature benchmark cannot predate source model registration")


def _validated_observations(
    *,
    source_registry: ModelLabRegistryEntry,
    observations: Sequence[CalibrationObservation],
    benchmark_as_of: datetime,
) -> list[CalibrationObservation]:
    validated = [
        CalibrationObservation.model_validate(row.model_dump(mode="python"))
        for row in observations
    ]
    closed = [row for row in validated if row.target_observed_at <= benchmark_as_of]
    if any(row.decision_at <= source_registry.registered_at for row in closed):
        raise ValueError(
            "temperature calibration observations must occur after model registration"
        )
    ordered = sorted(
        closed,
        key=lambda row: (
            row.decision_at,
            row.symbol,
            row.model_prediction_fingerprint,
            row.observation_fingerprint,
        ),
    )
    if len({row.observation_fingerprint for row in ordered}) != len(ordered):
        raise ValueError("temperature benchmark observations must be unique")
    if {row.source_registry_fingerprint for row in ordered} != {
        source_registry.registry_fingerprint
    }:
        raise ValueError("temperature observations must match source registry")
    return ordered


def _fit_temperature(
    observations: Sequence[CalibrationObservation],
    config: NeuralTemperatureConfig,
) -> float:
    probabilities = [row.raw_probability for row in observations]
    labels = [int(row.actual_positive) for row in observations]
    lower = math.log(config.minimum_temperature)
    upper = math.log(config.maximum_temperature)
    golden_ratio = (math.sqrt(5.0) - 1.0) / 2.0
    left = upper - (golden_ratio * (upper - lower))
    right = lower + (golden_ratio * (upper - lower))
    left_loss = _temperature_log_loss(probabilities, labels, math.exp(left))
    right_loss = _temperature_log_loss(probabilities, labels, math.exp(right))

    for _ in range(config.search_iterations):
        if left_loss <= right_loss:
            upper = right
            right = left
            right_loss = left_loss
            left = upper - (golden_ratio * (upper - lower))
            left_loss = _temperature_log_loss(probabilities, labels, math.exp(left))
        else:
            lower = left
            left = right
            left_loss = right_loss
            right = lower + (golden_ratio * (upper - lower))
            right_loss = _temperature_log_loss(probabilities, labels, math.exp(right))

    temperature = math.exp((lower + upper) / 2.0)
    return float(min(max(temperature, config.minimum_temperature), config.maximum_temperature))


def _temperature_log_loss(
    probabilities: Sequence[float],
    labels: Sequence[int],
    temperature: float,
) -> float:
    parameters = TemperatureParameters(temperature=temperature)
    calibrated = [apply_calibration(value, parameters) for value in probabilities]
    return float(log_loss(labels, calibrated, labels=[0, 1]))


def _create_temperature_artifact(
    *,
    source_registry: ModelLabRegistryEntry,
    observations: Sequence[CalibrationObservation],
    fit_as_of: datetime,
    parameters: TemperatureParameters,
    calibration_bins: int,
) -> NeuralTemperatureCalibrationArtifact:
    ordered = sorted(
        observations,
        key=lambda row: (
            row.decision_at,
            row.symbol,
            row.model_prediction_fingerprint,
            row.observation_fingerprint,
        ),
    )
    if not ordered:
        raise ValueError("temperature artifact requires calibration observations")
    if any(row.decision_at <= source_registry.registered_at for row in ordered):
        raise ValueError("temperature artifact cannot reuse pre-registration observations")
    if any(row.target_observed_at > fit_as_of for row in ordered):
        raise ValueError("temperature artifact cannot use future labels")
    if {row.source_registry_fingerprint for row in ordered} != {
        source_registry.registry_fingerprint
    }:
        raise ValueError("temperature artifact observations must match source registry")
    _require_both_classes(ordered, "temperature artifact")

    labels = [int(row.actual_positive) for row in ordered]
    raw_probabilities = [row.raw_probability for row in ordered]
    calibrated_probabilities = [
        apply_calibration(probability, parameters) for probability in raw_probabilities
    ]
    raw_metrics = _metrics(labels, raw_probabilities, calibration_bins)
    calibrated_metrics = _metrics(labels, calibrated_probabilities, calibration_bins)
    fingerprints = [row.observation_fingerprint for row in ordered]
    payload = {
        "contract_version": CALIBRATION_ARTIFACT_VERSION,
        "source_registry_fingerprint": source_registry.registry_fingerprint,
        "model_family": "PYTORCH",
        "model_version": source_registry.model_version,
        "model_artifact_fingerprint": source_registry.artifact_fingerprint,
        "target_spec_fingerprint": source_registry.target_spec_fingerprint,
        "feature_schema_fingerprint": source_registry.feature_schema_fingerprint,
        "fit_as_of": fit_as_of.isoformat(),
        "fit_start": ordered[0].decision_at.isoformat(),
        "fit_end": ordered[-1].target_observed_at.isoformat(),
        "calibration_bins": calibration_bins,
        "method": "TEMPERATURE",
        "parameters": parameters.model_dump(mode="json"),
        "observation_fingerprints": fingerprints,
        "calibration_dataset_fingerprint": _dataset_fingerprint(
            source_registry,
            fingerprints,
        ),
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
    return NeuralTemperatureCalibrationArtifact(
        **payload,
        artifact_fingerprint=canonical_fingerprint(payload),
    )


def _dataset_fingerprint(
    source_registry: ModelLabRegistryEntry,
    observation_fingerprints: Sequence[str],
) -> str:
    return canonical_fingerprint(
        {
            "source_registry_fingerprint": source_registry.registry_fingerprint,
            "observation_fingerprints": list(observation_fingerprints),
        }
    )


def _require_both_classes(
    observations: Sequence[CalibrationObservation],
    label: str,
) -> None:
    if {row.actual_positive for row in observations} != {False, True}:
        raise ValueError(f"{label} rows must contain both outcome classes")


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
