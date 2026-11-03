from datetime import UTC, datetime, timedelta
from math import comb

import pytest
from pydantic import ValidationError

from aitrading_intelligence.combinatorial_purged_cv import (
    CombinatorialPurgedCvConfigV1,
    CombinatorialPurgedCvPlanV1,
    build_combinatorial_purged_cv_plan,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
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


def _targets(count: int = 24) -> list[TripleBarrierTargetV1]:
    return [_target(bucket) for bucket in range(count)]


def _config(**overrides: int | None) -> CombinatorialPurgedCvConfigV1:
    values: dict[str, int | None] = {
        "n_groups": 4,
        "n_test_groups": 2,
        "min_group_decision_buckets": 4,
        "min_train_decision_buckets": 4,
        "embargo_decision_buckets": None,
        "max_splits": 100,
    }
    values.update(overrides)
    return CombinatorialPurgedCvConfigV1(**values)


def test_cpcv_enumerates_every_test_group_combination_once() -> None:
    plan = build_combinatorial_purged_cv_plan(_targets(), config=_config())

    assert plan.group_count == 4
    assert plan.split_count == comb(4, 2) == 6
    assert [split.test_group_indices for split in plan.splits] == [
        [0, 1],
        [0, 2],
        [0, 3],
        [1, 2],
        [1, 3],
        [2, 3],
    ]
    assert plan.effective_embargo_decision_buckets == SPEC.horizon_bars
    assert plan.promotion_allowed is False
    assert plan.can_authorize_capital is False


def test_groups_are_contiguous_balanced_and_partition_source_history() -> None:
    plan = build_combinatorial_purged_cv_plan(_targets(25), config=_config())

    assert [len(group.decision_times) for group in plan.groups] == [7, 6, 6, 6]
    assert [time for group in plan.groups for time in group.decision_times] == (
        plan.source_decision_times
    )
    for previous, current in zip(plan.groups, plan.groups[1:], strict=False):
        assert previous.end < current.start


def test_training_target_information_never_overlaps_any_selected_test_group() -> None:
    targets = _targets()
    plan = build_combinatorial_purged_cv_plan(targets, config=_config())
    by_fingerprint = {item.target_fingerprint: item for item in targets}

    for split in plan.splits:
        intervals = [
            (plan.groups[index].start, plan.groups[index].end)
            for index in split.test_group_indices
        ]
        for fingerprint in split.training_target_fingerprints:
            target = by_fingerprint[fingerprint]
            assert all(
                target.as_of > test_end or target.target_observed_at < test_start
                for test_start, test_end in intervals
            )


def test_exact_overlap_purge_handles_target_crossing_into_later_test_group() -> None:
    targets = _targets()
    targets[5] = _target(5, observed_buckets=4)
    plan = build_combinatorial_purged_cv_plan(
        targets,
        config=_config(n_test_groups=1),
    )
    split = next(item for item in plan.splits if item.test_group_indices == [1])

    assert targets[5].target_fingerprint in split.purged_target_fingerprints
    assert targets[5].target_fingerprint not in split.training_target_fingerprints
    assert split.candidate_training_target_count == (
        split.training_target_count + split.purged_target_count
    )


def test_post_test_embargo_is_removed_from_training_and_never_overwrites_test_role() -> None:
    plan = build_combinatorial_purged_cv_plan(
        _targets(),
        config=_config(n_test_groups=1),
    )
    first = next(item for item in plan.splits if item.test_group_indices == [0])

    assert first.embargo_decision_times == [
        BASE + timedelta(hours=6),
        BASE + timedelta(hours=7),
    ]
    assert set(first.embargo_decision_times).isdisjoint(first.training_decision_times)
    assert set(first.embargo_decision_times).isdisjoint(first.test_decision_times)
    assert set(first.embargo_target_fingerprints).isdisjoint(
        first.training_target_fingerprints
    )
    assert set(first.embargo_target_fingerprints).isdisjoint(first.test_target_fingerprints)


def test_embargo_that_lands_inside_another_selected_test_group_stays_test_evidence() -> None:
    plan = build_combinatorial_purged_cv_plan(_targets(), config=_config())
    adjacent = next(item for item in plan.splits if item.test_group_indices == [0, 1])
    second_group_times = set(plan.groups[1].decision_times)

    assert second_group_times.issubset(adjacent.test_decision_times)
    assert second_group_times.isdisjoint(adjacent.embargo_decision_times)


def test_embargo_shorter_than_target_horizon_fails_closed() -> None:
    with pytest.raises(ValueError, match="shorter than target horizon"):
        build_combinatorial_purged_cv_plan(
            _targets(),
            config=_config(embargo_decision_buckets=1),
        )


def test_thin_dataset_fails_instead_of_claiming_cpcv_support() -> None:
    with pytest.raises(ValueError, match="enough decision buckets"):
        build_combinatorial_purged_cv_plan(
            _targets(15),
            config=_config(min_group_decision_buckets=4),
        )


def test_insufficient_training_after_purge_embargo_fails_closed() -> None:
    with pytest.raises(ValueError, match="insufficient after purge/embargo"):
        build_combinatorial_purged_cv_plan(
            _targets(),
            config=_config(min_train_decision_buckets=20),
        )


def test_mixed_target_spec_and_frozen_cutoff_fail_closed() -> None:
    other_spec = TripleBarrierTargetSpecV1(
        bar_interval="5m",
        horizon_bars=3,
        upper_barrier_bps=50.0,
        lower_barrier_bps=30.0,
        round_trip_cost_bps=10.0,
    )
    mixed_spec = _targets()
    mixed_spec[-1] = _target(23, observed_buckets=3, spec=other_spec)
    with pytest.raises(ValueError, match="same target specification"):
        build_combinatorial_purged_cv_plan(mixed_spec, config=_config())

    mixed_cutoff = _targets()
    mixed_cutoff[-1] = _target(23, label_as_of=LABEL_AS_OF + timedelta(days=1))
    with pytest.raises(ValueError, match="same frozen label cutoff"):
        build_combinatorial_purged_cv_plan(mixed_cutoff, config=_config())


def test_duplicate_target_identity_fails_closed() -> None:
    targets = _targets()
    targets.append(_target(0))

    with pytest.raises(ValueError, match="unique by symbol and as_of"):
        build_combinatorial_purged_cv_plan(targets, config=_config())


def test_input_order_cannot_change_cpcv_plan_identity() -> None:
    targets = _targets(25)
    forward = build_combinatorial_purged_cv_plan(targets, config=_config())
    reverse = build_combinatorial_purged_cv_plan(
        list(reversed(targets)),
        config=_config(),
    )

    assert reverse.model_dump(mode="json") == forward.model_dump(mode="json")
    assert reverse.plan_fingerprint == forward.plan_fingerprint


def test_tampered_target_fingerprint_fails_closed() -> None:
    targets = _targets()
    targets[0] = targets[0].model_copy(update={"target_fingerprint": "0" * 64})

    with pytest.raises(ValidationError, match="target fingerprint mismatch"):
        build_combinatorial_purged_cv_plan(targets, config=_config())


def test_tampered_plan_fingerprint_fails_schema_validation() -> None:
    plan = build_combinatorial_purged_cv_plan(_targets(), config=_config())
    tampered = plan.model_dump(mode="python")
    tampered["plan_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="plan fingerprint mismatch"):
        CombinatorialPurgedCvPlanV1.model_validate(tampered)


def test_combinatorial_explosion_is_bounded_by_policy() -> None:
    with pytest.raises(ValueError, match="exceeds max_splits"):
        CombinatorialPurgedCvConfigV1(
            n_groups=20,
            n_test_groups=10,
            max_splits=5_000,
        )
