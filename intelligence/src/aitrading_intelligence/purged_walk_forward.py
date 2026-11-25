from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .triple_barrier_target import TripleBarrierTargetV1

PURGED_WALK_FORWARD_VERSION = "purged-walk-forward.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
BarInterval = Literal["1m", "3m", "5m", "10m", "15m", "30m", "1h", "1d"]


class PurgedWalkForwardConfigV1(BaseModel):
    """Chronological split policy with a target-horizon-aligned embargo."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["purged-walk-forward.v1"] = PURGED_WALK_FORWARD_VERSION
    min_train_decision_buckets: Annotated[int, Field(ge=1, le=100_000)] = 20
    test_decision_buckets: Annotated[int, Field(ge=1, le=10_000)] = 10
    step_decision_buckets: Annotated[int, Field(ge=1, le=10_000)] = 10
    embargo_decision_buckets: Annotated[int | None, Field(ge=1, le=10_000)] = None

    @model_validator(mode="after")
    def non_overlapping_test_windows(self) -> PurgedWalkForwardConfigV1:
        if self.step_decision_buckets < self.test_decision_buckets:
            raise ValueError("step_decision_buckets must be >= test_decision_buckets")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PurgedWalkForwardFoldV1(BaseModel):
    """One auditable train/embargo/test fold over exact target fingerprints."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["purged-walk-forward.v1"] = PURGED_WALK_FORWARD_VERSION
    fold_index: Annotated[int, Field(ge=0)]
    test_start: datetime
    test_end: datetime
    training_decision_times: list[datetime] = Field(min_length=1)
    embargo_decision_times: list[datetime] = Field(min_length=1)
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
    fold_fingerprint: Hash64
    status: Literal["RESEARCH_PURGED_SPLIT_ONLY"] = "RESEARCH_PURGED_SPLIT_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("test_start", "test_end")
    @classmethod
    def boundary_timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("walk-forward timestamps must be timezone-aware")
        return value

    @field_validator(
        "training_decision_times",
        "embargo_decision_times",
        "test_decision_times",
    )
    @classmethod
    def decision_times_must_be_aware(cls, values: list[datetime]) -> list[datetime]:
        if any(value.tzinfo is None or value.utcoffset() is None for value in values):
            raise ValueError("decision timestamps must be timezone-aware")
        return values

    @model_validator(mode="after")
    def validate_fold(self) -> PurgedWalkForwardFoldV1:
        if self.test_end < self.test_start:
            raise ValueError("test_end cannot precede test_start")
        if self.training_decision_times != sorted(set(self.training_decision_times)):
            raise ValueError("training decision times must be unique and chronological")
        if self.embargo_decision_times != sorted(set(self.embargo_decision_times)):
            raise ValueError("embargo decision times must be unique and chronological")
        if self.test_decision_times != sorted(set(self.test_decision_times)):
            raise ValueError("test decision times must be unique and chronological")
        if self.test_start != self.test_decision_times[0]:
            raise ValueError("test_start must equal the first test decision time")
        if self.test_end != self.test_decision_times[-1]:
            raise ValueError("test_end must equal the final test decision time")
        if self.training_decision_times[-1] >= self.embargo_decision_times[0]:
            raise ValueError("training decision times must precede embargo")
        if self.embargo_decision_times[-1] >= self.test_start:
            raise ValueError("embargo decision times must precede test start")
        if len(set(self.training_target_fingerprints)) != len(
            self.training_target_fingerprints
        ):
            raise ValueError("training target fingerprints must be unique")
        if len(set(self.embargo_target_fingerprints)) != len(
            self.embargo_target_fingerprints
        ):
            raise ValueError("embargo target fingerprints must be unique")
        if len(set(self.purged_target_fingerprints)) != len(
            self.purged_target_fingerprints
        ):
            raise ValueError("purged target fingerprints must be unique")
        if len(set(self.test_target_fingerprints)) != len(self.test_target_fingerprints):
            raise ValueError("test target fingerprints must be unique")
        roles = [
            set(self.training_target_fingerprints),
            set(self.embargo_target_fingerprints),
            set(self.purged_target_fingerprints),
            set(self.test_target_fingerprints),
        ]
        for index, left in enumerate(roles):
            for right in roles[index + 1 :]:
                if left & right:
                    raise ValueError("a target cannot occupy multiple fold roles")
        if self.training_target_count != len(self.training_target_fingerprints):
            raise ValueError("training_target_count mismatch")
        if self.embargo_target_count != len(self.embargo_target_fingerprints):
            raise ValueError("embargo_target_count mismatch")
        if self.purged_target_count != len(self.purged_target_fingerprints):
            raise ValueError("purged_target_count mismatch")
        if self.test_target_count != len(self.test_target_fingerprints):
            raise ValueError("test_target_count mismatch")
        if self.candidate_training_target_count != (
            self.training_target_count + self.purged_target_count
        ):
            raise ValueError("candidate training count must equal retained plus purged")
        if self.fold_fingerprint != canonical_fingerprint(_fold_payload(self)):
            raise ValueError("purged walk-forward fold fingerprint mismatch")
        return self


class PurgedWalkForwardPlanV1(BaseModel):
    """Immutable V3 validation split plan over one frozen triple-barrier archive."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    split_version: Literal["purged-walk-forward.v1"] = PURGED_WALK_FORWARD_VERSION
    config: PurgedWalkForwardConfigV1
    target_spec_fingerprint: Hash64
    label_as_of: datetime
    bar_interval: BarInterval
    horizon_bars: Annotated[int, Field(ge=1, le=390)]
    effective_embargo_decision_buckets: Annotated[int, Field(ge=1, le=10_000)]
    source_target_count: Annotated[int, Field(ge=1)]
    source_decision_bucket_count: Annotated[int, Field(ge=1)]
    source_target_fingerprints: list[Hash64] = Field(min_length=1)
    source_decision_times: list[datetime] = Field(min_length=1)
    fold_count: Annotated[int, Field(ge=1)]
    folds: list[PurgedWalkForwardFoldV1] = Field(min_length=1)
    plan_fingerprint: Hash64
    status: Literal["RESEARCH_PURGED_WALK_FORWARD_ONLY"] = (
        "RESEARCH_PURGED_WALK_FORWARD_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("label_as_of")
    @classmethod
    def label_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("label_as_of must be timezone-aware")
        return value

    @field_validator("source_decision_times")
    @classmethod
    def source_times_must_be_aware(cls, values: list[datetime]) -> list[datetime]:
        if any(value.tzinfo is None or value.utcoffset() is None for value in values):
            raise ValueError("source decision timestamps must be timezone-aware")
        return values

    @model_validator(mode="after")
    def validate_plan(self) -> PurgedWalkForwardPlanV1:
        expected_embargo = (
            self.config.embargo_decision_buckets
            if self.config.embargo_decision_buckets is not None
            else self.horizon_bars
        )
        if self.effective_embargo_decision_buckets != expected_embargo:
            raise ValueError("effective embargo does not match config/target horizon")
        if self.effective_embargo_decision_buckets < self.horizon_bars:
            raise ValueError("embargo cannot be shorter than target horizon")
        if self.source_target_count != len(self.source_target_fingerprints):
            raise ValueError("source_target_count mismatch")
        if len(set(self.source_target_fingerprints)) != len(
            self.source_target_fingerprints
        ):
            raise ValueError("source target fingerprints must be unique")
        if self.source_decision_times != sorted(set(self.source_decision_times)):
            raise ValueError("source decision times must be unique and chronological")
        if self.source_decision_bucket_count != len(self.source_decision_times):
            raise ValueError("source_decision_bucket_count mismatch")
        if self.fold_count != len(self.folds):
            raise ValueError("fold_count mismatch")
        if [fold.fold_index for fold in self.folds] != list(range(self.fold_count)):
            raise ValueError("fold indices must be contiguous")
        if any(
            fold.fold_fingerprint != canonical_fingerprint(_fold_payload(fold))
            for fold in self.folds
        ):
            raise ValueError("fold fingerprint mismatch")
        if any(
            self.folds[index].test_start >= self.folds[index + 1].test_start
            for index in range(len(self.folds) - 1)
        ):
            raise ValueError("walk-forward test windows must advance chronologically")
        if self.plan_fingerprint != canonical_fingerprint(_plan_payload(self)):
            raise ValueError("purged walk-forward plan fingerprint mismatch")
        return self


def build_purged_walk_forward_plan(
    targets: Sequence[TripleBarrierTargetV1],
    *,
    config: PurgedWalkForwardConfigV1 | None = None,
) -> PurgedWalkForwardPlanV1:
    """Build expanding purged walk-forward folds from frozen V3 target evidence."""

    cfg = config or PurgedWalkForwardConfigV1()
    checked = [
        TripleBarrierTargetV1.model_validate(item.model_dump(mode="python"))
        for item in targets
    ]
    if not checked:
        raise ValueError("triple-barrier targets are required")
    if len({(item.symbol, item.as_of) for item in checked}) != len(checked):
        raise ValueError("targets must be unique by symbol and as_of")
    if len({item.target_fingerprint for item in checked}) != len(checked):
        raise ValueError("target fingerprints must be unique")
    if len({item.target_spec_fingerprint for item in checked}) != 1:
        raise ValueError("all targets must use the same target specification")
    if len({item.bar_interval for item in checked}) != 1:
        raise ValueError("all targets must use the same bar interval")
    if len({item.horizon_bars for item in checked}) != 1:
        raise ValueError("all targets must use the same target horizon")
    if len({item.label_as_of for item in checked}) != 1:
        raise ValueError("all targets must use the same frozen label cutoff")

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
        raise ValueError("embargo_decision_buckets cannot be shorter than target horizon")

    minimum_required = (
        cfg.min_train_decision_buckets + embargo + cfg.test_decision_buckets
    )
    if len(decision_times) < minimum_required:
        raise ValueError(
            "not enough decision buckets for train + target-horizon embargo + test"
        )

    by_time: dict[datetime, list[TripleBarrierTargetV1]] = {}
    for item in ordered:
        by_time.setdefault(item.as_of, []).append(item)

    folds: list[PurgedWalkForwardFoldV1] = []
    test_start_index = cfg.min_train_decision_buckets + embargo
    while test_start_index + cfg.test_decision_buckets <= len(decision_times):
        embargo_start_index = test_start_index - embargo
        train_times = decision_times[:embargo_start_index]
        embargo_times = decision_times[embargo_start_index:test_start_index]
        test_times = decision_times[
            test_start_index : test_start_index + cfg.test_decision_buckets
        ]
        test_start = test_times[0]

        candidate_training = [
            item for timestamp in train_times for item in by_time[timestamp]
        ]
        purged = [
            item for item in candidate_training if item.target_observed_at >= test_start
        ]
        purged_fingerprints = {item.target_fingerprint for item in purged}
        training = [
            item
            for item in candidate_training
            if item.target_fingerprint not in purged_fingerprints
        ]
        if len({item.as_of for item in training}) < cfg.min_train_decision_buckets:
            test_start_index += cfg.step_decision_buckets
            continue

        embargo_targets = [
            item for timestamp in embargo_times for item in by_time[timestamp]
        ]
        test_targets = [item for timestamp in test_times for item in by_time[timestamp]]
        fold_payload = {
            "split_version": PURGED_WALK_FORWARD_VERSION,
            "fold_index": len(folds),
            "test_start": test_start.isoformat(),
            "test_end": test_times[-1].isoformat(),
            "training_decision_times": [item.isoformat() for item in train_times],
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
            "candidate_training_target_count": len(candidate_training),
            "training_target_count": len(training),
            "embargo_target_count": len(embargo_targets),
            "purged_target_count": len(purged),
            "test_target_count": len(test_targets),
            "status": "RESEARCH_PURGED_SPLIT_ONLY",
            "promotion_allowed": False,
            "can_authorize_capital": False,
        }
        folds.append(
            PurgedWalkForwardFoldV1(
                **fold_payload,
                fold_fingerprint=canonical_fingerprint(fold_payload),
            )
        )
        test_start_index += cfg.step_decision_buckets

    if not folds:
        raise ValueError("purge and history requirements produced no valid folds")

    plan_payload = {
        "split_version": PURGED_WALK_FORWARD_VERSION,
        "config": cfg.model_dump(mode="json"),
        "target_spec_fingerprint": ordered[0].target_spec_fingerprint,
        "label_as_of": ordered[0].label_as_of.isoformat(),
        "bar_interval": ordered[0].bar_interval,
        "horizon_bars": horizon_bars,
        "effective_embargo_decision_buckets": embargo,
        "source_target_count": len(ordered),
        "source_decision_bucket_count": len(decision_times),
        "source_target_fingerprints": [
            item.target_fingerprint for item in ordered
        ],
        "source_decision_times": [item.isoformat() for item in decision_times],
        "fold_count": len(folds),
        "folds": [fold.model_dump(mode="json") for fold in folds],
        "status": "RESEARCH_PURGED_WALK_FORWARD_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return PurgedWalkForwardPlanV1(
        **plan_payload,
        plan_fingerprint=canonical_fingerprint(plan_payload),
    )


def _fold_payload(fold: PurgedWalkForwardFoldV1) -> dict[str, object]:
    payload = fold.model_dump(mode="json", exclude={"fold_fingerprint"})
    payload["test_start"] = fold.test_start.isoformat()
    payload["test_end"] = fold.test_end.isoformat()
    payload["training_decision_times"] = [
        item.isoformat() for item in fold.training_decision_times
    ]
    payload["embargo_decision_times"] = [
        item.isoformat() for item in fold.embargo_decision_times
    ]
    payload["test_decision_times"] = [
        item.isoformat() for item in fold.test_decision_times
    ]
    return payload


def _plan_payload(plan: PurgedWalkForwardPlanV1) -> dict[str, object]:
    payload = plan.model_dump(mode="json", exclude={"plan_fingerprint"})
    payload["label_as_of"] = plan.label_as_of.isoformat()
    payload["source_decision_times"] = [
        item.isoformat() for item in plan.source_decision_times
    ]
    payload["folds"] = [fold.model_dump(mode="json") for fold in plan.folds]
    return payload


__all__ = [
    "PURGED_WALK_FORWARD_VERSION",
    "PurgedWalkForwardConfigV1",
    "PurgedWalkForwardFoldV1",
    "PurgedWalkForwardPlanV1",
    "build_purged_walk_forward_plan",
]
