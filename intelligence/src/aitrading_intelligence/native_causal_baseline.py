from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .cross_model_causal_cohort import (
    CrossModelCausalCohort,
    bind_common_causal_tournament_cohort,
)
from .fingerprints import canonical_fingerprint
from .forecast_directional_tournament import (
    ForecastTournamentSubmission,
    build_directional_tournament_submission,
)
from .india_causal_forecast_baseline import (
    CausalForecastBaselineReport,
    CausalForecastConfig,
    CausalForecastRow,
    _calibration_quantile,
    _causal_rows,
    evaluate_india_causal_forecast_baseline,
)
from .india_fee_tax import IndiaFeeTaxBookV1
from .market_source import MarketResearchExport, load_market_export
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-native-causal-baseline-evidence.v1"
SUBMISSION_VERSION = "india-native-causal-baseline-tournament.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class NativeCausalBaselineEvidence(BaseModel):
    """Exact deterministic causal-mean baseline with its own inclusive entry rule."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-native-causal-baseline-evidence.v1"] = VERSION
    candidate_id: Literal["causal-mean-baseline"] = "causal-mean-baseline"
    model_version_ref: Literal["v4-04-causal-moving-average.v1"] = (
        "v4-04-causal-moving-average.v1"
    )
    baseline_report: CausalForecastBaselineReport
    forecast_config: CausalForecastConfig
    cohort: CrossModelCausalCohort
    target: ForwardReturnTargetSpec
    calibration_rows: list[CausalForecastRow] = Field(min_length=20, max_length=10000)
    heldout_rows: list[CausalForecastRow] = Field(min_length=20, max_length=10000)
    net_returns_after_assumed_costs: list[float] = Field(min_length=20, max_length=10000)
    eligible_trade_count: Annotated[int, Field(ge=0)]
    entry_rule: Literal["INCLUSIVE_GREATER_OR_EQUAL"] = "INCLUSIVE_GREATER_OR_EQUAL"
    independent_market_authenticity_verified: Literal[False] = False
    externally_timestamped_registration_verified: Literal[False] = False
    eligible_for_paper_or_live: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    evidence_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_baseline(self) -> NativeCausalBaselineEvidence:
        report = self.baseline_report
        if report.status != "RESEARCH_FORECAST_METRICS_ONLY":
            raise ValueError("native baseline requires a completed causal research report")
        if self.forecast_config.fingerprint != report.forecast_config_fingerprint:
            raise ValueError("native baseline forecast config differs from original report")
        if self.target.fingerprint != report.target_spec_fingerprint:
            raise ValueError("native baseline target differs from original report")
        if self.cohort.target_binding_fingerprint != self.target.fingerprint:
            raise ValueError("native baseline target differs from shared cohort")
        if (
            self.cohort.training_export_fingerprint != report.train_export_fingerprint
            or self.cohort.holdout_export_fingerprint != report.holdout_export_fingerprint
            or self.cohort.fee_book_fingerprint != report.india_fee_book_fingerprint
        ):
            raise ValueError("native baseline source or fee differs from original report")
        if len(self.calibration_rows) != report.calibration_rows:
            raise ValueError("native baseline training calibration membership differs")
        if len(self.heldout_rows) != report.independent_holdout_rows:
            raise ValueError("native baseline holdout membership differs")
        if len(self.net_returns_after_assumed_costs) != len(self.heldout_rows):
            raise ValueError("native baseline after-cost decisions have missing rows")
        if canonical_fingerprint([
            row.row_fingerprint for row in self.calibration_rows
        ]) != report.training_example_membership_fingerprint:
            raise ValueError("native baseline calibration observation hashes disagree")
        if canonical_fingerprint([
            row.row_fingerprint for row in self.heldout_rows
        ]) != report.holdout_example_membership_fingerprint:
            raise ValueError("native baseline holdout observation hashes disagree")
        if self.cohort.parent_holdout_membership_fingerprint != (
            report.holdout_example_membership_fingerprint
        ):
            raise ValueError("native baseline shared holdout does not match original")
        if [row.decision_at for row in self.heldout_rows] != self.cohort.decision_times:
            raise ValueError("native baseline decision chronology differs from cohort")
        if self.calibration_rows[-1].label_available_at >= self.heldout_rows[0].decision_at:
            raise ValueError("native baseline calibration label overlaps holdout")
        if len({row.session_date for row in self.heldout_rows}) != (
            report.independent_holdout_sessions
        ):
            raise ValueError("native baseline session evidence differs from original")
        bound = _calibration_quantile([
            abs(row.observed_forward_return - row.predicted_forward_return)
            for row in self.calibration_rows
        ], self.forecast_config.interval_coverage)
        if report.calibration_absolute_error_quantile is None or not math.isclose(
            bound, report.calibration_absolute_error_quantile, abs_tol=1e-12
        ):
            raise ValueError("native baseline calibration interval was altered")
        # Unlike bounded neural model _metrics, the original PR #361 baseline
        # uses an INCLUSIVE >= comparison. Preserve that exactly here.
        threshold = (
            self.target.round_trip_cost_bps + self.target.minimum_edge_bps
            + self.forecast_config.minimum_lower_bound_edge_bps
        ) / 10000.0
        expected = [
            row.observed_forward_return - self.target.round_trip_cost_bps / 10000.0
            if row.predicted_forward_return - bound >= threshold else 0.0
            for row in self.heldout_rows
        ]
        count = sum(
            row.predicted_forward_return - bound >= threshold
            for row in self.heldout_rows
        )
        if count != self.eligible_trade_count or count != report.trade_count:
            raise ValueError("native baseline trade count differs from original")
        if any(not math.isfinite(value) or not math.isclose(
            value, calculated, rel_tol=1e-9, abs_tol=1e-12
        ) for value, calculated in zip(
            self.net_returns_after_assumed_costs, expected, strict=True
        )):
            raise ValueError("native baseline after-cost decisions contradict costs")
        if not math.isclose(
            sum(expected), report.net_return_after_assumed_costs,
            rel_tol=1e-9, abs_tol=1e-12,
        ):
            raise ValueError("native baseline summed return differs from report")
        if self.evidence_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"evidence_fingerprint"})
        ):
            raise ValueError("native baseline evidence fingerprint mismatch")
        return self


class NativeCausalBaselineTournamentSubmission(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-native-causal-baseline-tournament.v1"] = (
        SUBMISSION_VERSION
    )
    baseline: NativeCausalBaselineEvidence
    directional: ForecastTournamentSubmission
    external_market_source_verified: Literal[False] = False
    independent_model_run_attested: Literal[False] = False
    external_preregistration_verified: Literal[False] = False
    capital_authorization_allowed: Literal[False] = False
    submission_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_submission(self) -> NativeCausalBaselineTournamentSubmission:
        expected = _directional_baseline(self.baseline)
        if expected != self.directional:
            raise ValueError("native causal baseline directional submission was rewritten")
        if self.submission_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"submission_fingerprint"})
        ):
            raise ValueError("native baseline tournament submission fingerprint mismatch")
        return self


def build_native_causal_baseline(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: CausalForecastConfig | None = None,
) -> NativeCausalBaselineEvidence:
    policy = config or CausalForecastConfig()
    report = evaluate_india_causal_forecast_baseline(
        training_export, holdout_export, fee_book, target, config=policy,
    )
    if report.status != "RESEARCH_FORECAST_METRICS_ONLY":
        raise ValueError("causal baseline independent holdout is inconclusive")
    calibration = _causal_rows(
        training_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    heldout = _causal_rows(
        holdout_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    cohort = bind_common_causal_tournament_cohort(report, heldout, target)
    bound = report.calibration_absolute_error_quantile
    if bound is None:
        raise ValueError("baseline calibration is incomplete")
    threshold = (
        target.round_trip_cost_bps + target.minimum_edge_bps
        + policy.minimum_lower_bound_edge_bps
    ) / 10000.0
    eligibility = [
        row.predicted_forward_return - bound >= threshold for row in heldout
    ]
    hypothetical = [
        row.observed_forward_return - target.round_trip_cost_bps / 10000.0
        if eligible else 0.0
        for row, eligible in zip(heldout, eligibility, strict=True)
    ]
    payload = {
        "contract_version": VERSION,
        "candidate_id": "causal-mean-baseline",
        "model_version_ref": "v4-04-causal-moving-average.v1",
        "baseline_report": report.model_dump(mode="json"),
        "forecast_config": policy.model_dump(mode="json"),
        "cohort": cohort.model_dump(mode="json"),
        "target": target.model_dump(mode="json"),
        "calibration_rows": [row.model_dump(mode="json") for row in calibration],
        "heldout_rows": [row.model_dump(mode="json") for row in heldout],
        "net_returns_after_assumed_costs": hypothetical,
        "eligible_trade_count": sum(eligibility),
        "entry_rule": "INCLUSIVE_GREATER_OR_EQUAL",
        "independent_market_authenticity_verified": False,
        "externally_timestamped_registration_verified": False,
        "eligible_for_paper_or_live": False,
        "can_authorize_capital": False,
    }
    return NativeCausalBaselineEvidence(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )


def _directional_baseline(
    evidence: NativeCausalBaselineEvidence,
) -> ForecastTournamentSubmission:
    return build_directional_tournament_submission(
        evidence.cohort,
        candidate_id=evidence.candidate_id,
        model_version_ref=evidence.model_version_ref,
        model_source_result_fingerprint=evidence.baseline_report.report_fingerprint,
        calibration_forecasts=[
            row.predicted_forward_return for row in evidence.calibration_rows
        ],
        calibration_realized_returns=[
            row.observed_forward_return for row in evidence.calibration_rows
        ],
        calibration_label_available_times=[
            row.label_available_at for row in evidence.calibration_rows
        ],
        heldout_forecasts=[
            row.predicted_forward_return for row in evidence.heldout_rows
        ],
        heldout_net_returns_after_costs=evidence.net_returns_after_assumed_costs,
    )


def build_native_causal_baseline_tournament_submission(
    evidence: NativeCausalBaselineEvidence,
) -> NativeCausalBaselineTournamentSubmission:
    trusted = NativeCausalBaselineEvidence.model_validate(
        evidence.model_dump(mode="python")
    )
    directional = _directional_baseline(trusted)
    payload = {
        "contract_version": SUBMISSION_VERSION,
        "baseline": trusted.model_dump(mode="json"),
        "directional": directional.model_dump(mode="json"),
        "external_market_source_verified": False,
        "independent_model_run_attested": False,
        "external_preregistration_verified": False,
        "capital_authorization_allowed": False,
    }
    return NativeCausalBaselineTournamentSubmission(
        **payload, submission_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Emit native causal baseline + train-only direction evidence; research only"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--cost-bps", type=float, required=True)
    parser.add_argument("--horizon-bars", type=int, default=1)
    args = parser.parse_args()
    evidence = build_native_causal_baseline(
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.india_fee_book.read_text(encoding="utf-8")
        ),
        ForwardReturnTargetSpec(
            horizon_bars=args.horizon_bars,
            minimum_edge_bps=15,
            round_trip_cost_bps=args.cost_bps,
        ),
    )
    print(json.dumps(evidence.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
