from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

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

CONTRACT = "india-bounded-tft-quantile-cpu.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class BoundedTftConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-bounded-tft-quantile-cpu.v1"] = CONTRACT
    lookback_returns: Annotated[int, Field(ge=8, le=40)] = 8
    hidden_dim: Literal[8, 16, 32] = 16
    attention_heads: Literal[1, 2, 4] = 2
    epochs: Annotated[int, Field(ge=2, le=10)] = 5
    learning_rate: Annotated[float, Field(gt=0.0, le=0.01)] = 0.003
    fit_fraction: Annotated[float, Field(ge=0.60, le=0.80)] = 0.70
    minimum_fit_rows: Annotated[int, Field(ge=20, le=2000)] = 25
    minimum_calibration_rows: Annotated[int, Field(ge=15, le=2000)] = 16
    minimum_holdout_rows: Annotated[int, Field(ge=20, le=2000)] = 30
    interval_coverage: Annotated[float, Field(ge=0.80, le=0.95)] = 0.90
    bootstrap_seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @model_validator(mode="after")
    def validate_heads(self) -> BoundedTftConfig:
        if self.hidden_dim % self.attention_heads:
            raise ValueError("TFT attention heads must divide hidden dimension")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class BoundedTftReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-bounded-tft-quantile-cpu.v1"] = CONTRACT
    causal_baseline_report_fingerprint: Hash64
    training_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    india_fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    config_fingerprint: Hash64
    fit_membership_fingerprint: Hash64
    calibration_membership_fingerprint: Hash64
    independent_holdout_membership_fingerprint: Hash64
    fit_rows: Annotated[int, Field(ge=0)]
    calibration_rows: Annotated[int, Field(ge=0)]
    independent_holdout_rows: Annotated[int, Field(ge=0)]
    model_state_fingerprint: Hash64 | None
    quantile_forecasts_fingerprint: Hash64 | None
    native_quantile_interval_coverage: float | None
    baseline_metrics: BoundedPatchMetrics | None
    candidate_metrics: BoundedPatchMetrics | None
    paired_economic_report_fingerprint: Hash64 | None
    paired_economic_assessment: str | None
    status: Literal["RESEARCH_COMPARISON_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    architecture: Literal["ORIGINAL_BOUNDED_CAUSAL_TFT_STYLE"] = (
        "ORIGINAL_BOUNDED_CAUSAL_TFT_STYLE"
    )
    official_tft_implementation: Literal[False] = False
    independently_authenticated_market_data: Literal[False] = False
    independently_preregistered_holdout: Literal[False] = False
    economically_proven_in_indian_markets: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_report(self) -> BoundedTftReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("TFT reason codes must be sorted and unique")
        detailed = (
            "model_state_fingerprint",
            "quantile_forecasts_fingerprint",
            "native_quantile_interval_coverage",
            "baseline_metrics",
            "candidate_metrics",
            "paired_economic_report_fingerprint",
            "paired_economic_assessment",
        )
        if self.status == "RESEARCH_COMPARISON_ONLY":
            if self.reason_codes or any(getattr(self, name) is None for name in detailed):
                raise ValueError("completed TFT research requires full evidence")
        elif not self.reason_codes or any(getattr(self, name) is not None for name in detailed):
            raise ValueError("inconclusive TFT research cannot claim evaluated metrics")
        if self.native_quantile_interval_coverage is not None and not (
            0 <= self.native_quantile_interval_coverage <= 1
        ):
            raise ValueError("native quantile coverage must be between zero and one")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("TFT research report fingerprint mismatch")
        return self


def _fit_bounded_tft(
    fit_windows: list[list[float]],
    fit_labels: list[float],
    calibration_windows: list[list[float]],
    holdout_windows: list[list[float]],
    *,
    config: BoundedTftConfig,
) -> tuple[list[tuple[float, float, float]], list[tuple[float, float, float]], str]:
    try:
        import torch
        from torch import nn
        from torch.nn import functional as f
    except ImportError as exc:
        raise RuntimeError("Install the pinned optional CPU PyTorch research dependency") from exc

    torch.manual_seed(config.bootstrap_seed)
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.backends.mkldnn.enabled = False

    class TinyCausalTft(nn.Module):
        """Original variable-gated, causal-attention, monotone quantile network.

        Inspired by TFT blocks, NOT upstream TFT or pretrained TFT weights.
        Only past close-return features are inputs, so this does not implement
        static enrichment, known-future covariates or full TFT interpreters.
        """

        def __init__(self) -> None:
            super().__init__()
            self.variable_projection = nn.Linear(3, config.hidden_dim)
            self.variable_gate = nn.Linear(3, config.hidden_dim)
            self.attention = nn.MultiheadAttention(
                config.hidden_dim, config.attention_heads, dropout=0.0, batch_first=True
            )
            self.output_gate = nn.Linear(config.hidden_dim, config.hidden_dim)
            self.layer_norm = nn.LayerNorm(config.hidden_dim)
            self.quantiles = nn.Linear(config.hidden_dim, 3)

        def forward(self, returns):
            # All features are derived from the lookback window through the
            # observed decision bar, never from a future target or later session.
            cumulative = torch.cumsum(returns, dim=1)
            divisor = torch.arange(
                1, returns.shape[1] + 1, device=returns.device, dtype=returns.dtype
            )
            features = torch.stack((returns, returns.abs(), cumulative / divisor), dim=-1)
            hidden = torch.tanh(self.variable_projection(features))
            hidden = hidden * torch.sigmoid(self.variable_gate(features))
            steps = returns.shape[1]
            mask = torch.triu(
                torch.ones(steps, steps, dtype=torch.bool, device=returns.device),
                diagonal=1,
            )
            attended, _ = self.attention(
                hidden, hidden, hidden, attn_mask=mask, need_weights=False
            )
            merged = self.layer_norm(
                hidden + torch.sigmoid(self.output_gate(hidden)) * attended
            )
            raw = self.quantiles(merged[:, -1, :])
            median = raw[:, 1]
            lower = median - f.softplus(raw[:, 0])
            upper = median + f.softplus(raw[:, 2])
            return torch.stack((lower, median, upper), dim=-1)

    model = TinyCausalTft().cpu()
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=config.learning_rate, weight_decay=0.01
    )
    x = torch.tensor(fit_windows, dtype=torch.float32, device="cpu") * 100.0
    y = torch.tensor(fit_labels, dtype=torch.float32, device="cpu") * 100.0
    quantiles = torch.tensor([0.1, 0.5, 0.9], dtype=torch.float32, device="cpu")
    model.train()
    for _ in range(config.epochs):
        optimizer.zero_grad()
        prediction = model(x)
        error = y.unsqueeze(1) - prediction
        loss = torch.maximum(quantiles * error, (quantiles - 1) * error).mean()
        if not bool(torch.isfinite(loss)):
            raise ValueError("TFT model returned non-finite pinball loss")
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        optimizer.step()

    state = canonical_fingerprint({
        name: tensor.detach().cpu().tolist()
        for name, tensor in sorted(model.state_dict().items())
    })

    def infer(windows: list[list[float]]) -> list[tuple[float, float, float]]:
        model.eval()
        with torch.no_grad():
            inp = torch.tensor(windows, dtype=torch.float32, device="cpu") * 100.0
            results = (model(inp) / 100.0).cpu().tolist()
        if any(
            not all(math.isfinite(value) and abs(value) <= 1 for value in row)
            or not row[0] <= row[1] <= row[2]
            for row in results
        ):
            raise ValueError("TFT output quantiles are not finite, ordered or bounded")
        return [tuple(float(value) for value in row) for row in results]

    return infer(calibration_windows), infer(holdout_windows), state


def evaluate_bounded_tft(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: BoundedTftConfig | None = None,
) -> BoundedTftReport:
    policy = config or BoundedTftConfig()
    if target.horizon_bars != 1:
        raise ValueError("bounded TFT research v1 only supports next five-minute labels")
    parent = evaluate_india_causal_forecast_baseline(
        training_export, holdout_export, fee_book, target,
        config=CausalForecastConfig(
            lookback_returns=policy.lookback_returns,
            minimum_calibration_rows=max(20, policy.minimum_calibration_rows),
            minimum_holdout_rows=policy.minimum_holdout_rows,
            interval_coverage=policy.interval_coverage,
        ),
    )
    training = _causal_rows(training_export, lookback=policy.lookback_returns, horizon=1)
    heldout = _causal_rows(holdout_export, lookback=policy.lookback_returns, horizon=1)
    train_windows = _return_windows(training_export, lookback=policy.lookback_returns,
                                    horizon=1)
    hold_windows = _return_windows(holdout_export, lookback=policy.lookback_returns,
                                   horizon=1)
    if len(training) != len(train_windows) or len(heldout) != len(hold_windows):
        raise ValueError("TFT context differs from independent causal holdout rows")
    cut = int(len(training) * policy.fit_fraction)
    calibration, calibration_windows = training[cut:], train_windows[cut:]
    fit = [
        (row, window)
        for row, window in zip(training[:cut], train_windows[:cut], strict=True)
        if calibration and row.label_available_at < calibration[0].decision_at
    ]
    if calibration and heldout and max(row.label_available_at for row in calibration) >= min(
        row.decision_at for row in heldout
    ):
        raise ValueError("TFT calibration labels overlap independent market holdout")

    reasons = set(parent.reason_codes)
    if parent.status != "RESEARCH_FORECAST_METRICS_ONLY":
        reasons.add("CAUSAL_PARENT_INCONCLUSIVE")
    if len(fit) < policy.minimum_fit_rows:
        reasons.add("INSUFFICIENT_PURGED_FIT_ROWS")
    if len(calibration) < policy.minimum_calibration_rows:
        reasons.add("INSUFFICIENT_UNTOUCHED_CALIBRATION_ROWS")
    if len(heldout) < policy.minimum_holdout_rows:
        reasons.add("INSUFFICIENT_INDEPENDENT_HOLDOUT_ROWS")
    state = forecasts = base_metrics = candidate_metrics = paired_hash = assessment = None
    native_coverage = None
    if not reasons:
        train_q, held_q, state = _fit_bounded_tft(
            [window for _, window in fit],
            [row.observed_forward_return for row, _ in fit],
            calibration_windows, hold_windows, config=policy,
        )
        forecasts = canonical_fingerprint({
            "calibration_quantiles": train_q, "holdout_quantiles": held_q,
        })
        native_coverage = sum(
            quant[0] <= row.observed_forward_return <= quant[2]
            for row, quant in zip(heldout, held_q, strict=True)
        ) / len(heldout)
        base_metrics, base_returns = _metrics(
            calibration, [row.predicted_forward_return for row in calibration],
            heldout, [row.predicted_forward_return for row in heldout],
            target=target, interval_coverage=policy.interval_coverage,
        )
        candidate_metrics, candidate_returns = _metrics(
            calibration, [value[1] for value in train_q],
            heldout, [value[1] for value in held_q],
            target=target, interval_coverage=policy.interval_coverage,
        )
        membership = [
            canonical_fingerprint({
                "history": row.history_fingerprint,
                "target": row.target_fingerprint,
                "time": row.decision_at.isoformat(),
            })
            for row in heldout
        ]
        shared = {
            "target_binding_fingerprint": target.fingerprint,
            "fold_binding_fingerprint": canonical_fingerprint({
                "train": parent.train_export_fingerprint,
                "holdout": parent.holdout_export_fingerprint,
                "observations": membership,
            }),
            "economic_cost_binding_fingerprint": canonical_fingerprint({
                "fee_book": parent.india_fee_book_fingerprint,
                "cost_bps": target.round_trip_cost_bps,
            }),
            "observation_fingerprints": membership,
            "decision_times": [row.decision_at for row in heldout],
        }
        baseline = create_net_return_series(
            candidate_id="causal-mean-baseline",
            model_version_ref="v4-04-causal-moving-average.v1",
            source_result_fingerprint=canonical_fingerprint({
                "baseline": parent.report_fingerprint,
                "predictions": base_metrics.forecast_fingerprint,
            }),
            net_returns_after_costs=base_returns, **shared,
        )
        candidate = create_net_return_series(
            candidate_id="original-bounded-tft",
            model_version_ref=CONTRACT,
            source_result_fingerprint=canonical_fingerprint({
                "model_state": state,
                "forecasts": forecasts,
            }),
            net_returns_after_costs=candidate_returns, **shared,
        )
        paired = evaluate_incremental_economic_value(
            baseline, candidate,
            config=IncrementalValueConfig(bootstrap_seed=policy.bootstrap_seed),
        )
        paired_hash = paired.report_fingerprint
        assessment = paired.assessment

    payload = {
        "contract_version": CONTRACT,
        "causal_baseline_report_fingerprint": parent.report_fingerprint,
        "training_export_fingerprint": parent.train_export_fingerprint,
        "holdout_export_fingerprint": parent.holdout_export_fingerprint,
        "india_fee_book_fingerprint": parent.india_fee_book_fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "config_fingerprint": policy.fingerprint,
        "fit_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row, _ in fit
        ]),
        "calibration_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in calibration
        ]),
        "independent_holdout_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in heldout
        ]),
        "fit_rows": len(fit),
        "calibration_rows": len(calibration),
        "independent_holdout_rows": len(heldout),
        "model_state_fingerprint": state,
        "quantile_forecasts_fingerprint": forecasts,
        "native_quantile_interval_coverage": native_coverage,
        "baseline_metrics": base_metrics.model_dump(mode="json") if base_metrics else None,
        "candidate_metrics": (
            candidate_metrics.model_dump(mode="json") if candidate_metrics else None
        ),
        "paired_economic_report_fingerprint": paired_hash,
        "paired_economic_assessment": assessment,
        "status": "INCONCLUSIVE" if reasons else "RESEARCH_COMPARISON_ONLY",
        "reason_codes": sorted(reasons),
        "architecture": "ORIGINAL_BOUNDED_CAUSAL_TFT_STYLE",
        "official_tft_implementation": False,
        "independently_authenticated_market_data": False,
        "independently_preregistered_holdout": False,
        "economically_proven_in_indian_markets": False,
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return BoundedTftReport(**payload, report_fingerprint=canonical_fingerprint(payload))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Original CPU TFT-inspired causal quantile research, no trading authority"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--cost-bps", type=float, required=True)
    args = parser.parse_args()
    report = evaluate_bounded_tft(
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.india_fee_book.read_text(encoding="utf-8")
        ),
        ForwardReturnTargetSpec(
            horizon_bars=1, minimum_edge_bps=15, round_trip_cost_bps=args.cost_bps
        ),
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
