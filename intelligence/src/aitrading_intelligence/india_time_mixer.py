from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .cross_model_causal_cohort import bind_common_causal_tournament_cohort
from .native_forecast_trace import (
    NativeForecastDecisionTrace,
    build_native_forecast_trace,
)
from .fingerprints import canonical_fingerprint
from .incremental_economic_value import (
    IncrementalValueConfig,
    create_net_return_series,
    evaluate_incremental_economic_value,
)
from .india_causal_forecast_baseline import (
    CausalForecastConfig,
    _causal_rows,
    evaluate_india_causal_forecast_baseline,
)
from .india_fee_tax import IndiaFeeTaxBookV1
from .india_patch_transformer import BoundedPatchMetrics, _metrics, _return_windows
from .market_source import MarketResearchExport, load_market_export
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-bounded-time-mixer-cpu.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class TimeMixerResearchConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-bounded-time-mixer-cpu.v1"] = VERSION
    lookback_returns: Annotated[int, Field(ge=8, le=40)] = 8
    downsample_factor: Literal[2, 4] = 2
    hidden_dim: Annotated[int, Field(ge=8, le=32)] = 16
    epochs: Annotated[int, Field(ge=2, le=10)] = 5
    learning_rate: Annotated[float, Field(gt=0.0, le=0.01)] = 0.003
    fit_fraction: Annotated[float, Field(ge=0.60, le=0.80)] = 0.70
    minimum_fit_rows: Annotated[int, Field(ge=20, le=2000)] = 25
    minimum_calibration_rows: Annotated[int, Field(ge=15, le=2000)] = 16
    minimum_holdout_rows: Annotated[int, Field(ge=20, le=2000)] = 30
    interval_coverage: Annotated[float, Field(ge=0.80, le=0.95)] = 0.90
    bootstrap_seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @model_validator(mode="after")
    def check_scale(self) -> TimeMixerResearchConfig:
        if self.lookback_returns % self.downsample_factor != 0:
            raise ValueError("TimeMixer lookback must divide into whole pooled scales")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class TimeMixerResearchReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-bounded-time-mixer-cpu.v1"] = VERSION
    causal_baseline_report_fingerprint: Hash64
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    config_fingerprint: Hash64
    fit_example_membership_fingerprint: Hash64
    calibration_example_membership_fingerprint: Hash64
    holdout_example_membership_fingerprint: Hash64
    fit_rows: Annotated[int, Field(ge=0)]
    calibration_rows: Annotated[int, Field(ge=0)]
    holdout_rows: Annotated[int, Field(ge=0)]
    model_state_fingerprint: Hash64 | None
    causal_baseline_metrics: BoundedPatchMetrics | None
    time_mixer_metrics: BoundedPatchMetrics | None
    paired_economic_report_fingerprint: Hash64 | None
    paired_economic_assessment: str | None
    status: Literal["RESEARCH_COMPARISON_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    model_identity: Literal["ORIGINAL_BOUNDED_TIME_MIXER_STYLE_CPU"] = (
        "ORIGINAL_BOUNDED_TIME_MIXER_STYLE_CPU"
    )
    authentic_market_source_verified: Literal[False] = False
    preregistered_holdout_verified: Literal[False] = False
    genuine_market_economic_uplift_verified: Literal[False] = False
    upstream_timemixer_weights_used: Literal[False] = False
    production_model_approved: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    native_trace: NativeForecastDecisionTrace | None
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_report(self) -> TimeMixerResearchReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("TimeMixer reasons must be canonical")
        if self.status == "RESEARCH_COMPARISON_ONLY":
            if self.reason_codes or any(getattr(self, key) is None for key in (
                "model_state_fingerprint", "causal_baseline_metrics",
                "time_mixer_metrics", "paired_economic_report_fingerprint",
                "paired_economic_assessment",
            )):
                raise ValueError("TimeMixer comparison needs complete research evidence")
        elif not self.reason_codes or any(getattr(self, key) is not None for key in (
            "model_state_fingerprint", "causal_baseline_metrics", "time_mixer_metrics",
            "paired_economic_report_fingerprint", "paired_economic_assessment",
        )):
            raise ValueError("inconclusive TimeMixer must not claim model comparison")
        if self.native_trace is None:
            if self.model_state_fingerprint is not None:
                raise ValueError("model run must include its native decision trace")
        else:
            trace = self.native_trace
            candidate = self.time_mixer_metrics
            if (
                trace.candidate_id != "bounded-time-mixer-research"
                or trace.model_version_ref != VERSION
                or trace.model_state_fingerprint != self.model_state_fingerprint
                or trace.causal_baseline_report_fingerprint != self.causal_baseline_report_fingerprint
                or trace.cohort.holdout_export_fingerprint
                != self.holdout_export_fingerprint
                or trace.cohort.training_export_fingerprint
                != self.training_export_fingerprint
                or trace.cohort.fee_book_fingerprint != self.fee_book_fingerprint
                or trace.target.fingerprint != self.target_spec_fingerprint
                or canonical_fingerprint([
                    row.row_fingerprint for row in trace.calibration_rows
                ]) != self.calibration_example_membership_fingerprint
                or canonical_fingerprint([
                    row.row_fingerprint for row in trace.holdout_rows
                ]) != self.holdout_example_membership_fingerprint
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
            raise ValueError("TimeMixer research report fingerprint mismatch")
        return self


def _train_bounded_time_mixer(
    fit_windows: list[list[float]],
    fit_labels: list[float],
    calibration_windows: list[list[float]],
    holdout_windows: list[list[float]],
    *,
    config: TimeMixerResearchConfig,
) -> tuple[list[float], list[float], str]:
    try:
        import torch
        from torch import nn
        from torch.nn import functional as f
    except ImportError as exc:
        raise RuntimeError("Pinned CPU PyTorch is required for TimeMixer research") from exc

    torch.manual_seed(config.bootstrap_seed)
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.backends.mkldnn.enabled = False

    class SmallTimeMixer(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            width = config.lookback_returns
            coarse = width // config.downsample_factor
            self.trend_projection = nn.Linear(width, 1)
            self.seasonal_projection = nn.Sequential(
                nn.Linear(width + coarse, config.hidden_dim),
                nn.GELU(),
                nn.Linear(config.hidden_dim, 1),
            )
            self.gate = nn.Linear(width + coarse, 1)

        def forward(self, rows):
            # Left replicate padding uses no unseen bars. All values are known
            # at each decision; this is original bounded time/scale mixing,
            # not a reimplementation or claim of upstream TimeMixer weights.
            trend = f.avg_pool1d(
                f.pad(rows.unsqueeze(1), (2, 0), mode="replicate"),
                kernel_size=3,
                stride=1,
            ).squeeze(1)
            seasonal = rows - trend
            pooled = seasonal.reshape(
                -1, config.lookback_returns // config.downsample_factor,
                config.downsample_factor,
            ).mean(dim=-1)
            combined = torch.cat((seasonal, pooled), dim=-1)
            mixing = torch.sigmoid(self.gate(combined))
            return (
                (1.0 - mixing) * self.trend_projection(trend)
                + mixing * self.seasonal_projection(combined)
            ).reshape(-1)

    model = SmallTimeMixer().cpu()
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=config.learning_rate, weight_decay=0.01
    )
    features = torch.tensor(fit_windows, dtype=torch.float32, device="cpu") * 100.0
    targets = torch.tensor(fit_labels, dtype=torch.float32, device="cpu") * 100.0
    model.train()
    for _ in range(config.epochs):
        optimizer.zero_grad()
        loss = ((model(features) - targets) ** 2).mean()
        if not bool(torch.isfinite(loss)):
            raise ValueError("TimeMixer training returned non-finite loss")
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        optimizer.step()
    state_hash = canonical_fingerprint({
        key: tensor.detach().cpu().tolist()
        for key, tensor in sorted(model.state_dict().items())
    })

    def forecast(windows: list[list[float]]) -> list[float]:
        model.eval()
        with torch.no_grad():
            inputs = torch.tensor(windows, dtype=torch.float32, device="cpu") * 100.0
            raw = (model(inputs) / 100.0).cpu().tolist()
        if any(not math.isfinite(value) for value in raw):
            raise ValueError("TimeMixer returned non-finite forecast")
        return [max(-0.10, min(0.10, float(value))) for value in raw]

    return forecast(calibration_windows), forecast(holdout_windows), state_hash


def evaluate_bounded_time_mixer(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: TimeMixerResearchConfig | None = None,
) -> TimeMixerResearchReport:
    policy = config or TimeMixerResearchConfig()
    parent = evaluate_india_causal_forecast_baseline(
        training_export, holdout_export, fee_book, target,
        config=CausalForecastConfig(
            lookback_returns=policy.lookback_returns,
            minimum_calibration_rows=max(20, policy.minimum_calibration_rows),
            minimum_holdout_rows=policy.minimum_holdout_rows,
            interval_coverage=policy.interval_coverage,
        ),
    )
    training = _causal_rows(
        training_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    heldout = _causal_rows(
        holdout_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    train_windows = _return_windows(
        training_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    hold_windows = _return_windows(
        holdout_export, lookback=policy.lookback_returns, horizon=target.horizon_bars
    )
    if len(training) != len(train_windows) or len(heldout) != len(hold_windows):
        raise ValueError("TimeMixer and baseline causal observations differ")
    split = int(len(training) * policy.fit_fraction)
    calibration = training[split:]
    calibration_windows = train_windows[split:]
    fit = [
        (row, window)
        for row, window in zip(training[:split], train_windows[:split], strict=True)
        if calibration and row.label_available_at < calibration[0].decision_at
    ]
    if calibration and heldout and (
        max(row.label_available_at for row in calibration)
        >= min(row.decision_at for row in heldout)
    ):
        raise ValueError("TimeMixer calibration labels overlap independent holdout")
    reasons = set(parent.reason_codes)
    if parent.status != "RESEARCH_FORECAST_METRICS_ONLY":
        reasons.add("INCONCLUSIVE_CAUSAL_PARENT")
    if len(fit) < policy.minimum_fit_rows:
        reasons.add("INSUFFICIENT_PURGED_TRAINING_ROWS")
    if len(calibration) < policy.minimum_calibration_rows:
        reasons.add("INSUFFICIENT_UNTOUCHED_CALIBRATION_ROWS")
    if len(heldout) < policy.minimum_holdout_rows:
        reasons.add("INSUFFICIENT_INDEPENDENT_HOLDOUT_ROWS")
    baseline_metrics = candidate_metrics = None
    state_hash = paired_hash = assessment = None
    native_trace = None
    if not reasons:
        calib_prediction, holdout_prediction, state_hash = _train_bounded_time_mixer(
            [window for _, window in fit],
            [row.observed_forward_return for row, _ in fit],
            calibration_windows, hold_windows, config=policy,
        )
        baseline_metrics, baseline_returns = _metrics(
            calibration, [row.predicted_forward_return for row in calibration],
            heldout, [row.predicted_forward_return for row in heldout],
            target=target, interval_coverage=policy.interval_coverage,
        )
        candidate_metrics, candidate_returns = _metrics(
            calibration, calib_prediction, heldout, holdout_prediction,
            target=target, interval_coverage=policy.interval_coverage,
        )
        native_cohort = bind_common_causal_tournament_cohort(
            parent, heldout, target
        )
        native_trace = build_native_forecast_trace(
            candidate_id="bounded-time-mixer-research",
            model_version_ref=VERSION,
            model_state_fingerprint=state_hash,
            causal_baseline_report_fingerprint=parent.report_fingerprint,
            cohort=native_cohort,
            target=target,
            interval_coverage=policy.interval_coverage,
            calibration_rows=calibration,
            calibration_predictions=calib_prediction,
            holdout_rows=heldout,
            holdout_predictions=holdout_prediction,
            holdout_net_returns_after_costs=candidate_returns,
            reported_trade_count=candidate_metrics.trade_count,
        )
        common = native_cohort.series_kwargs()
        baseline_series = create_net_return_series(
            candidate_id="causal-mean-baseline",
            model_version_ref="v4-04-causal-moving-average.v1",
            source_result_fingerprint=canonical_fingerprint({
                "parent": parent.report_fingerprint,
                "forecast": baseline_metrics.forecast_fingerprint,
            }),
            net_returns_after_costs=baseline_returns, **common,
        )
        contender_series = create_net_return_series(
            candidate_id="bounded-time-mixer-research",
            model_version_ref=VERSION,
            source_result_fingerprint=canonical_fingerprint({
                "state": state_hash,
                "forecast": candidate_metrics.forecast_fingerprint,
            }),
            net_returns_after_costs=candidate_returns, **common,
        )
        paired = evaluate_incremental_economic_value(
            baseline_series, contender_series,
            config=IncrementalValueConfig(bootstrap_seed=policy.bootstrap_seed),
        )
        paired_hash = paired.report_fingerprint
        assessment = paired.assessment
    payload = {
        "contract_version": VERSION,
        "causal_baseline_report_fingerprint": parent.report_fingerprint,
        "training_export_fingerprint": parent.train_export_fingerprint,
        "holdout_export_fingerprint": parent.holdout_export_fingerprint,
        "fee_book_fingerprint": parent.india_fee_book_fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "config_fingerprint": policy.fingerprint,
        "fit_example_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row, _ in fit
        ]),
        "calibration_example_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in calibration
        ]),
        "holdout_example_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in heldout
        ]),
        "fit_rows": len(fit),
        "calibration_rows": len(calibration),
        "holdout_rows": len(heldout),
        "model_state_fingerprint": state_hash,
        "causal_baseline_metrics": (
            baseline_metrics.model_dump(mode="json") if baseline_metrics else None
        ),
        "time_mixer_metrics": (
            candidate_metrics.model_dump(mode="json") if candidate_metrics else None
        ),
        "paired_economic_report_fingerprint": paired_hash,
        "paired_economic_assessment": assessment,
        "status": "INCONCLUSIVE" if reasons else "RESEARCH_COMPARISON_ONLY",
        "reason_codes": sorted(reasons),
        "model_identity": "ORIGINAL_BOUNDED_TIME_MIXER_STYLE_CPU",
        "authentic_market_source_verified": False,
        "preregistered_holdout_verified": False,
        "genuine_market_economic_uplift_verified": False,
        "upstream_timemixer_weights_used": False,
        "production_model_approved": False,
        "native_trace": native_trace.model_dump(mode="json") if native_trace else None,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TimeMixerResearchReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run original bounded CPU TimeMixer-style research on India holdout"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--cost-bps", type=float, required=True)
    parser.add_argument("--horizon-bars", type=int, default=1)
    args = parser.parse_args()
    report = evaluate_bounded_time_mixer(
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
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
