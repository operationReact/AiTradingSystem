from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fusion import RankedOpportunity
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.replay import ReplayConfig, run_historical_replay
from aitrading_intelligence.transaction_cost_stress import (
    TransactionCostStressConfigV1,
    run_transaction_cost_stress_grid,
)

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
TARGET_SPEC = "d" * 64


def _opportunity(index: int, *, action: str = "BUY") -> RankedOpportunity:
    as_of = START + timedelta(minutes=10 * index)
    return RankedOpportunity(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        action=action,
        fused_score=0.60,
        rank_score=0.80,
        confidence=0.80,
        contributions=[],
        reason_codes=["fixture"],
        provenance=[f"fixture:opportunity:{index}"],
        opportunity_fingerprint=f"{index + 7000:064x}",
    )


def _example(index: int, forward_return: float) -> LabeledExample:
    as_of = START + timedelta(minutes=10 * index)
    return LabeledExample(
        symbol="RELIANCE",
        as_of=as_of,
        target_observed_at=as_of + timedelta(minutes=5),
        feature_row_fingerprint=f"{index + 7100:064x}",
        target_spec_fingerprint=TARGET_SPEC,
        label=1 if forward_return > 0.0035 else 0,
        forward_return=forward_return,
        threshold_return=0.0035,
        features={"market.close": 100.0 + index},
        provenance=[f"fixture:outcome:{index}"],
        example_fingerprint=f"{index + 7200:064x}",
    )


def _baseline(*, all_watch: bool = False, zero_friction: bool = False):
    returns = [0.012, -0.006, 0.018, -0.004, 0.011, 0.007]
    opportunities = [
        _opportunity(index, action="WATCH" if all_watch else "BUY")
        for index in range(len(returns))
    ]
    examples = [_example(index, value) for index, value in enumerate(returns)]
    config = ReplayConfig(
        initial_equity=1_000_000.0,
        allocation_fraction=0.10,
        minimum_rank_score=0.675,
        transaction_cost_bps=0.0 if zero_friction else 10.0,
        slippage_bps_per_side=0.0 if zero_friction else 5.0,
    )
    replay = run_historical_replay(opportunities, examples, config=config)
    return replay, config


def test_stress_grid_reprices_exact_decision_and_capital_path() -> None:
    replay, baseline_config = _baseline()
    config = TransactionCostStressConfigV1(
        transaction_cost_multipliers=[1.0, 2.0],
        slippage_multipliers=[1.0, 2.0],
    )

    report = run_transaction_cost_stress_grid(
        replay,
        baseline_config,
        config=config,
    )

    assert report.state == "EVALUATED"
    assert report.reason_codes == []
    assert report.scenario_count == 4
    assert report.baseline_trade_count == replay.metrics.trade_count
    assert report.source_trade_fingerprints == [
        trade.trade_fingerprint for trade in replay.trades
    ]
    assert all(
        scenario.decision_path_fingerprint == report.decision_path_fingerprint
        for scenario in report.scenarios
    )
    assert all(
        scenario.capital_path_fingerprint == report.capital_path_fingerprint
        for scenario in report.scenarios
    )
    assert all(
        scenario.trade_count == replay.metrics.trade_count for scenario in report.scenarios
    )
    assert report.status == "RESEARCH_TRANSACTION_COST_STRESS_ONLY"
    assert report.promotion_allowed is False
    assert report.can_authorize_capital is False


def test_one_x_scenario_reproduces_source_replay_exactly() -> None:
    replay, baseline_config = _baseline()
    report = run_transaction_cost_stress_grid(replay, baseline_config)
    baseline = next(
        scenario
        for scenario in report.scenarios
        if scenario.transaction_cost_multiplier == 1.0
        and scenario.slippage_multiplier == 1.0
    )

    assert baseline.final_equity == pytest.approx(replay.metrics.final_equity)
    assert baseline.total_return == pytest.approx(replay.metrics.total_return)
    assert baseline.max_drawdown == pytest.approx(replay.metrics.max_drawdown)
    assert baseline.total_friction_cost == pytest.approx(
        replay.metrics.total_friction_cost
    )
    assert baseline.incremental_friction_cost == pytest.approx(0.0)


def test_more_friction_never_improves_frozen_path_return() -> None:
    replay, baseline_config = _baseline()
    report = run_transaction_cost_stress_grid(
        replay,
        baseline_config,
        config=TransactionCostStressConfigV1(
            transaction_cost_multipliers=[1.0, 1.5, 2.0],
            slippage_multipliers=[1.0, 1.5, 2.0],
        ),
    )

    ordered = sorted(
        report.scenarios,
        key=lambda scenario: scenario.round_trip_friction_bps,
    )
    for left, right in zip(ordered, ordered[1:], strict=False):
        if right.round_trip_friction_bps > left.round_trip_friction_bps:
            assert right.total_return < left.total_return
            assert right.total_friction_cost > left.total_friction_cost
            assert right.incremental_friction_cost >= left.incremental_friction_cost

    assert report.worst_total_return == min(
        scenario.total_return for scenario in report.scenarios
    )
    assert report.maximum_total_friction_cost == max(
        scenario.total_friction_cost for scenario in report.scenarios
    )


def test_stress_report_is_deterministic() -> None:
    replay, baseline_config = _baseline()
    config = TransactionCostStressConfigV1(
        transaction_cost_multipliers=[1.0, 2.0],
        slippage_multipliers=[1.0, 3.0],
    )

    first = run_transaction_cost_stress_grid(replay, baseline_config, config=config)
    second = run_transaction_cost_stress_grid(replay, baseline_config, config=config)

    assert first == second
    assert first.report_fingerprint == second.report_fingerprint


def test_missing_economic_evidence_is_explicitly_inconclusive() -> None:
    no_trade_replay, normal_config = _baseline(all_watch=True)
    no_trade_report = run_transaction_cost_stress_grid(
        no_trade_replay,
        normal_config,
    )

    assert no_trade_report.state == "INCONCLUSIVE"
    assert no_trade_report.reason_codes == ["NO_BASELINE_TRADES"]
    assert no_trade_report.scenarios == []
    assert no_trade_report.scenario_count == 0
    assert no_trade_report.worst_total_return is None

    zero_cost_replay, zero_cost_config = _baseline(zero_friction=True)
    zero_cost_report = run_transaction_cost_stress_grid(
        zero_cost_replay,
        zero_cost_config,
    )

    assert zero_cost_report.state == "INCONCLUSIVE"
    assert zero_cost_report.reason_codes == ["ZERO_BASELINE_FRICTION_CANNOT_STRESS"]
    assert zero_cost_report.scenarios == []
    assert zero_cost_report.profitable_scenario_fraction is None


def test_source_replay_must_match_baseline_cost_config() -> None:
    replay, _ = _baseline()
    mismatched = ReplayConfig(
        transaction_cost_bps=20.0,
        slippage_bps_per_side=5.0,
    )

    with pytest.raises(ValueError, match="fingerprint does not match"):
        run_transaction_cost_stress_grid(replay, mismatched)


def test_stress_config_requires_ordered_grid_with_baseline() -> None:
    with pytest.raises(ValidationError, match="unique and sorted"):
        TransactionCostStressConfigV1(
            transaction_cost_multipliers=[2.0, 1.0],
        )

    with pytest.raises(ValidationError, match="include the 1.0 baseline"):
        TransactionCostStressConfigV1(
            slippage_multipliers=[1.5, 2.0],
        )
