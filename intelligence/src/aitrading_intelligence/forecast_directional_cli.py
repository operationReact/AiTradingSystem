from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path

from pydantic import BaseModel, ConfigDict

from .cross_model_causal_cohort import CrossModelCausalCohort
from .forecast_directional_tournament import build_directional_tournament_submission


class CalibrationInputs(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    forecasts: list[float]
    realized_returns: list[float]
    label_available_times: list[datetime]


class HoldoutInputs(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    forecasts: list[float]
    net_returns_after_costs: list[float]


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train-only research direction calibration; NO model/trading authority"
    )
    parser.add_argument("cohort", type=Path)
    parser.add_argument("calibration_inputs", type=Path)
    parser.add_argument("holdout_inputs", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--candidate-id", required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--source-result-fingerprint", required=True)
    args = parser.parse_args()

    cohort = CrossModelCausalCohort.model_validate_json(
        args.cohort.read_text(encoding="utf-8")
    )
    earlier = CalibrationInputs.model_validate_json(
        args.calibration_inputs.read_text(encoding="utf-8")
    )
    holdout = HoldoutInputs.model_validate_json(
        args.holdout_inputs.read_text(encoding="utf-8")
    )
    result = build_directional_tournament_submission(
        cohort,
        candidate_id=args.candidate_id,
        model_version_ref=args.model_version,
        model_source_result_fingerprint=args.source_result_fingerprint,
        calibration_forecasts=earlier.forecasts,
        calibration_realized_returns=earlier.realized_returns,
        calibration_label_available_times=earlier.label_available_times,
        heldout_forecasts=holdout.forecasts,
        heldout_net_returns_after_costs=holdout.net_returns_after_costs,
    )
    # Non-overwrite, private file. This is a self-declared research record,
    # not a certified model-run or broker-approved data capture.
    handle = os.open(str(args.output), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(handle, "w", encoding="utf-8") as stream:
        json.dump(result.model_dump(mode="json"), stream, sort_keys=True)
        stream.write("\n")
    print(result.submission_fingerprint)


if __name__ == "__main__":
    main()
