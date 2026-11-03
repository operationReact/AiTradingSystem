import math
from datetime import UTC, datetime, timedelta
from typing import NamedTuple

import pytest

from aitrading_intelligence.combinatorial_purged_cv import (
    CombinatorialPurgedCvConfigV1,
    CombinatorialPurgedCvPlanV1,
    build_combinatorial_purged_cv_plan,
)
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.fusion import RankedOpportunity
from aitrading_intelligence.minimum_evidence_policy import (
    MinimumEvidencePolicyConfigV1,
    build_production_candidate_evidence_report,
    evaluate_minimum_evidence_policy,
)
from aitrading_intelligence.monte_carlo import (
    MonteCarloConfig,
    MonteCarloEvidence,
    run_monte_carlo_robustness,
)
from aitrading_intelligence.overfit_diagnostics import (
    OverfitDiagnosticsConfigV1,
    OverfitDiagnosticsReportV1,
    build_candidate_return_series,
    evaluate_overfit_diagnostics,
)
from aitrading_intelligence.purged_walk_forward import (
    PurgedWalkForwardConfigV1,
    PurgedWalkForwardPlanV1,
    build_purged_walk_forward_plan,
)
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.regime import REGIME_LABELS
from aitrading_intelligence.replay import ReplayConfig, run_historical_replay
from aitrading_intelligence.stability_report import (
    StabilityReportConfigV1,
    StabilityReportV1,
    build_stability_observation,
    build_stability_report,
)
from aitrading_intelligence.transaction_cost_stress import (
    TransactionCostStressConfigV1,
    TransactionCostStressReportV1,
    run_transaction_cost_stress_grid,
)
from aitrading_intelligence.triple_barrier_target import (
    TripleBarrierTargetSpecV1,
    TripleBarrierTargetV1,
)
from aitrading_intelligence.walk_forward import WalkForwardConfig, run_walk_forward

START = datetime(2026, 1, 5, 3, 45, tzinfo=UTC)
LABEL_AS_OF = START + timedelta(days=30)
TARGET_SPEC = TripleBarrierTargetSpecV1(
    bar_interval="5m",
    horizon_bars=2,
    upper_barrier_bps=50.0,
    lower_barrier_bps=30.0,
    round_trip_cost_bps=10.0,
)


class EvidenceBundle(NamedTuple):
    purged: PurgedWalkForwardPlanV1
    cpcv: CombinatorialPurgedCvPlanV1
    monte_carlo: MonteCarloEvidence
    monte_carlo_config: MonteCarloConfig
    overfit: OverfitDiagnosticsReportV1
    cost_stress: TransactionCostStressReportV1
    stability: StabilityReportV1
    policy_config: MinimumEvidencePolicyConfigV1


def _target(bucket: int, *, symbol: str = "AAA") -> TripleBarrierTargetV1:
    as_of = START + timedelta(hours=bucket)
    observed_at = as_of + timedelta(hours=2)
    reference_price = 100.0
    exit_price = 100.2
    gross_return = (exit_price / reference_price) - 1.0
    net_return = gross_return - TARGET_SPEC.round_trip_cost_bps / 10_000.0
    source_bar_fingerprints = [
        canonical_fingerprint(
            {"symbol": symbol, "bucket": bucket, "source_bar": index}
        )
        for index in range(TARGET_SPEC.horizon_bars)
    ]
    payload = {
        "target_version": "triple-barrier-target.v1",
        "symbol": symbol,
        "as_of": as_of.isoformat(),
        "label_as_of": LABEL_AS_OF.isoformat(),
        "target_observed_at": observed_at.isoformat(),
        "bar_interval": TARGET_SPEC.bar_interval,
        "horizon_bars": TARGET_SPEC.horizon_bars,
        "observed_bar_count": TARGET_SPEC.horizon_bars,
        "upper_barrier_bps": TARGET_SPEC.upper_barrier_bps,
        "lower_barrier_bps": TARGET_SPEC.lower_barrier_bps,
        "round_trip_cost_bps": TARGET_SPEC.round_trip_cost_bps,
        "tie_policy": TARGET_SPEC.tie_policy,
        "reference_price": reference_price,
        "upper_barrier_price": reference_price
        * (1.0 + TARGET_SPEC.upper_barrier_bps / 10_000.0),
        "lower_barrier_price": reference_price
        * (1.0 - TARGET_SPEC.lower_barrier_bps / 10_000.0),
        "barrier_outcome": "VERTICAL",
        "label": 0,
        "first_touch_bar_index": None,
        "first_touch_bar_at": None,
        "exit_price": exit_price,
        "gross_return": gross_return,
        "net_return": net_return,
        "source_feature_row_fingerprint": canonical_fingerprint(
            {"symbol": symbol, "bucket": bucket, "kind": "feature"}
        ),
        "target_spec_fingerprint": TARGET_SPEC.fingerprint,
        "source_bar_fingerprints": source_bar_fingerprints,
        "status": "RESEARCH_TARGET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TripleBarrierTargetV1(
        **payload,
        target_fingerprint=canonical_fingerprint(payload),
    )


def _validation_plans() -> tuple[PurgedWalkForwardPlanV1, CombinatorialPurgedCvPlanV1]:
    targets = [_target(index) for index in range(18)]
    purged = build_purged_walk_forward_plan(
        targets,
        config=PurgedWalkForwardConfigV1(
            min_train_decision_buckets=3,
            test_decision_buckets=2,
            step_decision_buckets=2,
        ),
    )
    cpcv = build_combinatorial_purged_cv_plan(
        targets,
        config=CombinatorialPurgedCvConfigV1(
            n_groups=3,
            n_test_groups=1,
            min_group_decision_buckets=4,
            min_train_decision_buckets=4,
        ),
    )
    return purged, cpcv


def _walk_forward_for_monte_carlo(length: int = 40):
    opportunities: list[RankedOpportunity] = []
    examples: list[LabeledExample] = []
    rows: list[FeatureRow] = []
    target_spec_fingerprint = "c" * 64
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
                target_spec_fingerprint=target_spec_fingerprint,
                label=1 if forward_return > 0.0035 else 0,
                forward_return=forward_return,
                threshold_return=0.0035,
                features={"market.close": 100.0 + index},
                provenance=[f"fixture:outcome:{index}"],
                example_fingerprint=f"{index + 6000:064x}",
            )
        )
    return run_walk_forward(
        opportunities,
        examples,
        rows,
        config=WalkForwardConfig(
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
        ),
    )


def _monte_carlo() -> tuple[MonteCarloEvidence, MonteCarloConfig]:
    config = MonteCarloConfig(
        simulations=100,
        seed=17,
        block_size=2,
        minimum_oos_trades=5,
        ruin_equity_fraction=0.10,
        drawdown_limit=0.99,
        maximum_ruin_probability=1.0,
        maximum_drawdown_breach_probability=1.0,
        minimum_p05_total_return=-1.0,
        minimum_profitable_fold_fraction=0.0,
    )
    return run_monte_carlo_robustness(_walk_forward_for_monte_carlo(), config=config), config


def _overfit() -> OverfitDiagnosticsReportV1:
    decision_times = [START + timedelta(minutes=5 * index) for index in range(24)]
    candidates = [
        build_candidate_return_series(
            "alpha",
            f"{1:064x}",
            decision_times,
            [0.0030 + (0.0100 * math.sin(index * 0.37)) for index in range(24)],
        ),
        build_candidate_return_series(
            "beta",
            f"{2:064x}",
            decision_times,
            [
                0.0020 + (0.0090 * math.sin((index * 0.51) + 0.30))
                for index in range(24)
            ],
        ),
        build_candidate_return_series(
            "gamma",
            f"{3:064x}",
            decision_times,
            [0.0010 + (0.0080 * math.cos(index * 0.29)) for index in range(24)],
        ),
        build_candidate_return_series(
            "delta",
            f"{4:064x}",
            decision_times,
            [
                0.0005 + (0.0120 * math.sin((index * 0.77) + 0.15))
                for index in range(24)
            ],
        ),
    ]
    return evaluate_overfit_diagnostics(
        candidates,
        config=OverfitDiagnosticsConfigV1(
            pbo_partitions=6,
            minimum_candidates=3,
            minimum_observations=20,
            minimum_partition_observations=4,
            independent_trial_count=4,
        ),
    )


def _replay_opportunity(index: int) -> RankedOpportunity:
    as_of = START + timedelta(minutes=10 * index)
    return RankedOpportunity(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        action="BUY",
        fused_score=0.60,
        rank_score=0.80,
        confidence=0.80,
        contributions=[],
        reason_codes=["fixture"],
        provenance=[f"fixture:stress-opportunity:{index}"],
        opportunity_fingerprint=f"{index + 7000:064x}",
    )


def _replay_example(index: int, forward_return: float) -> LabeledExample:
    as_of = START + timedelta(minutes=10 * index)
    return LabeledExample(
        symbol="RELIANCE",
        as_of=as_of,
        target_observed_at=as_of + timedelta(minutes=5),
        feature_row_fingerprint=f"{index + 7100:064x}",
        target_spec_fingerprint="d" * 64,
        label=1 if forward_return > 0.0035 else 0,
        forward_return=forward_return,
        threshold_return=0.0035,
        features={"market.close": 100.0 + index},
        provenance=[f"fixture:stress-outcome:{index}"],
        example_fingerprint=f"{index + 7200:064x}",
    )


def _cost_stress() -> TransactionCostStressReportV1:
    returns = [0.012, -0.006, 0.018, -0.004, 0.011, 0.007]
    opportunities = [_replay_opportunity(index) for index in range(len(returns))]
    examples = [_replay_example(index, value) for index, value in enumerate(returns)]
    baseline_config = ReplayConfig(
        initial_equity=1_000_000.0,
        allocation_fraction=0.10,
        minimum_rank_score=0.675,
        transaction_cost_bps=10.0,
        slippage_bps_per_side=5.0,
    )
    replay = run_historical_replay(opportunities, examples, config=baseline_config)
    return run_transaction_cost_stress_grid(
        replay,
        baseline_config,
        config=TransactionCostStressConfigV1(
            transaction_cost_multipliers=[1.0, 2.0],
            slippage_multipliers=[1.0, 2.0],
        ),
    )


def _stability() -> StabilityReportV1:
    sectors = ("Financial Services", "Information Technology", "Energy")
    liquidity_percentiles = (0.20, 0.50, 0.85)
    observations = [
        build_stability_observation(
            decision_at=START + timedelta(minutes=5 * index),
            symbol=f"SYM{index}",
            net_return=0.004 + (0.001 * ((index % 7) - 3)),
            regime=REGIME_LABELS[index % len(REGIME_LABELS)],
            liquidity_percentile=liquidity_percentiles[index % 3],
            sector=sectors[index % 3],
            source_return_fingerprint=f"{index + 8000:064x}",
            source_regime_snapshot_fingerprint=f"{index + 8100:064x}",
            source_sector_liquidity_score_fingerprint=f"{index + 8200:064x}",
            source_liquidity_evidence_fingerprint=f"{index + 8300:064x}",
        )
        for index in range(24)
    ]
    return build_stability_report(
        observations,
        config=StabilityReportConfigV1(
            minimum_bucket_observations=4,
            minimum_evaluated_buckets_per_dimension=2,
        ),
    )


@pytest.fixture(scope="module")
def evidence_bundle() -> EvidenceBundle:
    purged, cpcv = _validation_plans()
    monte_carlo, monte_carlo_config = _monte_carlo()
    return EvidenceBundle(
        purged=purged,
        cpcv=cpcv,
        monte_carlo=monte_carlo,
        monte_carlo_config=monte_carlo_config,
        overfit=_overfit(),
        cost_stress=_cost_stress(),
        stability=_stability(),
        policy_config=MinimumEvidencePolicyConfigV1(
            minimum_purged_folds=3,
            minimum_source_target_count=18,
            minimum_source_decision_buckets=18,
            minimum_cpcv_splits=3,
            minimum_monte_carlo_oos_trades=5,
            minimum_overfit_candidates=4,
            minimum_overfit_observations=24,
            minimum_cost_stress_scenarios=4,
            minimum_stability_observations=24,
            minimum_stability_evaluated_buckets_per_dimension=2,
        ),
    )


def _evaluate(
    bundle: EvidenceBundle,
    *,
    overfit: OverfitDiagnosticsReportV1 | None = None,
    monte_carlo: MonteCarloEvidence | None = None,
    policy_config: MinimumEvidencePolicyConfigV1 | None = None,
):
    return evaluate_minimum_evidence_policy(
        purged_walk_forward=bundle.purged,
        cpcv=bundle.cpcv,
        monte_carlo=bundle.monte_carlo if monte_carlo is None else monte_carlo,
        monte_carlo_config=bundle.monte_carlo_config,
        overfit_diagnostics=bundle.overfit if overfit is None else overfit,
        transaction_cost_stress=bundle.cost_stress,
        stability_report=bundle.stability,
        config=policy_config or bundle.policy_config,
    )


def test_complete_evidence_bundle_can_generate_candidate(
    evidence_bundle: EvidenceBundle,
) -> None:
    policy = _evaluate(evidence_bundle)

    assert policy.state == "PRODUCTION_CANDIDATE"
    assert policy.reason_codes == []
    assert policy.satisfied_check_count == 6
    assert all(check.state == "SATISFIED" for check in policy.checks)
    assert policy.promotion_allowed is False
    assert policy.can_authorize_capital is False

    candidate = build_production_candidate_evidence_report(policy)

    assert candidate.status == "PRODUCTION_CANDIDATE_EVIDENCE_ONLY"
    assert len(candidate.mandatory_requirements) == 6
    assert len(candidate.mandatory_check_fingerprints) == 6
    assert len(candidate.mandatory_evidence_fingerprints) == 6
    assert candidate.promotion_allowed is False
    assert candidate.can_authorize_capital is False


def test_missing_mandatory_test_is_inconclusive_and_candidate_is_blocked(
    evidence_bundle: EvidenceBundle,
) -> None:
    policy = evaluate_minimum_evidence_policy(
        purged_walk_forward=evidence_bundle.purged,
        cpcv=evidence_bundle.cpcv,
        monte_carlo=evidence_bundle.monte_carlo,
        monte_carlo_config=evidence_bundle.monte_carlo_config,
        overfit_diagnostics=None,
        transaction_cost_stress=evidence_bundle.cost_stress,
        stability_report=evidence_bundle.stability,
        config=evidence_bundle.policy_config,
    )

    assert policy.state == "INCONCLUSIVE"
    assert policy.satisfied_check_count == 5
    assert "OVERFIT_DIAGNOSTICS:MISSING_OVERFIT_DIAGNOSTICS" in policy.reason_codes
    with pytest.raises(ValueError, match="requires every mandatory V3-08 evidence check"):
        build_production_candidate_evidence_report(policy)


def test_sample_size_insufficiency_is_explicitly_inconclusive(
    evidence_bundle: EvidenceBundle,
) -> None:
    strict = evidence_bundle.policy_config.model_copy(
        update={"minimum_stability_observations": 25}
    )
    policy = _evaluate(evidence_bundle, policy_config=strict)

    assert policy.state == "INCONCLUSIVE"
    assert "STABILITY_REPORT:INSUFFICIENT_STABILITY_OBSERVATIONS" in policy.reason_codes
    stability_check = next(
        item for item in policy.checks if item.requirement == "STABILITY_REPORT"
    )
    assert stability_check.state == "INCONCLUSIVE"
    assert stability_check.observed_sample_count == 24
    assert stability_check.minimum_required_sample_count == 25


def test_tampered_monte_carlo_fingerprint_cannot_satisfy_policy(
    evidence_bundle: EvidenceBundle,
) -> None:
    tampered = evidence_bundle.monte_carlo.model_copy(
        update={"evidence_fingerprint": "0" * 64}
    )
    policy = _evaluate(evidence_bundle, monte_carlo=tampered)

    assert policy.state == "INCONCLUSIVE"
    assert (
        "DEPENDENCE_BOOTSTRAP:MONTE_CARLO_EVIDENCE_FINGERPRINT_MISMATCH"
        in policy.reason_codes
    )
    bootstrap_check = next(
        item for item in policy.checks if item.requirement == "DEPENDENCE_BOOTSTRAP"
    )
    assert bootstrap_check.state == "INCONCLUSIVE"
    assert bootstrap_check.artifact_fingerprint is None
