from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .cross_sectional_ranking import (
    CROSS_SECTIONAL_RANKING_DATASET_VERSION,
    CROSS_SECTIONAL_RANKING_ROW_VERSION,
    CrossSectionalRankingDatasetV1,
    CrossSectionalRankingRowV1,
)
from .fingerprints import canonical_fingerprint
from .selective_policy import SelectiveDecisionV1

EXPECTED_EDGE_RISK_RANKING_VERSION = "expected-edge-risk-ranking.v1"
EXPECTED_EDGE_RISK_SCORE_VERSION = "expected-edge-risk-score.v1"
EXPECTED_EDGE_RISK_POLICY_ID = "expected-net-edge-risk.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ScoreState = Literal["RISK_ADJUSTED", "ABSTAIN_ZERO"]


class ExpectedEdgeRiskRankingConfigV1(BaseModel):
    """Frozen transform from pre-decision net edge and uncertainty into rank score."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_id: Literal["expected-net-edge-risk.v1"] = EXPECTED_EDGE_RISK_POLICY_ID
    edge_scale_bps: Annotated[float, Field(gt=0.0, le=10_000.0)] = 100.0
    minimum_risk_score: Annotated[float, Field(gt=0.0, le=1.0)] = 0.05
    coverage_penalty_weight: Annotated[float, Field(ge=0.0, le=2.0)] = 1.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class ExpectedEdgeRiskScoreV1(BaseModel):
    """Auditable pre-outcome score used instead of raw probability ordering."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["expected-edge-risk-score.v1"] = (
        EXPECTED_EDGE_RISK_SCORE_VERSION
    )
    instrument_id: Annotated[str, Field(min_length=1, max_length=120)]
    symbol: Annotated[str, Field(pattern=r"^[A-Z0-9&._-]{1,32}$")]
    as_of: datetime
    expected_net_edge_bps: float
    uncertainty_score: Annotated[float, Field(ge=0.0, le=1.0)]
    expert_coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    coverage_penalty: Annotated[float, Field(ge=0.0, le=2.0)]
    risk_score: Annotated[float, Field(gt=0.0, le=1.0)]
    positive_edge_strength: Annotated[float, Field(ge=0.0)]
    edge_to_risk_ratio: Annotated[float, Field(ge=0.0)]
    ranking_score: Annotated[float, Field(ge=0.0, lt=1.0)]
    selective_policy_decision: Literal["TRADE", "ABSTAIN"]
    state: ScoreState
    source_ranking_row_fingerprint: Hash64
    source_candidate_fingerprint: Hash64
    source_selective_decision_fingerprint: Hash64
    config_fingerprint: Hash64
    score_fingerprint: Hash64
    status: Literal["RESEARCH_RANKING_SCORE_ONLY"] = "RESEARCH_RANKING_SCORE_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("edge/risk score as_of must be timezone-aware")
        return value

    @field_validator(
        "expected_net_edge_bps",
        "positive_edge_strength",
        "edge_to_risk_ratio",
    )
    @classmethod
    def numeric_values_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("edge/risk numeric values must be finite")
        return value

    @model_validator(mode="after")
    def validate_score_semantics(self) -> ExpectedEdgeRiskScoreV1:
        if self.selective_policy_decision == "ABSTAIN":
            if self.state != "ABSTAIN_ZERO":
                raise ValueError("selective abstention must use ABSTAIN_ZERO state")
            if self.ranking_score != 0.0 or self.edge_to_risk_ratio != 0.0:
                raise ValueError("selective abstention must receive zero ranking score")
        else:
            if self.state != "RISK_ADJUSTED":
                raise ValueError("selective TRADE must use RISK_ADJUSTED state")
            if self.expected_net_edge_bps <= 0.0:
                raise ValueError("risk-adjusted TRADE ranking requires positive net edge")
            expected_ratio = self.positive_edge_strength / self.risk_score
            if not math.isclose(
                self.edge_to_risk_ratio,
                expected_ratio,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("edge_to_risk_ratio mismatch")
            expected_score = expected_ratio / (1.0 + expected_ratio)
            if not math.isclose(
                self.ranking_score,
                expected_score,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("normalized edge/risk ranking score mismatch")
        if self.score_fingerprint != canonical_fingerprint(_score_payload(self)):
            raise ValueError("expected edge/risk score fingerprint mismatch")
        return self


class ExpectedEdgeRiskRankingV1(BaseModel):
    """Evidence bundle proving deterministic re-ranking from selective decisions."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["expected-edge-risk-ranking.v1"] = (
        EXPECTED_EDGE_RISK_RANKING_VERSION
    )
    as_of: datetime
    config: ExpectedEdgeRiskRankingConfigV1
    source_ranking_dataset_fingerprint: Hash64
    source_ranking_policy_id: Annotated[str, Field(min_length=1, max_length=120)]
    source_ranked_member_count: Annotated[int, Field(ge=1, le=1_000)]
    score_count: Annotated[int, Field(ge=1, le=1_000)]
    scores: list[ExpectedEdgeRiskScoreV1] = Field(min_length=1, max_length=1_000)
    reranked_dataset: CrossSectionalRankingDatasetV1
    ranking_fingerprint: Hash64
    status: Literal["RESEARCH_EDGE_RISK_RANKING_ONLY"] = (
        "RESEARCH_EDGE_RISK_RANKING_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("edge/risk ranking as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_ranking_bundle(self) -> ExpectedEdgeRiskRankingV1:
        if self.score_count != len(self.scores):
            raise ValueError("score_count must match score records")
        if self.score_count != self.source_ranked_member_count:
            raise ValueError("every source-ranked row requires edge/risk evidence")
        if self.reranked_dataset.as_of != self.as_of:
            raise ValueError("reranked dataset as_of must match ranking bundle")
        if self.reranked_dataset.ranking_policy_id != EXPECTED_EDGE_RISK_POLICY_ID:
            raise ValueError("reranked dataset must use expected-net-edge-risk policy")
        if self.reranked_dataset.ranked_member_count != self.score_count:
            raise ValueError("reranked rows must match edge/risk score count")
        source_rows = [score.source_ranking_row_fingerprint for score in self.scores]
        candidates = [score.source_candidate_fingerprint for score in self.scores]
        if len(set(source_rows)) != len(source_rows):
            raise ValueError("edge/risk scores must be unique by source ranking row")
        if len(set(candidates)) != len(candidates):
            raise ValueError("edge/risk scores must be unique by candidate")

        expected_config_fingerprint = self.config.fingerprint
        for score in self.scores:
            if score.as_of != self.as_of:
                raise ValueError("edge/risk score as_of must match ranking bundle")
            if score.config_fingerprint != expected_config_fingerprint:
                raise ValueError("edge/risk score config fingerprint mismatch")
            _validate_score_derivations(score, self.config)

        ordered_scores = sorted(
            self.scores,
            key=lambda item: (
                -item.ranking_score,
                item.instrument_id,
                item.source_candidate_fingerprint,
            ),
        )
        if self.scores != ordered_scores:
            raise ValueError("edge/risk score evidence must use deterministic rank order")
        for row, score in zip(self.reranked_dataset.rows, self.scores, strict=True):
            if row.instrument_id != score.instrument_id or row.symbol != score.symbol:
                raise ValueError("reranked row identity must match edge/risk score")
            if row.as_of != score.as_of:
                raise ValueError("reranked row timestamp must match edge/risk score")
            if row.source_candidate_fingerprint != score.source_candidate_fingerprint:
                raise ValueError("reranked row must preserve candidate lineage")
            if not math.isclose(
                row.ranking_score,
                score.ranking_score,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("reranked row score must match edge/risk evidence")
        if self.ranking_fingerprint != canonical_fingerprint(_ranking_payload(self)):
            raise ValueError("expected edge/risk ranking fingerprint mismatch")
        return self


def rerank_by_expected_net_edge_risk(
    source_dataset: CrossSectionalRankingDatasetV1,
    selective_decisions: Sequence[SelectiveDecisionV1],
    *,
    config: ExpectedEdgeRiskRankingConfigV1 | None = None,
) -> ExpectedEdgeRiskRankingV1:
    """Re-rank one frozen cross-section using only causal selective-policy evidence."""

    cfg = config or ExpectedEdgeRiskRankingConfigV1()
    source = CrossSectionalRankingDatasetV1.model_validate(
        source_dataset.model_dump(mode="python")
    )
    rows_by_candidate = {
        row.source_candidate_fingerprint: row for row in source.rows
    }
    if len(rows_by_candidate) != len(source.rows):
        raise ValueError("source ranking candidates must be unique")

    decisions_by_candidate: dict[str, SelectiveDecisionV1] = {}
    for item in selective_decisions:
        decision = _validate_selective_decision(item)
        candidate_fingerprint = decision.source_opportunity_fingerprint
        source_row = rows_by_candidate.get(candidate_fingerprint)
        if source_row is None:
            raise ValueError("selective decision is outside the source ranking dataset")
        if candidate_fingerprint in decisions_by_candidate:
            raise ValueError("selective decisions must be unique by source candidate")
        if decision.symbol != source_row.symbol:
            raise ValueError("selective decision symbol does not match ranking row")
        if decision.decision_as_of != source.as_of:
            raise ValueError("selective decision_as_of must equal frozen ranking as_of")
        if not math.isclose(
            decision.fused_score,
            source_row.fused_score,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise ValueError("selective decision fused score does not match ranking row")
        if decision.policy_decision == "TRADE" and source_row.candidate_action != "BUY":
            raise ValueError("selective TRADE requires source candidate action BUY")
        decisions_by_candidate[candidate_fingerprint] = decision

    missing = set(rows_by_candidate) - set(decisions_by_candidate)
    if missing:
        raise ValueError("every source ranking row requires selective decision evidence")

    scores = [
        _build_score(row, decisions_by_candidate[row.source_candidate_fingerprint], cfg)
        for row in source.rows
    ]
    scores.sort(
        key=lambda item: (
            -item.ranking_score,
            item.instrument_id,
            item.source_candidate_fingerprint,
        )
    )
    source_row_by_candidate = {
        row.source_candidate_fingerprint: row for row in source.rows
    }
    reranked_rows: list[CrossSectionalRankingRowV1] = []
    for rank, score in enumerate(scores, start=1):
        source_row = source_row_by_candidate[score.source_candidate_fingerprint]
        row_payload = {
            "contract_version": CROSS_SECTIONAL_RANKING_ROW_VERSION,
            "rank": rank,
            "instrument_id": source_row.instrument_id,
            "symbol": source_row.symbol,
            "as_of": source_row.as_of.isoformat(),
            "candidate_action": source_row.candidate_action,
            "ranking_score": score.ranking_score,
            "fused_score": source_row.fused_score,
            "confidence": source_row.confidence,
            "source_scan_member_fingerprint": source_row.source_scan_member_fingerprint,
            "source_candidate_fingerprint": source_row.source_candidate_fingerprint,
            "can_authorize_capital": False,
        }
        reranked_rows.append(
            CrossSectionalRankingRowV1(
                **row_payload,
                row_fingerprint=canonical_fingerprint(row_payload),
            )
        )

    dataset_payload = {
        "contract_version": CROSS_SECTIONAL_RANKING_DATASET_VERSION,
        "ranking_policy_id": EXPECTED_EDGE_RISK_POLICY_ID,
        "as_of": source.as_of.isoformat(),
        "source_scan_plan_fingerprint": source.source_scan_plan_fingerprint,
        "source_universe_fingerprint": source.source_universe_fingerprint,
        "source_universe_member_count": source.source_universe_member_count,
        "ranked_member_count": source.ranked_member_count,
        "missing_member_count": source.missing_member_count,
        "coverage": source.coverage,
        "rows": [row.model_dump(mode="json") for row in reranked_rows],
        "missing_member_fingerprints": list(source.missing_member_fingerprints),
        "status": "RESEARCH_RANKING_DATASET_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    reranked_dataset = CrossSectionalRankingDatasetV1(
        **dataset_payload,
        dataset_fingerprint=canonical_fingerprint(dataset_payload),
    )
    ranking_payload = {
        "contract_version": EXPECTED_EDGE_RISK_RANKING_VERSION,
        "as_of": source.as_of.isoformat(),
        "config": cfg.model_dump(mode="json"),
        "source_ranking_dataset_fingerprint": source.dataset_fingerprint,
        "source_ranking_policy_id": source.ranking_policy_id,
        "source_ranked_member_count": source.ranked_member_count,
        "score_count": len(scores),
        "scores": [score.model_dump(mode="json") for score in scores],
        "reranked_dataset": reranked_dataset.model_dump(mode="json"),
        "status": "RESEARCH_EDGE_RISK_RANKING_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ExpectedEdgeRiskRankingV1(
        **ranking_payload,
        ranking_fingerprint=canonical_fingerprint(ranking_payload),
    )


def _build_score(
    row: CrossSectionalRankingRowV1,
    decision: SelectiveDecisionV1,
    config: ExpectedEdgeRiskRankingConfigV1,
) -> ExpectedEdgeRiskScoreV1:
    coverage_penalty = (1.0 - decision.expert_coverage) * config.coverage_penalty_weight
    risk_score = min(
        1.0,
        max(
            config.minimum_risk_score,
            decision.uncertainty_score + coverage_penalty,
        ),
    )
    positive_edge_strength = (
        max(0.0, decision.expected_net_edge_bps) / config.edge_scale_bps
    )
    if decision.policy_decision == "TRADE":
        if decision.expected_net_edge_bps <= 0.0:
            raise ValueError("selective TRADE must have positive net edge for ranking")
        ratio = positive_edge_strength / risk_score
        ranking_score = ratio / (1.0 + ratio)
        state: ScoreState = "RISK_ADJUSTED"
    else:
        ratio = 0.0
        ranking_score = 0.0
        state = "ABSTAIN_ZERO"

    payload = {
        "contract_version": EXPECTED_EDGE_RISK_SCORE_VERSION,
        "instrument_id": row.instrument_id,
        "symbol": row.symbol,
        "as_of": row.as_of.isoformat(),
        "expected_net_edge_bps": decision.expected_net_edge_bps,
        "uncertainty_score": decision.uncertainty_score,
        "expert_coverage": decision.expert_coverage,
        "coverage_penalty": coverage_penalty,
        "risk_score": risk_score,
        "positive_edge_strength": positive_edge_strength,
        "edge_to_risk_ratio": ratio,
        "ranking_score": ranking_score,
        "selective_policy_decision": decision.policy_decision,
        "state": state,
        "source_ranking_row_fingerprint": row.row_fingerprint,
        "source_candidate_fingerprint": row.source_candidate_fingerprint,
        "source_selective_decision_fingerprint": decision.decision_fingerprint,
        "config_fingerprint": config.fingerprint,
        "status": "RESEARCH_RANKING_SCORE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ExpectedEdgeRiskScoreV1(
        **payload,
        score_fingerprint=canonical_fingerprint(payload),
    )


def _validate_selective_decision(item: SelectiveDecisionV1) -> SelectiveDecisionV1:
    decision = SelectiveDecisionV1.model_validate(item.model_dump(mode="python"))
    payload = decision.model_dump(mode="json", exclude={"decision_fingerprint"})
    payload["decision_as_of"] = decision.decision_as_of.isoformat()
    if decision.decision_fingerprint != canonical_fingerprint(payload):
        raise ValueError("selective decision fingerprint mismatch")
    if decision.promotion_allowed or decision.can_authorize_capital:
        raise ValueError("selective decision cannot carry promotion or capital authority")
    return decision


def _validate_score_derivations(
    score: ExpectedEdgeRiskScoreV1,
    config: ExpectedEdgeRiskRankingConfigV1,
) -> None:
    expected_penalty = (1.0 - score.expert_coverage) * config.coverage_penalty_weight
    if not math.isclose(
        score.coverage_penalty,
        expected_penalty,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise ValueError("edge/risk coverage penalty mismatch")
    expected_risk = min(
        1.0,
        max(config.minimum_risk_score, score.uncertainty_score + expected_penalty),
    )
    if not math.isclose(score.risk_score, expected_risk, rel_tol=0.0, abs_tol=1e-12):
        raise ValueError("edge/risk risk_score mismatch")
    expected_strength = max(0.0, score.expected_net_edge_bps) / config.edge_scale_bps
    if not math.isclose(
        score.positive_edge_strength,
        expected_strength,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise ValueError("edge/risk positive edge strength mismatch")


def _score_payload(score: ExpectedEdgeRiskScoreV1) -> dict[str, object]:
    payload = score.model_dump(mode="json", exclude={"score_fingerprint"})
    payload["as_of"] = score.as_of.isoformat()
    return payload


def _ranking_payload(ranking: ExpectedEdgeRiskRankingV1) -> dict[str, object]:
    payload = ranking.model_dump(mode="json", exclude={"ranking_fingerprint"})
    payload["as_of"] = ranking.as_of.isoformat()
    return payload


__all__ = [
    "EXPECTED_EDGE_RISK_POLICY_ID",
    "EXPECTED_EDGE_RISK_RANKING_VERSION",
    "EXPECTED_EDGE_RISK_SCORE_VERSION",
    "ExpectedEdgeRiskRankingConfigV1",
    "ExpectedEdgeRiskRankingV1",
    "ExpectedEdgeRiskScoreV1",
    "rerank_by_expected_net_edge_risk",
]
