from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .cross_model_causal_cohort import CrossModelCausalCohort
from .fingerprints import canonical_fingerprint
from .incremental_economic_value import create_net_return_series
from .model_diversity import build_prediction_series
from .structured_alpha_tournament import TournamentCandidate

VERSION = "india-v4-forecast-directional-bridge.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class DirectionalCalibrationConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-v4-forecast-directional-bridge.v1"] = VERSION
    minimum_calibration_rows: Annotated[int, Field(ge=20, le=2000)] = 20
    minimum_positive_labels: Annotated[int, Field(ge=3, le=500)] = 5
    minimum_negative_labels: Annotated[int, Field(ge=3, le=500)] = 5
    gradient_steps: Literal[200] = 200
    learning_rate: Literal[0.05] = 0.05
    l2_slope_penalty: Literal[0.01] = 0.01

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class DirectionalCalibrationRecord(BaseModel):
    """Train-only conversion from signed return forecasts into direction probabilities."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-v4-forecast-directional-bridge.v1"] = VERSION
    candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    model_version_ref: Annotated[str, Field(min_length=1, max_length=128)]
    source_result_fingerprint: Hash64
    shared_cohort_binding_fingerprint: Hash64
    config_fingerprint: Hash64
    calibration_input_fingerprint: Hash64
    heldout_forecast_input_fingerprint: Hash64
    calibration_rows: Annotated[int, Field(ge=20, le=2000)]
    positive_labels: Annotated[int, Field(ge=3)]
    negative_labels: Annotated[int, Field(ge=3)]
    fitted_intercept: float
    fitted_forecast_slope: float
    calibration_in_sample_brier: float
    evidence_status: Literal["SELF_DECLARED_DIRECTIONAL_CALIBRATION_ONLY"] = (
        "SELF_DECLARED_DIRECTIONAL_CALIBRATION_ONLY"
    )
    independent_market_source_verified: Literal[False] = False
    independent_model_artifact_attestation_verified: Literal[False] = False
    holdout_fitted_or_tuned: Literal[False] = False
    real_market_probability_calibration_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    record_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_record(self) -> DirectionalCalibrationRecord:
        if any(not math.isfinite(value) for value in (
            self.fitted_intercept,
            self.fitted_forecast_slope,
            self.calibration_in_sample_brier,
        )):
            raise ValueError("directional calibrator coefficients must be finite")
        if not 0 <= self.calibration_in_sample_brier <= 1:
            raise ValueError("calibration Brier score must be in [0,1]")
        if self.positive_labels + self.negative_labels != self.calibration_rows:
            raise ValueError("calibration label counts must match fit rows")
        if self.record_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"record_fingerprint"})
        ):
            raise ValueError("directional calibration fingerprint mismatch")
        return self


class ForecastTournamentSubmission(BaseModel):
    """Auditable research candidate and calibration provenance; never trading proof."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-v4-forecast-directional-bridge.v1"] = VERSION
    cohort: CrossModelCausalCohort
    calibration: DirectionalCalibrationRecord
    candidate: TournamentCandidate
    independently_verified_model_run: Literal[False] = False
    independently_verified_india_market_history: Literal[False] = False
    external_trial_preregistration_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    submission_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_submission(self) -> ForecastTournamentSubmission:
        if self.calibration.shared_cohort_binding_fingerprint != (
            self.cohort.binding_fingerprint
        ):
            raise ValueError("calibrator does not belong to the given cohort")
        if self.candidate.prediction.candidate_id != self.calibration.candidate_id:
            raise ValueError("candidate identity differs from calibrated model")
        if self.candidate.prediction.model_version_ref != (
            self.calibration.model_version_ref
        ):
            raise ValueError("candidate version differs from calibrated model")
        if self.candidate.prediction.source_result_fingerprint != (
            self.calibration.source_result_fingerprint
        ):
            raise ValueError("model source result differs from calibration record")
        expected = self.cohort.series_kwargs()
        for name in (
            "target_binding_fingerprint", "fold_binding_fingerprint",
            "economic_cost_binding_fingerprint", "observation_fingerprints",
            "decision_times",
        ):
            if getattr(self.candidate.returns, name) != expected[name]:
                raise ValueError(f"candidate trial cohort mismatch: {name}")
        if self.submission_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"submission_fingerprint"})
        ):
            raise ValueError("forecast tournament submission fingerprint mismatch")
        return self


def _sigmoid(value: float) -> float:
    if value >= 0:
        ratio = math.exp(-value)
        return 1.0 / (1.0 + ratio)
    ratio = math.exp(value)
    return ratio / (1.0 + ratio)


def _bounded_return(value: float) -> float:
    candidate = float(value)
    if not math.isfinite(candidate) or candidate < -1 or candidate > 1:
        raise ValueError("calibration forecasts and labels must be finite signed returns")
    return candidate


def build_directional_tournament_submission(
    cohort: CrossModelCausalCohort,
    *,
    candidate_id: str,
    model_version_ref: str,
    model_source_result_fingerprint: str,
    calibration_forecasts: Sequence[float],
    calibration_realized_returns: Sequence[float],
    calibration_label_available_times: Sequence[datetime],
    heldout_forecasts: Sequence[float],
    heldout_net_returns_after_costs: Sequence[float],
    config: DirectionalCalibrationConfig | None = None,
) -> ForecastTournamentSubmission:
    """Fit logistic direction probabilities on past labels ONLY.

    No holdout labels are accepted for calibration. The provided heldout
    after-cost return vector is still self-declared, not model-verified;
    independent model/artifact/cost attestation is a separate prerequisite.
    """
    fixed = CrossModelCausalCohort.model_validate(cohort.model_dump(mode="python"))
    policy = config or DirectionalCalibrationConfig()
    values = [_bounded_return(value) for value in calibration_forecasts]
    observed = [_bounded_return(value) for value in calibration_realized_returns]
    label_times = list(calibration_label_available_times)
    if len(values) != len(observed) or len(values) != len(label_times):
        raise ValueError("calibration forecast, label, and timestamp counts must align")
    if len(values) < policy.minimum_calibration_rows:
        raise ValueError("insufficient earlier calibration labels")
    if any(t.tzinfo is None or t.utcoffset() is None for t in label_times):
        raise ValueError("calibration label timestamps must be timezone-aware")
    if any(later <= earlier for earlier, later in zip(
        label_times[:-1], label_times[1:], strict=True
    )):
        raise ValueError("calibration labels must be strictly chronological")
    if label_times[-1] >= fixed.decision_times[0]:
        raise ValueError("calibration uses a label unavailable before holdout")
    predictions = [_bounded_return(value) for value in heldout_forecasts]
    net_returns = [float(value) for value in heldout_net_returns_after_costs]
    if len(predictions) != len(fixed.observation_fingerprints):
        raise ValueError("holdout prediction membership differs from causal cohort")
    if len(net_returns) != len(predictions):
        raise ValueError("holdout net returns and predictions must align")
    if any(not math.isfinite(value) or not -1 <= value <= 10
           for value in net_returns):
        raise ValueError("holdout after-cost returns must be finite and bounded")
    labels = [float(value > 0) for value in observed]
    positives = int(sum(labels))
    negatives = len(labels) - positives
    if (positives < policy.minimum_positive_labels
            or negatives < policy.minimum_negative_labels):
        raise ValueError("directional calibrator needs both positive and negative labels")

    # Dimensionless, bounded return feature. No heldout target, P&L, event or
    # outcome is observed by this optimizer.
    fit_x = [max(-10.0, min(10.0, value * 100.0)) for value in values]
    if max(fit_x) - min(fit_x) < 1e-10:
        raise ValueError("constant return forecasts cannot be directionally calibrated")
    intercept, slope = 0.0, 0.0
    for _ in range(policy.gradient_steps):
        probabilities = [_sigmoid(intercept + slope * x) for x in fit_x]
        errors = [p - y for p, y in zip(probabilities, labels, strict=True)]
        bias_grad = sum(errors) / len(errors)
        slope_grad = (
            sum(error * x for error, x in zip(errors, fit_x, strict=True))
            / len(errors) + policy.l2_slope_penalty * slope
        )
        intercept -= policy.learning_rate * bias_grad
        slope -= policy.learning_rate * slope_grad
    brier = sum(
        (_sigmoid(intercept + slope * x) - y) ** 2
        for x, y in zip(fit_x, labels, strict=True)
    ) / len(labels)
    heldout_probabilities = [
        _sigmoid(intercept + slope * max(-10.0, min(10.0, raw * 100.0)))
        for raw in predictions
    ]
    calibration_payload = {
        "contract_version": VERSION,
        "candidate_id": candidate_id,
        "model_version_ref": model_version_ref,
        "source_result_fingerprint": model_source_result_fingerprint,
        "shared_cohort_binding_fingerprint": fixed.binding_fingerprint,
        "config_fingerprint": policy.fingerprint,
        "calibration_input_fingerprint": canonical_fingerprint({
            "forecasts": values,
            "realized": observed,
            "available_at": [time.isoformat() for time in label_times],
        }),
        "heldout_forecast_input_fingerprint": canonical_fingerprint(predictions),
        "calibration_rows": len(values),
        "positive_labels": positives,
        "negative_labels": negatives,
        "fitted_intercept": intercept,
        "fitted_forecast_slope": slope,
        "calibration_in_sample_brier": brier,
        "evidence_status": "SELF_DECLARED_DIRECTIONAL_CALIBRATION_ONLY",
        "independent_market_source_verified": False,
        "independent_model_artifact_attestation_verified": False,
        "holdout_fitted_or_tuned": False,
        "real_market_probability_calibration_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    calibration = DirectionalCalibrationRecord(
        **calibration_payload,
        record_fingerprint=canonical_fingerprint(calibration_payload),
    )
    prediction = build_prediction_series(
        candidate_id=candidate_id,
        model_version_ref=model_version_ref,
        source_result_fingerprint=model_source_result_fingerprint,
        observation_fingerprints=fixed.observation_fingerprints,
        probabilities=heldout_probabilities,
    )
    returns = create_net_return_series(
        candidate_id=candidate_id,
        model_version_ref=model_version_ref,
        source_result_fingerprint=model_source_result_fingerprint,
        net_returns_after_costs=net_returns,
        **fixed.series_kwargs(),
    )
    candidate = TournamentCandidate(prediction=prediction, returns=returns)
    payload = {
        "contract_version": VERSION,
        "cohort": fixed.model_dump(mode="json"),
        "calibration": calibration.model_dump(mode="json"),
        "candidate": candidate.model_dump(mode="json"),
        "independently_verified_model_run": False,
        "independently_verified_india_market_history": False,
        "external_trial_preregistration_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ForecastTournamentSubmission(
        **payload, submission_fingerprint=canonical_fingerprint(payload)
    )
