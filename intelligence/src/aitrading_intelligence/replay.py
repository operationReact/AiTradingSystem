from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .fusion import RankedOpportunity
from .pytorch_target import LabeledExample

REPLAY_VERSION = "historical-replay.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class ReplayConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    replay_version: Literal["historical-replay.v1"] = REPLAY_VERSION
    initial_equity: Annotated[float, Field(gt=0.0, le=1_000_000_000.0)] = 1_000_000.0
    allocation_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 0.10
    minimum_rank_score: Annotated[float, Field(ge=0.0, le=1.0)] = 0.675
    transaction_cost_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 10.0
    slippage_bps_per_side: Annotated[float, Field(ge=0.0, le=500.0)] = 5.0

    @property
    def round_trip_friction_bps(self) -> float:
        return self.transaction_cost_bps + (2.0 * self.slippage_bps_per_side)

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class ReplayTrade(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    replay_version: Literal["historical-replay.v1"] = REPLAY_VERSION
    config_fingerprint: Hash64
    symbol: str
    entry_at: datetime
    exit_at: datetime
    gross_return: float
    friction_bps: Annotated[float, Field(ge=0.0)]
    net_return: float
    allocated_capital: Annotated[float, Field(gt=0.0)]
    friction_cost: Annotated[float, Field(ge=0.0)]
    pnl: float
    equity_before: Annotated[float, Field(gt=0.0)]
    equity_after: Annotated[float, Field(gt=0.0)]
    opportunity_fingerprint: Hash64
    example_fingerprint: Hash64
    trade_fingerprint: Hash64

    @field_validator("entry_at", "exit_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("replay timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_trade(self) -> ReplayTrade:
        if self.exit_at <= self.entry_at:
            raise ValueError("replay exit must be later than entry")
        numeric_values = (
            self.gross_return,
            self.net_return,
            self.allocated_capital,
            self.friction_cost,
            self.pnl,
            self.equity_before,
            self.equity_after,
        )
        if any(not math.isfinite(value) for value in numeric_values):
            raise ValueError("replay trade values must be finite")
        return self


class ReplayEquityPoint(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    observed_at: datetime
    equity: Annotated[float, Field(gt=0.0)]
    drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    trade_fingerprint: Hash64


class ReplayMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    initial_equity: Annotated[float, Field(gt=0.0)]
    final_equity: Annotated[float, Field(gt=0.0)]
    total_return: float
    max_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    trade_count: Annotated[int, Field(ge=0)]
    winning_trades: Annotated[int, Field(ge=0)]
    losing_trades: Annotated[int, Field(ge=0)]
    win_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    average_net_return: float
    profit_factor: Annotated[float, Field(ge=0.0)] | None
    turnover_multiple: Annotated[float, Field(ge=0.0)]
    total_friction_cost: Annotated[float, Field(ge=0.0)]


class HistoricalReplay(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    replay_version: Literal["historical-replay.v1"] = REPLAY_VERSION
    config_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    start_at: datetime
    end_at: datetime
    considered_opportunities: Annotated[int, Field(ge=1)]
    skipped_watch: Annotated[int, Field(ge=0)]
    skipped_below_threshold: Annotated[int, Field(ge=0)]
    skipped_overlapping: Annotated[int, Field(ge=0)]
    trades: list[ReplayTrade]
    equity_curve: list[ReplayEquityPoint]
    metrics: ReplayMetrics
    provenance: list[str]
    replay_fingerprint: Hash64
    status: Literal["RESEARCH_REPLAY_ONLY"] = "RESEARCH_REPLAY_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("start_at", "end_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("replay timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_window(self) -> HistoricalReplay:
        if self.end_at < self.start_at:
            raise ValueError("replay end_at cannot precede start_at")
        return self


def _key(symbol: str, as_of: datetime) -> tuple[str, datetime]:
    return symbol, as_of


def _validate_inputs(
    opportunities: Sequence[RankedOpportunity],
    examples: Sequence[LabeledExample],
) -> dict[tuple[str, datetime], LabeledExample]:
    if not opportunities:
        raise ValueError("at least one ranked opportunity is required")
    if not examples:
        raise ValueError("at least one labeled example is required")
    if len({example.target_spec_fingerprint for example in examples}) != 1:
        raise ValueError("all replay examples must use the same target specification")

    for item in opportunities:
        for timestamp in (item.as_of, item.data_cutoff):
            if timestamp.tzinfo is None or timestamp.utcoffset() is None:
                raise ValueError("opportunity timestamps must be timezone-aware")
        if item.data_cutoff > item.as_of:
            raise ValueError("opportunity data_cutoff cannot exceed its decision time")

    opportunity_keys = [_key(item.symbol, item.as_of) for item in opportunities]
    if len(set(opportunity_keys)) != len(opportunity_keys):
        raise ValueError("ranked opportunities must be unique by symbol and as_of")

    example_by_key: dict[tuple[str, datetime], LabeledExample] = {}
    for example in examples:
        key = _key(example.symbol, example.as_of)
        if key in example_by_key:
            raise ValueError("labeled examples must be unique by symbol and as_of")
        example_by_key[key] = example

    missing = [key for key in opportunity_keys if key not in example_by_key]
    if missing:
        raise ValueError("every ranked opportunity must resolve to a labeled outcome")
    return example_by_key


def run_historical_replay(
    opportunities: Sequence[RankedOpportunity],
    examples: Sequence[LabeledExample],
    *,
    config: ReplayConfig | None = None,
) -> HistoricalReplay:
    config = config or ReplayConfig()
    example_by_key = _validate_inputs(opportunities, examples)
    ordered = sorted(opportunities, key=lambda item: (item.as_of, item.symbol))
    ordered_examples = sorted(
        examples,
        key=lambda item: (item.as_of, item.symbol, item.target_observed_at),
    )
    target_spec_fingerprint = ordered_examples[0].target_spec_fingerprint
    replay_end = max(
        example_by_key[_key(item.symbol, item.as_of)].target_observed_at for item in ordered
    )

    equity = config.initial_equity
    peak_equity = equity
    active_until: datetime | None = None
    trades: list[ReplayTrade] = []
    equity_curve: list[ReplayEquityPoint] = []
    skipped_watch = 0
    skipped_below_threshold = 0
    skipped_overlapping = 0
    turnover_notional = 0.0
    total_friction_cost = 0.0
    friction_fraction = config.round_trip_friction_bps / 10_000.0

    for opportunity in ordered:
        example = example_by_key[_key(opportunity.symbol, opportunity.as_of)]
        if opportunity.action != "BUY":
            skipped_watch += 1
            continue
        if opportunity.rank_score < config.minimum_rank_score:
            skipped_below_threshold += 1
            continue
        if active_until is not None and opportunity.as_of < active_until:
            skipped_overlapping += 1
            continue

        equity_before = equity
        allocated_capital = equity_before * config.allocation_fraction
        gross_return = example.forward_return
        net_return = gross_return - friction_fraction
        friction_cost = allocated_capital * friction_fraction
        pnl = allocated_capital * net_return
        equity_after = equity_before + pnl
        if not math.isfinite(equity_after) or equity_after <= 0.0:
            raise ValueError("replay configuration produced non-positive equity")

        trade_payload = {
            "replay_version": REPLAY_VERSION,
            "config_fingerprint": config.fingerprint,
            "symbol": opportunity.symbol,
            "entry_at": opportunity.as_of.isoformat(),
            "exit_at": example.target_observed_at.isoformat(),
            "gross_return": gross_return,
            "friction_bps": config.round_trip_friction_bps,
            "net_return": net_return,
            "allocated_capital": allocated_capital,
            "friction_cost": friction_cost,
            "pnl": pnl,
            "equity_before": equity_before,
            "equity_after": equity_after,
            "opportunity_fingerprint": opportunity.opportunity_fingerprint,
            "example_fingerprint": example.example_fingerprint,
        }
        trade_fingerprint = canonical_fingerprint(trade_payload)
        trade = ReplayTrade(**trade_payload, trade_fingerprint=trade_fingerprint)
        trades.append(trade)

        equity = equity_after
        peak_equity = max(peak_equity, equity)
        drawdown = max(0.0, (peak_equity - equity) / peak_equity)
        equity_curve.append(
            ReplayEquityPoint(
                observed_at=example.target_observed_at,
                equity=equity,
                drawdown=drawdown,
                trade_fingerprint=trade_fingerprint,
            )
        )
        active_until = example.target_observed_at
        turnover_notional += 2.0 * allocated_capital
        total_friction_cost += friction_cost

    net_returns = [trade.net_return for trade in trades]
    winning_trades = [trade for trade in trades if trade.pnl > 0.0]
    losing_trades = [trade for trade in trades if trade.pnl < 0.0]
    gross_profit = sum(trade.pnl for trade in winning_trades)
    gross_loss = abs(sum(trade.pnl for trade in losing_trades))
    profit_factor = gross_profit / gross_loss if gross_loss > 0.0 else None
    max_drawdown = max((point.drawdown for point in equity_curve), default=0.0)
    trade_count = len(trades)
    metrics = ReplayMetrics(
        initial_equity=config.initial_equity,
        final_equity=equity,
        total_return=(equity / config.initial_equity) - 1.0,
        max_drawdown=max_drawdown,
        trade_count=trade_count,
        winning_trades=len(winning_trades),
        losing_trades=len(losing_trades),
        win_rate=(len(winning_trades) / trade_count) if trade_count else 0.0,
        average_net_return=(sum(net_returns) / trade_count) if trade_count else 0.0,
        profit_factor=profit_factor,
        turnover_multiple=turnover_notional / config.initial_equity,
        total_friction_cost=total_friction_cost,
    )
    provenance = [
        f"opportunity:{item.opportunity_fingerprint}" for item in ordered
    ] + [f"outcome:{example.example_fingerprint}" for example in ordered_examples]
    payload = {
        "replay_version": REPLAY_VERSION,
        "config_fingerprint": config.fingerprint,
        "target_spec_fingerprint": target_spec_fingerprint,
        "start_at": ordered[0].as_of.isoformat(),
        "end_at": replay_end.isoformat(),
        "considered_opportunities": len(ordered),
        "skipped_watch": skipped_watch,
        "skipped_below_threshold": skipped_below_threshold,
        "skipped_overlapping": skipped_overlapping,
        "trades": [trade.model_dump(mode="json") for trade in trades],
        "equity_curve": [point.model_dump(mode="json") for point in equity_curve],
        "metrics": metrics.model_dump(mode="json"),
        "provenance": provenance,
        "status": "RESEARCH_REPLAY_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return HistoricalReplay(
        config_fingerprint=config.fingerprint,
        target_spec_fingerprint=target_spec_fingerprint,
        start_at=ordered[0].as_of,
        end_at=replay_end,
        considered_opportunities=len(ordered),
        skipped_watch=skipped_watch,
        skipped_below_threshold=skipped_below_threshold,
        skipped_overlapping=skipped_overlapping,
        trades=trades,
        equity_curve=equity_curve,
        metrics=metrics,
        provenance=provenance,
        replay_fingerprint=canonical_fingerprint(payload),
    )
