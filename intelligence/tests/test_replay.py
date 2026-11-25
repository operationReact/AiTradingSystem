from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.fusion import RankedOpportunity
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.replay import ReplayConfig, run_historical_replay

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
TARGET_SPEC = "a" * 64


def _opportunity(
    index: int,
    *,
    action: str = "BUY",
    rank_score: float = 0.80,
    data_cutoff_offset_minutes: int = 0,
) -> RankedOpportunity:
    as_of = START + timedelta(minutes=5 * index)
    return RankedOpportunity(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of + timedelta(minutes=data_cutoff_offset_minutes),
        action=action,
        fused_score=(2.0 * rank_score) - 1.0,
        rank_score=rank_score,
        confidence=0.80,
        contributions=[],
        reason_codes=["fixture"],
        provenance=[f"fixture:opportunity:{index}"],
        opportunity_fingerprint=f"{index + 1:064x}",
    )


def _example(index: int, *, forward_return: float, horizon_minutes: int = 10) -> LabeledExample:
    as_of = START + timedelta(minutes=5 * index)
    observed_at = as_of + timedelta(minutes=horizon_minutes)
    return LabeledExample(
        symbol="RELIANCE",
        as_of=as_of,
        target_observed_at=observed_at,
        feature_row_fingerprint=f"{index + 101:064x}",
        target_spec_fingerprint=TARGET_SPEC,
        label=1 if forward_return > 0.0035 else 0,
        forward_return=forward_return,
        threshold_return=0.0035,
        features={"market.close": 100.0 + index},
        provenance=[f"fixture:outcome:{index}"],
        example_fingerprint=f"{index + 201:064x}",
    )


def test_replay_applies_cost_slippage_and_non_overlapping_capital() -> None:
    opportunities = [_opportunity(0), _opportunity(1), _opportunity(2)]
    examples = [
        _example(0, forward_return=0.010),
        _example(1, forward_return=0.020),
        _example(2, forward_return=-0.010),
    ]
    config = ReplayConfig(
        initial_equity=1_000_000.0,
        allocation_fraction=0.10,
        minimum_rank_score=0.675,
        transaction_cost_bps=10.0,
        slippage_bps_per_side=5.0,
    )

    replay = run_historical_replay(opportunities, examples, config=config)

    assert replay.considered_opportunities == 3
    assert replay.skipped_overlapping == 1
    assert replay.metrics.trade_count == 2
    assert replay.trades[0].gross_return == pytest.approx(0.010)
    assert replay.trades[0].friction_bps == pytest.approx(20.0)
    assert replay.trades[0].net_return == pytest.approx(0.008)
    assert replay.trades[0].allocated_capital == pytest.approx(100_000.0)
    assert replay.trades[0].friction_cost == pytest.approx(200.0)
    assert replay.trades[0].pnl == pytest.approx(800.0)
    assert replay.trades[1].equity_before == pytest.approx(1_000_800.0)
    assert replay.metrics.final_equity < replay.trades[1].equity_before
    assert replay.metrics.max_drawdown > 0.0
    assert replay.metrics.total_friction_cost > 0.0
    assert replay.metrics.turnover_multiple > 0.0
    assert replay.status == "RESEARCH_REPLAY_ONLY"
    assert replay.promotion_allowed is False
    assert replay.can_authorize_capital is False


def test_replay_is_deterministic_for_caller_order() -> None:
    opportunities = [_opportunity(0), _opportunity(1), _opportunity(2)]
    examples = [
        _example(0, forward_return=0.010),
        _example(1, forward_return=0.020),
        _example(2, forward_return=-0.010),
    ]

    first = run_historical_replay(opportunities, examples)
    second = run_historical_replay(
        list(reversed(opportunities)),
        list(reversed(examples)),
    )

    assert first.replay_fingerprint == second.replay_fingerprint
    assert first.metrics == second.metrics
    assert first.trades == second.trades


def test_replay_tracks_watch_threshold_and_overlap_separately() -> None:
    opportunities = [
        _opportunity(0, action="WATCH"),
        _opportunity(1, rank_score=0.60),
        _opportunity(2),
        _opportunity(3),
    ]
    examples = [
        _example(0, forward_return=0.01),
        _example(1, forward_return=0.01),
        _example(2, forward_return=0.01),
        _example(3, forward_return=0.01),
    ]

    replay = run_historical_replay(opportunities, examples)

    assert replay.skipped_watch == 1
    assert replay.skipped_below_threshold == 1
    assert replay.skipped_overlapping == 1
    assert replay.metrics.trade_count == 1


def test_replay_rejects_missing_outcomes_and_future_data_cutoff() -> None:
    with pytest.raises(ValueError, match="every ranked opportunity"):
        run_historical_replay([_opportunity(0)], [_example(1, forward_return=0.01)])

    with pytest.raises(ValueError, match="data_cutoff"):
        run_historical_replay(
            [_opportunity(0, data_cutoff_offset_minutes=1)],
            [_example(0, forward_return=0.01)],
        )
