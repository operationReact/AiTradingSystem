from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss

from .fingerprints import canonical_fingerprint

MODEL_MONITORING_VERSION = "model-monitoring.v1"
Hash64 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class MonitoringObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    trade_id: Annotated[str, Field(min_length=1, max_length=120)]
    observed_at: datetime
    registry_fingerprint: Hash64
    outcome_record_fingerprint: Hash64
    predicted_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    actual_positive: bool
    market_regime: Annotated[str, Field(min_length=1, max_length=120)]

    @field_validator("observed_at")
    @classmethod
    def observed_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("observed_at must be timezone-aware")
        return value


class ModelMonitoringConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_reference_records: Annotated[int, Field(ge=50, le=100_000)] = 100
    minimum_current_records: Annotated[int, Field(ge=50, le=100_000)] = 100
    probability_bins: Annotated[int, Field(ge=5, le=50)] = 10
    psi_watch_threshold: Annotated[float, Field(gt=0.0)] = 0.10
    psi_retrain_threshold: Annotated[float, Field(gt=0.0)] = 0.25
    brier_watch_regression: Annotated[float, Field(gt=0.0)] = 0.01
    brier_retrain_regression: Annotated[float, Field(gt=0.0)] = 0.03
    log_loss_watch_regression: Annotated[float, Field(gt=0.0)] = 0.02
    log_loss_retrain_regression: Annotated[float, Field(gt=0.0)] = 0.05
    ece_watch_regression: Annotated[float, Field(gt=0.0)] = 0.02
    ece_retrain_regression: Annotated[float, Field(gt=0.0)] = 0.05
    positive_rate_watch_delta: Annotated[float, Field(gt=0.0, le=1.0)] = 0.15
    positive_rate_retrain_delta: Annotated[float, Field(gt=0.0, le=1.0)] = 0.25

    @model_validator(mode="after")
    def severe_thresholds_must_exceed_watch_thresholds(self) -> ModelMonitoringConfig:
        pairs = (
            (self.psi_watch_threshold, self.psi_retrain_threshold, "psi"),
            (self.brier_watch_regression, self.brier_retrain_regression, "brier"),
            (self.log_loss_watch_regression, self.log_loss_retrain_regression, "log_loss"),
            (self.ece_watch_regression, self.ece_retrain_regression, "ece"),
            (
                self.positive_rate_watch_delta,
                self.positive_rate_retrain_delta,
                "positive_rate",
            ),
        )
        for watch, severe, name in pairs:
            if severe <= watch:
                raise ValueError(f"{name} retrain threshold must exceed watch threshold")
        return self


class MonitoringMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    records: int
    positive_rate: float
    mean_probability: float
    brier_score: float
    log_loss: float
    ece: float


class ModelMonitoringReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-monitoring.v1"] = MODEL_MONITORING_VERSION
    registry_fingerprint: Hash64
    config_fingerprint: Hash64
    reference_fingerprint: Hash64
    current_fingerprint: Hash64
    reference_metrics: MonitoringMetrics
    current_metrics: MonitoringMetrics
    probability_psi: float
    positive_rate_delta: float
    brier_delta: float
    log_loss_delta: float
    ece_delta: float
    assessment: Literal["STABLE", "WATCH", "RETRAINING_RECOMMENDED"]
    reason_codes: list[str]
    required_next_gate: Literal[
        "V2_09_CONTINUE_PAPER_MONITORING",
        "V2_09_CANDIDATE_RETRAINING",
    ]
    candidate_training_allowed: bool
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64


def evaluate_model_monitoring(
    *,
    reference: list[MonitoringObservation],
    current: list[MonitoringObservation],
    config: ModelMonitoringConfig | None = None,
) -> ModelMonitoringReport:
    cfg = config or ModelMonitoringConfig()
    _validate_windows(reference=reference, current=current, config=cfg)

    registry_fingerprint = reference[0].registry_fingerprint
    reference_sorted = sorted(reference, key=lambda row: (row.observed_at, row.trade_id))
    current_sorted = sorted(current, key=lambda row: (row.observed_at, row.trade_id))

    reference_metrics = _metrics(reference_sorted, cfg.probability_bins)
    current_metrics = _metrics(current_sorted, cfg.probability_bins)
    probability_psi = _probability_psi(
        reference_sorted,
        current_sorted,
        cfg.probability_bins,
    )
    positive_rate_delta = abs(current_metrics.positive_rate - reference_metrics.positive_rate)
    brier_delta = current_metrics.brier_score - reference_metrics.brier_score
    log_loss_delta = current_metrics.log_loss - reference_metrics.log_loss
    ece_delta = current_metrics.ece - reference_metrics.ece

    severe_reasons: list[str] = []
    watch_reasons: list[str] = []
    _classify_metric(
        "PREDICTION_PSI",
        probability_psi,
        cfg.psi_watch_threshold,
        cfg.psi_retrain_threshold,
        severe_reasons,
        watch_reasons,
    )
    _classify_metric(
        "POSITIVE_RATE_DRIFT",
        positive_rate_delta,
        cfg.positive_rate_watch_delta,
        cfg.positive_rate_retrain_delta,
        severe_reasons,
        watch_reasons,
    )
    _classify_regression(
        "BRIER_REGRESSION",
        brier_delta,
        cfg.brier_watch_regression,
        cfg.brier_retrain_regression,
        severe_reasons,
        watch_reasons,
    )
    _classify_regression(
        "LOG_LOSS_REGRESSION",
        log_loss_delta,
        cfg.log_loss_watch_regression,
        cfg.log_loss_retrain_regression,
        severe_reasons,
        watch_reasons,
    )
    _classify_regression(
        "ECE_REGRESSION",
        ece_delta,
        cfg.ece_watch_regression,
        cfg.ece_retrain_regression,
        severe_reasons,
        watch_reasons,
    )

    if severe_reasons:
        assessment = "RETRAINING_RECOMMENDED"
        reason_codes = sorted(severe_reasons + watch_reasons)
        required_next_gate = "V2_09_CANDIDATE_RETRAINING"
        candidate_training_allowed = True
    elif watch_reasons:
        assessment = "WATCH"
        reason_codes = sorted(watch_reasons)
        required_next_gate = "V2_09_CONTINUE_PAPER_MONITORING"
        candidate_training_allowed = False
    else:
        assessment = "STABLE"
        reason_codes = ["MONITORING_WITHIN_ACCEPTED_THRESHOLDS"]
        required_next_gate = "V2_09_CONTINUE_PAPER_MONITORING"
        candidate_training_allowed = False

    config_fingerprint = canonical_fingerprint(cfg.model_dump(mode="json"))
    reference_fingerprint = canonical_fingerprint(
        [row.model_dump(mode="json") for row in reference_sorted]
    )
    current_fingerprint = canonical_fingerprint(
        [row.model_dump(mode="json") for row in current_sorted]
    )
    report_payload = {
        "contract_version": MODEL_MONITORING_VERSION,
        "registry_fingerprint": registry_fingerprint,
        "config_fingerprint": config_fingerprint,
        "reference_fingerprint": reference_fingerprint,
        "current_fingerprint": current_fingerprint,
        "reference_metrics": reference_metrics.model_dump(mode="json"),
        "current_metrics": current_metrics.model_dump(mode="json"),
        "probability_psi": probability_psi,
        "positive_rate_delta": positive_rate_delta,
        "brier_delta": brier_delta,
        "log_loss_delta": log_loss_delta,
        "ece_delta": ece_delta,
        "assessment": assessment,
        "reason_codes": reason_codes,
        "required_next_gate": required_next_gate,
        "candidate_training_allowed": candidate_training_allowed,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelMonitoringReport(
        **report_payload,
        report_fingerprint=canonical_fingerprint(report_payload),
    )


def _validate_windows(
    *,
    reference: list[MonitoringObservation],
    current: list[MonitoringObservation],
    config: ModelMonitoringConfig,
) -> None:
    if len(reference) < config.minimum_reference_records:
        raise ValueError("reference monitoring window has insufficient records")
    if len(current) < config.minimum_current_records:
        raise ValueError("current monitoring window has insufficient records")

    all_rows = [*reference, *current]
    registry_fingerprints = {row.registry_fingerprint for row in all_rows}
    if len(registry_fingerprints) != 1:
        raise ValueError("monitoring windows must reference exactly one registered model")

    trade_ids = [row.trade_id for row in all_rows]
    if len(set(trade_ids)) != len(trade_ids):
        raise ValueError("monitoring windows must not contain duplicate or overlapping trade ids")

    if max(row.observed_at for row in reference) >= min(row.observed_at for row in current):
        raise ValueError("reference window must end before the current window begins")


def _metrics(rows: list[MonitoringObservation], bins: int) -> MonitoringMetrics:
    actual = np.asarray([int(row.actual_positive) for row in rows], dtype=int)
    probability = np.asarray([row.predicted_probability for row in rows], dtype=float)
    return MonitoringMetrics(
        records=len(rows),
        positive_rate=float(actual.mean()),
        mean_probability=float(probability.mean()),
        brier_score=float(brier_score_loss(actual, probability)),
        log_loss=float(log_loss(actual, probability, labels=[0, 1])),
        ece=_expected_calibration_error(actual, probability, bins),
    )


def _expected_calibration_error(
    actual: np.ndarray,
    probability: np.ndarray,
    bins: int,
) -> float:
    edges = np.linspace(0.0, 1.0, bins + 1)
    assignments = np.minimum(np.digitize(probability, edges[1:-1]), bins - 1)
    result = 0.0
    for bucket in range(bins):
        mask = assignments == bucket
        if not np.any(mask):
            continue
        weight = float(np.mean(mask))
        observed = float(actual[mask].mean())
        predicted = float(probability[mask].mean())
        result += weight * abs(observed - predicted)
    return result


def _probability_psi(
    reference: list[MonitoringObservation],
    current: list[MonitoringObservation],
    bins: int,
) -> float:
    edges = np.linspace(0.0, 1.0, bins + 1)
    reference_values = np.asarray([row.predicted_probability for row in reference], dtype=float)
    current_values = np.asarray([row.predicted_probability for row in current], dtype=float)
    reference_counts, _ = np.histogram(reference_values, bins=edges)
    current_counts, _ = np.histogram(current_values, bins=edges)
    epsilon = 1e-6
    reference_share = reference_counts / reference_counts.sum()
    current_share = current_counts / current_counts.sum()
    reference_share = np.clip(reference_share, epsilon, None)
    current_share = np.clip(current_share, epsilon, None)
    return float(
        np.sum(
            (current_share - reference_share)
            * np.log(current_share / reference_share)
        )
    )


def _classify_metric(
    code: str,
    value: float,
    watch_threshold: float,
    severe_threshold: float,
    severe_reasons: list[str],
    watch_reasons: list[str],
) -> None:
    if value >= severe_threshold:
        severe_reasons.append(f"{code}_SEVERE")
    elif value >= watch_threshold:
        watch_reasons.append(f"{code}_WATCH")


def _classify_regression(
    code: str,
    delta: float,
    watch_threshold: float,
    severe_threshold: float,
    severe_reasons: list[str],
    watch_reasons: list[str],
) -> None:
    _classify_metric(
        code,
        max(delta, 0.0),
        watch_threshold,
        severe_threshold,
        severe_reasons,
        watch_reasons,
    )
