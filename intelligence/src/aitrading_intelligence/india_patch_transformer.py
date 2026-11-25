from __future__ import annotations

import argparse
import json
import math
from datetime import timedelta
from pathlib import Path
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .cross_model_causal_cohort import bind_common_causal_tournament_cohort
from .fingerprints import canonical_fingerprint
from .incremental_economic_value import (
    IncrementalValueConfig,
    create_net_return_series,
    evaluate_incremental_economic_value,
)
from .india_causal_forecast_baseline import (
    CausalForecastConfig,
    CausalForecastRow,
    _calibration_quantile,
    _causal_rows,
    evaluate_india_causal_forecast_baseline,
)
from .india_fee_tax import IndiaFeeTaxBookV1
from .market_source import MarketResearchExport, load_market_export
from .model_fair_comparison import _max_drawdown
from .native_forecast_trace import (
    NativeForecastDecisionTrace,
    build_native_forecast_trace,
)
from .pytorch_target import ForwardReturnTargetSpec

CONTRACT = "india-bounded-patch-transformer.v1"
IST = ZoneInfo("Asia/Kolkata")
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class PatchForecastConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-bounded-patch-transformer.v1"] = CONTRACT
    lookback_returns: Annotated[int, Field(ge=8, le=40)] = 8
    patch_size: Annotated[int, Field(ge=2, le=10)] = 4
    embedding_dim: Annotated[int, Field(ge=8, le=32)] = 16
    attention_heads: Annotated[int, Field(ge=1, le=4)] = 2
    epochs: Annotated[int, Field(ge=2, le=10)] = 5
    learning_rate: Annotated[float, Field(gt=0.0, le=0.01)] = 0.003
    fit_fraction: Annotated[float, Field(ge=0.60, le=0.80)] = 0.70
    minimum_fit_rows: Annotated[int, Field(ge=20, le=2000)] = 25
    minimum_calibration_rows: Annotated[int, Field(ge=15, le=1000)] = 16
    minimum_holdout_rows: Annotated[int, Field(ge=20, le=2000)] = 30
    interval_coverage: Annotated[float, Field(ge=0.80, le=0.95)] = 0.90
    bootstrap_seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @model_validator(mode="after")
    def check_config(self) -> PatchForecastConfig:
        if self.lookback_returns % self.patch_size != 0:
            raise ValueError("lookback must divide into whole non-overlapping patches")
        if self.embedding_dim % self.attention_heads != 0:
            raise ValueError("attention heads must divide the embedding width")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class BoundedPatchMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    forecast_fingerprint: Hash64
    calibration_absolute_error_quantile: float
    mae: float
    rmse: float
    interval_coverage: float
    average_interval_width: float
    trade_count: Annotated[int, Field(ge=0)]
    net_return_after_assumed_costs: float
    maximum_drawdown: float

    @model_validator(mode="after")
    def check_metrics(self) -> BoundedPatchMetrics:
        if self.calibration_absolute_error_quantile < 0:
            raise ValueError("forecast uncertainty cannot be negative")
        if not 0 <= self.interval_coverage <= 1:
            raise ValueError("forecast interval coverage must be within [0,1]")
        if not all(math.isfinite(v) for v in (
            self.mae, self.rmse, self.average_interval_width,
            self.net_return_after_assumed_costs, self.maximum_drawdown,
        )):
            raise ValueError("all forecast metrics must be finite")
        return self


class IndiaPatchForecastReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-bounded-patch-transformer.v1"] = CONTRACT
    baseline_parent_report_fingerprint: Hash64
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    model_config_fingerprint: Hash64
    fit_membership_fingerprint: Hash64
    calibration_membership_fingerprint: Hash64
    holdout_membership_fingerprint: Hash64
    fit_rows: Annotated[int, Field(ge=0)]
    calibration_rows: Annotated[int, Field(ge=0)]
    holdout_rows: Annotated[int, Field(ge=0)]
    model_state_fingerprint: Hash64 | None
    baseline: BoundedPatchMetrics | None
    challenger: BoundedPatchMetrics | None
    paired_economic_report_fingerprint: Hash64 | None
    paired_economic_assessment: str | None
    status: Literal["RESEARCH_CANDIDATE_ONLY", "NO_ECONOMIC_UPLIFT", "INCONCLUSIVE"]
    reason_codes: list[str]
    neural_family: Literal["BOUNDED_CPU_PATCH_TRANSFORMER"] = (
        "BOUNDED_CPU_PATCH_TRANSFORMER"
    )
    same_independent_holdout_compared: bool
    external_market_source_verified: Literal[False] = False
    holdout_pre_registration_verified: Literal[False] = False
    model_licence_production_approved: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    native_trace: NativeForecastDecisionTrace | None
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def check_report(self) -> IndiaPatchForecastReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("patch forecast reasons must be canonical")
        if self.status == "INCONCLUSIVE":
            if not self.reason_codes:
                raise ValueError("inconclusive patch forecast must include reasons")
        elif self.baseline is None or self.challenger is None or (
            self.paired_economic_report_fingerprint is None
            or self.model_state_fingerprint is None
            or not self.same_independent_holdout_compared
        ):
            raise ValueError("completed patch research requires complete comparison")
        if self.status == "RESEARCH_CANDIDATE_ONLY" and (
            self.paired_economic_assessment != "PROMISING_RESEARCH"
            or self.reason_codes
        ):
            raise ValueError("research challenger status requires positive paired evidence")
        if self.native_trace is None:
            if self.model_state_fingerprint is not None:
                raise ValueError("model run must include its native decision trace")
        else:
            trace = self.native_trace
            candidate = self.challenger
            if (
                trace.candidate_id != "bounded-patch-transformer"
                or trace.model_version_ref != CONTRACT
                or trace.model_state_fingerprint != self.model_state_fingerprint
                or trace.causal_baseline_report_fingerprint
                != self.baseline_parent_report_fingerprint
                or trace.cohort.holdout_export_fingerprint
                != self.holdout_export_fingerprint
                or trace.cohort.training_export_fingerprint
                != self.training_export_fingerprint
                or trace.cohort.fee_book_fingerprint != self.fee_book_fingerprint
                or trace.target.fingerprint != self.target_spec_fingerprint
                or canonical_fingerprint([
                    row.row_fingerprint for row in trace.calibration_rows
                ]) != self.calibration_membership_fingerprint
                or canonical_fingerprint([
                    row.row_fingerprint for row in trace.holdout_rows
                ]) != self.holdout_membership_fingerprint
                or candidate is None
                or candidate.forecast_fingerprint
                != canonical_fingerprint(trace.holdout_predictions)
                or candidate.trade_count != trace.reported_trade_count
                or not math.isclose(
                    candidate.net_return_after_assumed_costs,
                    sum(trace.holdout_net_returns_after_costs),
                    rel_tol=1e-9, abs_tol=1e-12,
                )
            ):
                raise ValueError("model summary and native decision trace disagree")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("patch forecast report fingerprint mismatch")
        return self


def _return_windows(
    export: MarketResearchExport, *, lookback: int, horizon: int
) -> list[list[float]]:
    """Exact same per-session row positions as the V4-04 causal baseline."""
    sessions: dict[str, list] = {}
    for bar in export.to_market_bars():
        session = bar.observed_at.astimezone(IST).date().isoformat()
        sessions.setdefault(session, []).append(bar)
    sequences: list[list[float]] = []
    for bars in sessions.values():
        for end in range(lookback, len(bars) - horizon):
            history = bars[end - lookback:end + 1]
            # The target horizon must also be contiguous. Otherwise one
            # declared five-minute forward label may secretly span a gap.
            full_path = bars[end - lookback:end + horizon + 1]
            if any(
                right.observed_at - left.observed_at != timedelta(minutes=5)
                for left, right in zip(full_path[:-1], full_path[1:], strict=True)
            ):
                raise ValueError("patch input and target require contiguous five-minute bars")
            returns = [
                right.close / left.close - 1
                for left, right in zip(history[:-1], history[1:], strict=True)
            ]
            sequences.append(returns)
    return sequences


def _fit_cpu_patch_transformer(
    fit_windows: list[list[float]],
    fit_targets: list[float],
    calibrate_windows: list[list[float]],
    holdout_windows: list[list[float]],
    *,
    config: PatchForecastConfig,
) -> tuple[list[float], list[float], str]:
    try:
        import torch
        from torch import nn
    except ImportError as exc:
        raise RuntimeError(
            "Optional pinned PyTorch CPU dependency is required for patch research"
        ) from exc

    torch.manual_seed(config.bootstrap_seed)
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.backends.mkldnn.enabled = False

    class PatchEncoder(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.patch = nn.Linear(config.patch_size, config.embedding_dim)
            layer = nn.TransformerEncoderLayer(
                d_model=config.embedding_dim,
                nhead=config.attention_heads,
                dim_feedforward=config.embedding_dim * 2,
                dropout=0.0,
                activation="gelu",
                batch_first=True,
            )
            self.encoder = nn.TransformerEncoder(
                layer, num_layers=1, enable_nested_tensor=False
            )
            self.head = nn.Linear(config.embedding_dim, 1)

        def forward(self, rows):
            patches = rows.reshape(-1, config.lookback_returns // config.patch_size,
                                   config.patch_size)
            features = self.encoder(self.patch(patches))
            return self.head(features.mean(dim=1)).reshape(-1)

    model = PatchEncoder().cpu()
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=config.learning_rate, weight_decay=0.01
    )
    x = torch.tensor(fit_windows, dtype=torch.float32, device="cpu") * 100.0
    y = torch.tensor(fit_targets, dtype=torch.float32, device="cpu") * 100.0
    model.train()
    for _ in range(config.epochs):
        optimizer.zero_grad()
        predicted = model(x)
        loss = torch.mean((predicted - y) ** 2)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        optimizer.step()
    state_hash = canonical_fingerprint({
        name: tensor.detach().cpu().tolist()
        for name, tensor in sorted(model.state_dict().items())
    })

    def forecast(rows: list[list[float]]) -> list[float]:
        model.eval()
        with torch.no_grad():
            inputs = torch.tensor(rows, dtype=torch.float32, device="cpu") * 100.0
            estimated = (model(inputs) / 100.0).cpu().tolist()
        return [max(-0.1, min(0.1, float(value))) for value in estimated]

    return forecast(calibrate_windows), forecast(holdout_windows), state_hash


def _metrics(
    calibration: list[CausalForecastRow],
    calibration_prediction: list[float],
    holdout: list[CausalForecastRow],
    holdout_prediction: list[float],
    *,
    target: ForwardReturnTargetSpec,
    interval_coverage: float,
) -> tuple[BoundedPatchMetrics, list[float]]:
    errors = [
        abs(row.observed_forward_return - predicted)
        for row, predicted in zip(calibration, calibration_prediction, strict=True)
    ]
    bound = _calibration_quantile(errors, interval_coverage)
    holdout_errors = [
        row.observed_forward_return - predicted
        for row, predicted in zip(holdout, holdout_prediction, strict=True)
    ]
    threshold = target.positive_threshold_return
    fees = target.round_trip_cost_bps / 10_000.0
    returns = [
        row.observed_forward_return - fees
        if predicted - bound > threshold else 0.0
        for row, predicted in zip(holdout, holdout_prediction, strict=True)
    ]
    metrics = BoundedPatchMetrics(
        forecast_fingerprint=canonical_fingerprint(holdout_prediction),
        calibration_absolute_error_quantile=bound,
        mae=sum(abs(error) for error in holdout_errors) / len(holdout),
        rmse=math.sqrt(sum(error**2 for error in holdout_errors) / len(holdout)),
        interval_coverage=sum(
            abs(error) <= bound for error in holdout_errors
        ) / len(holdout),
        average_interval_width=bound * 2,
        trade_count=sum(
            predicted - bound > threshold
            for predicted in holdout_prediction
        ),
        net_return_after_assumed_costs=float(sum(returns)),
        maximum_drawdown=float(_max_drawdown(returns)),
    )
    return metrics, returns


def evaluate_bounded_patch_forecast(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: PatchForecastConfig | None = None,
) -> IndiaPatchForecastReport:
    policy = config or PatchForecastConfig()
    baseline = evaluate_india_causal_forecast_baseline(
        training_export, holdout_export, fee_book, target,
        config=CausalForecastConfig(
            lookback_returns=policy.lookback_returns,
            minimum_calibration_rows=max(20, policy.minimum_calibration_rows),
            minimum_holdout_rows=policy.minimum_holdout_rows,
            interval_coverage=policy.interval_coverage,
        ),
    )
    train_rows = _causal_rows(
        training_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    hold_rows = _causal_rows(
        holdout_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    train_windows = _return_windows(
        training_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    hold_windows = _return_windows(
        holdout_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    if len(train_windows) != len(train_rows) or len(hold_windows) != len(hold_rows):
        raise ValueError("patch input and baseline causal cohort do not align")
    split = int(len(train_rows) * policy.fit_fraction)
    raw_fit = list(zip(train_rows[:split], train_windows[:split], strict=True))
    calibration = train_rows[split:]
    calibration_windows = train_windows[split:]
    fit = [
        (row, window)
        for row, window in raw_fit
        if calibration and row.label_available_at < calibration[0].decision_at
    ]
    reasons: set[str] = set(baseline.reason_codes)
    if baseline.status != "RESEARCH_FORECAST_METRICS_ONLY":
        reasons.add("CAUSAL_BASELINE_INCONCLUSIVE")
    if len(fit) < policy.minimum_fit_rows:
        reasons.add("INSUFFICIENT_PURGED_FIT_ROWS")
    if len(calibration) < policy.minimum_calibration_rows:
        reasons.add("INSUFFICIENT_UNTOUCHED_CALIBRATION_ROWS")
    if len(hold_rows) < policy.minimum_holdout_rows:
        reasons.add("INSUFFICIENT_INDEPENDENT_HOLDOUT_FORECAST_ROWS")
    if calibration and hold_rows and (
        max(row.label_available_at for row in calibration)
        >= min(row.decision_at for row in hold_rows)
    ):
        raise ValueError("calibration labels overlap independent market holdout")
    base_metrics = challenger_metrics = None
    state_hash = paired_hash = paired_assessment = None
    status = "INCONCLUSIVE"
    native_trace = None
    if not reasons:
        fit_x = [window for _, window in fit]
        fit_y = [row.observed_forward_return for row, _ in fit]
        candidate_calib, candidate_hold, state_hash = _fit_cpu_patch_transformer(
            fit_x, fit_y, calibration_windows, hold_windows, config=policy
        )
        base_metrics, base_returns = _metrics(
            calibration, [row.predicted_forward_return for row in calibration],
            hold_rows, [row.predicted_forward_return for row in hold_rows],
            target=target, interval_coverage=policy.interval_coverage,
        )
        challenger_metrics, challenger_returns = _metrics(
            calibration, candidate_calib, hold_rows, candidate_hold,
            target=target, interval_coverage=policy.interval_coverage,
        )
        native_cohort = bind_common_causal_tournament_cohort(
            baseline, hold_rows, target
        )
        native_trace = build_native_forecast_trace(
            candidate_id="bounded-patch-transformer",
            model_version_ref=CONTRACT,
            model_state_fingerprint=state_hash,
            causal_baseline_report_fingerprint=baseline.report_fingerprint,
            cohort=native_cohort,
            target=target,
            interval_coverage=policy.interval_coverage,
            calibration_rows=calibration,
            calibration_predictions=candidate_calib,
            holdout_rows=hold_rows,
            holdout_predictions=candidate_hold,
            holdout_net_returns_after_costs=challenger_returns,
            reported_trade_count=challenger_metrics.trade_count,
        )
        common = native_cohort.series_kwargs()
        first = create_net_return_series(
            candidate_id="causal-mean-baseline",
            model_version_ref="v4-04-causal-moving-average.v1",
            source_result_fingerprint=canonical_fingerprint({
                "baseline": baseline.report_fingerprint,
                "forecast": base_metrics.forecast_fingerprint,
            }),
            net_returns_after_costs=base_returns, **common,
        )
        second = create_net_return_series(
            candidate_id="bounded-patch-transformer",
            model_version_ref=CONTRACT,
            source_result_fingerprint=canonical_fingerprint({
                "state": state_hash,
                "forecast": challenger_metrics.forecast_fingerprint,
            }),
            net_returns_after_costs=challenger_returns, **common,
        )
        compared = evaluate_incremental_economic_value(
            first, second,
            config=IncrementalValueConfig(bootstrap_seed=policy.bootstrap_seed),
        )
        paired_hash = compared.report_fingerprint
        paired_assessment = compared.assessment
        status = (
            "RESEARCH_CANDIDATE_ONLY"
            if compared.assessment == "PROMISING_RESEARCH"
            else "INCONCLUSIVE"
            if compared.assessment == "INCONCLUSIVE"
            else "NO_ECONOMIC_UPLIFT"
        )
        if status != "RESEARCH_CANDIDATE_ONLY":
            reasons.add("NO_VERIFIED_INCREMENTAL_ECONOMIC_UPLIFT")
    payload = {
        "contract_version": CONTRACT,
        "baseline_parent_report_fingerprint": baseline.report_fingerprint,
        "training_export_fingerprint": baseline.train_export_fingerprint,
        "holdout_export_fingerprint": baseline.holdout_export_fingerprint,
        "fee_book_fingerprint": baseline.india_fee_book_fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "model_config_fingerprint": policy.fingerprint,
        "fit_membership_fingerprint": canonical_fingerprint(
            [row.row_fingerprint for row, _ in fit]
        ),
        "calibration_membership_fingerprint": canonical_fingerprint(
            [row.row_fingerprint for row in calibration]
        ),
        "holdout_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in hold_rows
        ]),
        "fit_rows": len(fit),
        "calibration_rows": len(calibration),
        "holdout_rows": len(hold_rows),
        "model_state_fingerprint": state_hash,
        "baseline": base_metrics.model_dump(mode="json") if base_metrics else None,
        "challenger": (
            challenger_metrics.model_dump(mode="json") if challenger_metrics else None
        ),
        "paired_economic_report_fingerprint": paired_hash,
        "paired_economic_assessment": paired_assessment,
        "status": status,
        "reason_codes": sorted(reasons),
        "neural_family": "BOUNDED_CPU_PATCH_TRANSFORMER",
        "same_independent_holdout_compared": (
            base_metrics is not None and challenger_metrics is not None
        ),
        "external_market_source_verified": False,
        "holdout_pre_registration_verified": False,
        "model_licence_production_approved": False,
        "native_trace": native_trace.model_dump(mode="json") if native_trace else None,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaPatchForecastReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="CPU-only research PatchTST-like comparator on independent Indian holdout"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--cost-bps", type=float, required=True)
    parser.add_argument("--horizon-bars", type=int, default=1)
    parser.add_argument("--minimum-edge-bps", type=float, default=15)
    args = parser.parse_args()
    report = evaluate_bounded_patch_forecast(
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.india_fee_book.read_text(encoding="utf-8")
        ),
        ForwardReturnTargetSpec(
            horizon_bars=args.horizon_bars,
            minimum_edge_bps=args.minimum_edge_bps,
            round_trip_cost_bps=args.cost_bps,
        ),
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
