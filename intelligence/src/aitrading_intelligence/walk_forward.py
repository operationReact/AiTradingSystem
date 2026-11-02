from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timedelta
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .fusion import RankedOpportunity
from .pytorch_target import LabeledExample
from .replay import HistoricalReplay, ReplayConfig, run_historical_replay

WALK_FORWARD_VERSION = "walk-forward.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Regime = Literal["HIGH_VOL", "TREND_UP", "TREND_DOWN", "RANGE"]


class WalkForwardConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    walk_forward_version: Literal["walk-forward.v1"] = WALK_FORWARD_VERSION
    minimum_history_periods: Annotated[int, Field(ge=5, le=100_000)] = 20
    test_periods: Annotated[int, Field(ge=1, le=10_000)] = 10
    step_periods: Annotated[int, Field(ge=1, le=10_000)] = 10
    purge_minutes: Annotated[int, Field(ge=0, le=100_000)] = 5
    high_volatility_threshold: Annotated[float, Field(gt=0.0, le=1.0)] = 0.02
    trend_return_threshold: Annotated[float, Field(gt=0.0, le=1.0)] = 0.005
    replay: ReplayConfig = ReplayConfig()

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class RegimeMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    regime: Regime
    candidate_count: Annotated[int, Field(ge=0)]
    trade_count: Annotated[int, Field(ge=0)]
    winning_trades: Annotated[int, Field(ge=0)]
    losing_trades: Annotated[int, Field(ge=0)]
    win_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    average_net_return: float
    total_pnl: float


class WalkForwardFold(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fold_index: Annotated[int, Field(ge=0)]
    training_cutoff: datetime
    test_start: datetime
    test_end: datetime
    training_example_fingerprints: list[Hash64]
    test_opportunity_fingerprints: list[Hash64]
    replay: HistoricalReplay
    regime_metrics: list[RegimeMetrics]
    fold_fingerprint: Hash64

    @field_validator("training_cutoff", "test_start", "test_end")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("walk-forward timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_window(self) -> WalkForwardFold:
        if self.training_cutoff > self.test_start:
            raise ValueError("training cutoff cannot exceed test start")
        if self.test_end < self.test_start:
            raise ValueError("test end cannot precede test start")
        return self


class WalkForwardSummary(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fold_count: Annotated[int, Field(ge=1)]
    total_test_opportunities: Annotated[int, Field(ge=1)]
    total_trades: Annotated[int, Field(ge=0)]
    profitable_folds: Annotated[int, Field(ge=0)]
    losing_folds: Annotated[int, Field(ge=0)]
    mean_fold_return: float
    worst_fold_return: float
    worst_fold_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    total_friction_cost: Annotated[float, Field(ge=0.0)]


class WalkForwardEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    walk_forward_version: Literal["walk-forward.v1"] = WALK_FORWARD_VERSION
    config_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    folds: list[WalkForwardFold]
    summary: WalkForwardSummary
    evidence_fingerprint: Hash64
    status: Literal["RESEARCH_WALK_FORWARD_ONLY"] = "RESEARCH_WALK_FORWARD_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def _key(symbol: str, as_of: datetime) -> tuple[str, datetime]:
    return symbol, as_of


def _regime(row: FeatureRow, config: WalkForwardConfig) -> Regime:
    range_pct = abs(float(row.features.get("market.range_pct", 0.0)))
    return_1 = float(row.features.get("market.return_1", 0.0))
    if range_pct >= config.high_volatility_threshold:
        return "HIGH_VOL"
    if return_1 >= config.trend_return_threshold:
        return "TREND_UP"
    if return_1 <= -config.trend_return_threshold:
        return "TREND_DOWN"
    return "RANGE"


def _validate_inputs(
    opportunities: Sequence[RankedOpportunity],
    examples: Sequence[LabeledExample],
    rows: Sequence[FeatureRow],
) -> tuple[
    dict[tuple[str, datetime], LabeledExample],
    dict[tuple[str, datetime], FeatureRow],
]:
    if not opportunities or not examples or not rows:
        raise ValueError("opportunities, examples and feature rows are required")
    if len({example.target_spec_fingerprint for example in examples}) != 1:
        raise ValueError("all examples must use the same target specification")

    example_by_key: dict[tuple[str, datetime], LabeledExample] = {}
    for example in examples:
        key = _key(example.symbol, example.as_of)
        if key in example_by_key:
            raise ValueError("examples must be unique by symbol and as_of")
        example_by_key[key] = example

    row_by_key: dict[tuple[str, datetime], FeatureRow] = {}
    for row in rows:
        key = _key(row.symbol, row.as_of)
        if key in row_by_key:
            raise ValueError("feature rows must be unique by symbol and as_of")
        if row.data_cutoff > row.as_of:
            raise ValueError("feature row data_cutoff cannot exceed as_of")
        row_by_key[key] = row

    for opportunity in opportunities:
        key = _key(opportunity.symbol, opportunity.as_of)
        if key not in example_by_key:
            raise ValueError("every opportunity must resolve to a labeled example")
        if key not in row_by_key:
            raise ValueError("every opportunity must resolve to a frozen feature row")
        if row_by_key[key].fingerprint != example_by_key[key].feature_row_fingerprint:
            raise ValueError("labeled outcome must reference the resolved frozen feature row")
    return example_by_key, row_by_key


def _regime_metrics(
    opportunities: Sequence[RankedOpportunity],
    rows: dict[tuple[str, datetime], FeatureRow],
    replay: HistoricalReplay,
    config: WalkForwardConfig,
) -> list[RegimeMetrics]:
    regime_by_opportunity = {
        item.opportunity_fingerprint: _regime(rows[_key(item.symbol, item.as_of)], config)
        for item in opportunities
    }
    candidate_counts = {name: 0 for name in ("HIGH_VOL", "TREND_UP", "TREND_DOWN", "RANGE")}
    for item in opportunities:
        candidate_counts[regime_by_opportunity[item.opportunity_fingerprint]] += 1

    trades_by_regime: dict[str, list] = {
        name: [] for name in ("HIGH_VOL", "TREND_UP", "TREND_DOWN", "RANGE")
    }
    for trade in replay.trades:
        trades_by_regime[regime_by_opportunity[trade.opportunity_fingerprint]].append(trade)

    metrics: list[RegimeMetrics] = []
    for regime in ("HIGH_VOL", "TREND_UP", "TREND_DOWN", "RANGE"):
        trades = trades_by_regime[regime]
        winners = [trade for trade in trades if trade.pnl > 0.0]
        losers = [trade for trade in trades if trade.pnl < 0.0]
        metrics.append(
            RegimeMetrics(
                regime=regime,
                candidate_count=candidate_counts[regime],
                trade_count=len(trades),
                winning_trades=len(winners),
                losing_trades=len(losers),
                win_rate=(len(winners) / len(trades)) if trades else 0.0,
                average_net_return=(
                    sum(trade.net_return for trade in trades) / len(trades)
                    if trades
                    else 0.0
                ),
                total_pnl=sum(trade.pnl for trade in trades),
            )
        )
    return metrics


def run_walk_forward(
    opportunities: Sequence[RankedOpportunity],
    examples: Sequence[LabeledExample],
    rows: Sequence[FeatureRow],
    *,
    config: WalkForwardConfig | None = None,
) -> WalkForwardEvidence:
    config = config or WalkForwardConfig()
    example_by_key, row_by_key = _validate_inputs(opportunities, examples, rows)
    ordered = sorted(opportunities, key=lambda item: (item.as_of, item.symbol))
    decision_times = sorted({item.as_of for item in ordered})
    minimum_required = config.minimum_history_periods + config.test_periods
    if len(decision_times) < minimum_required:
        raise ValueError("not enough chronological periods for walk-forward evaluation")

    folds: list[WalkForwardFold] = []
    fold_index = 0
    test_start_index = config.minimum_history_periods
    while test_start_index < len(decision_times):
        test_times = decision_times[test_start_index : test_start_index + config.test_periods]
        if not test_times:
            break
        test_start = test_times[0]
        test_end = test_times[-1]
        training_cutoff = test_start - timedelta(minutes=config.purge_minutes)
        training_examples = sorted(
            (
                example
                for example in examples
                if example.as_of < test_start
                and example.target_observed_at < training_cutoff
            ),
            key=lambda item: (item.as_of, item.symbol, item.target_observed_at),
        )
        if len({example.as_of for example in training_examples}) < config.minimum_history_periods:
            test_start_index += config.step_periods
            continue

        test_opportunities = [item for item in ordered if item.as_of in set(test_times)]
        test_examples = [
            example_by_key[_key(item.symbol, item.as_of)] for item in test_opportunities
        ]
        replay = run_historical_replay(
            test_opportunities,
            test_examples,
            config=config.replay,
        )
        regime_metrics = _regime_metrics(
            test_opportunities,
            row_by_key,
            replay,
            config,
        )
        fold_payload = {
            "walk_forward_version": WALK_FORWARD_VERSION,
            "config_fingerprint": config.fingerprint,
            "fold_index": fold_index,
            "training_cutoff": training_cutoff.isoformat(),
            "test_start": test_start.isoformat(),
            "test_end": test_end.isoformat(),
            "training_example_fingerprints": [
                example.example_fingerprint for example in training_examples
            ],
            "test_opportunity_fingerprints": [
                item.opportunity_fingerprint for item in test_opportunities
            ],
            "replay_fingerprint": replay.replay_fingerprint,
            "regime_metrics": [item.model_dump(mode="json") for item in regime_metrics],
        }
        folds.append(
            WalkForwardFold(
                fold_index=fold_index,
                training_cutoff=training_cutoff,
                test_start=test_start,
                test_end=test_end,
                training_example_fingerprints=[
                    example.example_fingerprint for example in training_examples
                ],
                test_opportunity_fingerprints=[
                    item.opportunity_fingerprint for item in test_opportunities
                ],
                replay=replay,
                regime_metrics=regime_metrics,
                fold_fingerprint=canonical_fingerprint(fold_payload),
            )
        )
        fold_index += 1
        test_start_index += config.step_periods

    if not folds:
        raise ValueError("purge/history requirements produced no valid walk-forward folds")

    returns = [fold.replay.metrics.total_return for fold in folds]
    summary = WalkForwardSummary(
        fold_count=len(folds),
        total_test_opportunities=sum(
            len(fold.test_opportunity_fingerprints) for fold in folds
        ),
        total_trades=sum(fold.replay.metrics.trade_count for fold in folds),
        profitable_folds=sum(value > 0.0 for value in returns),
        losing_folds=sum(value < 0.0 for value in returns),
        mean_fold_return=sum(returns) / len(returns),
        worst_fold_return=min(returns),
        worst_fold_drawdown=max(fold.replay.metrics.max_drawdown for fold in folds),
        total_friction_cost=sum(
            fold.replay.metrics.total_friction_cost for fold in folds
        ),
    )
    target_spec_fingerprint = next(iter({item.target_spec_fingerprint for item in examples}))
    payload = {
        "walk_forward_version": WALK_FORWARD_VERSION,
        "config_fingerprint": config.fingerprint,
        "target_spec_fingerprint": target_spec_fingerprint,
        "fold_fingerprints": [fold.fold_fingerprint for fold in folds],
        "summary": summary.model_dump(mode="json"),
        "status": "RESEARCH_WALK_FORWARD_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return WalkForwardEvidence(
        config_fingerprint=config.fingerprint,
        target_spec_fingerprint=target_spec_fingerprint,
        folds=folds,
        summary=summary,
        evidence_fingerprint=canonical_fingerprint(payload),
    )
