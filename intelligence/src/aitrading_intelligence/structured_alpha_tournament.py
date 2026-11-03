from __future__ import annotations

from collections.abc import Sequence
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .incremental_economic_value import (
    IncrementalValueConfig,
    NetReturnSeries,
    evaluate_incremental_economic_value,
)
from .model_diversity import ModelPredictionSeries, build_model_diversity_report

TOURNAMENT_VERSION = "structured-alpha-tournament-screen.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Decision = Literal["SHORTLISTED_RESEARCH_ONLY", "REJECTED", "INCONCLUSIVE"]


class TournamentCandidate(BaseModel):
    """The same immutable experiment result must explain predictions and net returns."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    prediction: ModelPredictionSeries
    returns: NetReturnSeries

    @model_validator(mode="after")
    def validate_lineage(self) -> TournamentCandidate:
        for field in (
            "candidate_id",
            "model_version_ref",
            "source_result_fingerprint",
            "observation_fingerprints",
        ):
            if getattr(self.prediction, field) != getattr(self.returns, field):
                raise ValueError(f"tournament prediction/net-return mismatches {field}")
        return self


class TournamentCandidateDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: str
    model_version_ref: str
    source_result_fingerprint: Hash64
    paired_economic_report_fingerprint: Hash64
    assessment: Literal["PROMISING_RESEARCH", "NOT_IMPROVED", "INCONCLUSIVE"]
    mean_paired_net_uplift: float
    adjusted_lower_bound: float | None
    decision: Decision
    reason_codes: list[str]
    decision_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_decision(self) -> TournamentCandidateDecision:
        if self.decision == "SHORTLISTED_RESEARCH_ONLY" and (
            self.assessment != "PROMISING_RESEARCH" or self.reason_codes
        ):
            raise ValueError("shortlisted research must clear every gate")
        if self.decision != "SHORTLISTED_RESEARCH_ONLY" and not self.reason_codes:
            raise ValueError("rejected and inconclusive decisions require reasons")
        if self.decision_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"decision_fingerprint"})
        ):
            raise ValueError("tournament decision fingerprint mismatch")
        return self


class StructuredAlphaTournamentReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["structured-alpha-tournament-screen.v1"] = TOURNAMENT_VERSION
    baseline_candidate_id: str
    baseline_source_result_fingerprint: Hash64
    diversity_report_fingerprint: Hash64
    original_economic_config_fingerprint: Hash64
    corrected_economic_config_fingerprint: Hash64
    registered_challenger_count: Annotated[int, Field(ge=1, le=5)]
    adjusted_confidence_level: Annotated[float, Field(ge=0.9, le=0.99)]
    observation_count: Annotated[int, Field(ge=2)]
    decisions: list[TournamentCandidateDecision] = Field(min_length=1, max_length=5)
    shortlisted_candidate_ids: list[str]
    status: Literal[
        "RESEARCH_SHORTLIST_ONLY", "NO_ADDITIVE_RESEARCH_CANDIDATES", "INCONCLUSIVE"
    ]
    # Bonferroni only covers the declared candidate family, not iterative tuning.
    familywise_declared_tests_adjusted: Literal[True] = True
    independent_indian_market_validation_complete: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_report(self) -> StructuredAlphaTournamentReport:
        if len(self.decisions) != self.registered_challenger_count:
            raise ValueError("decision count must equal registered challenger count")
        if [row.candidate_id for row in self.decisions] != sorted(
            row.candidate_id for row in self.decisions
        ):
            raise ValueError("tournament decisions must be in canonical candidate order")
        expected = sorted(
            row.candidate_id
            for row in self.decisions
            if row.decision == "SHORTLISTED_RESEARCH_ONLY"
        )
        if self.shortlisted_candidate_ids != expected:
            raise ValueError("shortlist must agree with screened candidate decisions")
        if (self.status == "RESEARCH_SHORTLIST_ONLY") != bool(expected):
            raise ValueError("tournament status does not match shortlist")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("tournament report fingerprint mismatch")
        return self


def screen_structured_alpha_tournament(
    baseline: TournamentCandidate,
    challengers: Sequence[TournamentCandidate],
    *,
    economic_config: IncrementalValueConfig | None = None,
) -> StructuredAlphaTournamentReport:
    """Research-only Bonferroni-screened shortlist on one identical causal OOS cohort.

    Adjusts a declared candidate family's block-bootstrap confidence requirement.
    It does not fix repeated experiments, selection bias, nonstationarity, or lack
    of independent India-market/PAPER validation. No trading authority is granted.
    """
    canonical_baseline = TournamentCandidate.model_validate(baseline.model_dump(mode="python"))
    pool = [
        TournamentCandidate.model_validate(candidate.model_dump(mode="python"))
        for candidate in challengers
    ]
    if not 1 <= len(pool) <= 5:
        raise ValueError("tournament requires between one and five registered challengers")
    ids = [canonical_baseline.prediction.candidate_id] + [
        item.prediction.candidate_id for item in pool
    ]
    if len(set(ids)) != len(ids):
        raise ValueError("tournament candidate identities must be unique")

    policy = economic_config or IncrementalValueConfig()
    confidence = 1.0 - (1.0 - policy.confidence_level) / len(pool)
    if confidence > 0.99 + 1e-12:
        raise ValueError("too many tests for supported Bonferroni confidence range")
    corrected = IncrementalValueConfig.model_validate(
        {**policy.model_dump(mode="python"), "confidence_level": min(0.99, confidence)}
    )

    all_candidates = [canonical_baseline, *pool]
    diversity = build_model_diversity_report(
        [candidate.prediction for candidate in all_candidates]
    )
    baseline_returns = canonical_baseline.returns
    for item in pool:
        for field in (
            "target_binding_fingerprint",
            "fold_binding_fingerprint",
            "economic_cost_binding_fingerprint",
            "observation_fingerprints",
            "decision_times",
        ):
            if getattr(item.returns, field) != getattr(baseline_returns, field):
                raise ValueError(f"tournament causal cohort mismatches {field}")

    pairs = {
        frozenset((pair.left_candidate_id, pair.right_candidate_id)): pair
        for pair in diversity.pairs
    }
    comparisons = {
        item.prediction.candidate_id: evaluate_incremental_economic_value(
            baseline_returns, item.returns, config=corrected
        )
        for item in pool
    }
    # The strongest after-cost result is examined first; pairwise redundancy
    # against already selected research candidates keeps the shortlist diverse.
    ordered = sorted(
        pool,
        key=lambda item: (
            -comparisons[item.prediction.candidate_id].mean_paired_uplift,
            item.prediction.candidate_id,
        ),
    )
    selected: list[str] = []
    rows: list[TournamentCandidateDecision] = []
    for item in ordered:
        candidate_id = item.prediction.candidate_id
        economic = comparisons[candidate_id]
        reasons = [f"ECONOMIC_{reason}" for reason in economic.reason_codes]
        against_baseline = pairs[frozenset((candidate_id, ids[0]))]
        if not against_baseline.correlation_defined:
            reasons.append("BASELINE_CORRELATION_UNDEFINED")
        elif against_baseline.redundancy_risk:
            reasons.append("REDUNDANT_WITH_BASELINE")
        for accepted_id in selected:
            pair = pairs[frozenset((candidate_id, accepted_id))]
            if not pair.correlation_defined:
                reasons.append(f"CORRELATION_UNDEFINED_WITH_{accepted_id}")
            elif pair.redundancy_risk:
                reasons.append(f"REDUNDANT_WITH_{accepted_id}")
        if economic.assessment == "INCONCLUSIVE" or any(
            "CORRELATION_UNDEFINED" in reason for reason in reasons
        ):
            decision: Decision = "INCONCLUSIVE"
        elif reasons:
            decision = "REJECTED"
        else:
            decision = "SHORTLISTED_RESEARCH_ONLY"
            selected.append(candidate_id)
        payload = {
            "candidate_id": candidate_id,
            "model_version_ref": item.prediction.model_version_ref,
            "source_result_fingerprint": item.prediction.source_result_fingerprint,
            "paired_economic_report_fingerprint": economic.report_fingerprint,
            "assessment": economic.assessment,
            "mean_paired_net_uplift": economic.mean_paired_uplift,
            "adjusted_lower_bound": economic.bootstrap_lower_bound,
            "decision": decision,
            "reason_codes": sorted(set(reasons)),
        }
        rows.append(TournamentCandidateDecision(
            **payload, decision_fingerprint=canonical_fingerprint(payload)
        ))
    rows.sort(key=lambda row: row.candidate_id)
    if selected:
        status = "RESEARCH_SHORTLIST_ONLY"
    elif any(row.decision == "INCONCLUSIVE" for row in rows):
        status = "INCONCLUSIVE"
    else:
        status = "NO_ADDITIVE_RESEARCH_CANDIDATES"

    payload = {
        "contract_version": TOURNAMENT_VERSION,
        "baseline_candidate_id": ids[0],
        "baseline_source_result_fingerprint": (
            canonical_baseline.prediction.source_result_fingerprint
        ),
        "diversity_report_fingerprint": diversity.report_fingerprint,
        "original_economic_config_fingerprint": policy.fingerprint,
        "corrected_economic_config_fingerprint": corrected.fingerprint,
        "registered_challenger_count": len(pool),
        "adjusted_confidence_level": corrected.confidence_level,
        "observation_count": diversity.observation_count,
        "decisions": [row.model_dump(mode="json") for row in rows],
        "shortlisted_candidate_ids": sorted(selected),
        "status": status,
        "familywise_declared_tests_adjusted": True,
        "independent_indian_market_validation_complete": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return StructuredAlphaTournamentReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
