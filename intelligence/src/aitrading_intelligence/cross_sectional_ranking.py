from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .cross_sectional_scan import CrossSectionalScanPlanV1
from .fingerprints import canonical_fingerprint
from .fusion import RankedOpportunity
from .pytorch_target import LabeledExample
from .selective_evaluation import _validate_outcome

CROSS_SECTIONAL_RANKING_DATASET_VERSION = "cross-sectional-ranking-dataset.v1"
CROSS_SECTIONAL_RANKING_ROW_VERSION = "cross-sectional-ranking-row.v1"
CROSS_SECTIONAL_RANKING_EVALUATION_VERSION = "cross-sectional-ranking-evaluation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
MetricState = Literal["COMPLETE", "INCOMPLETE"]


class CrossSectionalRankingRowV1(BaseModel):
    """One immutable pre-outcome ranking decision for a scanned universe member."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["cross-sectional-ranking-row.v1"] = (
        CROSS_SECTIONAL_RANKING_ROW_VERSION
    )
    rank: Annotated[int, Field(ge=1)]
    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    candidate_action: Literal["BUY", "WATCH"]
    ranking_score: Annotated[float, Field(ge=0.0, le=1.0)]
    fused_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    source_scan_member_fingerprint: Hash64
    source_candidate_fingerprint: Hash64
    row_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("ranking row as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_row_fingerprint(self) -> CrossSectionalRankingRowV1:
        payload = self.model_dump(mode="json", exclude={"row_fingerprint"})
        if self.row_fingerprint != canonical_fingerprint(payload):
            raise ValueError("ranking row fingerprint mismatch")
        return self


class CrossSectionalRankingDatasetV1(BaseModel):
    """Deterministic cross-sectional ordering before any realized outcome is known."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["cross-sectional-ranking-dataset.v1"] = (
        CROSS_SECTIONAL_RANKING_DATASET_VERSION
    )
    ranking_policy_id: Annotated[str, Field(min_length=1, max_length=120)]
    as_of: datetime
    source_scan_plan_fingerprint: Hash64
    source_universe_fingerprint: Hash64
    source_universe_member_count: Annotated[int, Field(ge=1, le=1_000)]
    ranked_member_count: Annotated[int, Field(ge=1, le=1_000)]
    missing_member_count: Annotated[int, Field(ge=0, le=1_000)]
    coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    rows: list[CrossSectionalRankingRowV1] = Field(min_length=1, max_length=1_000)
    missing_member_fingerprints: list[Hash64] = Field(default_factory=list, max_length=1_000)
    dataset_fingerprint: Hash64
    status: Literal["RESEARCH_RANKING_DATASET_ONLY"] = (
        "RESEARCH_RANKING_DATASET_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def dataset_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("ranking dataset as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_dataset(self) -> CrossSectionalRankingDatasetV1:
        if self.ranked_member_count != len(self.rows):
            raise ValueError("ranked_member_count must match rows")
        if self.missing_member_count != len(self.missing_member_fingerprints):
            raise ValueError("missing_member_count must match missing fingerprints")
        if self.ranked_member_count + self.missing_member_count != self.source_universe_member_count:
            raise ValueError("ranked plus missing members must equal source universe count")
        expected_coverage = self.ranked_member_count / self.source_universe_member_count
        if not math.isclose(self.coverage, expected_coverage, rel_tol=0.0, abs_tol=1e-12):
            raise ValueError("ranking coverage must equal ranked/source member count")
        if [row.rank for row in self.rows] != list(range(1, len(self.rows) + 1)):
            raise ValueError("ranking rows must have contiguous one-based ranks")
        ordering = [
            (-row.ranking_score, row.instrument_id, row.source_candidate_fingerprint)
            for row in self.rows
        ]
        if ordering != sorted(ordering):
            raise ValueError("ranking rows must be deterministically ordered")
        if len({row.instrument_id for row in self.rows}) != len(self.rows):
            raise ValueError("ranking dataset cannot contain duplicate instruments")
        if len(set(self.missing_member_fingerprints)) != len(
            self.missing_member_fingerprints
        ):
            raise ValueError("missing scan members must be unique")
        payload = self.model_dump(mode="json", exclude={"dataset_fingerprint"})
        if self.dataset_fingerprint != canonical_fingerprint(payload):
            raise ValueError("ranking dataset fingerprint mismatch")
        return self


class TopKRankingMetricV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    k: Annotated[int, Field(ge=1, le=1_000)]
    selected_count: Annotated[int, Field(ge=1, le=1_000)]
    resolved_count: Annotated[int, Field(ge=0, le=1_000)]
    unresolved_count: Annotated[int, Field(ge=0, le=1_000)]
    state: MetricState
    win_rate: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    total_net_return: float | None = None
    mean_net_return: float | None = None
    expectancy_bps: float | None = None

    @field_validator("total_net_return", "mean_net_return", "expectancy_bps")
    @classmethod
    def optional_metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("Top-K ranking metrics must be finite")
        return value

    @model_validator(mode="after")
    def validate_metric_state(self) -> TopKRankingMetricV1:
        if self.resolved_count + self.unresolved_count != self.selected_count:
            raise ValueError("Top-K resolved and unresolved counts must equal selected_count")
        metrics = (
            self.win_rate,
            self.total_net_return,
            self.mean_net_return,
            self.expectancy_bps,
        )
        if self.state == "COMPLETE":
            if self.unresolved_count != 0 or self.resolved_count != self.selected_count:
                raise ValueError("complete Top-K metric requires every selected outcome")
            if any(value is None for value in metrics):
                raise ValueError("complete Top-K metric requires economic metrics")
        elif any(value is not None for value in metrics):
            raise ValueError("incomplete Top-K metric cannot publish partial economics")
        return self


class CrossSectionalRankingEvaluationV1(BaseModel):
    """Frozen after-cost Top-K evaluation without replacing unresolved top ranks."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["cross-sectional-ranking-evaluation.v1"] = (
        CROSS_SECTIONAL_RANKING_EVALUATION_VERSION
    )
    evaluation_as_of: datetime
    source_dataset_fingerprint: Hash64
    target_spec_fingerprint: Hash64 | None = None
    round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=500.0)]
    ranked_member_count: Annotated[int, Field(ge=1, le=1_000)]
    resolved_member_count: Annotated[int, Field(ge=0, le=1_000)]
    unresolved_member_count: Annotated[int, Field(ge=0, le=1_000)]
    top_k_metrics: list[TopKRankingMetricV1] = Field(min_length=1, max_length=32)
    score_return_spearman: Annotated[float | None, Field(ge=-1.0, le=1.0)] = None
    source_outcome_fingerprints: list[Hash64] = Field(default_factory=list, max_length=1_000)
    evaluation_fingerprint: Hash64
    status: Literal["RESEARCH_RANKING_EVALUATION_ONLY"] = (
        "RESEARCH_RANKING_EVALUATION_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("evaluation_as_of")
    @classmethod
    def evaluation_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("ranking evaluation_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_evaluation(self) -> CrossSectionalRankingEvaluationV1:
        if self.resolved_member_count + self.unresolved_member_count != self.ranked_member_count:
            raise ValueError("resolved plus unresolved must equal ranked member count")
        if len(self.source_outcome_fingerprints) != self.resolved_member_count:
            raise ValueError("outcome fingerprints must match resolved member count")
        if len(set(self.source_outcome_fingerprints)) != len(
            self.source_outcome_fingerprints
        ):
            raise ValueError("resolved ranking outcomes must be unique")
        if [metric.k for metric in self.top_k_metrics] != sorted(
            metric.k for metric in self.top_k_metrics
        ):
            raise ValueError("Top-K metrics must be sorted by k")
        payload = self.model_dump(mode="json", exclude={"evaluation_fingerprint"})
        if self.evaluation_fingerprint != canonical_fingerprint(payload):
            raise ValueError("ranking evaluation fingerprint mismatch")
        return self


def build_cross_sectional_ranking_dataset(
    scan_plan: CrossSectionalScanPlanV1,
    opportunities: Sequence[RankedOpportunity],
    *,
    ranking_policy_id: str = "signal-fusion-rank-score.v1",
) -> CrossSectionalRankingDatasetV1:
    """Bind deterministic pre-outcome candidate scores to one exact scan plan."""

    validated_plan = CrossSectionalScanPlanV1.model_validate(
        scan_plan.model_dump(mode="python")
    )
    if not ranking_policy_id or len(ranking_policy_id) > 120:
        raise ValueError("ranking_policy_id must be between 1 and 120 characters")

    scan_members = [member for batch in validated_plan.batches for member in batch.members]
    member_by_symbol = {member.symbol: member for member in scan_members}
    if len(member_by_symbol) != len(scan_members):
        raise ValueError("scan plan symbols must be unique for ranking")

    candidate_by_symbol: dict[str, RankedOpportunity] = {}
    for opportunity in opportunities:
        checked = _validate_opportunity(opportunity)
        if checked.as_of != validated_plan.scan_as_of:
            raise ValueError("ranking opportunity as_of must match scan plan")
        if checked.symbol not in member_by_symbol:
            raise ValueError("ranking opportunity is not a member of the source scan plan")
        if checked.symbol in candidate_by_symbol:
            raise ValueError("ranking opportunities must be unique by symbol")
        candidate_by_symbol[checked.symbol] = checked

    if not candidate_by_symbol:
        raise ValueError("at least one ranked opportunity is required")

    ordered_candidates = sorted(
        candidate_by_symbol.values(),
        key=lambda item: (
            -item.rank_score,
            member_by_symbol[item.symbol].instrument_id,
            item.opportunity_fingerprint,
        ),
    )
    rows: list[CrossSectionalRankingRowV1] = []
    for rank, opportunity in enumerate(ordered_candidates, start=1):
        member = member_by_symbol[opportunity.symbol]
        payload = {
            "contract_version": CROSS_SECTIONAL_RANKING_ROW_VERSION,
            "rank": rank,
            "instrument_id": member.instrument_id,
            "symbol": opportunity.symbol,
            "as_of": opportunity.as_of.isoformat(),
            "candidate_action": opportunity.action,
            "ranking_score": opportunity.rank_score,
            "fused_score": opportunity.fused_score,
            "confidence": opportunity.confidence,
            "source_scan_member_fingerprint": member.member_fingerprint,
            "source_candidate_fingerprint": opportunity.opportunity_fingerprint,
            "can_authorize_capital": False,
        }
        rows.append(
            CrossSectionalRankingRowV1(
                **payload,
                row_fingerprint=canonical_fingerprint(payload),
            )
        )

    missing = [
        member.member_fingerprint
        for member in scan_members
        if member.symbol not in candidate_by_symbol
    ]
    dataset_payload = {
        "contract_version": CROSS_SECTIONAL_RANKING_DATASET_VERSION,
        "ranking_policy_id": ranking_policy_id,
        "as_of": validated_plan.scan_as_of.isoformat(),
        "source_scan_plan_fingerprint": validated_plan.plan_fingerprint,
        "source_universe_fingerprint": validated_plan.source_universe_fingerprint,
        "source_universe_member_count": validated_plan.source_universe_member_count,
        "ranked_member_count": len(rows),
        "missing_member_count": len(missing),
        "coverage": len(rows) / validated_plan.source_universe_member_count,
        "rows": [row.model_dump(mode="json") for row in rows],
        "missing_member_fingerprints": missing,
        "status": "RESEARCH_RANKING_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CrossSectionalRankingDatasetV1(
        **dataset_payload,
        dataset_fingerprint=canonical_fingerprint(dataset_payload),
    )


def evaluate_cross_sectional_ranking(
    dataset: CrossSectionalRankingDatasetV1,
    outcomes: Sequence[LabeledExample],
    *,
    evaluation_as_of: datetime,
    top_k: Sequence[int] = (1, 3, 5, 10),
    round_trip_cost_bps: float = 10.0,
) -> CrossSectionalRankingEvaluationV1:
    """Evaluate frozen ranks after costs without filling unresolved top slots from below."""

    validated_dataset = CrossSectionalRankingDatasetV1.model_validate(
        dataset.model_dump(mode="python")
    )
    if evaluation_as_of.tzinfo is None or evaluation_as_of.utcoffset() is None:
        raise ValueError("evaluation_as_of must be timezone-aware")
    if round_trip_cost_bps < 0.0 or round_trip_cost_bps > 500.0:
        raise ValueError("round_trip_cost_bps must be between 0 and 500")
    requested_k = sorted(set(int(value) for value in top_k))
    if not requested_k or requested_k[0] < 1:
        raise ValueError("top_k must contain positive integers")

    row_by_key = {(row.symbol, row.as_of): row for row in validated_dataset.rows}
    resolved: dict[tuple[str, datetime], LabeledExample] = {}
    for outcome in outcomes:
        if outcome.target_observed_at > evaluation_as_of:
            continue
        checked = _validate_outcome(outcome)
        key = (checked.symbol, checked.as_of)
        if key not in row_by_key:
            continue
        if key in resolved:
            raise ValueError("ranking outcomes must be unique by symbol and as_of")
        resolved[key] = checked

    target_specs = {item.target_spec_fingerprint for item in resolved.values()}
    if len(target_specs) > 1:
        raise ValueError("resolved ranking outcomes must share one target specification")
    target_spec = next(iter(target_specs)) if target_specs else None
    cost_fraction = round_trip_cost_bps / 10_000.0

    metrics: list[TopKRankingMetricV1] = []
    for k in requested_k:
        selected = validated_dataset.rows[: min(k, len(validated_dataset.rows))]
        if not selected:
            continue
        selected_outcomes = [resolved.get((row.symbol, row.as_of)) for row in selected]
        resolved_count = sum(item is not None for item in selected_outcomes)
        unresolved_count = len(selected) - resolved_count
        if unresolved_count:
            metrics.append(
                TopKRankingMetricV1(
                    k=k,
                    selected_count=len(selected),
                    resolved_count=resolved_count,
                    unresolved_count=unresolved_count,
                    state="INCOMPLETE",
                )
            )
            continue
        returns = [
            item.forward_return - cost_fraction
            for item in selected_outcomes
            if item is not None
        ]
        total_return = sum(returns)
        mean_return = total_return / len(returns)
        metrics.append(
            TopKRankingMetricV1(
                k=k,
                selected_count=len(selected),
                resolved_count=len(returns),
                unresolved_count=0,
                state="COMPLETE",
                win_rate=sum(value > 0.0 for value in returns) / len(returns),
                total_net_return=total_return,
                mean_net_return=mean_return,
                expectancy_bps=mean_return * 10_000.0,
            )
        )

    all_complete = len(resolved) == len(validated_dataset.rows)
    spearman = None
    if all_complete and len(validated_dataset.rows) >= 2:
        realized = [
            resolved[(row.symbol, row.as_of)].forward_return - cost_fraction
            for row in validated_dataset.rows
        ]
        scores = [row.ranking_score for row in validated_dataset.rows]
        spearman = _spearman(scores, realized)

    payload = {
        "contract_version": CROSS_SECTIONAL_RANKING_EVALUATION_VERSION,
        "evaluation_as_of": evaluation_as_of.isoformat(),
        "source_dataset_fingerprint": validated_dataset.dataset_fingerprint,
        "target_spec_fingerprint": target_spec,
        "round_trip_cost_bps": round_trip_cost_bps,
        "ranked_member_count": len(validated_dataset.rows),
        "resolved_member_count": len(resolved),
        "unresolved_member_count": len(validated_dataset.rows) - len(resolved),
        "top_k_metrics": [metric.model_dump(mode="json") for metric in metrics],
        "score_return_spearman": spearman,
        "source_outcome_fingerprints": sorted(
            item.example_fingerprint for item in resolved.values()
        ),
        "status": "RESEARCH_RANKING_EVALUATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CrossSectionalRankingEvaluationV1(
        **payload,
        evaluation_fingerprint=canonical_fingerprint(payload),
    )


def _validate_opportunity(item: RankedOpportunity) -> RankedOpportunity:
    checked = RankedOpportunity.model_validate(item.model_dump(mode="python"))
    payload = checked.model_dump(mode="json", exclude={"opportunity_fingerprint"})
    if checked.opportunity_fingerprint != canonical_fingerprint(payload):
        raise ValueError("ranking opportunity fingerprint mismatch")
    if checked.can_authorize_capital:
        raise ValueError("ranking opportunity cannot carry capital authority")
    return checked


def _spearman(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right) or len(left) < 2:
        raise ValueError("Spearman correlation requires equal vectors of length at least two")
    left_ranks = _average_ranks(left)
    right_ranks = _average_ranks(right)
    left_mean = sum(left_ranks) / len(left_ranks)
    right_mean = sum(right_ranks) / len(right_ranks)
    numerator = sum(
        (x - left_mean) * (y - right_mean)
        for x, y in zip(left_ranks, right_ranks, strict=True)
    )
    left_scale = math.sqrt(sum((x - left_mean) ** 2 for x in left_ranks))
    right_scale = math.sqrt(sum((y - right_mean) ** 2 for y in right_ranks))
    if left_scale == 0.0 or right_scale == 0.0:
        return 0.0
    return max(-1.0, min(1.0, numerator / (left_scale * right_scale)))


def _average_ranks(values: Sequence[float]) -> list[float]:
    indexed = sorted(enumerate(values), key=lambda item: (item[1], item[0]))
    ranks = [0.0] * len(values)
    index = 0
    while index < len(indexed):
        end = index + 1
        while end < len(indexed) and indexed[end][1] == indexed[index][1]:
            end += 1
        average_rank = ((index + 1) + end) / 2.0
        for position in range(index, end):
            ranks[indexed[position][0]] = average_rank
        index = end
    return ranks
