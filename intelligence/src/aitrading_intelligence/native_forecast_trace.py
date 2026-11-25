from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .cross_model_causal_cohort import CrossModelCausalCohort
from .fingerprints import canonical_fingerprint
from .india_causal_forecast_baseline import CausalForecastRow, _calibration_quantile
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-native-forecast-decision-trace.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class NativeForecastDecisionTrace(BaseModel):
    """Model-produced chronological forecast/return evidence, still research only.

    Unlike an operator-supplied bridge JSON, callers build this while the
    forecasting model's results are in memory. This validates arithmetic and
    time alignment but cannot attest the binary, market provider or broker fills.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-native-forecast-decision-trace.v1"] = VERSION
    candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    model_version_ref: Annotated[str, Field(min_length=1, max_length=128)]
    model_state_fingerprint: Hash64
    causal_baseline_report_fingerprint: Hash64
    cohort: CrossModelCausalCohort
    target: ForwardReturnTargetSpec
    interval_coverage: Annotated[float, Field(ge=0.80, le=0.95)]
    calibration_rows: list[CausalForecastRow] = Field(min_length=20, max_length=10000)
    calibration_predictions: list[float] = Field(min_length=20, max_length=10000)
    holdout_rows: list[CausalForecastRow] = Field(min_length=20, max_length=10000)
    holdout_predictions: list[float] = Field(min_length=20, max_length=10000)
    holdout_net_returns_after_costs: list[float] = Field(min_length=20, max_length=10000)
    calibration_absolute_error_quantile: float
    reported_trade_count: Annotated[int, Field(ge=0)]
    actual_model_checkpoint_independently_verified: Literal[False] = False
    source_market_data_independently_verified: Literal[False] = False
    preregistration_independently_verified: Literal[False] = False
    eligible_for_model_promotion: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    trace_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_trace(self) -> NativeForecastDecisionTrace:
        if self.target.fingerprint != self.cohort.target_binding_fingerprint:
            raise ValueError("native trace target differs from the shared cohort")
        n = len(self.holdout_rows)
        if (len(self.holdout_predictions) != n
                or len(self.holdout_net_returns_after_costs) != n):
            raise ValueError("native trace holdout dimensions must agree")
        if len(self.calibration_predictions) != len(self.calibration_rows):
            raise ValueError("native trace calibration dimensions must agree")
        for name, values in (
            ("calibration", self.calibration_predictions),
            ("holdout", self.holdout_predictions),
            ("net", self.holdout_net_returns_after_costs),
        ):
            if any(not math.isfinite(v) or abs(v) > 10 for v in values):
                raise ValueError(f"native trace {name} values are nonfinite or unbounded")
        if any(later.decision_at <= earlier.decision_at for earlier, later in zip(
            self.calibration_rows[:-1], self.calibration_rows[1:], strict=True
        )):
            raise ValueError("native calibration decisions must be chronological")
        if any(later.label_available_at <= earlier.label_available_at for earlier, later
               in zip(self.calibration_rows[:-1], self.calibration_rows[1:], strict=True)):
            raise ValueError("native calibration labels must be chronological")
        if self.calibration_rows[-1].label_available_at >= self.holdout_rows[0].decision_at:
            raise ValueError("native calibration labels overlap the independent holdout")
        if self.cohort.parent_holdout_membership_fingerprint != canonical_fingerprint([
            row.row_fingerprint for row in self.holdout_rows
        ]):
            raise ValueError("native trace holdout membership differs from cohort")
        observation_ids = [
            canonical_fingerprint({
                "history": row.history_fingerprint,
                "target": row.target_fingerprint,
                "time": row.decision_at.isoformat(),
            })
            for row in self.holdout_rows
        ]
        if observation_ids != self.cohort.observation_fingerprints or [
            row.decision_at for row in self.holdout_rows
        ] != self.cohort.decision_times:
            raise ValueError("native trace decision identities differ from cohort")
        bound = _calibration_quantile([
            abs(row.observed_forward_return - prediction)
            for row, prediction in zip(
                self.calibration_rows, self.calibration_predictions, strict=True
            )
        ], self.interval_coverage)
        if not math.isclose(
            bound, self.calibration_absolute_error_quantile, abs_tol=1e-12
        ):
            raise ValueError("native trace calibration interval does not match labels")
        if self.reported_trade_count > n:
            raise ValueError("native trace trade count exceeds holdout decisions")
        fees = self.target.round_trip_cost_bps / 10000.0
        threshold = self.target.positive_threshold_return
        expected_returns = []
        trades = 0
        for row, prediction in zip(
            self.holdout_rows, self.holdout_predictions, strict=True
        ):
            if prediction - bound > threshold:
                trades += 1
                expected_returns.append(row.observed_forward_return - fees)
            else:
                expected_returns.append(0.0)
        if trades != self.reported_trade_count:
            raise ValueError("native trace trade count does not match decisions")
        if any(not math.isclose(real, expected, rel_tol=1e-9, abs_tol=1e-12)
               for real, expected in zip(
                   self.holdout_net_returns_after_costs, expected_returns, strict=True
               )):
            raise ValueError("native trace net returns contradict forecasts and fees")
        if self.trace_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"trace_fingerprint"})
        ):
            raise ValueError("native trace fingerprint mismatch")
        return self


def build_native_forecast_trace(
    *,
    candidate_id: str,
    model_version_ref: str,
    model_state_fingerprint: str,
    causal_baseline_report_fingerprint: str,
    cohort: CrossModelCausalCohort,
    target: ForwardReturnTargetSpec,
    interval_coverage: float,
    calibration_rows: list[CausalForecastRow],
    calibration_predictions: list[float],
    holdout_rows: list[CausalForecastRow],
    holdout_predictions: list[float],
    holdout_net_returns_after_costs: list[float],
    reported_trade_count: int,
) -> NativeForecastDecisionTrace:
    if len(calibration_rows) != len(calibration_predictions):
        raise ValueError("calibration rows and predictions must align")
    bound = _calibration_quantile([
        abs(row.observed_forward_return - prediction)
        for row, prediction in zip(
            calibration_rows, calibration_predictions, strict=True
        )
    ], interval_coverage)
    payload = {
        "contract_version": VERSION,
        "candidate_id": candidate_id,
        "model_version_ref": model_version_ref,
        "model_state_fingerprint": model_state_fingerprint,
        "causal_baseline_report_fingerprint": causal_baseline_report_fingerprint,
        "cohort": cohort.model_dump(mode="json"),
        "target": target.model_dump(mode="json"),
        "interval_coverage": interval_coverage,
        "calibration_rows": [row.model_dump(mode="json") for row in calibration_rows],
        "calibration_predictions": list(calibration_predictions),
        "holdout_rows": [row.model_dump(mode="json") for row in holdout_rows],
        "holdout_predictions": list(holdout_predictions),
        "holdout_net_returns_after_costs": list(holdout_net_returns_after_costs),
        "calibration_absolute_error_quantile": bound,
        "reported_trade_count": reported_trade_count,
        "actual_model_checkpoint_independently_verified": False,
        "source_market_data_independently_verified": False,
        "preregistration_independently_verified": False,
        "eligible_for_model_promotion": False,
        "can_authorize_capital": False,
    }
    return NativeForecastDecisionTrace(
        **payload, trace_fingerprint=canonical_fingerprint(payload)
    )
