from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss

from .calibration_artifact import (
    CalibrationArtifact,
    CalibrationMetrics,
    CalibrationObservation,
    IsotonicParameters,
    PlattParameters,
    apply_calibration,
    create_calibration_artifact,
)
from .fingerprints import canonical_fingerprint
from .model_registry import ModelLabRegistryEntry

CALIBRATION_BENCHMARK_VERSION = "calibration-benchmark.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
SelectedMethod = Literal["RAW", "PLATT", "ISOTONIC"]


class CalibrationBenchmarkConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    benchmark_version: Literal["calibration-benchmark.v1"] = CALIBRATION_BENCHMARK_VERSION
    fit_fraction: Annotated[float, Field(ge=0.5, le=0.8)] = 0.7
    minimum_fit_rows: Annotated[int, Field(ge=20, le=100_000)] = 30
    minimum_evaluation_rows: Annotated[int, Field(ge=10, le=100_000)] = 20
    calibration_bins: Annotated[int, Field(ge=5, le=50)] = 10
    maximum_brier_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0
    maximum_log_loss_regression: Annotated[float, Field(ge=0.0, le=10.0)] = 0.0
    maximum_ece_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CalibrationCandidateEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    method: SelectedMethod
    artifact_fingerprint: Hash64 | None = None
    probability_fingerprint: Hash64
    metrics: CalibrationMetrics
    brier_delta_vs_raw: float
    log_loss_delta_vs_raw: float
    ece_delta_vs_raw: float
    eligible_for_selection: bool
    reason_codes: list[str]

    @field_validator(
        "brier_delta_vs_raw",
        "log_loss_delta_vs_raw",
        "ece_delta_vs_raw",
    )
    @classmethod
    def deltas_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("calibration benchmark deltas must be finite")
        return value

    @model_validator(mode="after")
    def validate_raw_candidate(self) -> CalibrationCandidateEvaluation:
        if self.method == "RAW" and self.artifact_fingerprint is not None:
            raise ValueError("RAW benchmark candidate cannot reference a calibration artifact")
        if self.method != "RAW" and self.artifact_fingerprint is None:
            raise ValueError("calibrated benchmark candidate requires an artifact fingerprint")
        return self


class CalibrationBenchmarkReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    benchmark_version: Literal["calibration-benchmark.v1"] = CALIBRATION_BENCHMARK_VERSION
    source_registry_fingerprint: Hash64
    model_family: Literal["LIGHTGBM", "CATBOOST"]
    model_version: str
    benchmark_as_of: datetime
    config_fingerprint: Hash64
    fit_observation_fingerprints: list[Hash64] = Field(min_length=20)
    evaluation_observation_fingerprints: list[Hash64] = Field(min_length=10)
    purged_observation_fingerprints: list[Hash64]
    fit_dataset_fingerprint: Hash64
    evaluation_dataset_fingerprint: Hash64
    platt_artifact_fingerprint: Hash64
    isotonic_artifact_fingerprint: Hash64
    candidates: list[CalibrationCandidateEvaluation] = Field(min_length=3, max_length=3)
    selected_method: SelectedMethod
    selected_artifact_fingerprint: Hash64 | None
    selection_reason_codes: list[str]
    status: Literal["CALIBRATION_BENCHMARK_ONLY"] = "CALIBRATION_BENCHMARK_ONLY"
    required_next_gate: Literal["V3_05_TEMPERATURE_SCALING"] = "V3_05_TEMPERATURE_SCALING"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @field_validator("benchmark_as_of")
    @classmethod
    def benchmark_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("benchmark_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_report(self) -> CalibrationBenchmarkReport:
        methods = {candidate.method for candidate in self.candidates}
        if methods != {"RAW", "PLATT", "ISOTONIC"}:
            raise ValueError("benchmark must contain RAW, PLATT and ISOTONIC candidates")
        if set(self.fit_observation_fingerprints) & set(
            self.evaluation_observation_fingerprints
        ):
            raise ValueError("fit and evaluation observations must be disjoint")
        selected = [
            candidate
            for candidate in self.candidates
            if candidate.method == self.selected_method
        ]
        if len(selected) != 1:
            raise ValueError("selected benchmark method must identify exactly one candidate")
        if self.selected_method == "RAW" and self.selected_artifact_fingerprint is not None:
            raise ValueError("RAW selection cannot reference a calibration artifact")
        if self.selected_method != "RAW":
            if self.selected_artifact_fingerprint != selected[0].artifact_fingerprint:
                raise ValueError("selected artifact fingerprint must match selected candidate")
        return self


def benchmark_platt_and_isotonic(
    *,
    source_registry: ModelLabRegistryEntry,
    observations: Sequence[CalibrationObservation],
    benchmark_as_of: datetime,
    config: CalibrationBenchmarkConfig | None = None,
) -> CalibrationBenchmarkReport:
    cfg = config or CalibrationBenchmarkConfig()
    if benchmark_as_of.tzinfo is None or benchmark_as_of.utcoffset() is None:
        raise ValueError("benchmark_as_of must be timezone-aware")
    if source_registry.promotion_allowed or source_registry.can_authorize_capital:
        raise ValueError("source registry entry cannot carry promotion or capital authority")
    if source_registry.required_next_gate != "V3_05_CALIBRATION":
        raise ValueError("source registry entry is not routed to V3-05 calibration")
    if benchmark_as_of < source_registry.evaluation_as_of:
        raise ValueError("benchmark cannot predate source model evaluation")

    eligible = _validated_observations(
        source_registry=source_registry,
        observations=observations,
        benchmark_as_of=benchmark_as_of,
    )
    required_rows = cfg.minimum_fit_rows + cfg.minimum_evaluation_rows
    if len(eligible) < required_rows:
        raise ValueError("insufficient closed observations for calibration benchmark")

    proposed_split = max(
        cfg.minimum_fit_rows,
        int(len(eligible) * cfg.fit_fraction),
    )
    maximum_split = len(eligible) - cfg.minimum_evaluation_rows
    split_index = min(proposed_split, maximum_split)
    evaluation_rows = list(eligible[split_index:])
    if len(evaluation_rows) < cfg.minimum_evaluation_rows:
        raise ValueError("insufficient held-out observations for calibration benchmark")
    evaluation_start = evaluation_rows[0].decision_at

    pre_evaluation_rows = list(eligible[:split_index])
    fit_rows = [
        row for row in pre_evaluation_rows if row.target_observed_at < evaluation_start
    ]
    purged_rows = [
        row for row in pre_evaluation_rows if row.target_observed_at >= evaluation_start
    ]
    if len(fit_rows) < cfg.minimum_fit_rows:
        raise ValueError("purged calibration fit set has fewer than minimum rows")
    if {row.actual_positive for row in fit_rows} != {False, True}:
        raise ValueError("calibration fit rows must contain both outcome classes")
    if {row.actual_positive for row in evaluation_rows} != {False, True}:
        raise ValueError("calibration evaluation rows must contain both outcome classes")

    fit_as_of = max(row.target_observed_at for row in fit_rows)
    if fit_as_of >= evaluation_start:
        raise ValueError("calibration fit labels must be observed before evaluation starts")

    platt_parameters = _fit_platt(fit_rows)
    isotonic_parameters = _fit_isotonic(fit_rows)
    platt_artifact = create_calibration_artifact(
        source_registry=source_registry,
        observations=fit_rows,
        fit_as_of=fit_as_of,
        parameters=platt_parameters,
        calibration_bins=cfg.calibration_bins,
    )
    isotonic_artifact = create_calibration_artifact(
        source_registry=source_registry,
        observations=fit_rows,
        fit_as_of=fit_as_of,
        parameters=isotonic_parameters,
        calibration_bins=cfg.calibration_bins,
    )

    labels = [int(row.actual_positive) for row in evaluation_rows]
    raw_probabilities = [row.raw_probability for row in evaluation_rows]
    platt_probabilities = [
        apply_calibration(probability, platt_parameters)
        for probability in raw_probabilities
    ]
    isotonic_probabilities = [
        apply_calibration(probability, isotonic_parameters)
        for probability in raw_probabilities
    ]
    raw_metrics = _metrics(labels, raw_probabilities, cfg.calibration_bins)
    candidates = [
        _candidate(
            method="RAW",
            artifact=None,
            probabilities=raw_probabilities,
            metrics=raw_metrics,
            raw_metrics=raw_metrics,
            config=cfg,
        ),
        _candidate(
            method="PLATT",
            artifact=platt_artifact,
            probabilities=platt_probabilities,
            metrics=_metrics(labels, platt_probabilities, cfg.calibration_bins),
            raw_metrics=raw_metrics,
            config=cfg,
        ),
        _candidate(
            method="ISOTONIC",
            artifact=isotonic_artifact,
            probabilities=isotonic_probabilities,
            metrics=_metrics(labels, isotonic_probabilities, cfg.calibration_bins),
            raw_metrics=raw_metrics,
            config=cfg,
        ),
    ]
    selected_method, selection_reason_codes = _select_candidate(candidates)
    selected = next(candidate for candidate in candidates if candidate.method == selected_method)

    fit_fingerprints = [row.observation_fingerprint for row in fit_rows]
    evaluation_fingerprints = [row.observation_fingerprint for row in evaluation_rows]
    purged_fingerprints = [row.observation_fingerprint for row in purged_rows]
    fit_dataset_fingerprint = canonical_fingerprint(
        {
            "source_registry_fingerprint": source_registry.registry_fingerprint,
            "observation_fingerprints": fit_fingerprints,
        }
    )
    evaluation_dataset_fingerprint = canonical_fingerprint(
        {
            "source_registry_fingerprint": source_registry.registry_fingerprint,
            "observation_fingerprints": evaluation_fingerprints,
        }
    )
    payload = {
        "benchmark_version": CALIBRATION_BENCHMARK_VERSION,
        "source_registry_fingerprint": source_registry.registry_fingerprint,
        "model_family": source_registry.model_family,
        "model_version": source_registry.model_version,
        "benchmark_as_of": benchmark_as_of.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "fit_observation_fingerprints": fit_fingerprints,
        "evaluation_observation_fingerprints": evaluation_fingerprints,
        "purged_observation_fingerprints": purged_fingerprints,
        "fit_dataset_fingerprint": fit_dataset_fingerprint,
        "evaluation_dataset_fingerprint": evaluation_dataset_fingerprint,
        "platt_artifact_fingerprint": platt_artifact.artifact_fingerprint,
        "isotonic_artifact_fingerprint": isotonic_artifact.artifact_fingerprint,
        "candidates": [candidate.model_dump(mode="json") for candidate in candidates],
        "selected_method": selected_method,
        "selected_artifact_fingerprint": selected.artifact_fingerprint,
        "selection_reason_codes": selection_reason_codes,
        "status": "CALIBRATION_BENCHMARK_ONLY",
        "required_next_gate": "V3_05_TEMPERATURE_SCALING",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CalibrationBenchmarkReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


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
    ordered = sorted(
        (
            row
            for row in validated
            if row.target_observed_at <= benchmark_as_of
        ),
        key=lambda row: (
            row.decision_at,
            row.symbol,
            row.model_prediction_fingerprint,
            row.observation_fingerprint,
        ),
    )
    if len({row.observation_fingerprint for row in ordered}) != len(ordered):
        raise ValueError("calibration benchmark observations must be unique")
    if {row.source_registry_fingerprint for row in ordered} != {
        source_registry.registry_fingerprint
    }:
        raise ValueError("calibration benchmark observations must match source registry")
    return ordered


def _fit_platt(observations: Sequence[CalibrationObservation]) -> PlattParameters:
    probabilities = [row.raw_probability for row in observations]
    labels = [int(row.actual_positive) for row in observations]
    logits = [[_logit(probability)] for probability in probabilities]
    model = LogisticRegression(
        solver="lbfgs",
        penalty=None,
        max_iter=1_000,
        tol=1e-10,
    )
    model.fit(logits, labels)
    return PlattParameters(
        slope=float(model.coef_[0][0]),
        intercept=float(model.intercept_[0]),
    )


def _fit_isotonic(observations: Sequence[CalibrationObservation]) -> IsotonicParameters:
    probabilities = [row.raw_probability for row in observations]
    labels = [int(row.actual_positive) for row in observations]
    model = IsotonicRegression(
        y_min=0.0,
        y_max=1.0,
        increasing=True,
        out_of_bounds="clip",
    )
    model.fit(probabilities, labels)
    return IsotonicParameters(
        x_thresholds=[float(value) for value in model.X_thresholds_],
        y_thresholds=[float(value) for value in model.y_thresholds_],
    )


def _candidate(
    *,
    method: SelectedMethod,
    artifact: CalibrationArtifact | None,
    probabilities: Sequence[float],
    metrics: CalibrationMetrics,
    raw_metrics: CalibrationMetrics,
    config: CalibrationBenchmarkConfig,
) -> CalibrationCandidateEvaluation:
    brier_delta = metrics.brier_score - raw_metrics.brier_score
    log_loss_delta = metrics.log_loss - raw_metrics.log_loss
    ece_delta = metrics.expected_calibration_error - raw_metrics.expected_calibration_error
    reasons: list[str] = []
    if method == "RAW":
        reasons.append("RAW_REFERENCE")
    else:
        if brier_delta > config.maximum_brier_regression:
            reasons.append("BRIER_REGRESSION")
        if log_loss_delta > config.maximum_log_loss_regression:
            reasons.append("LOG_LOSS_REGRESSION")
        if ece_delta > config.maximum_ece_regression:
            reasons.append("ECE_REGRESSION")
        if not reasons:
            reasons.append("NO_CALIBRATION_METRIC_REGRESSION")
    return CalibrationCandidateEvaluation(
        method=method,
        artifact_fingerprint=(artifact.artifact_fingerprint if artifact else None),
        probability_fingerprint=canonical_fingerprint(
            [round(float(value), 12) for value in probabilities]
        ),
        metrics=metrics,
        brier_delta_vs_raw=float(brier_delta),
        log_loss_delta_vs_raw=float(log_loss_delta),
        ece_delta_vs_raw=float(ece_delta),
        eligible_for_selection=(method == "RAW" or len(reasons) == 1 and reasons[0] == "NO_CALIBRATION_METRIC_REGRESSION"),
        reason_codes=reasons,
    )


def _select_candidate(
    candidates: Sequence[CalibrationCandidateEvaluation],
) -> tuple[SelectedMethod, list[str]]:
    calibrated = [
        candidate
        for candidate in candidates
        if candidate.method != "RAW" and candidate.eligible_for_selection
    ]
    if not calibrated:
        return "RAW", ["NO_CALIBRATOR_PASSED_NON_REGRESSION_GATES"]

    selected = min(
        calibrated,
        key=lambda candidate: (
            candidate.metrics.brier_score,
            candidate.metrics.log_loss,
            candidate.metrics.expected_calibration_error,
            candidate.method,
        ),
    )
    raw = next(candidate for candidate in candidates if candidate.method == "RAW")
    if (
        selected.metrics.brier_score == raw.metrics.brier_score
        and selected.metrics.log_loss == raw.metrics.log_loss
        and selected.metrics.expected_calibration_error
        == raw.metrics.expected_calibration_error
    ):
        return "RAW", ["CALIBRATOR_TIED_RAW_REFERENCE"]
    return selected.method, ["BEST_NON_REGRESSING_HELD_OUT_CALIBRATOR"]


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


def _logit(probability: float) -> float:
    clipped = min(max(probability, 1e-12), 1.0 - 1e-12)
    return math.log(clipped / (1.0 - clipped))
