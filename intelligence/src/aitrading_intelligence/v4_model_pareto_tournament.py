"""V4-08.1 immutable, predeclared multiobjective tournament research summary.

This consumes self-declared comparative evidence, NOT real market observations,
worker execution, verified model source rights, or trusted production promotion.
Equal declared cohort identity is necessary but not independently sufficient to
prove equal data usage. The existing V4-02 experiment and Java risk authorities
are never modified.
"""
from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

VERSION = "v4-multiobjective-research-tournament.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CandidateID = Annotated[str, Field(pattern=r"^[A-Za-z0-9._-]{1,64}$")]


class MultiobjectiveTournamentConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_oos_observations: Annotated[int, Field(ge=2, le=100_000)] = 50
    minimum_oos_trades: Annotated[int, Field(ge=1, le=100_000)] = 10

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class TournamentRosterV1(BaseModel):
    """Predeclared and fingerprinted candidate family and common causal cohort."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-tournament-roster.v1"] = "v4-tournament-roster.v1"
    registered_at: datetime
    baseline_candidate_id: CandidateID
    declared_candidate_ids: list[CandidateID] = Field(min_length=2, max_length=12)
    source_experiment_spec_fingerprint: Hash64
    dataset_fingerprint: Hash64
    target_fingerprint: Hash64
    fold_fingerprint: Hash64
    economic_cost_fingerprint: Hash64
    holdout_cohort_fingerprint: Hash64
    registration_fingerprint: Hash64

    @field_validator("registered_at")
    @classmethod
    def aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("tournament registration timestamp must be timezone-aware")
        return value

    @model_validator(mode="after")
    def canonical_roster(self) -> TournamentRosterV1:
        if self.declared_candidate_ids != sorted(set(self.declared_candidate_ids)):
            raise ValueError("tournament roster must be unique and in sorted order")
        if self.baseline_candidate_id not in self.declared_candidate_ids:
            raise ValueError("frozen baseline must be in the declared roster")
        payload = self.model_dump(mode="python", exclude={"registration_fingerprint"})
        payload["registered_at"] = self.registered_at.isoformat()
        if canonical_fingerprint(payload) != self.registration_fingerprint:
            raise ValueError("tournament registration fingerprint mismatch")
        return self


class TournamentMetricsV1(BaseModel):
    """All economics are hypothetical after-assumed-cost metrics from a source report."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    oos_observation_count: Annotated[int, Field(ge=0, le=100_000)]
    oos_trade_count: Annotated[int, Field(ge=0, le=100_000)]
    mean_net_return_bps: float | None
    maximum_drawdown: Annotated[float, Field(ge=0.0, le=1.0)] | None
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)] | None
    p95_cpu_inference_ms: Annotated[float, Field(ge=0.0, le=100_000.0)] | None
    cost_per_1000_inferences_inr: Annotated[float, Field(ge=0.0, le=1_000_000.0)] | None

    @field_validator(
        "mean_net_return_bps", "maximum_drawdown", "brier_score",
        "p95_cpu_inference_ms", "cost_per_1000_inferences_inr",
    )
    @classmethod
    def metrics_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("tournament metrics must be finite")
        return value

    @model_validator(mode="after")
    def valid_sample(self) -> TournamentMetricsV1:
        if self.oos_trade_count > self.oos_observation_count:
            raise ValueError("OOS trades cannot exceed OOS observations")
        return self


class TournamentCandidateV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: CandidateID
    model_version_fingerprint: Hash64
    source_result_fingerprint: Hash64
    registration_fingerprint: Hash64
    dataset_fingerprint: Hash64
    target_fingerprint: Hash64
    fold_fingerprint: Hash64
    economic_cost_fingerprint: Hash64
    holdout_cohort_fingerprint: Hash64
    evaluated_at: datetime
    metrics: TournamentMetricsV1
    evidence_status: Literal["SELF_DECLARED_RESEARCH_ONLY"] = (
        "SELF_DECLARED_RESEARCH_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    evidence_fingerprint: Hash64

    @field_validator("evaluated_at")
    @classmethod
    def aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("candidate evaluation timestamp must be timezone-aware")
        return value

    @model_validator(mode="after")
    def verify_source(self) -> TournamentCandidateV1:
        payload = self.model_dump(mode="python", exclude={"evidence_fingerprint"})
        payload["evaluated_at"] = self.evaluated_at.isoformat()
        payload["metrics"] = self.metrics.model_dump(mode="json")
        if canonical_fingerprint(payload) != self.evidence_fingerprint:
            raise ValueError("candidate evidence fingerprint mismatch")
        return self


class ParetoCandidateDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: CandidateID
    source_evidence_fingerprint: Hash64 | None
    decision: Literal[
        "NONDOMINATED_RESEARCH_ONLY", "DOMINATED_RESEARCH_ONLY", "INCONCLUSIVE"
    ]
    dominated_by_ids: list[CandidateID]
    reason_codes: list[str]


class MultiobjectiveTournamentReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-multiobjective-research-tournament.v1"] = VERSION
    evaluated_as_of: datetime
    registration_fingerprint: Hash64
    config_fingerprint: Hash64
    baseline_candidate_id: CandidateID
    declared_candidate_count: Annotated[int, Field(ge=2, le=12)]
    valid_candidate_count: Annotated[int, Field(ge=0, le=12)]
    decisions: list[ParetoCandidateDecision] = Field(min_length=2, max_length=12)
    pareto_front_candidate_ids: list[CandidateID]
    status: Literal["RESEARCH_PARETO_FRONT_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str] = Field(min_length=1, max_length=12)
    independent_market_source_verified: Literal[False] = False
    external_trial_provenance_verified: Literal[False] = False
    experiment_worker_execution_verified: Literal[False] = False
    automatic_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @field_validator("evaluated_as_of")
    @classmethod
    def aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("tournament cutoff must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_tournament(self) -> MultiobjectiveTournamentReportV1:
        if len(self.decisions) != self.declared_candidate_count:
            raise ValueError("tournament decisions must cover the declared roster")
        if [d.candidate_id for d in self.decisions] != sorted(
            d.candidate_id for d in self.decisions
        ):
            raise ValueError("tournament results must be in canonical order")
        selected = [
            d.candidate_id for d in self.decisions
            if d.decision == "NONDOMINATED_RESEARCH_ONLY"
        ]
        if selected != self.pareto_front_candidate_ids:
            raise ValueError("Pareto frontier must match nondominated decisions")
        if (self.status == "RESEARCH_PARETO_FRONT_ONLY") != bool(selected):
            raise ValueError("tournament status and Pareto frontier disagree")
        if canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ) != self.report_fingerprint:
            raise ValueError("tournament report fingerprint mismatch")
        return self


def _dominates(left: TournamentMetricsV1, right: TournamentMetricsV1) -> bool:
    left_values = (
        -left.mean_net_return_bps,
        left.maximum_drawdown,
        left.brier_score,
        left.p95_cpu_inference_ms,
        left.cost_per_1000_inferences_inr,
    )
    right_values = (
        -right.mean_net_return_bps,
        right.maximum_drawdown,
        right.brier_score,
        right.p95_cpu_inference_ms,
        right.cost_per_1000_inferences_inr,
    )
    # Called only when all metric axes are finite and complete.
    return (
        all(a <= b + 1e-12 for a, b in zip(left_values, right_values, strict=True))
        and any(a < b - 1e-12 for a, b in zip(left_values, right_values, strict=True))
    )


def evaluate_research_pareto_tournament(
    roster: TournamentRosterV1,
    candidate_evidence: Sequence[TournamentCandidateV1],
    *,
    evaluated_as_of: datetime,
    config: MultiobjectiveTournamentConfig | None = None,
) -> MultiobjectiveTournamentReportV1:
    """Offline Pareto research display only; no actual run orchestration or promotion."""
    policy = config or MultiobjectiveTournamentConfig()
    if evaluated_as_of.tzinfo is None or evaluated_as_of.utcoffset() is None:
        raise ValueError("evaluated_as_of must be timezone-aware")
    frozen = TournamentRosterV1.model_validate(roster.model_dump(mode="python"))
    if frozen.registered_at > evaluated_as_of:
        raise ValueError("cannot evaluate a tournament before its registration")

    known: dict[str, TournamentCandidateV1] = {}
    for item in candidate_evidence:
        if item.evaluated_at > evaluated_as_of:
            continue
        checked = TournamentCandidateV1.model_validate(item.model_dump(mode="python"))
        if checked.candidate_id not in frozen.declared_candidate_ids:
            raise ValueError("undeclared tournament candidate")
        if checked.evaluated_at <= frozen.registered_at:
            raise ValueError("candidate evaluation must follow frozen registration")
        if checked.candidate_id in known:
            raise ValueError("duplicate candidate evidence before evaluation cutoff")
        for field in (
            "registration_fingerprint", "dataset_fingerprint",
            "target_fingerprint", "fold_fingerprint",
            "economic_cost_fingerprint", "holdout_cohort_fingerprint",
        ):
            expected = (
                frozen.registration_fingerprint
                if field == "registration_fingerprint" else getattr(frozen, field)
            )
            if getattr(checked, field) != expected:
                raise ValueError(f"tournament causal cohort mismatch: {field}")
        known[checked.candidate_id] = checked

    complete: dict[str, TournamentCandidateV1] = {}
    incomplete: dict[str, list[str]] = {}
    for candidate_id in frozen.declared_candidate_ids:
        candidate = known.get(candidate_id)
        reasons = []
        if candidate is None:
            reasons.append("REGISTERED_CANDIDATE_RESULT_MISSING")
        else:
            metrics = candidate.metrics
            if metrics.oos_observation_count < policy.minimum_oos_observations:
                reasons.append("INSUFFICIENT_OOS_OBSERVATIONS")
            if metrics.oos_trade_count < policy.minimum_oos_trades:
                reasons.append("INSUFFICIENT_OOS_TRADES")
            if any(getattr(metrics, key) is None for key in (
                "mean_net_return_bps", "maximum_drawdown", "brier_score",
                "p95_cpu_inference_ms", "cost_per_1000_inferences_inr",
            )):
                reasons.append("MISSING_MULTI_OBJECTIVE_EVIDENCE")
        if reasons:
            incomplete[candidate_id] = reasons
        elif candidate is not None:
            complete[candidate_id] = candidate

    rows: list[ParetoCandidateDecision] = []
    frontier: list[str] = []
    if not incomplete:
        for candidate_id in frozen.declared_candidate_ids:
            candidate = complete[candidate_id]
            dominators = sorted(
                other_id for other_id, other in complete.items()
                if other_id != candidate_id
                and _dominates(other.metrics, candidate.metrics)
            )
            if not dominators:
                frontier.append(candidate_id)
            rows.append(ParetoCandidateDecision(
                candidate_id=candidate_id,
                source_evidence_fingerprint=candidate.evidence_fingerprint,
                decision=(
                    "DOMINATED_RESEARCH_ONLY" if dominators
                    else "NONDOMINATED_RESEARCH_ONLY"
                ),
                dominated_by_ids=dominators,
                reason_codes=(
                    ["MULTI_OBJECTIVE_DOMINANCE_NO_PROMOTION"]
                    if dominators else ["SELF_DECLARED_PARETO_FRONT_NOT_PRODUCTION_PROOF"]
                ),
            ))
    else:
        for candidate_id in frozen.declared_candidate_ids:
            candidate = known.get(candidate_id)
            rows.append(ParetoCandidateDecision(
                candidate_id=candidate_id,
                source_evidence_fingerprint=(
                    candidate.evidence_fingerprint if candidate else None
                ),
                decision="INCONCLUSIVE",
                dominated_by_ids=[],
                reason_codes=sorted(incomplete.get(candidate_id, [
                    "COMPARISON_WAITING_FOR_COMPLETE_DECLARED_ROSTER"
                ])),
            ))

    payload = {
        "contract_version": VERSION,
        "evaluated_as_of": evaluated_as_of.isoformat(),
        "registration_fingerprint": frozen.registration_fingerprint,
        "config_fingerprint": policy.fingerprint,
        "baseline_candidate_id": frozen.baseline_candidate_id,
        "declared_candidate_count": len(frozen.declared_candidate_ids),
        "valid_candidate_count": len(complete),
        "decisions": [d.model_dump(mode="json") for d in rows],
        "pareto_front_candidate_ids": frontier,
        "status": ("INCONCLUSIVE" if incomplete else "RESEARCH_PARETO_FRONT_ONLY"),
        "reason_codes": sorted(
            set([
                "SELF_DECLARED_METRICS_NOT_INDEPENDENT_MARKET_PROOF",
                "NO_AUTOMATIC_MODEL_PROMOTION",
                *(
                    ["DECLARED_TOURNAMENT_EVIDENCE_INCOMPLETE"] if incomplete else []
                ),
            ])
        ),
        "independent_market_source_verified": False,
        "external_trial_provenance_verified": False,
        "experiment_worker_execution_verified": False,
        "automatic_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return MultiobjectiveTournamentReportV1(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
