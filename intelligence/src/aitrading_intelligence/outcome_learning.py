from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

LEARNING_RECORD_VERSION = "trade-learning-record.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class PredictionSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    decision_at: datetime
    feature_row_fingerprint: Hash64
    opportunity_fingerprint: Hash64
    model_name: Annotated[str, Field(min_length=1, max_length=120)]
    model_version: Annotated[str, Field(min_length=1, max_length=120)]
    model_artifact_fingerprint: Hash64
    feature_schema_version: Annotated[str, Field(min_length=1, max_length=120)]
    probability_up: Annotated[float, Field(ge=0.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]

    @field_validator("decision_at")
    @classmethod
    def decision_time_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value, "decision_at")


class GeminiSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    generated_at: datetime
    evidence_fingerprint: Hash64
    model: Annotated[str, Field(min_length=1, max_length=120)]
    request_fingerprint: Hash64
    response_fingerprint: Hash64
    source_ids: list[Annotated[str, Field(min_length=1, max_length=240)]]
    direction_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    materiality: Annotated[float, Field(ge=0.0, le=1.0)]

    @field_validator("generated_at")
    @classmethod
    def generated_time_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value, "generated_at")

    @model_validator(mode="after")
    def source_ids_must_be_unique(self) -> GeminiSnapshot:
        if len(set(self.source_ids)) != len(self.source_ids):
            raise ValueError("Gemini source_ids must be unique")
        return self


class RiskSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    decided_at: datetime
    approved: Literal[True] = True
    decision_fingerprint: Hash64
    policy_version: Annotated[str, Field(min_length=1, max_length=120)]
    reason_codes: list[Annotated[str, Field(min_length=1, max_length=120)]]
    max_loss_amount: Annotated[float, Field(ge=0.0)]

    @field_validator("decided_at")
    @classmethod
    def decided_time_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value, "decided_at")


class ExecutionSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    order_id: Annotated[str, Field(min_length=1, max_length=160)]
    position_id: Annotated[str, Field(min_length=1, max_length=160)]
    trading_mode: Literal["PAPER", "LIVE"]
    execution_mode: Literal["MANUAL", "AUTOMATED"]
    side: Literal["BUY", "SELL"]
    quantity: Annotated[int, Field(gt=0)]
    expected_entry_price: Annotated[float, Field(gt=0.0)]
    entry_price: Annotated[float, Field(gt=0.0)]
    exit_price: Annotated[float, Field(gt=0.0)]
    opened_at: datetime
    closed_at: datetime
    total_fees: Annotated[float, Field(ge=0.0)] = 0.0
    execution_fingerprint: Hash64

    @field_validator("opened_at", "closed_at")
    @classmethod
    def execution_times_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value, "execution timestamp")

    @model_validator(mode="after")
    def close_must_follow_open(self) -> ExecutionSnapshot:
        if self.closed_at <= self.opened_at:
            raise ValueError("closed_at must be later than opened_at")
        return self


class OutcomeMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    gross_pnl: float
    realized_pnl: float
    realized_return: float
    mae_return: Annotated[float, Field(le=0.0)]
    mfe_return: Annotated[float, Field(ge=0.0)]
    slippage_bps: float
    exit_reason: Annotated[str, Field(min_length=1, max_length=120)]
    market_regime: Annotated[str, Field(min_length=1, max_length=120)]
    holding_seconds: Annotated[int, Field(gt=0)]

    @model_validator(mode="after")
    def metrics_must_be_finite(self) -> OutcomeMetrics:
        numeric_values = (
            self.gross_pnl,
            self.realized_pnl,
            self.realized_return,
            self.mae_return,
            self.mfe_return,
            self.slippage_bps,
        )
        if any(not math.isfinite(value) for value in numeric_values):
            raise ValueError("outcome metrics must be finite")
        return self


class TradeLearningRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    record_version: Literal["trade-learning-record.v1"] = LEARNING_RECORD_VERSION
    trade_id: Annotated[str, Field(min_length=1, max_length=160)]
    symbol: Annotated[str, Field(min_length=1, max_length=40)]
    prediction: PredictionSnapshot
    gemini: GeminiSnapshot | None = None
    risk: RiskSnapshot
    execution: ExecutionSnapshot
    outcome: OutcomeMetrics
    dataset_eligible_at: datetime
    provenance: list[Annotated[str, Field(min_length=1, max_length=300)]]
    learning_fingerprint: Hash64
    status: Literal["EVALUATION_EVIDENCE_ONLY"] = "EVALUATION_EVIDENCE_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("dataset_eligible_at")
    @classmethod
    def eligible_time_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value, "dataset_eligible_at")

    @model_validator(mode="after")
    def validate_causal_ordering(self) -> TradeLearningRecord:
        if self.prediction.decision_at > self.risk.decided_at:
            raise ValueError("risk decision cannot precede the prediction snapshot")
        if self.risk.decided_at > self.execution.opened_at:
            raise ValueError("execution cannot open before the risk decision")
        if self.gemini is not None and self.gemini.generated_at > self.prediction.decision_at:
            raise ValueError("Gemini evidence generated after decision time cannot be attached")
        if self.dataset_eligible_at != self.execution.closed_at:
            raise ValueError("dataset_eligible_at must equal the closed trade timestamp")
        return self


def build_trade_learning_record(
    *,
    trade_id: str,
    symbol: str,
    prediction: PredictionSnapshot,
    risk: RiskSnapshot,
    execution: ExecutionSnapshot,
    mae_return: float,
    mfe_return: float,
    exit_reason: str,
    market_regime: str,
    gemini: GeminiSnapshot | None = None,
) -> TradeLearningRecord:
    direction = 1.0 if execution.side == "BUY" else -1.0
    gross_pnl = direction * (execution.exit_price - execution.entry_price) * execution.quantity
    realized_pnl = gross_pnl - execution.total_fees
    entry_notional = execution.entry_price * execution.quantity
    realized_return = realized_pnl / entry_notional
    slippage_bps = (
        direction
        * (execution.entry_price - execution.expected_entry_price)
        / execution.expected_entry_price
        * 10_000.0
    )
    holding_seconds = int((execution.closed_at - execution.opened_at).total_seconds())
    outcome = OutcomeMetrics(
        gross_pnl=gross_pnl,
        realized_pnl=realized_pnl,
        realized_return=realized_return,
        mae_return=mae_return,
        mfe_return=mfe_return,
        slippage_bps=slippage_bps,
        exit_reason=exit_reason,
        market_regime=market_regime,
        holding_seconds=holding_seconds,
    )
    provenance = [
        f"feature:{prediction.feature_row_fingerprint}",
        f"opportunity:{prediction.opportunity_fingerprint}",
        f"model:{prediction.model_artifact_fingerprint}",
        f"risk:{risk.decision_fingerprint}",
        f"execution:{execution.execution_fingerprint}",
    ]
    if gemini is not None:
        provenance.append(f"gemini:{gemini.evidence_fingerprint}")
    payload = {
        "record_version": LEARNING_RECORD_VERSION,
        "trade_id": trade_id,
        "symbol": symbol,
        "prediction": prediction.model_dump(mode="json"),
        "gemini": gemini.model_dump(mode="json") if gemini is not None else None,
        "risk": risk.model_dump(mode="json"),
        "execution": execution.model_dump(mode="json"),
        "outcome": outcome.model_dump(mode="json"),
        "dataset_eligible_at": execution.closed_at.isoformat(),
        "provenance": provenance,
        "status": "EVALUATION_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TradeLearningRecord(
        trade_id=trade_id,
        symbol=symbol,
        prediction=prediction,
        gemini=gemini,
        risk=risk,
        execution=execution,
        outcome=outcome,
        dataset_eligible_at=execution.closed_at,
        provenance=provenance,
        learning_fingerprint=canonical_fingerprint(payload),
    )


def _require_aware(value: datetime, field_name: str) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field_name} must be timezone-aware")
    return value
