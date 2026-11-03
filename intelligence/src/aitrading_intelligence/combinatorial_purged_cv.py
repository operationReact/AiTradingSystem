from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from itertools import combinations
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .triple_barrier_target import TripleBarrierTargetV1

CPCV_VERSION = "combinatorial-purged-cv.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
BarInterval = Literal["1m", "3m", "5m", "10m", "15m", "30m", "1h", "1d"]


class CombinatorialPurgedCvConfigV1(BaseModel):
    """Fail-closed CPCV policy for sufficiently deep chronological target archives."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["combinatorial-purged-cv.v1"] = CPCV_VERSION
    n_groups: Annotated[int, Field(ge=3, le=20)] = 6
    n_test_groups: Annotated[int, Field(ge=1, le=10)] = 2
    min_group_decision_buckets: Annotated[int, Field(ge=1, le=10_000)] = 5
    min_train_decision_buckets: Annotated[int, Field(ge=1, le=100_000)] = 20
    embargo_decision_buckets: Annotated[int | None, Field(ge=1, le=10_000)] = None
    max_splits: Annotated[int, Field(ge=1, le=10_000)] = 5_000

    @model_validator(mode="after")
    def validate_combinatorics(self) -> CombinatorialPurgedCvConfigV1:
        if self.n_test_groups >= self.n_groups:
            raise ValueError("n_test_groups must be smaller than n_groups")
        split_count = math.comb(self.n_groups, self.n_test_groups)
        if split_count > self.max_splits:
            raise ValueError("configured CPCV split count exceeds max_splits")
        return self

    @property
    def expected_split_count(self) -> int:
        return math.comb(self.n_groups, self.n_test_groups)

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CombinatorialPurgedCvGroupV1(BaseModel):
    """One immutable contiguous chronological group used by every CPCV split."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["combinatorial-purged-cv.v1"] = CPCV_VERSION
    group_index: Annotated[int, Field(ge=0)]
    start: datetime
    end: datetime
    decision_times: list[datetime] = Field(min_length=1)
    target_fingerprints: list[Hash64] = Field(min_length=1)
    target_count: Annotated[int, Field(ge=1)]
    group_fingerprint: Hash64

    @field_validator("start", "end")
    @classmethod
    def boundaries_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("CPCV group timestamps must be timezone-aware")
        return value

    @field_validator("decision_times")
    @classmethod
    def decision_times_must_be_aware(cls, values: list[datetime]) -> list[datetime]:
        if any(value.tzinfo is None or value.utcoffset() is None for value in values):
            raise ValueError("CPCV decision timestamps must be timezone-aware")
        return values

    @model_validator(mode="after")
    def validate_group(self) -> CombinatorialPurgedCvGroupV1:
        if self.decision_times != sorted(set(self.decision_times)):
            raise ValueError("CPCV group decision times must be unique and chronological")
        if self.start != self.decision_times[0] or self.end != self.decision_times[-1]:
            raise ValueError("CPCV group boundaries must match decision times")
        if self.target_count != len(self.target_fingerprints):
            raise ValueError("CPCV group target_count mismatch")
        if len(set(self.target_fingerprints)) != len(self.target_fingerprints):
            raise ValueError("CPCV group target fingerprints must be unique")
        if self.group_fingerprint != canonical_fingerprint(_group_payload(self)):
            raise ValueError("CPCV group fingerprint mismatch")
        return self


class CombinatorialPurgedCvSplitV1(BaseModel):
    """One combinatorial test selection with exact purge and embargo evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["combinatorial-purged-cv.v1"] = CPCV_VERSION
    split_index: Annotated[int, Field(ge=0)]
    test_group_indices: list[Annotated[int, Field(ge=0)]] = Field(min_length=1)
    training_decision_times: list[datetime] = Field(min_length=1)
    embargo_decision_times: list[datetime]
    test_decision_times: list[datetime] = Field(min_length=1)
    training_target_fingerprints: list[Hash64] = Field(min_length=1)
    embargo_target_fingerprints: list[Hash64]
    purged_target_fingerprints: list[Hash64]
    test_target_fingerprints: list[Hash64] = Field(min_length=1)
    candidate_training_target_count: Annotated[int, Field(ge=1)]
    training_target_count: Annotated[int, Field(ge=1)]
    embargo_target_count: Annotated[int, Field(ge=0)]
    purged_target_count: Annotated[int, Field(ge=0)]
    test_target_count: Annotated[int, Field(ge=1)]
    split_fingerprint: Hash64
    status: Literal["RESEARCH_CPCV_SPLIT_ONLY"] = "RESEARCH_CPCV_SPLIT_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("training_decision_times", "embargo_decision_times", "test_decision_times")
    @classmethod
    def split_times_must_be_aware(cls, values: list[datetime]) -> list[datetime]:
        if any(value.tzinfo is None or value.utcoffset() is None for value in values):
            raise ValueError("CPCV split timestamps must be timezone-aware")
        return values

    @model_validator(mode="after")
    def validate_split(self) -> CombinatorialPurgedCvSplitV1:
        if self.test_group_indices != sorted(set(self.test_group_indices)):
            raise ValueError("CPCV test group indices must be unique and sorted")
        for values, label in (
            (self.training_decision_times, "training"),
            (self.embargo_decision_times, "embargo"),
            (self.test_decision_times, "test"),
        ):
            if values != sorted(set(values)):
                raise ValueError(f"CPCV {label} decision times must be unique and chronological")
        time_roles = [
            set(self.training_decision_times),
            set(self.embargo_decision_times),
            set(self.test_decision_times),
        ]
        for index, left in enumerate(time_roles):
            for right in time_roles[index + 1 :]:
                if left & right:
                    raise ValueError("a decision time cannot occupy multiple CPCV split roles")

        fingerprint_roles = [
            set(self.training_target_fingerprints),
            set(self.embargo_target_fingerprints),
            set(self.purged_target_fingerprints),
            set(self.test_target_fingerprints),
        ]
        for values, label in (
            (self.training_target_fingerprints, "training"),
            (self.embargo_target_fingerprints, "embargo"),
            (self.purged_target_fingerprints, "purged"),
            (self.test_target_fingerprints, "test"),
        ):
            if len(set(values)) != len(values):
                raise ValueError(f"CPCV {label} target fingerprints must be unique")
        for index, left in enumerate(fingerprint_roles):
            for right in fingerprint_roles[index + 1 :]:
                if left & right:
                    raise ValueError("a target cannot occupy multiple CPCV split roles")

        if self.training_target_count != len(self.training_target_fingerprints):
            raise ValueError("CPCV training_target_count mismatch")
        if self.embargo_target_count != len(self.embargo_target_fingerprints):
            raise ValueError("CPCV embargo_target_count mismatch")
        if self.purged_target_count != len(self.purged_target_fingerprints):
            raise ValueError("CPCV purged_target_count mismatch")
        if self.test_target_count != len(self.test_target_fingerprints):
            raise ValueError("CPCV test_target_count mismatch")
        if self.candidate_training_target_count != (
            self.training_target_count + self.purged_target_count
        ):
            raise ValueError("CPCV candidate training count must equal retained plus purged")
        if self.split_fingerprint != canonical_fingerprint(_split_payload(self)):
            raise ValueError("CPCV split fingerprint mismatch")
        return self


class CombinatorialPurgedCvPlanV1(BaseModel):
    """Auditable CPCV plan over one frozen triple-barrier target archive."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["combinatorial-purged-cv.v1"] = CPCV_VERSION
    config: CombinatorialPurgedCvConfigV1
    target_spec_fingerprint: Hash64
    label_as_of: datetime
    bar_interval: BarInterval
    horizon_bars: Annotated[int, Field(ge=1, le=390)]
    effective_embargo_decision_buckets: Annotated[int, Field(ge=1, le=10_000)]
    source_target_count: Annotated[int, Field(ge=1)]
    source_decision_bucket_count: Annotated[int, Field(ge=1)]
    source_target_fingerprints: list[Hash64] = Field(min_length=1)
    source_decision_times: list[datetime] = Field(min_length=1)
    group_count: Annotated[int, Field(ge=3)]
    groups: list[CombinatorialPurgedCvGroupV1] = Field(min_length=3)
    split_count: Annotated[int, Field(ge=1)]
    splits: list[CombinatorialPurgedCvSplitV1] = Field(min_length=1)
    plan_fingerprint: Hash64
    status: Literal["RESEARCH_CPCV_ONLY"] = "RESEARCH_CPCV_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("label_as_of")
    @classmethod
    def label_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("CPCV label_as_of must be timezone-aware")
        return value

    @field_validator("source_decision_times")
    @classmethod
    def source_times_must_be_aware(cls, values: list[datetime]) -> list[datetime]:
        if any(value.tzinfo is None or value.utcoffset() is None for value in values):
            raise ValueError("CPCV source decision timestamps must be timezone-aware")
        return values

    @model_validator(mode="after")
    def validate_plan(self) -> CombinatorialPurgedCvPlanV1:
        expected_embargo = (
            self.config.embargo_decision_buckets
            if self.config.embargo_decision_buckets is not None
            else self.horizon_bars
        )
        if self.effective_embargo_decision_buckets != expected_embargo:
            raise ValueError("CPCV effective embargo does not match config/target horizon")
        if self.effective_embargo_decision_buckets < self.horizon_bars:
            raise ValueError("CPCV embargo cannot be shorter than target horizon")
        if self.source_target_count != len(self.source_target_fingerprints):
            raise ValueError("CPCV source_target_count mismatch")
        if len(set(self.source_target_fingerprints)) != len(self.source_target_fingerprints):
            raise ValueError("CPCV source target fingerprints must be unique")
        if self.source_decision_times != sorted(set(self.source_decision_times)):
            raise ValueError("CPCV source decision times must be unique and chronological")
        if self.source_decision_bucket_count != len(self.source_decision_times):
            raise ValueError("CPCV source_decision_bucket_count mismatch")
        if self.group_count != self.config.n_groups or self.group_count != len(self.groups):
            raise ValueError("CPCV group count must match configuration")
        if [group.group_index for group in self.groups] != list(range(self.group_count)):
            raise ValueError("CPCV group indices must be contiguous")
        if any(
            self.groups[index].end >= self.groups[index + 1].start
            for index in range(len(self.groups) - 1)
        ):
            raise ValueError("CPCV groups must be chronological and non-overlapping")
        grouped_times = [time for group in self.groups for time in group.decision_times]
        if grouped_times != self.source_decision_times:
            raise ValueError("CPCV groups must partition all source decision times exactly once")
        grouped_targets = [
            fingerprint
            for group in self.groups
            for fingerprint in group.target_fingerprints
        ]
        if sorted(grouped_targets) != sorted(self.source_target_fingerprints):
            raise ValueError("CPCV groups must partition all source targets exactly once")
        if self.split_count != self.config.expected_split_count:
            raise ValueError("CPCV split count must equal the configured combination count")
        if self.split_count != len(self.splits):
            raise ValueError("CPCV split_count mismatch")
        if [split.split_index for split in self.splits] != list(range(self.split_count)):
            raise ValueError("CPCV split indices must be contiguous")
        expected_combinations = [
            list(item)
            for item in combinations(range(self.config.n_groups), self.config.n_test_groups)
        ]
        if [split.test_group_indices for split in self.splits] != expected_combinations:
            raise ValueError("CPCV splits must enumerate each test-group combination exactly once")
        if self.plan_fingerprint != canonical_fingerprint(_plan_payload(self)):
            raise ValueError("CPCV plan fingerprint mismatch")
        return self


def build_combinatorial_purged_cv_plan(
    targets: Sequence[TripleBarrierTargetV1],
    *,
    config: CombinatorialPurgedCvConfigV1 | None = None,
) -> CombinatorialPurgedCvPlanV1:
    """Build every configured CPCV combination with exact overlap purge and embargo."""

    cfg = config or CombinatorialPurgedCvConfigV1()
    checked = [
        TripleBarrierTargetV1.model_validate(item.model_dump(mode="python"))
        for item in targets
    ]
    if not checked:
        raise ValueError("triple-barrier targets are required for CPCV")
    if len({(item.symbol, item.as_of) for item in checked}) != len(checked):
        raise ValueError("CPCV targets must be unique by symbol and as_of")
    if len({item.target_fingerprint for item in checked}) != len(checked):
        raise ValueError("CPCV target fingerprints must be unique")
    if len({item.target_spec_fingerprint for item in checked}) != 1:
        raise ValueError("all CPCV targets must use the same target specification")
    if len({item.bar_interval for item in checked}) != 1:
        raise ValueError("all CPCV targets must use the same bar interval")
    if len({item.horizon_bars for item in checked}) != 1:
        raise ValueError("all CPCV targets must use the same target horizon")
    if len({item.label_as_of for item in checked}) != 1:
        raise ValueError("all CPCV targets must use the same frozen label cutoff")

    ordered = sorted(
        checked,
        key=lambda item: (item.as_of, item.symbol, item.target_fingerprint),
    )
    decision_times = sorted({item.as_of for item in ordered})
    horizon_bars = ordered[0].horizon_bars
    embargo = (
        cfg.embargo_decision_buckets
        if cfg.embargo_decision_buckets is not None
        else horizon_bars
    )
    if embargo < horizon_bars:
        raise ValueError("CPCV embargo_decision_buckets cannot be shorter than target horizon")
    minimum_decisions = cfg.n_groups * cfg.min_group_decision_buckets
    if len(decision_times) < minimum_decisions:
        raise ValueError(
            "CPCV requires enough decision buckets to populate every chronological group"
        )

    by_time: dict[datetime, list[TripleBarrierTargetV1]] = {}
    for item in ordered:
        by_time.setdefault(item.as_of, []).append(item)

    group_times = _partition_decision_times(decision_times, cfg.n_groups)
    if any(len(values) < cfg.min_group_decision_buckets for values in group_times):
        raise ValueError("CPCV chronological groups are below min_group_decision_buckets")

    groups: list[CombinatorialPurgedCvGroupV1] = []
    for group_index, times in enumerate(group_times):
        group_targets = [item for timestamp in times for item in by_time[timestamp]]
        payload = {
            "split_version": CPCV_VERSION,
            "group_index": group_index,
            "start": times[0].isoformat(),
            "end": times[-1].isoformat(),
            "decision_times": [item.isoformat() for item in times],
            "target_fingerprints": [item.target_fingerprint for item in group_targets],
            "target_count": len(group_targets),
        }
        groups.append(
            CombinatorialPurgedCvGroupV1(
                **payload,
                group_fingerprint=canonical_fingerprint(payload),
            )
        )

    time_index = {timestamp: index for index, timestamp in enumerate(decision_times)}
    split_models: list[CombinatorialPurgedCvSplitV1] = []
    for split_index, test_indices_tuple in enumerate(
        combinations(range(cfg.n_groups), cfg.n_test_groups)
    ):
        test_indices = list(test_indices_tuple)
        test_times = sorted(
            timestamp
            for group_index in test_indices
            for timestamp in group_times[group_index]
        )
        test_time_set = set(test_times)
        embargo_time_set: set[datetime] = set()
        for group_index in test_indices:
            group_end_index = time_index[group_times[group_index][-1]]
            embargo_time_set.update(
                decision_times[group_end_index + 1 : group_end_index + 1 + embargo]
            )
        embargo_time_set.difference_update(test_time_set)
        embargo_times = sorted(embargo_time_set)

        candidate_times = [
            timestamp
            for timestamp in decision_times
            if timestamp not in test_time_set and timestamp not in embargo_time_set
        ]
        candidate_targets = [
            item for timestamp in candidate_times for item in by_time[timestamp]
        ]
        test_intervals = [
            (group_times[group_index][0], group_times[group_index][-1])
            for group_index in test_indices
        ]
        purged = [
            item
            for item in candidate_targets
            if any(
                item.as_of <= test_end and item.target_observed_at >= test_start
                for test_start, test_end in test_intervals
            )
        ]
        purged_fingerprints = {item.target_fingerprint for item in purged}
        training = [
            item
            for item in candidate_targets
            if item.target_fingerprint not in purged_fingerprints
        ]
        training_times = sorted({item.as_of for item in training})
        if len(training_times) < cfg.min_train_decision_buckets:
            raise ValueError(
                "CPCV data volume is insufficient after purge/embargo for every combination"
            )

        embargo_targets = [
            item for timestamp in embargo_times for item in by_time[timestamp]
        ]
        test_targets = [item for timestamp in test_times for item in by_time[timestamp]]
        payload = {
            "split_version": CPCV_VERSION,
            "split_index": split_index,
            "test_group_indices": test_indices,
            "training_decision_times": [item.isoformat() for item in training_times],
            "embargo_decision_times": [item.isoformat() for item in embargo_times],
            "test_decision_times": [item.isoformat() for item in test_times],
            "training_target_fingerprints": [
                item.target_fingerprint for item in training
            ],
            "embargo_target_fingerprints": [
                item.target_fingerprint for item in embargo_targets
            ],
            "purged_target_fingerprints": [
                item.target_fingerprint for item in purged
            ],
            "test_target_fingerprints": [
                item.target_fingerprint for item in test_targets
            ],
            "candidate_training_target_count": len(candidate_targets),
            "training_target_count": len(training),
            "embargo_target_count": len(embargo_targets),
            "purged_target_count": len(purged),
            "test_target_count": len(test_targets),
            "status": "RESEARCH_CPCV_SPLIT_ONLY",
            "promotion_allowed": False,
            "can_authorize_capital": False,
        }
        split_models.append(
            CombinatorialPurgedCvSplitV1(
                **payload,
                split_fingerprint=canonical_fingerprint(payload),
            )
        )

    plan_payload = {
        "split_version": CPCV_VERSION,
        "config": cfg.model_dump(mode="json"),
        "target_spec_fingerprint": ordered[0].target_spec_fingerprint,
        "label_as_of": ordered[0].label_as_of.isoformat(),
        "bar_interval": ordered[0].bar_interval,
        "horizon_bars": horizon_bars,
        "effective_embargo_decision_buckets": embargo,
        "source_target_count": len(ordered),
        "source_decision_bucket_count": len(decision_times),
        "source_target_fingerprints": [item.target_fingerprint for item in ordered],
        "source_decision_times": [item.isoformat() for item in decision_times],
        "group_count": len(groups),
        "groups": [group.model_dump(mode="json") for group in groups],
        "split_count": len(split_models),
        "splits": [split.model_dump(mode="json") for split in split_models],
        "status": "RESEARCH_CPCV_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CombinatorialPurgedCvPlanV1(
        **plan_payload,
        plan_fingerprint=canonical_fingerprint(plan_payload),
    )


def _partition_decision_times(
    decision_times: list[datetime],
    n_groups: int,
) -> list[list[datetime]]:
    base_size, remainder = divmod(len(decision_times), n_groups)
    groups: list[list[datetime]] = []
    offset = 0
    for group_index in range(n_groups):
        size = base_size + (1 if group_index < remainder else 0)
        groups.append(decision_times[offset : offset + size])
        offset += size
    return groups


def _group_payload(group: CombinatorialPurgedCvGroupV1) -> dict[str, object]:
    return {
        "split_version": group.split_version,
        "group_index": group.group_index,
        "start": group.start.isoformat(),
        "end": group.end.isoformat(),
        "decision_times": [item.isoformat() for item in group.decision_times],
        "target_fingerprints": group.target_fingerprints,
        "target_count": group.target_count,
    }


def _split_payload(split: CombinatorialPurgedCvSplitV1) -> dict[str, object]:
    return {
        "split_version": split.split_version,
        "split_index": split.split_index,
        "test_group_indices": split.test_group_indices,
        "training_decision_times": [
            item.isoformat() for item in split.training_decision_times
        ],
        "embargo_decision_times": [
            item.isoformat() for item in split.embargo_decision_times
        ],
        "test_decision_times": [item.isoformat() for item in split.test_decision_times],
        "training_target_fingerprints": split.training_target_fingerprints,
        "embargo_target_fingerprints": split.embargo_target_fingerprints,
        "purged_target_fingerprints": split.purged_target_fingerprints,
        "test_target_fingerprints": split.test_target_fingerprints,
        "candidate_training_target_count": split.candidate_training_target_count,
        "training_target_count": split.training_target_count,
        "embargo_target_count": split.embargo_target_count,
        "purged_target_count": split.purged_target_count,
        "test_target_count": split.test_target_count,
        "status": split.status,
        "promotion_allowed": split.promotion_allowed,
        "can_authorize_capital": split.can_authorize_capital,
    }


def _plan_payload(plan: CombinatorialPurgedCvPlanV1) -> dict[str, object]:
    return {
        "split_version": plan.split_version,
        "config": plan.config.model_dump(mode="json"),
        "target_spec_fingerprint": plan.target_spec_fingerprint,
        "label_as_of": plan.label_as_of.isoformat(),
        "bar_interval": plan.bar_interval,
        "horizon_bars": plan.horizon_bars,
        "effective_embargo_decision_buckets": plan.effective_embargo_decision_buckets,
        "source_target_count": plan.source_target_count,
        "source_decision_bucket_count": plan.source_decision_bucket_count,
        "source_target_fingerprints": plan.source_target_fingerprints,
        "source_decision_times": [item.isoformat() for item in plan.source_decision_times],
        "group_count": plan.group_count,
        "groups": [group.model_dump(mode="json") for group in plan.groups],
        "split_count": plan.split_count,
        "splits": [split.model_dump(mode="json") for split in plan.splits],
        "status": plan.status,
        "promotion_allowed": plan.promotion_allowed,
        "can_authorize_capital": plan.can_authorize_capital,
    }
