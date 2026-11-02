from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .fusion import RankedOpportunity
from .pytorch_target import LabeledExample
from .selective_policy import SelectiveDecisionV1, _validate_source_opportunity

SELECTIVE_EVALUATION_VERSION = "selective-policy-evaluation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Assessment = Literal[
    "QUALITY_PRESERVED_OR_IMPROVED",
    "QUALITY_REGRESSED",
    "INSUFFICIENT_EVIDENCE",
]


class SelectiveEvaluationConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evaluation_version: Literal["selective-policy-evaluation.v1"] = (
        SELECTIVE_EVALUATION_VERSION
    )
    minimum_resolved_decisions: Annotated[int, Field(ge=2, le=100_000)] = 20
    minimum_candidate_decisions: Annotated[int, Field(ge=1, le=100_000)] = 5
    minimum_abstained_decisions: Annotated[int, Field(ge=1, le=100_000)] = 5
    minimum_selective_coverage: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.05
    maximum_expectancy_regression_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 0.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CoverageExpectancyPoint(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    policy: Literal["ALWAYS_TRADE", "SELECTIVE"]
    coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    trade_count: Annotated[int, Field(ge=0)]
    winning_trades: Annotated[int, Field(ge=0)]
    win_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    total_net_return: float
    mean_net_return: float | None
    expectancy_bps: float | None

    @field_validator("total_net_return", "mean_net_return", "expectancy_bps")
    @classmethod
    def returns_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("coverage/expectancy metrics must be finite")
        return value


class SelectivePolicyEvaluationReport(BaseModel):
    """Offline counterfactual evaluation of selective eligibility; never execution authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["selective-policy-evaluation.v1"] = (
        SELECTIVE_EVALUATION_VERSION
    )
    evaluation_as_of: datetime
    config_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    eligible_decision_count: Annotated[int, Field(ge=1)]
    resolved_decision_count: Annotated[int, Field(ge=1)]
    unresolved_decision_count: Annotated[int, Field(ge=0)]
    candidate_count: Annotated[int, Field(ge=0)]
    abstain_count: Annotated[int, Field(ge=0)]
    always_trade: CoverageExpectancyPoint
    selective: CoverageExpectancyPoint
    abstained_counterfactual_expectancy_bps: float | None
    expectancy_uplift_bps: float | None
    assessment: Assessment
    reason_codes: list[str] = Field(min_length=1, max_length=32)
    source_decision_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    source_opportunity_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    source_outcome_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    required_next_gate: Literal[
        "V3_05_API_UI_SEMANTICS",
        "V3_05_SELECTIVE_POLICY_RESEARCH_REVIEW",
    ]
    report_fingerprint: Hash64
    status: Literal["RESEARCH_EVALUATION_ONLY"] = "RESEARCH_EVALUATION_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("evaluation_as_of")
    @classmethod
    def evaluation_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluation_as_of must be timezone-aware")
        return value

    @field_validator("abstained_counterfactual_expectancy_bps", "expectancy_uplift_bps")
    @classmethod
    def optional_metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("selective evaluation metrics must be finite")
        return value

    @model_validator(mode="after")
    def counts_must_reconcile(self) -> SelectivePolicyEvaluationReport:
        if self.resolved_decision_count + self.unresolved_decision_count != self.eligible_decision_count:
            raise ValueError("resolved and unresolved decisions must equal eligible decisions")
        if self.candidate_count + self.abstain_count != self.resolved_decision_count:
            raise ValueError("candidate and abstain counts must equal resolved decisions")
        if self.always_trade.trade_count != self.resolved_decision_count:
            raise ValueError("always-trade count must equal resolved decisions")
        if self.selective.trade_count != self.candidate_count:
            raise ValueError("selective trade count must equal candidate count")
        return self


def evaluate_selective_coverage_expectancy(
    decisions: Sequence[SelectiveDecisionV1],
    opportunities: Sequence[RankedOpportunity],
    outcomes: Sequence[LabeledExample],
    *,
    evaluation_as_of: datetime,
    config: SelectiveEvaluationConfig | None = None,
) -> SelectivePolicyEvaluationReport:
    cfg = config or SelectiveEvaluationConfig()
    if evaluation_as_of.tzinfo is None or evaluation_as_of.utcoffset() is None:
        raise ValueError("evaluation_as_of must be timezone-aware")
    if not decisions:
        raise ValueError("at least one selective decision is required")
    if not opportunities:
        raise ValueError("at least one ranked opportunity is required")
    if not outcomes:
        raise ValueError("at least one labeled outcome is required")

    eligible = sorted(
        (item for item in decisions if item.decision_as_of <= evaluation_as_of),
        key=lambda item: (item.decision_as_of, item.symbol, item.decision_fingerprint),
    )
    if not eligible:
        raise ValueError("no selective decisions are eligible at evaluation_as_of")
    validated_decisions = [_validate_decision(item) for item in eligible]
    if len({item.decision_fingerprint for item in validated_decisions}) != len(validated_decisions):
        raise ValueError("selective decisions must have unique fingerprints")

    opportunity_by_fingerprint: dict[str, RankedOpportunity] = {}
    for item in opportunities:
        if item.opportunity_fingerprint not in {d.source_opportunity_fingerprint for d in validated_decisions}:
            continue
        validated = _validate_source_opportunity(item)
        if validated.opportunity_fingerprint in opportunity_by_fingerprint:
            raise ValueError("ranked opportunities must have unique fingerprints")
        opportunity_by_fingerprint[validated.opportunity_fingerprint] = validated

    resolved_outcomes: dict[tuple[str, datetime], LabeledExample] = {}
    for item in outcomes:
        if item.target_observed_at > evaluation_as_of:
            continue
        validated = _validate_outcome(item)
        key = (validated.symbol, validated.as_of)
        if key in resolved_outcomes:
            raise ValueError("resolved outcomes must be unique by symbol and as_of")
        resolved_outcomes[key] = validated

    resolved: list[tuple[SelectiveDecisionV1, RankedOpportunity, LabeledExample, float]] = []
    unresolved = 0
    for decision in validated_decisions:
        opportunity = opportunity_by_fingerprint.get(decision.source_opportunity_fingerprint)
        if opportunity is None:
            raise ValueError("every selective decision must resolve to its exact opportunity")
        if decision.symbol != opportunity.symbol:
            raise ValueError("selective decision and opportunity symbols must match")
        if opportunity.as_of > decision.decision_as_of:
            raise ValueError("opportunity cannot postdate the selective decision")
        outcome = resolved_outcomes.get((opportunity.symbol, opportunity.as_of))
        if outcome is None:
            unresolved += 1
            continue
        cost_fraction = decision.estimated_round_trip_cost_bps / 10_000.0
        net_return = outcome.forward_return - cost_fraction
        if not math.isfinite(net_return):
            raise ValueError("realized net return must be finite")
        resolved.append((decision, opportunity, outcome, net_return))

    if not resolved:
        raise ValueError("at least one decision must have a resolved outcome by evaluation_as_of")
    target_fingerprints = {item[2].target_spec_fingerprint for item in resolved}
    if len(target_fingerprints) != 1:
        raise ValueError("all resolved outcomes must use one target specification")
    target_spec_fingerprint = next(iter(target_fingerprints))

    baseline_returns = [item[3] for item in resolved]
    selected_returns = [item[3] for item in resolved if item[0].policy_decision == "TRADE"]
    abstained_returns = [item[3] for item in resolved if item[0].policy_decision == "ABSTAIN"]
    candidate_count = len(selected_returns)
    abstain_count = len(abstained_returns)
    resolved_count = len(resolved)

    always_trade = _point("ALWAYS_TRADE", baseline_returns, resolved_count)
    selective = _point("SELECTIVE", selected_returns, resolved_count)
    abstained_expectancy_bps = _expectancy_bps(abstained_returns)
    expectancy_uplift_bps = (
        None
        if selective.expectancy_bps is None or always_trade.expectancy_bps is None
        else selective.expectancy_bps - always_trade.expectancy_bps
    )

    reasons: list[str] = []
    insufficient = False
    if resolved_count < cfg.minimum_resolved_decisions:
        reasons.append("INSUFFICIENT_RESOLVED_DECISIONS")
        insufficient = True
    if candidate_count < cfg.minimum_candidate_decisions:
        reasons.append("INSUFFICIENT_CANDIDATE_DECISIONS")
        insufficient = True
    if abstain_count < cfg.minimum_abstained_decisions:
        reasons.append("INSUFFICIENT_ABSTAINED_DECISIONS")
        insufficient = True
    if selective.coverage < cfg.minimum_selective_coverage:
        reasons.append("SELECTIVE_COVERAGE_BELOW_MINIMUM")
        insufficient = True

    if insufficient or expectancy_uplift_bps is None:
        assessment: Assessment = "INSUFFICIENT_EVIDENCE"
        required_next_gate = "V3_05_SELECTIVE_POLICY_RESEARCH_REVIEW"
    elif expectancy_uplift_bps + cfg.maximum_expectancy_regression_bps >= 0.0:
        assessment = "QUALITY_PRESERVED_OR_IMPROVED"
        required_next_gate = "V3_05_API_UI_SEMANTICS"
        reasons.append("SELECTIVE_EXPECTANCY_PRESERVED_OR_IMPROVED")
    else:
        assessment = "QUALITY_REGRESSED"
        required_next_gate = "V3_05_SELECTIVE_POLICY_RESEARCH_REVIEW"
        reasons.append("SELECTIVE_EXPECTANCY_REGRESSED")

    if unresolved:
        reasons.append("UNRESOLVED_OUTCOMES_EXCLUDED_AT_CUTOFF")
    if not reasons:
        reasons.append("SELECTIVE_EVALUATION_COMPLETE")
    reasons = sorted(set(reasons))

    source_decisions = [item.decision_fingerprint for item in validated_decisions]
    source_opportunities = [
        opportunity_by_fingerprint[item.source_opportunity_fingerprint].opportunity_fingerprint
        for item in validated_decisions
    ]
    source_outcomes = [item[2].example_fingerprint for item in resolved]
    payload = {
        "contract_version": SELECTIVE_EVALUATION_VERSION,
        "evaluation_as_of": evaluation_as_of.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "target_spec_fingerprint": target_spec_fingerprint,
        "eligible_decision_count": len(validated_decisions),
        "resolved_decision_count": resolved_count,
        "unresolved_decision_count": unresolved,
        "candidate_count": candidate_count,
        "abstain_count": abstain_count,
        "always_trade": always_trade.model_dump(mode="json"),
        "selective": selective.model_dump(mode="json"),
        "abstained_counterfactual_expectancy_bps": abstained_expectancy_bps,
        "expectancy_uplift_bps": expectancy_uplift_bps,
        "assessment": assessment,
        "reason_codes": reasons,
        "source_decision_fingerprints": source_decisions,
        "source_opportunity_fingerprints": source_opportunities,
        "source_outcome_fingerprints": source_outcomes,
        "required_next_gate": required_next_gate,
        "status": "RESEARCH_EVALUATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return SelectivePolicyEvaluationReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


def _point(
    policy: Literal["ALWAYS_TRADE", "SELECTIVE"],
    returns: Sequence[float],
    resolved_count: int,
) -> CoverageExpectancyPoint:
    trade_count = len(returns)
    mean_return = (sum(returns) / trade_count) if trade_count else None
    return CoverageExpectancyPoint(
        policy=policy,
        coverage=(trade_count / resolved_count) if resolved_count else 0.0,
        trade_count=trade_count,
        winning_trades=sum(value > 0.0 for value in returns),
        win_rate=(sum(value > 0.0 for value in returns) / trade_count) if trade_count else 0.0,
        total_net_return=sum(returns),
        mean_net_return=mean_return,
        expectancy_bps=None if mean_return is None else mean_return * 10_000.0,
    )


def _expectancy_bps(returns: Sequence[float]) -> float | None:
    return None if not returns else (sum(returns) / len(returns)) * 10_000.0


def _validate_decision(item: SelectiveDecisionV1) -> SelectiveDecisionV1:
    validated = SelectiveDecisionV1.model_validate(item.model_dump(mode="python"))
    payload = validated.model_dump(mode="python", exclude={"decision_fingerprint"})
    payload["decision_as_of"] = validated.decision_as_of.isoformat()
    if validated.decision_fingerprint != canonical_fingerprint(payload):
        raise ValueError("selective decision fingerprint mismatch")
    if validated.promotion_allowed or validated.can_authorize_capital:
        raise ValueError("selective decision cannot carry promotion or capital authority")
    return validated


def _validate_outcome(item: LabeledExample) -> LabeledExample:
    validated = LabeledExample.model_validate(item.model_dump(mode="python"))
    payload = {
        "target_version": validated.target_version,
        "symbol": validated.symbol,
        "as_of": validated.as_of.isoformat(),
        "target_observed_at": validated.target_observed_at.isoformat(),
        "feature_row_fingerprint": validated.feature_row_fingerprint,
        "target_spec_fingerprint": validated.target_spec_fingerprint,
        "label": validated.label,
        "forward_return": validated.forward_return,
        "threshold_return": validated.threshold_return,
        "features": validated.features,
        "provenance": validated.provenance,
    }
    if validated.example_fingerprint != canonical_fingerprint(payload):
        raise ValueError("labeled outcome fingerprint mismatch")
    if validated.can_authorize_capital:
        raise ValueError("labeled outcome cannot carry capital authority")
    return validated
