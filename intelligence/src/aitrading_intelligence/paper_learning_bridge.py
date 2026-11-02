from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .outcome_learning import (
    ExecutionSnapshot,
    GeminiSnapshot,
    PredictionSnapshot,
    RiskSnapshot,
    TradeLearningRecord,
    build_trade_learning_record,
)

PAPER_CLOSED_TRADE_VERSION = "paper-closed-trade.v1"
_MONEY = Decimal("0.01")


class PaperClosedTrade(BaseModel):
    """One authoritative Java PAPER close allocation.

    The shape mirrors PaperLearningResearchExport.ClosedTrade exactly.  Model/risk/Gemini
    evidence is intentionally absent and must be supplied independently by the caller.
    """

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    allocation_id: UUID = Field(alias="allocationId")
    lot_id: UUID = Field(alias="lotId")
    entry_order_id: UUID = Field(alias="entryOrderId")
    entry_fill_id: UUID = Field(alias="entryFillId")
    exit_order_id: UUID = Field(alias="exitOrderId")
    exit_fill_id: UUID = Field(alias="exitFillId")
    instrument_id: int | None = Field(alias="instrumentId")
    exchange: Annotated[str, Field(min_length=1, max_length=40)]
    symbol: Annotated[str, Field(min_length=1, max_length=40)]
    product_type: Annotated[str, Field(min_length=1, max_length=40)] = Field(alias="productType")
    execution_origin: Literal["MANUAL", "AUTOMATED"] = Field(alias="executionOrigin")
    entry_side: Literal["BUY", "SELL"] = Field(alias="entrySide")
    quantity: Annotated[int, Field(gt=0)]
    expected_entry_price: Annotated[Decimal, Field(gt=0)] = Field(alias="expectedEntryPrice")
    entry_price: Annotated[Decimal, Field(gt=0)] = Field(alias="entryPrice")
    exit_price: Annotated[Decimal, Field(gt=0)] = Field(alias="exitPrice")
    allocated_entry_fee_amount: Annotated[Decimal, Field(ge=0)] = Field(
        alias="allocatedEntryFeeAmount"
    )
    allocated_exit_fee_amount: Annotated[Decimal, Field(ge=0)] = Field(
        alias="allocatedExitFeeAmount"
    )
    allocated_entry_slippage_amount: Annotated[Decimal, Field(ge=0)] = Field(
        alias="allocatedEntrySlippageAmount"
    )
    allocated_exit_slippage_amount: Annotated[Decimal, Field(ge=0)] = Field(
        alias="allocatedExitSlippageAmount"
    )
    allocated_realized_pnl: Decimal = Field(alias="allocatedRealizedPnl")
    exit_reason: Annotated[str, Field(min_length=1, max_length=120)] = Field(alias="exitReason")
    opened_at: datetime = Field(alias="openedAt")
    closed_at: datetime = Field(alias="closedAt")

    @field_validator("opened_at", "closed_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("PAPER closed-trade timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def close_must_follow_open(self) -> PaperClosedTrade:
        if self.closed_at <= self.opened_at:
            raise ValueError("closedAt must be later than openedAt")
        return self


class PaperClosedTradeExport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    contract_version: Literal["paper-closed-trade.v1"] = Field(alias="contractVersion")
    generated_at: datetime = Field(alias="generatedAt")
    trades: list[PaperClosedTrade]

    @field_validator("generated_at")
    @classmethod
    def generated_time_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("generatedAt must be timezone-aware")
        return value

    @model_validator(mode="after")
    def allocation_ids_must_be_unique(self) -> PaperClosedTradeExport:
        ids = [trade.allocation_id for trade in self.trades]
        if len(set(ids)) != len(ids):
            raise ValueError("paper-closed-trade export contains duplicate allocationId values")
        return self


class PaperLearningEvidence(BaseModel):
    """Persisted non-execution evidence required to make one entry fill learnable.

    Linkage to both the entry order and fill is required so prediction/risk evidence cannot be
    attached by symbol or timestamp guessing.  Gemini remains optional because a valid trade may
    have been generated without Gemini evidence.  MAE/MFE and market regime are required genuine
    outcome-context evidence; the bridge never manufactures them.
    """

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    entry_order_id: UUID = Field(alias="entryOrderId")
    entry_fill_id: UUID = Field(alias="entryFillId")
    symbol: Annotated[str, Field(min_length=1, max_length=40)]
    prediction: PredictionSnapshot
    risk: RiskSnapshot
    gemini: GeminiSnapshot | None = None
    mae_return: Annotated[float, Field(le=0.0)] = Field(alias="maeReturn")
    mfe_return: Annotated[float, Field(ge=0.0)] = Field(alias="mfeReturn")
    market_regime: Annotated[str, Field(min_length=1, max_length=120)] = Field(
        alias="marketRegime"
    )


class PaperLearningBridgeError(ValueError):
    """Raised when authoritative PAPER data cannot be safely joined for learning."""


def bridge_paper_closed_trades(
    export: PaperClosedTradeExport,
    evidence: Iterable[PaperLearningEvidence],
) -> list[TradeLearningRecord]:
    """Bind Java PAPER close allocations to genuine model/risk/outcome evidence.

    This function is deliberately all-or-nothing.  Missing, duplicate, mismatched or accounting-
    inconsistent evidence fails the export instead of producing a partial dataset that could bias
    later model evaluation.
    """

    evidence_by_entry: dict[tuple[UUID, UUID], PaperLearningEvidence] = {}
    for item in evidence:
        key = (item.entry_order_id, item.entry_fill_id)
        if key in evidence_by_entry:
            raise PaperLearningBridgeError(
                "duplicate learning evidence for entry order/fill "
                f"{item.entry_order_id}/{item.entry_fill_id}"
            )
        evidence_by_entry[key] = item

    records: list[TradeLearningRecord] = []
    for trade in export.trades:
        key = (trade.entry_order_id, trade.entry_fill_id)
        joined = evidence_by_entry.get(key)
        if joined is None:
            raise PaperLearningBridgeError(
                "missing required learning evidence for entry order/fill "
                f"{trade.entry_order_id}/{trade.entry_fill_id}"
            )
        if joined.symbol.strip().upper() != trade.symbol.strip().upper():
            raise PaperLearningBridgeError(
                "learning evidence symbol does not match authoritative PAPER close allocation"
            )

        expected_realized = _reconciled_realized_pnl(trade)
        authoritative_realized = _money(trade.allocated_realized_pnl)
        if expected_realized != authoritative_realized:
            raise PaperLearningBridgeError(
                "authoritative PAPER realized P&L does not reconcile to the closed lot allocation; "
                "refusing to create learning evidence"
            )

        execution_payload = {
            "contract_version": PAPER_CLOSED_TRADE_VERSION,
            "closed_trade": trade.model_dump(mode="json", by_alias=True),
        }
        total_fees = trade.allocated_entry_fee_amount + trade.allocated_exit_fee_amount
        execution = ExecutionSnapshot(
            order_id=str(trade.entry_order_id),
            position_id=str(trade.lot_id),
            trading_mode="PAPER",
            execution_mode=trade.execution_origin,
            side=trade.entry_side,
            quantity=trade.quantity,
            expected_entry_price=float(trade.expected_entry_price),
            entry_price=float(trade.entry_price),
            exit_price=float(trade.exit_price),
            opened_at=trade.opened_at,
            closed_at=trade.closed_at,
            total_fees=float(total_fees),
            execution_fingerprint=canonical_fingerprint(execution_payload),
        )

        record = build_trade_learning_record(
            trade_id=str(trade.allocation_id),
            symbol=trade.symbol,
            prediction=joined.prediction,
            gemini=joined.gemini,
            risk=joined.risk,
            execution=execution,
            mae_return=joined.mae_return,
            mfe_return=joined.mfe_return,
            exit_reason=trade.exit_reason,
            market_regime=joined.market_regime,
        )
        if _money(Decimal(str(record.outcome.realized_pnl))) != authoritative_realized:
            raise PaperLearningBridgeError(
                "normalized learning outcome diverges from authoritative PAPER realized P&L"
            )
        records.append(record)

    return records


def _reconciled_realized_pnl(trade: PaperClosedTrade) -> Decimal:
    direction = Decimal("1") if trade.entry_side == "BUY" else Decimal("-1")
    gross = direction * (trade.exit_price - trade.entry_price) * Decimal(trade.quantity)
    total_fees = trade.allocated_entry_fee_amount + trade.allocated_exit_fee_amount
    return _money(gross - total_fees)


def _money(value: Decimal) -> Decimal:
    return value.quantize(_MONEY, rounding=ROUND_HALF_UP)
