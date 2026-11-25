from __future__ import annotations

import argparse
import json
import math
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_fee_tax import IndiaFeeTaxBookV1
from .india_independent_holdout import (
    IndiaHoldoutConfig,
    IndiaHoldoutReport,
    evaluate_india_independent_holdout,
)
from .market_source import MarketResearchExport, load_market_export
from .model_fair_comparison import _max_drawdown
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-causal-forecast-baseline.v1"
IST = ZoneInfo("Asia/Kolkata")
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CausalForecastConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-causal-forecast-baseline.v1"] = VERSION
    lookback_returns: Annotated[int, Field(ge=3, le=40)] = 8
    minimum_calibration_rows: Annotated[int, Field(ge=20, le=10_000)] = 25
    minimum_holdout_rows: Annotated[int, Field(ge=20, le=10_000)] = 30
    minimum_holdout_sessions: Annotated[int, Field(ge=2, le=100)] = 2
    interval_coverage: Annotated[float, Field(ge=0.80, le=0.95)] = 0.90
    minimum_lower_bound_edge_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 0.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CausalForecastRow(BaseModel):
    """A point-in-time future-return target whose input closes precede its decision."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    decision_at: datetime
    label_available_at: datetime
    session_date: str
    history_fingerprint: Hash64
    target_fingerprint: Hash64
    observed_forward_return: float
    predicted_forward_return: float
    row_fingerprint: Hash64

    @model_validator(mode="after")
    def check_row(self) -> CausalForecastRow:
        if self.decision_at.tzinfo is None or self.label_available_at.tzinfo is None:
            raise ValueError("forecast row times must be timezone-aware")
        if self.decision_at >= self.label_available_at:
            raise ValueError("forecast label must become available after decision")
        if (self.decision_at.astimezone(IST).date()
                != self.label_available_at.astimezone(IST).date()):
            raise ValueError("forecast targets cannot cross India trading sessions")
        if self.session_date != self.decision_at.astimezone(IST).date().isoformat():
            raise ValueError("forecast row session identity mismatch")
        if not all(math.isfinite(v) for v in (
            self.observed_forward_return, self.predicted_forward_return
        )):
            raise ValueError("forecast returns must be finite")
        if self.row_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"row_fingerprint"})
        ):
            raise ValueError("forecast row fingerprint mismatch")
        return self


class CausalForecastBaselineReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-causal-forecast-baseline.v1"] = VERSION
    parent_independent_holdout_report_fingerprint: Hash64
    train_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    india_fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    forecast_config_fingerprint: Hash64
    training_example_membership_fingerprint: Hash64
    holdout_example_membership_fingerprint: Hash64
    calibration_rows: Annotated[int, Field(ge=0)]
    independent_holdout_rows: Annotated[int, Field(ge=0)]
    independent_holdout_sessions: Annotated[int, Field(ge=0)]
    calibration_absolute_error_quantile: float | None
    holdout_mae: float | None
    holdout_rmse: float | None
    holdout_interval_coverage: float | None
    holdout_average_interval_width: float | None
    trade_count: int
    net_return_after_assumed_costs: float
    maximum_drawdown: float
    assessed_round_trip_cost_bps: float
    status: Literal["RESEARCH_FORECAST_METRICS_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    forecast_family: Literal["CAUSAL_MOVING_AVERAGE_BASELINE"] = (
        "CAUSAL_MOVING_AVERAGE_BASELINE"
    )
    external_market_source_verified: Literal[False] = False
    holdout_pre_registration_verified: Literal[False] = False
    competing_model_improvement_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_report(self) -> CausalForecastBaselineReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("forecast report reasons must be canonical")
        if self.status == "RESEARCH_FORECAST_METRICS_ONLY":
            if self.reason_codes or any(getattr(self, key) is None for key in (
                "calibration_absolute_error_quantile", "holdout_mae",
                "holdout_rmse", "holdout_interval_coverage",
                "holdout_average_interval_width",
            )):
                raise ValueError("evaluated forecast requires complete metrics without failures")
        elif not self.reason_codes or self.calibration_absolute_error_quantile is not None:
            raise ValueError("inconclusive forecast must not claim calibrated intervals")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("forecast baseline report fingerprint mismatch")
        return self


def _causal_rows(
    export: MarketResearchExport, *, lookback: int, horizon: int
) -> list[CausalForecastRow]:
    by_session: dict[str, list] = {}
    for bar in export.to_market_bars():
        session = bar.observed_at.astimezone(IST).date().isoformat()
        by_session.setdefault(session, []).append(bar)
    rows: list[CausalForecastRow] = []
    for session, bars in sorted(by_session.items()):
        for end in range(lookback, len(bars) - horizon):
            history = bars[end - lookback:end + 1]
            # Use observed close values through the decision bar, never future labels.
            returns = [
                right.close / left.close - 1.0
                for left, right in zip(history[:-1], history[1:], strict=True)
            ]
            forecast = max(-0.10, min(0.10, sum(returns) / lookback * horizon))
            current = bars[end]
            future = bars[end + horizon]
            outcome = future.close / current.close - 1.0
            payload = {
                "decision_at": current.observed_at,
                "label_available_at": future.observed_at,
                "session_date": session,
                "history_fingerprint": canonical_fingerprint([
                    {
                        "observed_at": bar.observed_at.isoformat(),
                        "close": bar.close,
                        "source": bar.source,
                    }
                    for bar in history
                ]),
                "target_fingerprint": canonical_fingerprint({
                    "horizon_bars": horizon, "target_at": future.observed_at.isoformat(),
                    "target_close": future.close,
                }),
                "observed_forward_return": outcome,
                "predicted_forward_return": forecast,
            }
            obj = CausalForecastRow.model_construct(
                **payload, row_fingerprint="0" * 64
            )
            rows.append(CausalForecastRow(
                **payload,
                row_fingerprint=canonical_fingerprint(
                    obj.model_dump(mode="json", exclude={"row_fingerprint"})
                ),
            ))
    return rows


def _calibration_quantile(residuals: list[float], confidence: float) -> float:
    # Split-conformal quantile with (n+1) finite-sample correction, using TRAIN only.
    ordered = sorted(residuals)
    order = math.ceil((len(ordered) + 1) * confidence)
    if order > len(ordered):
        raise ValueError("insufficient calibration rows for desired coverage")
    return ordered[order - 1]


def evaluate_india_causal_forecast_baseline(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: CausalForecastConfig | None = None,
    holdout_config: IndiaHoldoutConfig | None = None,
) -> CausalForecastBaselineReport:
    """Compare a causal naive return forecaster to a disjoint Indian market cohort.

    Reuse the existing independently held-out CatBoost/LightGBM runner's
    instrument, source, label chronology, India GST/fee and slippage gates.
    Every result remains RESEARCH ONLY regardless of apparent after-cost return.
    """
    policy = config or CausalForecastConfig()
    parent: IndiaHoldoutReport = evaluate_india_independent_holdout(
        training_export, holdout_export, fee_book, target, config=holdout_config
    )
    train = _causal_rows(
        training_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    hold = _causal_rows(
        holdout_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    if train and hold and max(r.label_available_at for r in train) >= min(
        r.decision_at for r in hold
    ):
        raise ValueError("training calibration label was unavailable at holdout start")

    sessions = len({r.session_date for r in hold})
    reasons = set(parent.reason_codes)
    if len(train) < policy.minimum_calibration_rows:
        reasons.add("INSUFFICIENT_TRAIN_ONLY_CALIBRATION_ROWS")
    if len(hold) < policy.minimum_holdout_rows:
        reasons.add("INSUFFICIENT_HOLDOUT_FORECAST_ROWS")
    if sessions < policy.minimum_holdout_sessions:
        reasons.add("INSUFFICIENT_HOLDOUT_FORECAST_SESSIONS")
    if parent.status == "INCONCLUSIVE":
        reasons.add("PARENT_INDIA_HOLDOUT_INCONCLUSIVE")
    residual_quantile: float | None = None
    mae: float | None = None
    rmse: float | None = None
    coverage: float | None = None
    avg_width: float | None = None
    trades: list[float] = []
    if not reasons:
        residual_quantile = _calibration_quantile(
            [abs(row.observed_forward_return - row.predicted_forward_return)
             for row in train],
            policy.interval_coverage,
        )
        errors = [
            row.observed_forward_return - row.predicted_forward_return for row in hold
        ]
        mae = sum(abs(v) for v in errors) / len(errors)
        rmse = math.sqrt(sum(v * v for v in errors) / len(errors))
        coverage = sum(abs(v) <= residual_quantile for v in errors) / len(errors)
        avg_width = 2 * residual_quantile
        fee_fraction = target.round_trip_cost_bps / 10_000
        threshold = (
            target.round_trip_cost_bps + target.minimum_edge_bps
            + policy.minimum_lower_bound_edge_bps
        ) / 10_000
        trades = [
            row.observed_forward_return - fee_fraction
            for row in hold
            if row.predicted_forward_return - residual_quantile >= threshold
        ]
    payload = {
        "contract_version": VERSION,
        "parent_independent_holdout_report_fingerprint": parent.report_fingerprint,
        "train_export_fingerprint": parent.train_export_fingerprint,
        "holdout_export_fingerprint": parent.holdout_export_fingerprint,
        "india_fee_book_fingerprint": parent.india_fee_book_fingerprint,
        "target_spec_fingerprint": parent.target_spec_fingerprint,
        "forecast_config_fingerprint": policy.fingerprint,
        "training_example_membership_fingerprint": canonical_fingerprint(
            [row.row_fingerprint for row in train]
        ),
        "holdout_example_membership_fingerprint": canonical_fingerprint(
            [row.row_fingerprint for row in hold]
        ),
        "calibration_rows": len(train),
        "independent_holdout_rows": len(hold),
        "independent_holdout_sessions": sessions,
        "calibration_absolute_error_quantile": residual_quantile,
        "holdout_mae": mae,
        "holdout_rmse": rmse,
        "holdout_interval_coverage": coverage,
        "holdout_average_interval_width": avg_width,
        "trade_count": len(trades),
        "net_return_after_assumed_costs": float(sum(trades)),
        "maximum_drawdown": float(_max_drawdown(trades)),
        "assessed_round_trip_cost_bps": target.round_trip_cost_bps,
        "status": "INCONCLUSIVE" if reasons else "RESEARCH_FORECAST_METRICS_ONLY",
        "reason_codes": sorted(reasons),
        "forecast_family": "CAUSAL_MOVING_AVERAGE_BASELINE",
        "external_market_source_verified": False,
        "holdout_pre_registration_verified": False,
        "competing_model_improvement_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CausalForecastBaselineReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run a research-only India intraday causal time-series baseline"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--cost-bps", type=float, required=True)
    parser.add_argument("--horizon-bars", type=int, default=1)
    parser.add_argument("--minimum-edge-bps", type=float, default=15)
    parser.add_argument("--lookback", type=int, default=8)
    args = parser.parse_args()
    target = ForwardReturnTargetSpec(
        horizon_bars=args.horizon_bars,
        minimum_edge_bps=args.minimum_edge_bps,
        round_trip_cost_bps=args.cost_bps,
    )
    report = evaluate_india_causal_forecast_baseline(
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.india_fee_book.read_text(encoding="utf-8")
        ),
        target,
        config=CausalForecastConfig(lookback_returns=args.lookback),
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
