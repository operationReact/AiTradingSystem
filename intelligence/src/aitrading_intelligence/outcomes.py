from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

OUTCOME_VERSION = "trade-outcome.v1"
OUTCOME_DATASET_VERSION = "trade-outcome-dataset.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]

ExecutionOrigin = Literal["MANUAL", "AUTOMATED"]
ProductType = Literal["DELIVERY", "INTRADAY"]
Side = Literal["BUY", "SELL"]
ExitReason = Literal[
    "CUSTOMER_ORDER",
    "STOP_LOSS",
    "TARGET",
    "TRAILING_STOP",
    "INTRADAY_CUTOFF",
]
MarketRegime = Literal["HIGH_VOL", "TREND_UP", "TREND_DOWN", "RANGE", "UNKNOWN"]


class TradeOutcomeInput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    entry_order_id: UUID
    exit_order_id: UUID
    entry_risk_decision_id: UUID
    exit_risk_decision_id: UUID | None = None
    candidate_id: Annotated[int, Field(gt=0)] | None = None
    entry_fill_ids: list[UUID]
    exit_fill_ids: list[UUID]
    symbol: Annotated[str, Field(min_length=1, max_length=40)]
    product_type: ProductType
    execution_origin: ExecutionOrigin
    entry_side: Side
    exit_side: Side
    quantity: Annotated[int, Field(gt=0)]
    entry_price: Annotated[float, Field(gt=0.0)]
    exit_price: Annotated[float, Field(gt=0.0)]
    entry_at: datetime
    exit_at: datetime
    gross_pnl: float
    fee_amount: Annotated[float, Field(ge=0.0)]
    slippage_amount: Annotated[float, Field(ge=0.0)]
    realized_pnl: float
    mae_return: Annotated[float, Field(le=0.0)]
    mfe_return: Annotated[float, Field(ge=0.0)]
    exit_reason: ExitReason
    market_regime: MarketRegime
    evidence_cutoff: datetime
    feature_row_fingerprint: Hash64 | None = None
    model_artifact_fingerprint: Hash64 | None = None
    fusion_opportunity_fingerprint: Hash64 | None = None
    gemini_evidence_fingerprints: list[Hash64] = Field(default_factory=list)
    risk_policy_fingerprints: list[Hash64] = Field(default_factory=list)
    provenance: list[str] = Field(default_factory=list)

    @field_validator("entry_at", "exit_at", "evidence_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("outcome timestamps must be timezone-aware")
        return value

    @field_validator("entry_fill_ids", "exit_fill_ids")
    @classmethod
    def fill_ids_must_be_unique_and_present(cls, value: list[UUID]) -> list[UUID]:
        if not value:
            raise ValueError("at least one fill id is required")
        if len(set(value)) != len(value):
            raise ValueError("fill ids must be unique")
        return value

    @field_validator(
        "gemini_evidence_fingerprints",
        "risk_policy_fingerprints",
        "provenance",
    )
    @classmethod
    def ordered_evidence_must_not_repeat(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("evidence lineage must not contain duplicates")
        return value

    @model_validator(mode="after")
    def validate_causal_round_trip(self) -> TradeOutcomeInput:
        if self.entry_order_id == self.exit_order_id:
            raise ValueError("entry and exit orders must be distinct")
        if self.entry_side == self.exit_side:
            raise ValueError("entry and exit sides must be opposite")
        if self.exit_at <= self.entry_at:
            raise ValueError("exit_at must be later than entry_at")
        if self.evidence_cutoff > self.entry_at:
            raise ValueError("evidence_cutoff cannot exceed entry time")
        numeric_values = (
            self.entry_price,
            self.exit_price,
            self.gross_pnl,
            self.fee_amount,
            self.slippage_amount,
            self.realized_pnl,
            self.mae_return,
            self.mfe_return,
        )
        if any(not math.isfinite(value) for value in numeric_values):
            raise ValueError("outcome values must be finite")
        return self


class TradeOutcomeRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    outcome_version: Literal["trade-outcome.v1"] = OUTCOME_VERSION
    entry_order_id: UUID
    exit_order_id: UUID
    entry_risk_decision_id: UUID
    exit_risk_decision_id: UUID | None = None
    candidate_id: int | None = None
    entry_fill_ids: list[UUID]
    exit_fill_ids: list[UUID]
    symbol: str
    product_type: ProductType
    execution_origin: ExecutionOrigin
    entry_side: Side
    exit_side: Side
    quantity: int
    entry_price: float
    exit_price: float
    entry_at: datetime
    exit_at: datetime
    gross_pnl: float
    fee_amount: float
    slippage_amount: float
    realized_pnl: float
    net_return: float
    mae_return: float
    mfe_return: float
    exit_reason: ExitReason
    market_regime: MarketRegime
    evidence_cutoff: datetime
    feature_row_fingerprint: Hash64 | None = None
    model_artifact_fingerprint: Hash64 | None = None
    fusion_opportunity_fingerprint: Hash64 | None = None
    gemini_evidence_fingerprints: list[Hash64]
    risk_policy_fingerprints: list[Hash64]
    provenance: list[str]
    record_fingerprint: Hash64
    status: Literal["EVALUATION_EVIDENCE_ONLY"] = "EVALUATION_EVIDENCE_ONLY"
    retraining_triggered: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


class OutcomeDataset(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    dataset_version: Literal["trade-outcome-dataset.v1"] = OUTCOME_DATASET_VERSION
    records: list[TradeOutcomeRecord]
    record_count: Annotated[int, Field(ge=1)]
    first_exit_at: datetime
    last_exit_at: datetime
    dataset_fingerprint: Hash64
    status: Literal["BATCH_RESEARCH_INPUT_ONLY"] = "BATCH_RESEARCH_INPUT_ONLY"
    retraining_triggered: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def build_trade_outcome(source: TradeOutcomeInput) -> TradeOutcomeRecord:
    notional = source.entry_price * source.quantity
    if not math.isfinite(notional) or notional <= 0.0:
        raise ValueError("entry notional must be finite and positive")

    payload = source.model_dump(mode="json")
    payload["symbol"] = source.symbol.strip().upper()
    payload["gemini_evidence_fingerprints"] = sorted(source.gemini_evidence_fingerprints)
    payload["risk_policy_fingerprints"] = sorted(source.risk_policy_fingerprints)
    payload["provenance"] = sorted(source.provenance)
    payload["net_return"] = source.realized_pnl / notional
    payload["status"] = "EVALUATION_EVIDENCE_ONLY"
    payload["retraining_triggered"] = False
    payload["promotion_allowed"] = False
    payload["can_authorize_capital"] = False
    record_fingerprint = canonical_fingerprint(
        {"outcome_version": OUTCOME_VERSION, **payload}
    )
    return TradeOutcomeRecord(
        **payload,
        record_fingerprint=record_fingerprint,
    )


def build_outcome_dataset(records: Sequence[TradeOutcomeRecord]) -> OutcomeDataset:
    if not records:
        raise ValueError("at least one outcome record is required")
    if len({record.record_fingerprint for record in records}) != len(records):
        raise ValueError("outcome records must be unique")

    ordered = sorted(
        records,
        key=lambda item: (item.exit_at, str(item.exit_order_id), item.record_fingerprint),
    )
    payload = {
        "dataset_version": OUTCOME_DATASET_VERSION,
        "record_fingerprints": [item.record_fingerprint for item in ordered],
        "record_count": len(ordered),
        "first_exit_at": ordered[0].exit_at.isoformat(),
        "last_exit_at": ordered[-1].exit_at.isoformat(),
        "status": "BATCH_RESEARCH_INPUT_ONLY",
        "retraining_triggered": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return OutcomeDataset(
        records=ordered,
        record_count=len(ordered),
        first_exit_at=ordered[0].exit_at,
        last_exit_at=ordered[-1].exit_at,
        dataset_fingerprint=canonical_fingerprint(payload),
    )
