from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fusion import RankedOpportunity
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.replay import ReplayConfig
from aitrading_intelligence.walk_forward import WalkForwardConfig, run_walk_forward

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
TARGET_SPEC = "b" * 64


def _fixture(length: int = 50) -> tuple[list[RankedOpportunity], list[LabeledExample], list[FeatureRow]]:
    opportunities: list[RankedOpportunity] = []
    examples: list[LabeledExample] = []
    rows: list[FeatureRow] = []
    for index in range(length):
        as_of = START + timedelta(minutes=5 * index)
        if index % 4 == 0:
            return_1 = 0.002
            range_pct = 0.030
        elif index % 3 == 0:
            return_1 = -0.010
            range_pct = 0.012
        elif index % 3 == 1:
            return_1 = 0.010
            range_pct = 0.012
        else:
            return_1 = 0.001
            range_pct = 0.012
        forward_return = 0.012 if return_1 >= 0.005 else -0.008 if return_1 <= -0.005 else 0.004
        row_fingerprint = f"{index + 1000:064x}"
        opportunity_fingerprint = f"{index + 2000:064x}"
        example_fingerprint = f"{index + 3000:064x}"
        rows.append(
            FeatureRow(
                symbol="RELIANCE",
                as_of=as_of,
                data_cutoff=as_of,
                features={
                    "market.return_1": return_1,
                    "market.range_pct": range_pct,
                    "market.close": 100.0 + index,
                },
                provenance=[f"fixture:row:{index}"],
                fingerprint=row_fingerprint,
            )
        )
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
                opportunity_fingerprint=opportunity_fingerprint,
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
                example_fingerprint=example_fingerprint,
            )
        )
    return opportunities, examples, rows


def _config() -> WalkForwardConfig:
    return WalkForwardConfig(
        minimum_history_periods=10,
        test_periods=5,
        step_periods=5,
        purge_minutes=5,
        replay=ReplayConfig(
            initial_equity=1_000_000.0,
            allocation_fraction=0.10,
            transaction_cost_bps=10.0,
            slippage_bps_per_side=5.0,
        ),
    )


def test_walk_forward_is_purged_deterministic_and_research_only() -> None:
    opportunities, examples, rows = _fixture()
    config = _config()

    first = run_walk_forward(opportunities, examples, rows, config=config)
    second = run_walk_forward(
        list(reversed(opportunities)),
        list(reversed(examples)),
        list(reversed(rows)),
        config=config,
    )

    assert first.evidence_fingerprint == second.evidence_fingerprint
    assert first.folds == second.folds
    assert first.summary.fold_count >= 2
    assert first.summary.total_trades > 0
    assert first.summary.total_friction_cost > 0.0
    assert first.status == "RESEARCH_WALK_FORWARD_ONLY"
    assert first.promotion_allowed is False
    assert first.can_authorize_capital is False

    example_by_fingerprint = {item.example_fingerprint: item for item in examples}
    for fold in first.folds:
        assert fold.training_cutoff < fold.test_start
        for fingerprint in fold.training_example_fingerprints:
            assert example_by_fingerprint[fingerprint].target_observed_at < fold.training_cutoff
        assert sum(item.candidate_count for item in fold.regime_metrics) == len(
            fold.test_opportunity_fingerprints
        )
        assert sum(item.trade_count for item in fold.regime_metrics) == fold.replay.metrics.trade_count


def test_walk_forward_uses_causal_feature_regimes() -> None:
    opportunities, examples, rows = _fixture()

    evidence = run_walk_forward(opportunities, examples, rows, config=_config())

    observed_regimes = {
        metric.regime
        for fold in evidence.folds
        for metric in fold.regime_metrics
        if metric.candidate_count > 0
    }
    assert "HIGH_VOL" in observed_regimes
    assert "TREND_UP" in observed_regimes
    assert "TREND_DOWN" in observed_regimes
    assert "RANGE" in observed_regimes


def test_walk_forward_rejects_broken_frozen_feature_lineage() -> None:
    opportunities, examples, rows = _fixture()
    broken = examples[0].model_copy(update={"feature_row_fingerprint": "f" * 64})

    with pytest.raises(ValueError, match="frozen feature row"):
        run_walk_forward(opportunities, [broken, *examples[1:]], rows, config=_config())


def test_walk_forward_requires_enough_chronological_history() -> None:
    opportunities, examples, rows = _fixture(length=12)

    with pytest.raises(ValueError, match="not enough chronological periods"):
        run_walk_forward(opportunities, examples, rows, config=_config())
