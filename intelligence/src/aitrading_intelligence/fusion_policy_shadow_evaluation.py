from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .adaptive_fusion import AdaptiveFusionDecisionV1, fusion_request_fingerprint
from .fingerprints import canonical_fingerprint
from .fusion import FusionRequest, RankedOpportunity, fuse_signals
from .fusion_disagreement_control import (
    FusionDisagreementControlV1,
    fuse_with_disagreement_control,
)
from .pytorch_target import LabeledExample
from .selective_evaluation import _validate_outcome

FUSION_POLICY_SHADOW_EVALUATION_VERSION = "fusion-policy-shadow-evaluation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Assessment = Literal[
    "CHALLENGER_EVIDENCE_SUPERIOR",
    "STATIC_CHAMPION_PRESERVED",
    "INSUFFICIENT_EVIDENCE",
]


class FusionPolicyShadowEvaluationConfig(BaseModel):
    """Research-only acceptance criteria for static-vs-adaptive shadow comparison."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["fusion-policy-shadow-evaluation.v1"] = (
        FUSION_POLICY_SHADOW_EVALUATION_VERSION
    )
    minimum_resolved_requests: Annotated[int, Field(ge=2, le=100_000)] = 30
    minimum_static_trades: Annotated[int, Field(ge=1, le=100_000)] = 8
    minimum_challenger_trades: Annotated[int, Field(ge=1, le=100_000)] = 8
    minimum_challenger_coverage: Annotated[float, Field(gt=0.0, le=1.0)] = 0.05
    round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 10.0
    minimum_expectancy_uplift_bps: Annotated[float, Field(ge=0.0, le=500.0)] = 0.0
    maximum_drawdown_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class FusionPolicyShadowPoint(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    policy: Literal["STATIC_45_35_20", "ADAPTIVE_DISAGREEMENT_GUARDED"]
    coverage: Annotated[float, Field(ge=0.0, le=1.0)]
    trade_count: Annotated[int, Field(ge=0)]
    winning_trades: Annotated[int, Field(ge=0)]
    win_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    total_net_return: float
    mean_net_return: float | None
    expectancy_bps: float | None
    maximum_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]

    @field_validator("total_net_return", "mean_net_return", "expectancy_bps")
    @classmethod
    def metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("fusion shadow metrics must be finite")
        return value

    @model_validator(mode="after")
    def counts_must_reconcile(self) -> FusionPolicyShadowPoint:
        if self.winning_trades > self.trade_count:
            raise ValueError("winning trades cannot exceed trade count")
        if self.trade_count == 0:
            if self.mean_net_return is not None or self.expectancy_bps is not None:
                raise ValueError("zero-trade shadow point cannot carry expectancy metrics")
        elif self.mean_net_return is None or self.expectancy_bps is None:
            raise ValueError("non-empty shadow point requires expectancy metrics")
        return self


class FusionPolicyShadowEvaluationReport(BaseModel):
    """Causal shadow comparison; evidence only and never promotion authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["fusion-policy-shadow-evaluation.v1"] = (
        FUSION_POLICY_SHADOW_EVALUATION_VERSION
    )
    evaluation_as_of: datetime
    config_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    eligible_request_count: Annotated[int, Field(ge=1)]
    resolved_request_count: Annotated[int, Field(ge=1)]
    unresolved_request_count: Annotated[int, Field(ge=0)]
    static_champion: FusionPolicyShadowPoint
    guarded_challenger: FusionPolicyShadowPoint
    expectancy_uplift_bps: float | None
    total_net_return_uplift: float
    maximum_drawdown_change: float
    assessment: Assessment
    reason_codes: list[str] = Field(min_length=1, max_length=48)
    source_request_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    source_adaptive_decision_fingerprints: list[Hash64] = Field(
        min_length=1,
        max_length=100_000,
    )
    source_disagreement_control_fingerprints: list[Hash64] = Field(
        min_length=1,
        max_length=100_000,
    )
    source_static_opportunity_fingerprints: list[Hash64] = Field(
        min_length=1,
        max_length=100_000,
    )
    source_guarded_opportunity_fingerprints: list[Hash64] = Field(
        min_length=1,
        max_length=100_000,
    )
    source_outcome_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    required_next_gate: Literal[
        "V3_06_POLICY_GOVERNANCE_REVIEW",
        "V3_06_CHALLENGER_RESEARCH_REVIEW",
    ]
    report_fingerprint: Hash64
    status: Literal["RESEARCH_SHADOW_EVALUATION_ONLY"] = (
        "RESEARCH_SHADOW_EVALUATION_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("evaluation_as_of")
    @classmethod
    def evaluation_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluation_as_of must be timezone-aware")
        return value

    @field_validator(
        "expectancy_uplift_bps",
        "total_net_return_uplift",
        "maximum_drawdown_change",
    )
    @classmethod
    def comparison_metrics_must_be_finite(
        cls,
        value: float | None,
    ) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("fusion shadow comparison metrics must be finite")
        return value

    @model_validator(mode="after")
    def counts_and_lineage_must_reconcile(self) -> FusionPolicyShadowEvaluationReport:
        if self.resolved_request_count + self.unresolved_request_count != self.eligible_request_count:
            raise ValueError("resolved and unresolved requests must equal eligible requests")
        for fingerprints in (
            self.source_request_fingerprints,
            self.source_adaptive_decision_fingerprints,
            self.source_disagreement_control_fingerprints,
            self.source_static_opportunity_fingerprints,
            self.source_guarded_opportunity_fingerprints,
        ):
            if len(fingerprints) != self.eligible_request_count:
                raise ValueError("request-side source fingerprints must match eligible request count")
            if len(set(fingerprints)) != len(fingerprints):
                raise ValueError("request-side source fingerprints must be unique")
        if len(self.source_outcome_fingerprints) != self.resolved_request_count:
            raise ValueError("outcome fingerprint count must match resolved requests")
        if len(set(self.source_outcome_fingerprints)) != len(self.source_outcome_fingerprints):
            raise ValueError("resolved outcome fingerprints must be unique")
        return self


def evaluate_fusion_policy_shadow(
    requests: Sequence[FusionRequest],
    adaptive_decisions: Sequence[AdaptiveFusionDecisionV1],
    controls: Sequence[FusionDisagreementControlV1],
    outcomes: Sequence[LabeledExample],
    *,
    evaluation_as_of: datetime,
    config: FusionPolicyShadowEvaluationConfig | None = None,
) -> FusionPolicyShadowEvaluationReport:
    cfg = config or FusionPolicyShadowEvaluationConfig()
    if evaluation_as_of.tzinfo is None or evaluation_as_of.utcoffset() is None:
        raise ValueError("evaluation_as_of must be timezone-aware")
    if not requests:
        raise ValueError("at least one fusion request is required")
    if not adaptive_decisions:
        raise ValueError("at least one adaptive decision is required")
    if not controls:
        raise ValueError("at least one disagreement control is required")
    if not outcomes:
        raise ValueError("at least one labeled outcome is required")

    eligible_requests = sorted(
        (request for request in requests if request.as_of <= evaluation_as_of),
        key=lambda request: (
            request.as_of,
            request.symbol,
            fusion_request_fingerprint(request),
        ),
    )
    if not eligible_requests:
        raise ValueError("no fusion requests are eligible at evaluation_as_of")

    request_by_fingerprint: dict[str, FusionRequest] = {}
    for request in eligible_requests:
        fingerprint = fusion_request_fingerprint(request)
        if fingerprint in request_by_fingerprint:
            raise ValueError("fusion requests must have unique fingerprints")
        request_by_fingerprint[fingerprint] = request

    decision_by_request: dict[str, AdaptiveFusionDecisionV1] = {}
    for decision in adaptive_decisions:
        if decision.decision_at > evaluation_as_of:
            continue
        if decision.source_request_fingerprint not in request_by_fingerprint:
            continue
        if decision.source_request_fingerprint in decision_by_request:
            raise ValueError("adaptive decisions must be unique by source request")
        decision_by_request[decision.source_request_fingerprint] = decision

    control_by_request: dict[str, FusionDisagreementControlV1] = {}
    for control in controls:
        if control.assessed_at > evaluation_as_of:
            continue
        if control.source_request_fingerprint not in request_by_fingerprint:
            continue
        if control.source_request_fingerprint in control_by_request:
            raise ValueError("disagreement controls must be unique by source request")
        control_by_request[control.source_request_fingerprint] = control

    resolved_outcomes: dict[tuple[str, datetime], LabeledExample] = {}
    for outcome in outcomes:
        if outcome.target_observed_at > evaluation_as_of:
            continue
        checked = _validate_outcome(outcome)
        key = (checked.symbol, checked.as_of)
        if key in resolved_outcomes:
            raise ValueError("resolved outcomes must be unique by symbol and as_of")
        resolved_outcomes[key] = checked

    static_opportunities: list[RankedOpportunity] = []
    guarded_opportunity_fingerprints: list[str] = []
    adaptive_fingerprints: list[str] = []
    control_fingerprints: list[str] = []
    request_fingerprints: list[str] = []
    resolved_rows: list[tuple[datetime, str, bool, bool, LabeledExample]] = []
    unresolved = 0

    for request in eligible_requests:
        request_fingerprint = fusion_request_fingerprint(request)
        decision = decision_by_request.get(request_fingerprint)
        control = control_by_request.get(request_fingerprint)
        if decision is None:
            raise ValueError("every eligible request must resolve to one adaptive decision")
        if control is None:
            raise ValueError("every eligible request must resolve to one disagreement control")
        static_opportunity = fuse_signals(request)
        guarded = fuse_with_disagreement_control(request, decision, control)

        request_fingerprints.append(request_fingerprint)
        adaptive_fingerprints.append(decision.decision_fingerprint)
        control_fingerprints.append(control.control_fingerprint)
        static_opportunities.append(static_opportunity)
        guarded_opportunity_fingerprints.append(guarded.guarded_fingerprint)

        outcome = resolved_outcomes.get((request.symbol, request.as_of))
        if outcome is None:
            unresolved += 1
            continue
        resolved_rows.append(
            (
                request.as_of,
                request_fingerprint,
                static_opportunity.action == "BUY",
                guarded.guarded_action == "BUY",
                outcome,
            )
        )

    if not resolved_rows:
        raise ValueError("at least one request must have a resolved outcome by evaluation_as_of")
    target_fingerprints = {row[4].target_spec_fingerprint for row in resolved_rows}
    if len(target_fingerprints) != 1:
        raise ValueError("all resolved outcomes must use one target specification")
    target_spec_fingerprint = next(iter(target_fingerprints))

    cost_fraction = cfg.round_trip_cost_bps / 10_000.0
    static_returns = [
        row[4].forward_return - cost_fraction
        for row in resolved_rows
        if row[2]
    ]
    challenger_returns = [
        row[4].forward_return - cost_fraction
        for row in resolved_rows
        if row[3]
    ]
    resolved_count = len(resolved_rows)
    static_point = _point("STATIC_45_35_20", static_returns, resolved_count)
    challenger_point = _point(
        "ADAPTIVE_DISAGREEMENT_GUARDED",
        challenger_returns,
        resolved_count,
    )

    expectancy_uplift = (
        None
        if static_point.expectancy_bps is None or challenger_point.expectancy_bps is None
        else challenger_point.expectancy_bps - static_point.expectancy_bps
    )
    total_return_uplift = (
        challenger_point.total_net_return - static_point.total_net_return
    )
    drawdown_change = (
        challenger_point.maximum_drawdown - static_point.maximum_drawdown
    )

    reasons: list[str] = []
    insufficient = False
    if resolved_count < cfg.minimum_resolved_requests:
        reasons.append("INSUFFICIENT_RESOLVED_SHADOW_REQUESTS")
        insufficient = True
    if static_point.trade_count < cfg.minimum_static_trades:
        reasons.append("INSUFFICIENT_STATIC_CHAMPION_TRADES")
        insufficient = True
    if challenger_point.trade_count < cfg.minimum_challenger_trades:
        reasons.append("INSUFFICIENT_GUARDED_CHALLENGER_TRADES")
        insufficient = True
    if challenger_point.coverage < cfg.minimum_challenger_coverage:
        reasons.append("GUARDED_CHALLENGER_COVERAGE_BELOW_MINIMUM")
        insufficient = True

    if insufficient or expectancy_uplift is None:
        assessment: Assessment = "INSUFFICIENT_EVIDENCE"
        required_next_gate = "V3_06_CHALLENGER_RESEARCH_REVIEW"
    else:
        expectancy_passed = expectancy_uplift >= cfg.minimum_expectancy_uplift_bps
        drawdown_passed = drawdown_change <= cfg.maximum_drawdown_regression
        if expectancy_passed and drawdown_passed:
            assessment = "CHALLENGER_EVIDENCE_SUPERIOR"
            required_next_gate = "V3_06_POLICY_GOVERNANCE_REVIEW"
            reasons.append("GUARDED_CHALLENGER_EXPECTANCY_GATE_PASSED")
            reasons.append("GUARDED_CHALLENGER_DRAWDOWN_GATE_PASSED")
        else:
            assessment = "STATIC_CHAMPION_PRESERVED"
            required_next_gate = "V3_06_CHALLENGER_RESEARCH_REVIEW"
            if not expectancy_passed:
                reasons.append("GUARDED_CHALLENGER_EXPECTANCY_GATE_FAILED")
            if not drawdown_passed:
                reasons.append("GUARDED_CHALLENGER_DRAWDOWN_GATE_FAILED")

    if unresolved:
        reasons.append("UNRESOLVED_SHADOW_OUTCOMES_EXCLUDED_AT_CUTOFF")
    if not reasons:
        reasons.append("FUSION_POLICY_SHADOW_EVALUATION_COMPLETE")
    reasons = sorted(set(reasons))

    payload = {
        "contract_version": FUSION_POLICY_SHADOW_EVALUATION_VERSION,
        "evaluation_as_of": evaluation_as_of.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "target_spec_fingerprint": target_spec_fingerprint,
        "eligible_request_count": len(eligible_requests),
        "resolved_request_count": resolved_count,
        "unresolved_request_count": unresolved,
        "static_champion": static_point.model_dump(mode="json"),
        "guarded_challenger": challenger_point.model_dump(mode="json"),
        "expectancy_uplift_bps": expectancy_uplift,
        "total_net_return_uplift": total_return_uplift,
        "maximum_drawdown_change": drawdown_change,
        "assessment": assessment,
        "reason_codes": reasons,
        "source_request_fingerprints": request_fingerprints,
        "source_adaptive_decision_fingerprints": adaptive_fingerprints,
        "source_disagreement_control_fingerprints": control_fingerprints,
        "source_static_opportunity_fingerprints": [
            item.opportunity_fingerprint for item in static_opportunities
        ],
        "source_guarded_opportunity_fingerprints": guarded_opportunity_fingerprints,
        "source_outcome_fingerprints": [row[4].example_fingerprint for row in resolved_rows],
        "required_next_gate": required_next_gate,
        "status": "RESEARCH_SHADOW_EVALUATION_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return FusionPolicyShadowEvaluationReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


def _point(
    policy: Literal["STATIC_45_35_20", "ADAPTIVE_DISAGREEMENT_GUARDED"],
    returns: Sequence[float],
    resolved_count: int,
) -> FusionPolicyShadowPoint:
    trade_count = len(returns)
    total_return = sum(returns)
    mean_return = total_return / trade_count if trade_count else None
    wins = sum(value > 0.0 for value in returns)
    win_rate = wins / trade_count if trade_count else 0.0
    coverage = trade_count / resolved_count if resolved_count else 0.0
    expectancy_bps = None if mean_return is None else mean_return * 10_000.0
    return FusionPolicyShadowPoint(
        policy=policy,
        coverage=coverage,
        trade_count=trade_count,
        winning_trades=wins,
        win_rate=win_rate,
        total_net_return=total_return,
        mean_net_return=mean_return,
        expectancy_bps=expectancy_bps,
        maximum_drawdown=_maximum_drawdown(returns),
    )


def _maximum_drawdown(returns: Sequence[float]) -> float:
    equity = 1.0
    peak = 1.0
    maximum_drawdown = 0.0
    for net_return in returns:
        if not math.isfinite(net_return):
            raise ValueError("shadow policy returns must be finite")
        equity *= max(0.0, 1.0 + net_return)
        peak = max(peak, equity)
        drawdown = 0.0 if peak <= 0.0 else (peak - equity) / peak
        maximum_drawdown = max(maximum_drawdown, drawdown)
    return min(1.0, maximum_drawdown)
