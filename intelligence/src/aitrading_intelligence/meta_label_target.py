from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .cross_sectional_ranking import (
    CrossSectionalRankingDatasetV1,
    CrossSectionalRankingRowV1,
)
from .fingerprints import canonical_fingerprint
from .triple_barrier_target import TripleBarrierTargetV1

META_LABEL_TARGET_VERSION = "meta-label-target.v1"
META_LABEL_DATASET_VERSION = "meta-label-dataset.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
MetaLabelState = Literal["LABELED", "PRIMARY_ABSTAIN", "AMBIGUOUS_TARGET"]
BarrierOutcome = Literal["UPPER", "LOWER", "VERTICAL", "AMBIGUOUS"]


class MetaLabelTargetV1(BaseModel):
    """Take-vs-skip target derived only after a primary ranking decision is frozen."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    target_version: Literal["meta-label-target.v1"] = META_LABEL_TARGET_VERSION
    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    label_as_of: datetime
    label_observed_at: datetime
    primary_action: Literal["BUY", "WATCH"]
    ranking_score: Annotated[float, Field(ge=0.0, le=1.0)]
    fused_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    barrier_outcome: BarrierOutcome
    state: MetaLabelState
    meta_label: Literal[0, 1] | None
    realized_net_return: float | None = None
    training_eligible: bool
    source_ranking_dataset_fingerprint: Hash64
    source_ranking_row_fingerprint: Hash64
    source_candidate_fingerprint: Hash64
    source_triple_barrier_target_fingerprint: Hash64
    source_target_spec_fingerprint: Hash64
    meta_target_fingerprint: Hash64
    status: Literal["RESEARCH_META_LABEL_ONLY"] = "RESEARCH_META_LABEL_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "label_as_of", "label_observed_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("meta-label timestamps must be timezone-aware")
        return value

    @field_validator("realized_net_return")
    @classmethod
    def realized_return_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("meta-label realized return must be finite")
        return value

    @model_validator(mode="after")
    def validate_meta_target(self) -> MetaLabelTargetV1:
        if self.label_as_of <= self.as_of:
            raise ValueError("meta-label cutoff must follow primary decision time")
        if self.label_observed_at <= self.as_of:
            raise ValueError("meta-label observation must follow primary decision time")
        if self.label_observed_at > self.label_as_of:
            raise ValueError("meta-label observation cannot exceed frozen cutoff")

        if self.state == "LABELED":
            if self.primary_action != "BUY":
                raise ValueError("only a primary BUY decision can produce a meta label")
            if self.barrier_outcome == "AMBIGUOUS":
                raise ValueError("ambiguous triple-barrier evidence cannot be meta-labeled")
            if self.meta_label is None or self.realized_net_return is None:
                raise ValueError("labeled meta target requires label and realized return")
            expected = 1 if self.realized_net_return > 0.0 else 0
            if self.meta_label != expected:
                raise ValueError("meta label must reflect positive after-cost return")
            if not self.training_eligible:
                raise ValueError("labeled meta target must be training eligible")
        elif self.state == "PRIMARY_ABSTAIN":
            if self.primary_action != "WATCH":
                raise ValueError("primary abstain state requires WATCH action")
            if self.meta_label is not None or self.realized_net_return is not None:
                raise ValueError("primary abstain cannot fabricate meta-label economics")
            if self.training_eligible:
                raise ValueError("primary abstain cannot be training eligible")
        else:
            if self.primary_action != "BUY":
                raise ValueError("ambiguous target state requires a primary BUY action")
            if self.barrier_outcome != "AMBIGUOUS":
                raise ValueError("ambiguous target state requires ambiguous barrier evidence")
            if self.meta_label is not None or self.realized_net_return is not None:
                raise ValueError("ambiguous target cannot fabricate meta-label economics")
            if self.training_eligible:
                raise ValueError("ambiguous target cannot be training eligible")

        if self.meta_target_fingerprint != canonical_fingerprint(_meta_target_payload(self)):
            raise ValueError("meta-label target fingerprint mismatch")
        return self


class MetaLabelDatasetV1(BaseModel):
    """Auditable meta-label coverage over one exact cross-sectional ranking dataset."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    dataset_version: Literal["meta-label-dataset.v1"] = META_LABEL_DATASET_VERSION
    as_of: datetime
    label_as_of: datetime
    source_ranking_dataset_fingerprint: Hash64
    source_ranked_member_count: Annotated[int, Field(ge=1, le=1_000)]
    matched_target_count: Annotated[int, Field(ge=0, le=1_000)]
    labeled_count: Annotated[int, Field(ge=0, le=1_000)]
    primary_abstain_count: Annotated[int, Field(ge=0, le=1_000)]
    ambiguous_target_count: Annotated[int, Field(ge=0, le=1_000)]
    unresolved_count: Annotated[int, Field(ge=0, le=1_000)]
    coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    targets: list[MetaLabelTargetV1] = Field(default_factory=list, max_length=1_000)
    unresolved_row_fingerprints: list[Hash64] = Field(
        default_factory=list,
        max_length=1_000,
    )
    dataset_fingerprint: Hash64
    status: Literal["RESEARCH_META_LABEL_DATASET_ONLY"] = (
        "RESEARCH_META_LABEL_DATASET_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "label_as_of")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("meta-label dataset timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_dataset(self) -> MetaLabelDatasetV1:
        if self.label_as_of <= self.as_of:
            raise ValueError("meta-label dataset cutoff must follow decision time")
        if self.matched_target_count != len(self.targets):
            raise ValueError("matched_target_count must match target records")
        if self.unresolved_count != len(self.unresolved_row_fingerprints):
            raise ValueError("unresolved_count must match unresolved row lineage")
        if self.matched_target_count + self.unresolved_count != self.source_ranked_member_count:
            raise ValueError("matched plus unresolved rows must equal ranked member count")
        state_total = (
            self.labeled_count
            + self.primary_abstain_count
            + self.ambiguous_target_count
        )
        if state_total != self.matched_target_count:
            raise ValueError("meta-label state counts must equal matched targets")
        expected_coverage = self.matched_target_count / self.source_ranked_member_count
        if not math.isclose(
            self.coverage,
            expected_coverage,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("meta-label coverage must equal matched/ranked count")
        source_rows = [target.source_ranking_row_fingerprint for target in self.targets]
        if len(set(source_rows)) != len(source_rows):
            raise ValueError("meta-label targets must be unique by source ranking row")
        if len(set(self.unresolved_row_fingerprints)) != len(
            self.unresolved_row_fingerprints
        ):
            raise ValueError("unresolved ranking rows must be unique")
        if set(source_rows) & set(self.unresolved_row_fingerprints):
            raise ValueError("a ranking row cannot be both matched and unresolved")
        if self.dataset_fingerprint != canonical_fingerprint(_dataset_payload(self)):
            raise ValueError("meta-label dataset fingerprint mismatch")
        return self


def build_meta_label_dataset(
    ranking_dataset: CrossSectionalRankingDatasetV1,
    triple_barrier_targets: Sequence[TripleBarrierTargetV1],
    *,
    label_as_of: datetime,
) -> MetaLabelDatasetV1:
    """Join frozen pre-decision ranks to same-cutoff triple-barrier outcomes."""

    if label_as_of.tzinfo is None or label_as_of.utcoffset() is None:
        raise ValueError("label_as_of must be timezone-aware")
    ranking = CrossSectionalRankingDatasetV1.model_validate(
        ranking_dataset.model_dump(mode="python")
    )
    if label_as_of <= ranking.as_of:
        raise ValueError("label_as_of must follow ranking decision time")

    row_by_key = {(row.symbol, row.as_of): row for row in ranking.rows}
    target_by_key: dict[tuple[str, datetime], TripleBarrierTargetV1] = {}
    for target in triple_barrier_targets:
        checked = TripleBarrierTargetV1.model_validate(target.model_dump(mode="python"))
        if checked.label_as_of != label_as_of:
            raise ValueError("triple-barrier target cutoff must match meta-label cutoff")
        key = (checked.symbol, checked.as_of)
        if key not in row_by_key:
            raise ValueError("triple-barrier target is outside the ranking dataset")
        if key in target_by_key:
            raise ValueError("triple-barrier targets must be unique by symbol and as_of")
        target_by_key[key] = checked

    meta_targets: list[MetaLabelTargetV1] = []
    unresolved_rows: list[str] = []
    for row in ranking.rows:
        target = target_by_key.get((row.symbol, row.as_of))
        if target is None:
            unresolved_rows.append(row.row_fingerprint)
            continue
        meta_targets.append(_build_meta_target(ranking, row, target))

    labeled_count = sum(target.state == "LABELED" for target in meta_targets)
    primary_abstain_count = sum(
        target.state == "PRIMARY_ABSTAIN" for target in meta_targets
    )
    ambiguous_target_count = sum(
        target.state == "AMBIGUOUS_TARGET" for target in meta_targets
    )
    payload = {
        "dataset_version": META_LABEL_DATASET_VERSION,
        "as_of": ranking.as_of.isoformat(),
        "label_as_of": label_as_of.isoformat(),
        "source_ranking_dataset_fingerprint": ranking.dataset_fingerprint,
        "source_ranked_member_count": len(ranking.rows),
        "matched_target_count": len(meta_targets),
        "labeled_count": labeled_count,
        "primary_abstain_count": primary_abstain_count,
        "ambiguous_target_count": ambiguous_target_count,
        "unresolved_count": len(unresolved_rows),
        "coverage": len(meta_targets) / len(ranking.rows),
        "targets": [target.model_dump(mode="json") for target in meta_targets],
        "unresolved_row_fingerprints": unresolved_rows,
        "status": "RESEARCH_META_LABEL_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return MetaLabelDatasetV1(
        **payload,
        dataset_fingerprint=canonical_fingerprint(payload),
    )


def _build_meta_target(
    ranking: CrossSectionalRankingDatasetV1,
    row: CrossSectionalRankingRowV1,
    target: TripleBarrierTargetV1,
) -> MetaLabelTargetV1:
    if row.symbol != target.symbol or row.as_of != target.as_of:
        raise ValueError("meta-label source decision and target identity mismatch")

    if row.candidate_action == "WATCH":
        state: MetaLabelState = "PRIMARY_ABSTAIN"
        meta_label: Literal[0, 1] | None = None
        realized_net_return = None
        training_eligible = False
    elif target.barrier_outcome == "AMBIGUOUS":
        state = "AMBIGUOUS_TARGET"
        meta_label = None
        realized_net_return = None
        training_eligible = False
    else:
        if target.net_return is None:
            raise ValueError("resolved triple-barrier target must contain net return")
        state = "LABELED"
        realized_net_return = target.net_return
        meta_label = 1 if realized_net_return > 0.0 else 0
        training_eligible = True

    payload = {
        "target_version": META_LABEL_TARGET_VERSION,
        "instrument_id": row.instrument_id,
        "symbol": row.symbol,
        "as_of": row.as_of.isoformat(),
        "label_as_of": target.label_as_of.isoformat(),
        "label_observed_at": target.target_observed_at.isoformat(),
        "primary_action": row.candidate_action,
        "ranking_score": row.ranking_score,
        "fused_score": row.fused_score,
        "confidence": row.confidence,
        "barrier_outcome": target.barrier_outcome,
        "state": state,
        "meta_label": meta_label,
        "realized_net_return": realized_net_return,
        "training_eligible": training_eligible,
        "source_ranking_dataset_fingerprint": ranking.dataset_fingerprint,
        "source_ranking_row_fingerprint": row.row_fingerprint,
        "source_candidate_fingerprint": row.source_candidate_fingerprint,
        "source_triple_barrier_target_fingerprint": target.target_fingerprint,
        "source_target_spec_fingerprint": target.target_spec_fingerprint,
        "status": "RESEARCH_META_LABEL_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return MetaLabelTargetV1(
        **payload,
        meta_target_fingerprint=canonical_fingerprint(payload),
    )


def _meta_target_payload(target: MetaLabelTargetV1) -> dict[str, object]:
    return {
        "target_version": target.target_version,
        "instrument_id": target.instrument_id,
        "symbol": target.symbol,
        "as_of": target.as_of.isoformat(),
        "label_as_of": target.label_as_of.isoformat(),
        "label_observed_at": target.label_observed_at.isoformat(),
        "primary_action": target.primary_action,
        "ranking_score": target.ranking_score,
        "fused_score": target.fused_score,
        "confidence": target.confidence,
        "barrier_outcome": target.barrier_outcome,
        "state": target.state,
        "meta_label": target.meta_label,
        "realized_net_return": target.realized_net_return,
        "training_eligible": target.training_eligible,
        "source_ranking_dataset_fingerprint": target.source_ranking_dataset_fingerprint,
        "source_ranking_row_fingerprint": target.source_ranking_row_fingerprint,
        "source_candidate_fingerprint": target.source_candidate_fingerprint,
        "source_triple_barrier_target_fingerprint": (
            target.source_triple_barrier_target_fingerprint
        ),
        "source_target_spec_fingerprint": target.source_target_spec_fingerprint,
        "status": target.status,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _dataset_payload(dataset: MetaLabelDatasetV1) -> dict[str, object]:
    return {
        "dataset_version": dataset.dataset_version,
        "as_of": dataset.as_of.isoformat(),
        "label_as_of": dataset.label_as_of.isoformat(),
        "source_ranking_dataset_fingerprint": dataset.source_ranking_dataset_fingerprint,
        "source_ranked_member_count": dataset.source_ranked_member_count,
        "matched_target_count": dataset.matched_target_count,
        "labeled_count": dataset.labeled_count,
        "primary_abstain_count": dataset.primary_abstain_count,
        "ambiguous_target_count": dataset.ambiguous_target_count,
        "unresolved_count": dataset.unresolved_count,
        "coverage": dataset.coverage,
        "targets": [target.model_dump(mode="json") for target in dataset.targets],
        "unresolved_row_fingerprints": list(dataset.unresolved_row_fingerprints),
        "status": dataset.status,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
