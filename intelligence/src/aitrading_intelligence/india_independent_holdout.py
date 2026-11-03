from __future__ import annotations

import argparse
import json
import math
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, model_validator
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

from .features import build_feature_row
from .fingerprints import canonical_fingerprint
from .india_fee_tax import (
    IndiaFeeTaxBookV1,
    IndiaFeeTaxScheduleV1,
    resolve_india_fee_tax_schedule,
)
from .market_source import MarketResearchExport, load_market_export
from .model_fair_comparison import (
    _expected_calibration_error,
    _max_drawdown,
    _predict_catboost,
    _predict_lightgbm,
    _predict_pytorch,
)
from .pytorch_target import ForwardReturnTargetSpec, LabeledExample, build_labeled_examples

CONTRACT = "india-independent-holdout.v1"
MARKET_ZONE = ZoneInfo("Asia/Kolkata")
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelName = Literal["CATBOOST", "LIGHTGBM", "PYTORCH"]


class IndiaHoldoutConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-independent-holdout.v1"] = CONTRACT
    minimum_train_rows: Annotated[int, Field(ge=20, le=100_000)] = 60
    minimum_holdout_rows: Annotated[int, Field(ge=20, le=100_000)] = 30
    minimum_holdout_sessions: Annotated[int, Field(ge=2, le=100)] = 2
    probability_threshold: Annotated[float, Field(gt=0, lt=1)] = 0.5
    assumed_round_trip_spread_slippage_bps: Annotated[
        float, Field(ge=0.5, le=500)
    ] = 3.0
    seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42
    feature_names: list[str] = Field(
        default_factory=lambda: [
            "market.return_1", "market.range_pct", "market.volume_change_1"
        ],
        min_length=1,
        max_length=32,
    )

    @model_validator(mode="after")
    def validate_features(self) -> IndiaHoldoutConfig:
        if len(self.feature_names) != len(set(self.feature_names)):
            raise ValueError("holdout feature names must be unique")
        if not set(self.feature_names).issubset({
            "market.return_1", "market.range_pct",
            "market.volume_change_1", "market.volume", "market.close",
        }):
            raise ValueError("holdout currently permits market-only causal features")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class IndiaHoldoutModelResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_family: ModelName
    model_version: str
    holdout_probability_fingerprint: Hash64
    holdout_rows: int
    trade_count: int
    roc_auc: float
    brier_score: float
    log_loss: float
    expected_calibration_error: float
    net_return_after_assumed_costs: float
    maximum_drawdown: float
    result_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def verify_result(self) -> IndiaHoldoutModelResult:
        if self.result_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"result_fingerprint"})
        ):
            raise ValueError("independent holdout model fingerprint mismatch")
        return self


class IndiaHoldoutReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-independent-holdout.v1"] = CONTRACT
    train_export_fingerprint: Hash64
    holdout_export_fingerprint: Hash64
    india_fee_book_fingerprint: Hash64
    config_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    exchange: Literal["NSE", "BSE"]
    trading_symbol: str
    instrument_id: int
    training_last_label_available_at: datetime | None
    holdout_first_decision_at: datetime | None
    training_rows: int
    holdout_rows: int
    holdout_sessions: int
    assessed_cost_bps: float
    training_example_membership_fingerprint: Hash64
    holdout_example_membership_fingerprint: Hash64
    status: Literal["RESEARCH_HOLDOUT_METRICS_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    results: list[IndiaHoldoutModelResult]
    # Market provenance and prior experiment registration require separate independent audit.
    external_market_source_verified: Literal[False] = False
    holdout_pre_registration_verified: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_report(self) -> IndiaHoldoutReport:
        if self.status == "INCONCLUSIVE" and (not self.reason_codes or self.results):
            raise ValueError("inconclusive holdout cannot contain fitted model results")
        if self.status == "RESEARCH_HOLDOUT_METRICS_ONLY" and (
            self.reason_codes or not self.results
        ):
            raise ValueError("evaluated holdout requires model evidence without failed gates")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("independent holdout report fingerprint mismatch")
        return self


def _build_examples(
    export: MarketResearchExport, target: ForwardReturnTargetSpec
) -> list[LabeledExample]:
    bars = export.to_market_bars()
    rows = [
        build_feature_row(
            symbol=bar.symbol,
            as_of=bar.observed_at,
            bars=bars[max(0, index - 1):index + 1],
        )
        for index, bar in enumerate(bars)
    ]
    if len(rows) <= target.horizon_bars + 1:
        return []
    # The first row has no previous candle, so do not silently train on missing lag features.
    return build_labeled_examples(rows, target)[1:]


def _configured_round_trip_fee_bps(schedule: IndiaFeeTaxScheduleV1) -> float:
    taxable = {
        "BROKERAGE": 2 * schedule.brokerage_rate_bps,
        "EXCHANGE_TRANSACTION_CHARGE": 2 * schedule.exchange_transaction_charge_bps,
        "SEBI_TURNOVER_CHARGE": 2 * schedule.sebi_turnover_charge_bps,
    }
    charge = (
        2 * schedule.brokerage_rate_bps
        + 2 * schedule.exchange_transaction_charge_bps
        + 2 * schedule.sebi_turnover_charge_bps
        + schedule.stt_buy_bps
        + schedule.stt_sell_bps
        + schedule.stamp_duty_buy_bps
    )
    charge += (schedule.gst_rate_percent / 100) * sum(
        taxable[name] for name in schedule.gst_taxable_components
    )
    return charge


def evaluate_india_independent_holdout(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    config: IndiaHoldoutConfig | None = None,
    include_pytorch: bool = False,
) -> IndiaHoldoutReport:
    """Fit on earlier market-history exports and score a disjoint, untouched later export.

    This is executable research benchmarking, not proof that the exports are genuine,
    pre-registered, licensed or adequate for PAPER/LIVE model promotion.
    """
    training = MarketResearchExport.model_validate(training_export.model_dump(mode="python"))
    holdout = MarketResearchExport.model_validate(holdout_export.model_dump(mode="python"))
    costs = IndiaFeeTaxBookV1.model_validate(fee_book.model_dump(mode="python"))
    policy = config or IndiaHoldoutConfig()
    if training.exchange not in ("NSE", "BSE") or holdout.exchange != training.exchange:
        raise ValueError("holdout requires the same NSE/BSE cash-equity exchange")
    if (training.instrument_id, training.trading_symbol, training.symbol_token) != (
        holdout.instrument_id, holdout.trading_symbol, holdout.symbol_token
    ):
        raise ValueError("train and holdout must identify the same cash-equity instrument")
    if training.catalogue_version != holdout.catalogue_version:
        raise ValueError("train and holdout must share the frozen instrument catalogue")
    if training.interval != "FIVE_MINUTE" or holdout.interval != "FIVE_MINUTE":
        raise ValueError("holdout v1 supports only five-minute cash-equity bars")
    if training.to_exclusive > holdout.from_inclusive:
        raise ValueError("train and holdout declared market windows must not overlap")
    if training.provider_sources != holdout.provider_sources:
        raise ValueError("train and holdout must share identical provider lineage")
    for export in (training, holdout):
        if not export.candles:
            raise ValueError("both market exports must contain observed candles")
        if any(c.fetched_at < c.available_at for c in export.candles):
            raise ValueError("candle cannot be fetched before first availability")
        bars = export.to_market_bars()
        if len({bar.observed_at for bar in bars}) != len(bars):
            raise ValueError("duplicate decision availability times are prohibited")

    if target.round_trip_cost_bps <= 0:
        raise ValueError("transaction cost must be strictly positive")
    train_examples = _build_examples(training, target)
    holdout_examples = _build_examples(holdout, target)
    if train_examples and holdout_examples and (
        max(example.target_observed_at for example in train_examples)
        >= min(example.as_of for example in holdout_examples)
    ):
        raise ValueError("training labels must become observable before the holdout")
    dates = {
        example.as_of.astimezone(MARKET_ZONE).date()
        for example in [*train_examples, *holdout_examples]
    }
    minimum_fee_cost = 0.0
    for trade_date in sorted(dates):
        schedule = resolve_india_fee_tax_schedule(
            costs, exchange=training.exchange, product="EQUITY_INTRADAY",
            trade_date=trade_date,
        )
        minimum_fee_cost = max(minimum_fee_cost, _configured_round_trip_fee_bps(schedule))
    minimum_fee_cost += policy.assumed_round_trip_spread_slippage_bps
    if target.round_trip_cost_bps + 1e-9 < minimum_fee_cost:
        raise ValueError("target cost bps understates configured India fees and slippage")

    sessions = len({
        example.as_of.astimezone(MARKET_ZONE).date() for example in holdout_examples
    })
    reasons: list[str] = []
    if len(train_examples) < policy.minimum_train_rows:
        reasons.append("INSUFFICIENT_TRAINING_OBSERVATIONS")
    if len(holdout_examples) < policy.minimum_holdout_rows:
        reasons.append("INSUFFICIENT_INDEPENDENT_HOLDOUT_OBSERVATIONS")
    if sessions < policy.minimum_holdout_sessions:
        reasons.append("INSUFFICIENT_HOLDOUT_SESSIONS")
    if len({example.label for example in train_examples}) < 2:
        reasons.append("TRAINING_TARGET_SINGLE_CLASS")
    if len({example.label for example in holdout_examples}) < 2:
        reasons.append("HOLDOUT_TARGET_SINGLE_CLASS")

    evidence: list[IndiaHoldoutModelResult] = []
    if not reasons:
        predictors = [
            ("CATBOOST", "catboost-binary.v1", _predict_catboost),
            ("LIGHTGBM", "lightgbm-binary.v1", _predict_lightgbm),
        ]
        if include_pytorch:
            predictors.append(("PYTORCH", "pytorch-mlp.v1", _predict_pytorch))
        labels = [example.label for example in holdout_examples]
        for family, version, predict in predictors:
            probabilities = predict(
                train_examples, holdout_examples, policy.feature_names, policy.seed
            )
            if len(probabilities) != len(labels) or any(
                not math.isfinite(value) or value < 0 or value > 1
                for value in probabilities
            ):
                raise ValueError("holdout predictor returned invalid probabilities")
            trade_returns = [
                example.forward_return - target.round_trip_cost_bps / 10_000
                for example, probability in zip(holdout_examples, probabilities, strict=True)
                if probability >= policy.probability_threshold
            ]
            payload = {
                "model_family": family,
                "model_version": version,
                "holdout_probability_fingerprint": canonical_fingerprint(probabilities),
                "holdout_rows": len(holdout_examples),
                "trade_count": len(trade_returns),
                "roc_auc": float(roc_auc_score(labels, probabilities)),
                "brier_score": float(brier_score_loss(labels, probabilities)),
                "log_loss": float(log_loss(labels, probabilities, labels=[0, 1])),
                "expected_calibration_error": _expected_calibration_error(
                    labels, probabilities, bins=10
                ),
                "net_return_after_assumed_costs": float(sum(trade_returns)),
                "maximum_drawdown": _max_drawdown(trade_returns),
                "promotion_allowed": False,
                "can_authorize_capital": False,
            }
            evidence.append(IndiaHoldoutModelResult(
                **payload, result_fingerprint=canonical_fingerprint(payload)
            ))

    payload = {
        "contract_version": CONTRACT,
        "train_export_fingerprint": canonical_fingerprint(training.model_dump(mode="json")),
        "holdout_export_fingerprint": canonical_fingerprint(holdout.model_dump(mode="json")),
        "india_fee_book_fingerprint": costs.book_fingerprint,
        "config_fingerprint": policy.fingerprint,
        "target_spec_fingerprint": target.fingerprint,
        "exchange": training.exchange,
        "trading_symbol": training.trading_symbol,
        "instrument_id": training.instrument_id,
        "training_last_label_available_at": (
            max(example.target_observed_at for example in train_examples).isoformat()
            if train_examples else None
        ),
        "holdout_first_decision_at": (
            min(example.as_of for example in holdout_examples).isoformat()
            if holdout_examples else None
        ),
        "training_rows": len(train_examples),
        "holdout_rows": len(holdout_examples),
        "holdout_sessions": sessions,
        "assessed_cost_bps": float(target.round_trip_cost_bps),
        "training_example_membership_fingerprint": canonical_fingerprint([
            example.example_fingerprint for example in train_examples
        ]),
        "holdout_example_membership_fingerprint": canonical_fingerprint([
            example.example_fingerprint for example in holdout_examples
        ]),
        "status": "INCONCLUSIVE" if reasons else "RESEARCH_HOLDOUT_METRICS_ONLY",
        "reason_codes": sorted(reasons),
        "results": [item.model_dump(mode="json") for item in evidence],
        "external_market_source_verified": False,
        "holdout_pre_registration_verified": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaHoldoutReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run a strict research-only NSE/BSE independent five-minute holdout"
    )
    parser.add_argument("training_export", type=Path)
    parser.add_argument("holdout_export", type=Path)
    parser.add_argument("india_fee_book", type=Path)
    parser.add_argument("--horizon-bars", type=int, default=1)
    parser.add_argument("--cost-bps", type=float, required=True)
    parser.add_argument("--minimum-edge-bps", type=float, default=15)
    parser.add_argument("--include-pytorch", action="store_true")
    args = parser.parse_args()
    target = ForwardReturnTargetSpec(
        horizon_bars=args.horizon_bars,
        minimum_edge_bps=args.minimum_edge_bps,
        round_trip_cost_bps=args.cost_bps,
    )
    report = evaluate_india_independent_holdout(
        load_market_export(args.training_export),
        load_market_export(args.holdout_export),
        IndiaFeeTaxBookV1.model_validate_json(
            args.india_fee_book.read_text(encoding="utf-8")
        ),
        target,
        include_pytorch=args.include_pytorch,
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
