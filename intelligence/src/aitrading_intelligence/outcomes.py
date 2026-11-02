from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

OUTCOME_VERSION = "trade-outcome.v1"
OUTCOME_DATASET_VERSION = "trade-outcome-dataset.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]

TradingMode = Literal["PAPER", "LIVE"]
ExecutionMode = Literal["MANUAL", "AUTOMATED"]
PositionSide = Literal["LONG", "SHORT"]
ExitReason = Literal[
    "STOP_LOSS",
    "TARGET",
    "TRAILING_STOP",
    "TIME_EXIT",
    "RISK_EXIT",
    "MANUAL_EXIT",
    "EMERGENCY_STOP",
    "OTHER",
]
MarketRegime = Literal["HIGH_VOL", "TREND_UP", "TREND_DOWN", "RANGE", "UNKNOWN"]


class PriceObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    observed_at: datetime
    price: Annotated[float, Field(gt=0.0)]

    @field_validator("observed_at")
    @classmethod
    def observed_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("price observation timestamp must be timezone-aware")
        return value


class TradeOutcomeInput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    trade_id: Annotated[str, Field(min_length=1, max_length=128)]
    symbol: Annotated[str, Field(min_length=1, max_length=32)]
    side: PositionSide = "LONG"
    trading_mode: TradingMode
    execution_mode: ExecutionMode
    decision_at: datetime
    data_cutoff: datetime
    entry_at: datetime
    exit_at: datetime
    outcome_available_at: datetime
    decision_price: Annotated[float, Field(gt=0.0)]
    entry_price: Annotated[float, Field(gt=0.0)]
    exit_price: Annotated[float, Field(gt=0.0)]
    quantity: Annotated[int, Field(gt=0)]
    realized_pnl: float
    exit_reason: ExitReason
    market_regime: MarketRegime
    feature_schema_version: Annotated[str, Field(min_length=1, max_length=128)]
    feature_row_fingerprint: Hash64
    opportunity_fingerprint: Hash64
    model_fingerprint: Hash64 | None = None
    inference_fingerprint: Hash64 | None = None
    gemini_evidence_fingerprints: list[Hash64] = Field(default_factory=list, max_length=64)
    risk_decision_fingerprint: Hash64
    order_intent_fingerprint: Hash64
    execution_fingerprint: Hash64
    price_path: list[PriceObservation] = Field(min_length=1, max_length=100_000)

    @field_validator(
        "decision_at",
        "data_cutoff",
        "entry_at",
        "exit_at",
        "outcome_available_at",
    )
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("trade outcome timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_timeline(self) -> TradeOutcomeInput:
        if self.data_cutoff > self.decision_at:
            raise ValueError("data_cutoff cannot exceed the frozen decision time")
        if self.decision_at > self.entry_at:
            raise ValueError("decision_at cannot be later than entry_at")
        if self.exit_at <= self.entry_at:
            raise ValueError("exit_at must be later than entry_at")
        if self.outcome_available_at < self.exit_at:
            raise ValueError("outcome cannot be available before the trade exits")
        if not math.isfinite(self.realized_pnl):
            raise ValueError("realized_pnl must be finite")
        for observation in self.price_path:
            if observation.observed_at < self.entry_at or observation.observed_at > self.exit_at:
                raise ValueError("price path observations must stay inside the holding window")
        return self


class TradeOutcomeRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    outcome_version: Literal["trade-outcome.v1"] = OUTCOME_VERSION
    trade_id: str
    symbol: str
    side: PositionSide
    trading_mode: TradingMode
    execution_mode: ExecutionMode
    decision_at: datetime
    data_cutoff: datetime
    entry_at: datetime
    exit_at: datetime
    outcome_available_at: datetime
    decision_price: float
    entry_price: float
    exit_price: float
    quantity: int
    realized_pnl: float
    realized_return: float
    entry_slippage_bps: float
    mae_return: Annotated[float, Field(le=0.0)]
    mfe_return: Annotated[float, Field(ge=0.0)]
    exit_reason: ExitReason
    market_regime: MarketRegime
    feature_schema_version: str
    feature_row_fingerprint: Hash64
    opportunity_fingerprint: Hash64
    model_fingerprint: Hash64 | None
    inference_fingerprint: Hash64 | None
    gemini_evidence_fingerprints: list[Hash64]
    risk_decision_fingerprint: Hash64
    order_intent_fingerprint: Hash64
    execution_fingerprint: Hash64
    price_path_fingerprint: Hash64
    record_fingerprint: Hash64
    status: Literal["EVALUATION_EVIDENCE_ONLY"] = "EVALUATION_EVIDENCE_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


class OutcomeDatasetManifest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    dataset_version: Literal["trade-outcome-dataset.v1"] = OUTCOME_DATASET_VERSION
    generated_at: datetime
    feature_schema_version: str
    start_at: datetime
    end_at: datetime
    source_cutoff: datetime
    record_count: Annotated[int, Field(ge=1)]
    record_fingerprints: list[Hash64]
    model_fingerprints: list[Hash64]
    dataset_fingerprint: Hash64
    status: Literal["VERSIONED_EVALUATION_DATASET"] = "VERSIONED_EVALUATION_DATASET"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def _return_from_price(entry_price: float, price: float, side: PositionSide) -> float:
    raw = (price / entry_price) - 1.0
    return raw if side == "LONG" else -raw


def build_trade_outcome_record(source: TradeOutcomeInput) -> TradeOutcomeRecord:
    ordered_path = sorted(source.price_path, key=lambda item: (item.observed_at, item.price))
    price_path_payload = [item.model_dump(mode="json") for item in ordered_path]
    price_path_fingerprint = canonical_fingerprint(price_path_payload)

    path_returns = [
        _return_from_price(source.entry_price, item.price, source.side)
        for item in ordered_path
    ]
    path_returns.extend(
        [
            0.0,
            _return_from_price(source.entry_price, source.exit_price, source.side),
        ]
    )
    mae_return = min(0.0, min(path_returns))
    mfe_return = max(0.0, max(path_returns))

    entry_move = (source.entry_price / source.decision_price) - 1.0
    entry_slippage_bps = 10_000.0 * (entry_move if source.side == "LONG" else -entry_move)
    realized_return = source.realized_pnl / (source.entry_price * source.quantity)
    gemini_fingerprints = sorted(set(source.gemini_evidence_fingerprints))

    payload = {
        "outcome_version": OUTCOME_VERSION,
        "trade_id": source.trade_id,
        "symbol": source.symbol,
        "side": source.side,
        "trading_mode": source.trading_mode,
        "execution_mode": source.execution_mode,
        "decision_at": source.decision_at.isoformat(),
        "data_cutoff": source.data_cutoff.isoformat(),
        "entry_at": source.entry_at.isoformat(),
        "exit_at": source.exit_at.isoformat(),
        "outcome_available_at": source.outcome_available_at.isoformat(),
        "decision_price": source.decision_price,
        "entry_price": source.entry_price,
        "exit_price": source.exit_price,
        "quantity": source.quantity,
        "realized_pnl": source.realized_pnl,
        "realized_return": realized_return,
        "entry_slippage_bps": entry_slippage_bps,
        "mae_return": mae_return,
        "mfe_return": mfe_return,
        "exit_reason": source.exit_reason,
        "market_regime": source.market_regime,
        "feature_schema_version": source.feature_schema_version,
        "feature_row_fingerprint": source.feature_row_fingerprint,
        "opportunity_fingerprint": source.opportunity_fingerprint,
        "model_fingerprint": source.model_fingerprint,
        "inference_fingerprint": source.inference_fingerprint,
        "gemini_evidence_fingerprints": gemini_fingerprints,
        "risk_decision_fingerprint": source.risk_decision_fingerprint,
        "order_intent_fingerprint": source.order_intent_fingerprint,
        "execution_fingerprint": source.execution_fingerprint,
        "price_path_fingerprint": price_path_fingerprint,
        "status": "EVALUATION_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TradeOutcomeRecord(
        **payload,
        record_fingerprint=canonical_fingerprint(payload),
    )


def build_outcome_dataset_manifest(
    records: list[TradeOutcomeRecord],
    *,
    generated_at: datetime,
) -> OutcomeDatasetManifest:
    if generated_at.tzinfo is None or generated_at.utcoffset() is None:
        raise ValueError("generated_at must be timezone-aware")
    if not records:
        raise ValueError("at least one trade outcome record is required")
    if len({record.trade_id for record in records}) != len(records):
        raise ValueError("trade outcome dataset cannot contain duplicate trade IDs")
    schema_versions = {record.feature_schema_version for record in records}
    if len(schema_versions) != 1:
        raise ValueError("one outcome dataset version must use one feature schema version")

    ordered = sorted(records, key=lambda item: (item.exit_at, item.trade_id))
    source_cutoff = max(record.outcome_available_at for record in ordered)
    if generated_at < source_cutoff:
        raise ValueError("dataset cannot be generated before all included outcomes are available")
    record_fingerprints = [record.record_fingerprint for record in ordered]
    model_fingerprints = sorted(
        {record.model_fingerprint for record in ordered if record.model_fingerprint is not None}
    )
    payload = {
        "dataset_version": OUTCOME_DATASET_VERSION,
        "generated_at": generated_at.isoformat(),
        "feature_schema_version": next(iter(schema_versions)),
        "start_at": ordered[0].decision_at.isoformat(),
        "end_at": max(record.exit_at for record in ordered).isoformat(),
        "source_cutoff": source_cutoff.isoformat(),
        "record_fingerprints": record_fingerprints,
        "model_fingerprints": model_fingerprints,
        "status": "VERSIONED_EVALUATION_DATASET",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return OutcomeDatasetManifest(
        generated_at=generated_at,
        feature_schema_version=next(iter(schema_versions)),
        start_at=ordered[0].decision_at,
        end_at=max(record.exit_at for record in ordered),
        source_cutoff=source_cutoff,
        record_count=len(ordered),
        record_fingerprints=record_fingerprints,
        model_fingerprints=model_fingerprints,
        dataset_fingerprint=canonical_fingerprint(payload),
    )
