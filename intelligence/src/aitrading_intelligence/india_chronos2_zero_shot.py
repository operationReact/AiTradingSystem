from __future__ import annotations

import argparse
import json
import math
from collections.abc import Callable
from datetime import timedelta
from pathlib import Path
from typing import Annotated, Any, Literal

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
from .india_patch_transformer import _metrics, _return_windows
from .market_source import MarketResearchExport, load_market_export
from .pytorch_target import ForwardReturnTargetSpec

VERSION = "india-chronos2-zero-shot-research.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class Chronos2ResearchConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-chronos2-zero-shot-research.v1"] = VERSION
    checkpoint_id: Literal["amazon/chronos-2"] = "amazon/chronos-2"
    checkpoint_revision: Annotated[str, Field(pattern=r"^[a-f0-9]{40}$")]
    context_returns: Annotated[int, Field(ge=8, le=40)] = 8
    minimum_calibration_rows: Annotated[int, Field(ge=15, le=512)] = 16
    minimum_holdout_rows: Annotated[int, Field(ge=30, le=512)] = 30
    maximum_forecast_calls: Annotated[int, Field(ge=40, le=1024)] = 256
    interval_coverage: Literal[0.90] = 0.90
    bootstrap_seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class Chronos2ResearchReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-chronos2-zero-shot-research.v1"] = VERSION
    checkpoint_id: Literal["amazon/chronos-2"]
    checkpoint_revision: str
    licence_spdx: Literal["Apache-2.0"] = "Apache-2.0"
    licence_source_urls: tuple[str, str] = (
        "https://huggingface.co/amazon/chronos-2",
        "https://github.com/amazon-science/chronos-forecasting/blob/main/LICENSE",
    )
    baseline_report_fingerprint: Hash64
    train_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    fee_book_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    configuration_fingerprint: Hash64
    train_calibration_membership_fingerprint: Hash64
    holdout_membership_fingerprint: Hash64
    calibrated_rows: Annotated[int, Field(ge=0)]
    holdout_rows: Annotated[int, Field(ge=0)]
    native_quantile_forecasts_fingerprint: Hash64 | None
    baseline_metrics_fingerprint: Hash64 | None
    chronos_metrics_fingerprint: Hash64 | None
    paired_economic_report_fingerprint: Hash64 | None
    paired_economic_assessment: str | None
    status: Literal["RESEARCH_COMPARISON_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    adapter_invocation_completed: bool
    checkpoint_integrity_independently_verified: Literal[False] = False
    market_source_independently_verified: Literal[False] = False
    independent_holdout_preregistration_verified: Literal[False] = False
    checkpoint_commercial_review_complete: Literal[False] = False
    production_model_approved: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_report(self) -> Chronos2ResearchReport:
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("Chronos-2 research reasons must be canonical")
        if self.status == "RESEARCH_COMPARISON_ONLY" and (
            self.reason_codes
            or not self.adapter_invocation_completed
            or not self.native_quantile_forecasts_fingerprint
            or not self.paired_economic_report_fingerprint
        ):
            raise ValueError("comparison requires complete research evidence")
        if self.status == "INCONCLUSIVE" and (
            not self.reason_codes or self.adapter_invocation_completed
        ):
            raise ValueError("inconclusive evidence cannot claim model execution")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("Chronos-2 research report fingerprint mismatch")
        return self


def load_local_chronos2_pipeline() -> Any:
    """No model downloads: require operator-prepared, locally cached checkpoint."""
    try:
        from chronos import Chronos2Pipeline
    except ImportError as exc:
        raise RuntimeError(
            "Install a separately reviewed chronos-forecasting research environment"
        ) from exc
    return Chronos2Pipeline


def _forecast_one(
    pipeline: Any,
    frame_factory: Callable[[list[dict[str, Any]]], Any],
    history: list[float],
    *,
    decision_at: Any,
) -> tuple[float, float, float]:
    # The input is strictly the N returns observable at this decision, never
    # a holdout label or a future candle. Generate a contiguous five-minute grid.
    rows = [{
        "item_id": "single-cash-equity-research",
        "timestamp": decision_at - timedelta(minutes=5 * (len(history) - i - 1)),
        "target": return_value,
    } for i, return_value in enumerate(history)]
    response = pipeline.predict_df(
        frame_factory(rows),
        prediction_length=1,
        quantile_levels=[0.1, 0.5, 0.9],
        id_column="item_id",
        timestamp_column="timestamp",
        target="target",
    )
    output = response.to_dict(orient="records") if hasattr(response, "to_dict") else response
    if not isinstance(output, list) or len(output) != 1 or not isinstance(output[0], dict):
        raise ValueError("Chronos-2 must return exactly one next-bar quantile prediction")
    try:
        low, median, high = (float(output[0][key]) for key in ("0.1", "0.5", "0.9"))
    except (KeyError, ValueError, TypeError):
        raise ValueError("Chronos-2 quantile forecast is invalid") from None
    if not all(math.isfinite(v) for v in (low, median, high)) or not low <= median <= high:
        raise ValueError("Chronos-2 output quantiles are non-finite or unordered")
    if any(abs(v) > 1 for v in (low, median, high)):
        raise ValueError("Chronos-2 return forecast exceeds conservative bounds")
    return low, median, high


def evaluate_chronos2_zero_shot(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: Chronos2ResearchConfig,
    pipeline: Any,
    frame_factory: Callable[[list[dict[str, Any]]], Any],
) -> Chronos2ResearchReport:
    """Bounded zero-shot next-bar research with no external checkpoint fetching.

    pipeline must have been loaded explicitly from locally cached approved
    revision outside CI. Mocked pipelines in CI do NOT establish checkpoint
    execution or real India market performance.
    """
    if target.horizon_bars != 1:
        raise ValueError("Chronos-2 research v1 only supports next-five-minute returns")
    parent = evaluate_india_causal_forecast_baseline(
        training_export, holdout_export, fee_book, target,
        config=CausalForecastConfig(
            lookback_returns=config.context_returns,
            minimum_calibration_rows=max(20, config.minimum_calibration_rows),
            minimum_holdout_rows=config.minimum_holdout_rows,
        ),
    )
    training = _causal_rows(training_export, lookback=config.context_returns, horizon=1)
    heldout = _causal_rows(holdout_export, lookback=config.context_returns, horizon=1)
    train_context = _return_windows(training_export, lookback=config.context_returns,
                                    horizon=1)
    hold_context = _return_windows(holdout_export, lookback=config.context_returns,
                                   horizon=1)
    if len(train_context) != len(training) or len(hold_context) != len(heldout):
        raise ValueError("Chronos context differs from the frozen causal cohort")
    calibration = training[-config.minimum_calibration_rows:]
    calib_context = train_context[-config.minimum_calibration_rows:]
    reasons: set[str] = set(parent.reason_codes)
    if parent.status != "RESEARCH_FORECAST_METRICS_ONLY":
        reasons.add("PARENT_CAUSAL_HOLDOUT_INCONCLUSIVE")
    if len(training) < config.minimum_calibration_rows:
        reasons.add("INSUFFICIENT_TRAINING_CALIBRATION")
    if len(heldout) < config.minimum_holdout_rows:
        reasons.add("INSUFFICIENT_HOLDOUT")
    if len(calibration) + len(heldout) > config.maximum_forecast_calls:
        reasons.add("FORECAST_CALL_BUDGET_EXCEEDED")
    if calibration and heldout and max(row.label_available_at for row in calibration) >= min(
        row.decision_at for row in heldout
    ):
        raise ValueError("Chronos calibration label overlaps independent holdout")

    native_hash = baseline_metrics_hash = challenger_metrics_hash = paired_hash = None
    assessment = None
    if not reasons:
        calibrations = [
            _forecast_one(pipeline, frame_factory, window, decision_at=row.decision_at)
            for row, window in zip(calibration, calib_context, strict=True)
        ]
        predictions = [
            _forecast_one(pipeline, frame_factory, window, decision_at=row.decision_at)
            for row, window in zip(heldout, hold_context, strict=True)
        ]
        native_hash = canonical_fingerprint({
            "calibration": calibrations, "holdout": predictions,
        })
        base_metrics, base_returns = _metrics(
            calibration, [row.predicted_forward_return for row in calibration],
            heldout, [row.predicted_forward_return for row in heldout],
            target=target, interval_coverage=config.interval_coverage,
        )
        challenger_metrics, challenger_returns = _metrics(
            calibration, [value[1] for value in calibrations],
            heldout, [value[1] for value in predictions],
            target=target, interval_coverage=config.interval_coverage,
        )
        baseline_metrics_hash = canonical_fingerprint(base_metrics.model_dump(mode="json"))
        challenger_metrics_hash = canonical_fingerprint(challenger_metrics.model_dump(mode="json"))
        members = [
            canonical_fingerprint({
                "history": row.history_fingerprint,
                "target": row.target_fingerprint,
                "time": row.decision_at.isoformat(),
            }) for row in heldout
        ]
        common = {
            "target_binding_fingerprint": target.fingerprint,
            "fold_binding_fingerprint": canonical_fingerprint({
                "training": parent.train_export_fingerprint,
                "holdout": parent.holdout_export_fingerprint,
                "members": members,
            }),
            "economic_cost_binding_fingerprint": canonical_fingerprint({
                "fee_book": parent.india_fee_book_fingerprint,
                "assumed_round_trip_cost_bps": target.round_trip_cost_bps,
            }),
            "observation_fingerprints": members,
            "decision_times": [row.decision_at for row in heldout],
        }
        base_series = create_net_return_series(
            candidate_id="causal-mean-baseline",
            model_version_ref="v4-04-causal-moving-average.v1",
            source_result_fingerprint=canonical_fingerprint({
                "parent": parent.report_fingerprint,
                "forecast": base_metrics.forecast_fingerprint,
            }),
            net_returns_after_costs=base_returns, **common,
        )
        candidate_series = create_net_return_series(
            candidate_id="chronos2-zero-shot-research",
            model_version_ref=VERSION,
            source_result_fingerprint=canonical_fingerprint({
                "checkpoint": config.checkpoint_revision,
                "forecast": native_hash,
            }),
            net_returns_after_costs=challenger_returns, **common,
        )
        paired = evaluate_incremental_economic_value(
            base_series, candidate_series,
            config=IncrementalValueConfig(bootstrap_seed=config.bootstrap_seed),
        )
        paired_hash = paired.report_fingerprint
        assessment = paired.assessment
    payload = {
        "contract_version": VERSION,
        "checkpoint_id": config.checkpoint_id,
        "checkpoint_revision": config.checkpoint_revision,
        "licence_spdx": "Apache-2.0",
        "licence_source_urls": (
            "https://huggingface.co/amazon/chronos-2",
            "https://github.com/amazon-science/chronos-forecasting/blob/main/LICENSE",
        ),
        "baseline_report_fingerprint": parent.report_fingerprint,
        "train_export_fingerprint": parent.train_export_fingerprint,
        "holdout_export_fingerprint": parent.holdout_export_fingerprint,
        "fee_book_fingerprint": parent.india_fee_book_fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "configuration_fingerprint": config.fingerprint,
        "train_calibration_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in calibration
        ]),
        "holdout_membership_fingerprint": canonical_fingerprint([
            row.row_fingerprint for row in heldout
        ]),
        "calibrated_rows": len(calibration),
        "holdout_rows": len(heldout),
        "native_quantile_forecasts_fingerprint": native_hash,
        "baseline_metrics_fingerprint": baseline_metrics_hash,
        "chronos_metrics_fingerprint": challenger_metrics_hash,
        "paired_economic_report_fingerprint": paired_hash,
        "paired_economic_assessment": assessment,
        "status": "INCONCLUSIVE" if reasons else "RESEARCH_COMPARISON_ONLY",
        "reason_codes": sorted(reasons),
        "adapter_invocation_completed": not bool(reasons),
        "checkpoint_integrity_independently_verified": False,
        "market_source_independently_verified": False,
        "independent_holdout_preregistration_verified": False,
        "checkpoint_commercial_review_complete": False,
        "production_model_approved": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return Chronos2ResearchReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Opt-in local-only Chronos-2 zero-shot India research adapter"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--cost-bps", type=float, required=True)
    parser.add_argument("--checkpoint-revision", required=True)
    args = parser.parse_args()
    config = Chronos2ResearchConfig(checkpoint_revision=args.checkpoint_revision)
    # Requires the EXACT revision to be present in the runtime's local HF cache.
    chronos = load_local_chronos2_pipeline()
    model = chronos.from_pretrained(
        config.checkpoint_id,
        revision=config.checkpoint_revision,
        device_map="cpu",
        local_files_only=True,
    )
    import pandas as pd

    report = evaluate_chronos2_zero_shot(
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.india_fee_book.read_text(encoding="utf-8")
        ),
        ForwardReturnTargetSpec(
            horizon_bars=1, minimum_edge_bps=15,
            round_trip_cost_bps=args.cost_bps,
        ),
        config=config, pipeline=model, frame_factory=pd.DataFrame,
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
