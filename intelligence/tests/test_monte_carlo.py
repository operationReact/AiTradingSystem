import random
from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fusion import RankedOpportunity
from aitrading_intelligence.monte_carlo import (
    MonteCarloConfig,
    _bootstrap_sequence,
    run_monte_carlo_robustness,
)
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.replay import ReplayConfig
from aitrading_intelligence.walk_forward import WalkForwardConfig, run_walk_forward

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
TARGET_SPEC = "c" * 64


def _walk_forward(length: int = 80):
    opportunities: list[RankedOpportunity] = []
    examples: list[LabeledExample] = []
    rows: list[FeatureRow] = []
    for index in range(length):
        as_of = START + timedelta(minutes=5 * index)
        forward_return = -0.005 if index % 5 == 0 else 0.010
        row_fingerprint = f"{index + 4000:064x}"
        opportunities.append(
            RankedOpportunity(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                action="BUY",
                fused_score=0.60,
                rank_score=0.80,
                confidence=0.80,
                contributions=[],
                reason_codes=["fixture"],
                provenance=[f"fixture:opportunity:{index}"],
                opportunity_fingerprint=f"{index + 5000:064x}",
            )
        )
        rows.append(
            FeatureRow(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                features={
                    "market.return_1": 0.010 if index % 2 else -0.010,
                    "market.range_pct": 0.012,
                    "market.close": 100.0 + index,
                },
                provenance=[f"fixture:row:{index}"],
                fingerprint=row_fingerprint,
            )
        )
        examples.append(
            LabeledExample(
                symbol="RELIANCE",
                as_of=as_of,
                target_observed_at=as_of + timedelta(minutes=5),
                feature_row_fingerprint=row_fingerprint,
                target_spec_fingerprint=TARGET_SPEC,
                label=1 if forward_return > 0.0035 else 0,
                forward_return=forward_return,
                threshold_return=0.0035,
                features={"market.close": 100.0 + index},
                provenance=[f"fixture:outcome:{index}"],
                example_fingerprint=f"{index + 6000:064x}",
            )
        )
    config = WalkForwardConfig(
        minimum_history_periods=10,
        test_periods=10,
        step_periods=10,
        purge_minutes=0,
        replay=ReplayConfig(
            initial_equity=1_000_000.0,
            allocation_fraction=0.10,
            transaction_cost_bps=10.0,
            slippage_bps_per_side=5.0,
        ),
    )
    return run_walk_forward(opportunities, examples, rows, config=config)


def test_default_bootstrap_preserves_dependence_and_rejects_iid_block_size() -> None:
    config = MonteCarloConfig()

    assert config.bootstrap_method == "stationary"
    assert config.block_size == 5
    assert config.block_size > 1

    with pytest.raises(ValueError):
        MonteCarloConfig(block_size=1)


def test_stationary_bootstrap_is_seeded_and_keeps_contiguous_runs() -> None:
    values = [float(index) for index in range(20)]
    first = _bootstrap_sequence(
        values,
        method="stationary",
        block_size=5,
        rng=random.Random(17),
    )
    second = _bootstrap_sequence(
        values,
        method="stationary",
        block_size=5,
        rng=random.Random(17),
    )

    assert first == second
    assert len(first) == len(values)
    contiguous_transitions = sum(
        int((int(right) - int(left)) % len(values) == 1)
        for left, right in zip(first, first[1:])
    )
    assert contiguous_transitions >= len(values) // 2


def test_monte_carlo_is_deterministic_and_never_promotes() -> None:
    evidence = _walk_forward()
    config = MonteCarloConfig(
        simulations=500,
        seed=42,
        block_size=2,
        minimum_oos_trades=20,
    )

    first = run_monte_carlo_robustness(evidence, config=config)
    second = run_monte_carlo_robustness(evidence, config=config)

    assert first.evidence_fingerprint == second.evidence_fingerprint
    assert first.summary == second.summary
    assert first.source_trade_fingerprints == second.source_trade_fingerprints
    assert first.summary.oos_trade_count >= 20
    assert first.summary.p05_total_return <= first.summary.median_total_return
    assert first.summary.median_total_return <= first.summary.p95_total_return
    assert first.summary.p50_max_drawdown <= first.summary.p95_max_drawdown
    assert first.status == "RESEARCH_MONTE_CARLO_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False


def test_strong_fixture_can_only_advance_to_governance_not_production() -> None:
    evidence = _walk_forward()
    result = run_monte_carlo_robustness(
        evidence,
        config=MonteCarloConfig(
            simulations=500,
            seed=7,
            minimum_oos_trades=20,
            minimum_p05_total_return=-0.10,
            maximum_drawdown_breach_probability=0.25,
            maximum_ruin_probability=0.01,
            minimum_profitable_fold_fraction=0.50,
        ),
    )

    assert result.assessment == "V2_09_GOVERNANCE_CANDIDATE"
    assert result.next_gate == "V2_09_OUTCOME_GOVERNANCE"
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False
    assert result.summary.probability_of_ruin == 0.0


def test_strict_robustness_thresholds_force_research_review() -> None:
    evidence = _walk_forward()
    result = run_monte_carlo_robustness(
        evidence,
        config=MonteCarloConfig(
            simulations=200,
            seed=42,
            minimum_oos_trades=20,
            minimum_p05_total_return=0.50,
        ),
    )

    assert result.assessment == "V2_08_RESEARCH_REVIEW"
    assert result.next_gate == "V2_08_RESEARCH_REVIEW"
    assert result.promotion_allowed is False


def test_monte_carlo_requires_enough_unique_oos_trades() -> None:
    evidence = _walk_forward(length=30)

    with pytest.raises(ValueError, match="not enough unique out-of-sample trades"):
        run_monte_carlo_robustness(
            evidence,
            config=MonteCarloConfig(simulations=100, minimum_oos_trades=25),
        )
