from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .forecast_directional_tournament import (
    ForecastTournamentSubmission,
    build_directional_tournament_submission,
)
from .india_bounded_tft import BoundedTftReport
from .india_patch_transformer import IndiaPatchForecastReport
from .india_time_mixer import TimeMixerResearchReport

VERSION = "india-native-forecast-tournament-submission.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
NativeModelReport = (
    IndiaPatchForecastReport | TimeMixerResearchReport | BoundedTftReport
)


class NativeForecastTournamentSubmission(BaseModel):
    """Native report -> train-only directional probabilities -> research tournament.

    The full original model report is retained, not an unverified claim
    that a manually edited candidate array came from the model binary.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-native-forecast-tournament-submission.v1"] = VERSION
    native_model_report: NativeModelReport
    directional: ForecastTournamentSubmission
    independently_attested_binary_execution: Literal[False] = False
    independently_authenticated_indian_market_source: Literal[False] = False
    externally_preregistered_trial_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    submission_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_native_linkage(self) -> NativeForecastTournamentSubmission:
        record = self.native_model_report
        trace = record.native_trace
        if trace is None:
            raise ValueError("native directional submission requires a completed model trace")
        if record.model_state_fingerprint != trace.model_state_fingerprint:
            raise ValueError("native model state differs from its decision trace")
        expected = build_directional_tournament_submission(
            trace.cohort,
            candidate_id=trace.candidate_id,
            model_version_ref=trace.model_version_ref,
            # The entire original model-result fingerprint is the experiment
            # result identity, not a user-written sidecar digest.
            model_source_result_fingerprint=record.report_fingerprint,
            calibration_forecasts=trace.calibration_predictions,
            calibration_realized_returns=[
                row.observed_forward_return for row in trace.calibration_rows
            ],
            calibration_label_available_times=[
                row.label_available_at for row in trace.calibration_rows
            ],
            heldout_forecasts=trace.holdout_predictions,
            heldout_net_returns_after_costs=trace.holdout_net_returns_after_costs,
        )
        if expected != self.directional:
            raise ValueError("directional candidate differs from native model predictions")
        if self.submission_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"submission_fingerprint"})
        ):
            raise ValueError("native tournament submission fingerprint mismatch")
        return self


def build_native_forecast_tournament_submission(
    report: NativeModelReport,
) -> NativeForecastTournamentSubmission:
    """Use the model's own immutable forecast and after-cost vectors.

    The calibration implementation remains TRAIN ONLY; no holdout future
    outcomes enter the logistic probability fitting process. Statistical
    improvement and external data custody are separately unverified.
    """
    if not isinstance(
        report, (IndiaPatchForecastReport, TimeMixerResearchReport, BoundedTftReport)
    ):
        raise ValueError("unsupported native forecasting model")
    record = type(report).model_validate(report.model_dump(mode="python"))
    trace = record.native_trace
    if trace is None:
        raise ValueError("inconclusive model without native trace cannot enter tournament")
    candidate = build_directional_tournament_submission(
        trace.cohort,
        candidate_id=trace.candidate_id,
        model_version_ref=trace.model_version_ref,
        model_source_result_fingerprint=record.report_fingerprint,
        calibration_forecasts=trace.calibration_predictions,
        calibration_realized_returns=[
            row.observed_forward_return for row in trace.calibration_rows
        ],
        calibration_label_available_times=[
            row.label_available_at for row in trace.calibration_rows
        ],
        heldout_forecasts=trace.holdout_predictions,
        heldout_net_returns_after_costs=trace.holdout_net_returns_after_costs,
    )
    payload = {
        "contract_version": VERSION,
        "native_model_report": record.model_dump(mode="json"),
        "directional": candidate.model_dump(mode="json"),
        "independently_attested_binary_execution": False,
        "independently_authenticated_indian_market_source": False,
        "externally_preregistered_trial_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return NativeForecastTournamentSubmission(
        **payload, submission_fingerprint=canonical_fingerprint(payload)
    )
