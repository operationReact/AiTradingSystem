from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.purged_walk_forward import (
    PurgedWalkForwardConfigV1,
    PurgedWalkForwardPlanV1,
    build_purged_walk_forward_plan,
)
from aitrading_intelligence.triple_barrier_target import (
    TripleBarrierTargetSpecV1,
    TripleBarrierTargetV1,
)

BASE = datetime(2026, 1, 5, 4, 0, tzinfo=UTC)
LABEL_AS_OF = BASE + timedelta(days=30)
SPEC = TripleBarrierTargetSpecV1(
    bar_interval="5m",
    horizon_bars=2,
    upper_barrier_bps=50.0,
    lower_barrier_bps=30.0,
    round_trip_cost_bps=10.0,
)


def _target(
    bucket: int,
    *,
    symbol: str = "AAA",
    observed_buckets: int = 2,
    spec: TripleBarrierTargetSpecV1 = SPEC,
    label_as_of: datetime = LABEL_AS_OF,
) -> TripleBarrierTargetV1:
    as_of = BASE + timedelta(hours=bucket)
    observed_at = as_of + timedelta(hours=observed_buckets)
    reference_price = 100.0
    exit_price = 100.2
    gross_return = (exit_price / reference_price) - 1.0
    net_return = gross_return - spec.round_trip_cost_bps / 10_000.0
    source_bar_fingerprints = [
        canonical_fingerprint(
            {"symbol": symbol, "bucket": bucket, "source_bar": index}
        )
        for index in range(spec.horizon_bars)
    ]
    payload = {
        "target_version": "triple-barrier-target.v1",
        "symbol": symbol,
        "as_of": as_of.isoformat(),
        "label_as_of": label_as_of.isoformat(),
        "target_observed_at": observed_at.isoformat(),
        "bar_interval": spec.bar_interval,
        "horizon_bars": spec.horizon_bars,
        "observed_bar_count": spec.horizon_bars,
        "upper_barrier_bps": spec.upper_barrier_bps,
        "lower_barrier_bps": spec.lower_barrier_bps,
        "round_trip_cost_bps": spec.round_trip_cost_bps,
        "tie_policy": spec.tie_policy,
        "reference_price": reference_price,
        "upper_barrier_price": reference_price
        * (1.0 + spec.upper_barrier_bps / 10_000.0),
        "lower_barrier_price": reference_price
        * (1.0 - spec.lower_barrier_bps / 10_000.0),
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
        "target_spec_fingerprint": spec.fingerprint,
        "source_bar_fingerprints": source_bar_fingerprints,
        "status": "RESEARCH_TARGET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TripleBarrierTargetV1(
        **payload,
        target_fingerprint=canonical_fingerprint(payload),
    )


def _targets(count: int = 12) -> list[TripleBarrierTargetV1]:
    return [_target(bucket) for bucket in range(count)]


def _config(**overrides: int | None) -> PurgedWalkForwardConfigV1:
    values: dict[str, int | None] = {
        "min_train_decision_buckets": 3,
        "test_decision_buckets": 2,
        "step_decision_buckets": 2,
        "embargo_decision_buckets": None,
    }
    values.update(overrides)
    return PurgedWalkForwardConfigV1(**values)


def test_embargo_defaults_to_exact_target_horizon() -> None:
    plan = build_purged_walk_forward_plan(_targets(), config=_config())
    first = plan.folds[0]

    assert plan.horizon_bars == 2
    assert plan.effective_embargo_decision_buckets == 2
    assert len(first.embargo_decision_times) == 2
    assert first.training_decision_times == [
        BASE,
        BASE + timedelta(hours=1),
        BASE + timedelta(hours=2),
    ]
    assert first.embargo_decision_times == [
        BASE + timedelta(hours=3),
        BASE + timedelta(hours=4),
    ]
    assert first.test_decision_times == [
        BASE + timedelta(hours=5),
        BASE + timedelta(hours=6),
    ]
    assert plan.promotion_allowed is False
    assert plan.can_authorize_capital is False


def test_training_labels_all_terminate_before_test_start() -> None:
    targets = _targets()
    plan = build_purged_walk_forward_plan(targets, config=_config())
    by_fingerprint = {item.target_fingerprint: item for item in targets}

    for fold in plan.folds:
        assert all(
            by_fingerprint[fingerprint].target_observed_at < fold.test_start
            for fingerprint in fold.training_target_fingerprints
        )


def test_exact_target_observation_purges_irregular_overlap_beyond_bucket_embargo() -> None:
    targets = _targets(9)
    targets[1] = _target(1, observed_buckets=5)
    plan = build_purged_walk_forward_plan(
        targets,
        config=_config(min_train_decision_buckets=2),
    )
    first = plan.folds[0]

    assert targets[1].target_fingerprint in first.purged_target_fingerprints
    assert targets[1].target_fingerprint not in first.training_target_fingerprints
    assert first.candidate_training_target_count == 2
    assert first.training_target_count == 1
    assert first.purged_target_count == 1


def test_embargo_targets_never_appear_in_training_or_test() -> None:
    plan = build_purged_walk_forward_plan(_targets(), config=_config())

    for fold in plan.folds:
        embargo = set(fold.embargo_target_fingerprints)
        assert embargo.isdisjoint(fold.training_target_fingerprints)
        assert embargo.isdisjoint(fold.test_target_fingerprints)
        assert embargo.isdisjoint(fold.purged_target_fingerprints)


def test_walk_forward_folds_advance_chronologically_without_test_overlap() -> None:
    plan = build_purged_walk_forward_plan(_targets(14), config=_config())

    assert plan.fold_count >= 3
    for previous, current in zip(plan.folds, plan.folds[1:], strict=False):
        assert previous.test_end < current.test_start
        assert set(previous.test_decision_times).isdisjoint(current.test_decision_times)
        assert len(current.training_decision_times) > len(previous.training_decision_times)


def test_explicit_embargo_may_be_longer_than_target_horizon() -> None:
    plan = build_purged_walk_forward_plan(
        _targets(14),
        config=_config(embargo_decision_buckets=3),
    )

    assert plan.effective_embargo_decision_buckets == 3
    assert len(plan.folds[0].embargo_decision_times) == 3


def test_embargo_shorter_than_target_horizon_fails_closed() -> None:
    with pytest.raises(ValueError, match="shorter than target horizon"):
        build_purged_walk_forward_plan(
            _targets(),
            config=_config(embargo_decision_buckets=1),
        )


def test_mixed_target_spec_fails_closed() -> None:
    other_spec = TripleBarrierTargetSpecV1(
        bar_interval="5m",
        horizon_bars=3,
        upper_barrier_bps=50.0,
        lower_barrier_bps=30.0,
        round_trip_cost_bps=10.0,
    )
    targets = _targets()
    targets[-1] = _target(11, observed_buckets=3, spec=other_spec)

    with pytest.raises(ValueError, match="same target specification"):
        build_purged_walk_forward_plan(targets, config=_config())


def test_mixed_frozen_label_cutoff_fails_closed() -> None:
    targets = _targets()
    targets[-1] = _target(11, label_as_of=LABEL_AS_OF + timedelta(days=1))

    with pytest.raises(ValueError, match="same frozen label cutoff"):
        build_purged_walk_forward_plan(targets, config=_config())


def test_duplicate_symbol_and_decision_time_fails_closed() -> None:
    targets = _targets()
    targets.append(_target(0, observed_buckets=3))

    with pytest.raises(ValueError, match="unique by symbol and as_of"):
        build_purged_walk_forward_plan(targets, config=_config())


def test_input_order_cannot_change_plan_identity() -> None:
    targets = _targets(14)
    forward = build_purged_walk_forward_plan(targets, config=_config())
    reverse = build_purged_walk_forward_plan(
        list(reversed(targets)),
        config=_config(),
    )

    assert reverse.model_dump(mode="json") == forward.model_dump(mode="json")
    assert reverse.plan_fingerprint == forward.plan_fingerprint


def test_insufficient_decision_buckets_fail_closed() -> None:
    with pytest.raises(ValueError, match="not enough decision buckets"):
        build_purged_walk_forward_plan(_targets(6), config=_config())


def test_tampered_target_fingerprint_fails_closed() -> None:
    targets = _targets()
    targets[0] = targets[0].model_copy(update={"target_fingerprint": "0" * 64})

    with pytest.raises(ValidationError, match="target fingerprint mismatch"):
        build_purged_walk_forward_plan(targets, config=_config())


def test_tampered_plan_fingerprint_fails_schema_validation() -> None:
    plan = build_purged_walk_forward_plan(_targets(), config=_config())
    tampered = plan.model_dump(mode="python")
    tampered["plan_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="plan fingerprint mismatch"):
        PurgedWalkForwardPlanV1.model_validate(tampered)
