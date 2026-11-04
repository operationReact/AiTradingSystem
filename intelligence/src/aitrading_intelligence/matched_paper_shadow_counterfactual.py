from __future__ import annotations

import argparse
import json
import math
import os
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator

from .fingerprints import canonical_fingerprint

VERSION = "v4-matched-paper-shadow-counterfactual.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Action = Literal["ENTER", "WAIT", "ABSTAIN"]
Role = Literal["PAPER_CHAMPION", "SHADOW_CHALLENGER"]
Origin = Literal["CI_SYNTHETIC", "RUNTIME_EXPORT"]


def _aware(timestamp: datetime) -> datetime:
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise ValueError("counterfactual timestamps must be timezone-aware")
    return timestamp


def _iso(timestamp: datetime) -> str:
    """Use the same UTC/Z timestamp format as Pydantic JSON fingerprints."""
    return TypeAdapter(datetime).dump_python(_aware(timestamp), mode="json")


class RegisteredPaperShadowModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")]
    registry_fingerprint: Hash64
    role: Role


class PaperShadowEnrollment(BaseModel):
    """Self-declared pre-decision registration, not an external timestamp witness."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-matched-paper-shadow-counterfactual.v1"] = VERSION
    registered_at: datetime
    target_spec_fingerprint: Hash64
    models: list[RegisteredPaperShadowModel] = Field(min_length=2, max_length=10)
    minimum_cases: Annotated[int, Field(ge=2, le=100000)] = 30
    minimum_enter_decisions: Annotated[int, Field(ge=1, le=100000)] = 5
    assumed_round_trip_cost_bps: Annotated[float, Field(ge=0.0, le=500)] = 10.0
    external_registration_witnessed: Literal[False] = False
    live_order_authority: Literal[False] = False
    enrollment_fingerprint: Hash64

    @model_validator(mode="after")
    def check_registration(self) -> PaperShadowEnrollment:
        _aware(self.registered_at)
        if [model.model_id for model in self.models] != sorted(
            model.model_id for model in self.models
        ):
            raise ValueError("PAPER/SHADOW models must be sorted by model ID")
        if len({model.model_id for model in self.models}) != len(self.models):
            raise ValueError("PAPER/SHADOW model IDs must be unique")
        if sum(model.role == "PAPER_CHAMPION" for model in self.models) != 1:
            raise ValueError("exactly one PAPER champion is required")
        if any(
            model.role != "PAPER_CHAMPION" for model in self.models
        ) is False:
            raise ValueError("at least one SHADOW challenger is required")
        if self.enrollment_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"enrollment_fingerprint"})
        ):
            raise ValueError("PAPER/SHADOW enrollment fingerprint mismatch")
        return self


def freeze_paper_shadow_enrollment(
    *,
    registered_at: datetime,
    target_spec_fingerprint: str,
    models: list[RegisteredPaperShadowModel],
    minimum_cases: int = 30,
    minimum_enter_decisions: int = 5,
    assumed_round_trip_cost_bps: float = 10.0,
) -> PaperShadowEnrollment:
    payload = {
        "contract_version": VERSION,
        "registered_at": _iso(registered_at),
        "target_spec_fingerprint": target_spec_fingerprint,
        "models": [
            model.model_dump(mode="json")
            for model in sorted(models, key=lambda model: model.model_id)
        ],
        "minimum_cases": minimum_cases,
        "minimum_enter_decisions": minimum_enter_decisions,
        "assumed_round_trip_cost_bps": float(assumed_round_trip_cost_bps),
        "external_registration_witnessed": False,
        "live_order_authority": False,
    }
    temporary = PaperShadowEnrollment.model_construct(
        **payload, enrollment_fingerprint="0" * 64
    )
    return PaperShadowEnrollment(
        **payload,
        enrollment_fingerprint=canonical_fingerprint(
            temporary.model_dump(mode="json", exclude={"enrollment_fingerprint"})
        ),
    )


class MatchedModelDecision(BaseModel):
    """Model-only inference output; no future market label and no broker order."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    model_id: str
    registry_fingerprint: Hash64
    predicted_at: datetime
    action: Action
    input_fingerprint: Hash64
    decision_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_decision(self) -> MatchedModelDecision:
        _aware(self.predicted_at)
        if self.decision_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"decision_fingerprint"})
        ):
            raise ValueError("model decision fingerprint mismatch")
        return self


def build_matched_model_decision(
    *,
    model_id: str,
    registry_fingerprint: str,
    predicted_at: datetime,
    action: Action,
    input_fingerprint: str,
) -> MatchedModelDecision:
    payload = {
        "model_id": model_id,
        "registry_fingerprint": registry_fingerprint,
        "predicted_at": _iso(predicted_at),
        "action": action,
        "input_fingerprint": input_fingerprint,
    }
    return MatchedModelDecision(
        **payload, decision_fingerprint=canonical_fingerprint(payload)
    )


class MatchedMarketObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    case_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")]
    symbol: Annotated[str, Field(min_length=2, max_length=30)]
    decision_at: datetime
    market_available_at: datetime
    news_available_at: datetime
    future_outcome_available_at: datetime
    market_source_fingerprint: Hash64
    news_source_fingerprint: Hash64
    input_fingerprint: Hash64
    observed_forward_return_bps: Annotated[float, Field(ge=-10000, le=30000)]
    decisions: list[MatchedModelDecision] = Field(min_length=2, max_length=10)

    @model_validator(mode="after")
    def validate_case(self) -> MatchedMarketObservation:
        for stamp in (
            self.decision_at, self.market_available_at, self.news_available_at,
            self.future_outcome_available_at,
        ):
            _aware(stamp)
        if not (
            self.market_available_at <= self.decision_at
            and self.news_available_at <= self.decision_at
            < self.future_outcome_available_at
        ):
            raise ValueError("market/news must precede decision and future label")
        if not math.isfinite(self.observed_forward_return_bps):
            raise ValueError("market return must be finite")
        if [d.model_id for d in self.decisions] != sorted(
            d.model_id for d in self.decisions
        ):
            raise ValueError("model decisions must be sorted by ID")
        if len({d.model_id for d in self.decisions}) != len(self.decisions):
            raise ValueError("duplicate model decision for the same market case")
        for decision in self.decisions:
            if (
                decision.predicted_at < max(
                    self.market_available_at, self.news_available_at
                )
                or decision.predicted_at > self.decision_at
            ):
                raise ValueError("model prediction used unavailable market or news data")
            if decision.input_fingerprint != self.input_fingerprint:
                raise ValueError("models did not use the same frozen input identity")
        return self


class PaperShadowCaseResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    case_id: str
    champion_action: Action
    shadow_disagreements: Annotated[int, Field(ge=0, le=9)]
    source_input_fingerprint: Hash64
    future_outcome_available_at: datetime
    row_fingerprint: Hash64

    @model_validator(mode="after")
    def check_row(self) -> PaperShadowCaseResult:
        if self.row_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"row_fingerprint"})
        ):
            raise ValueError("counterfactual row fingerprint mismatch")
        return self


class PaperShadowModelMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_id: str
    role: Role
    registry_fingerprint: Hash64
    total_cases: Annotated[int, Field(ge=1)]
    enter_count: Annotated[int, Field(ge=0)]
    wait_count: Annotated[int, Field(ge=0)]
    abstain_count: Annotated[int, Field(ge=0)]
    action_coverage: Annotated[float, Field(ge=0, le=1)]
    hypothetical_mean_net_bps: float | None
    hypothetical_total_net_bps: float
    hypothetical_max_drawdown_bps: Annotated[float, Field(ge=0)]
    mean_net_return_independently_verified: Literal[False] = False


class MatchedPaperShadowCounterfactualReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-matched-paper-shadow-counterfactual.v1"] = VERSION
    enrollment_fingerprint: Hash64
    capture_origin: Origin
    evaluated_at: datetime
    matched_case_count: Annotated[int, Field(ge=2)]
    case_rows: list[PaperShadowCaseResult] = Field(min_length=2, max_length=100000)
    model_metrics: list[PaperShadowModelMetrics] = Field(min_length=2, max_length=10)
    cases_with_any_shadow_disagreement: Annotated[int, Field(ge=0)]
    assessment: Literal["COUNTERFACTUAL_RESEARCH_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    actual_paper_orders_submitted: Literal[False] = False
    actual_shadow_orders_submitted: Literal[False] = False
    actual_paper_fills_verified: Literal[False] = False
    original_market_data_custody_verified: Literal[False] = False
    genuine_live_runtime_soak_verified: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_report(self) -> MatchedPaperShadowCounterfactualReport:
        _aware(self.evaluated_at)
        if self.matched_case_count != len(self.case_rows):
            raise ValueError("counterfactual case count does not reconcile")
        if len({row.case_id for row in self.case_rows}) != len(self.case_rows):
            raise ValueError("duplicate case results")
        if self.case_rows != sorted(self.case_rows, key=lambda r: r.case_id):
            raise ValueError("case results must be sorted by ID")
        if self.cases_with_any_shadow_disagreement != sum(
            row.shadow_disagreements > 0 for row in self.case_rows
        ):
            raise ValueError("shadow disagreement totals do not reconcile")
        if [r.model_id for r in self.model_metrics] != sorted(
            r.model_id for r in self.model_metrics
        ):
            raise ValueError("model metrics must be sorted by model ID")
        for entry in self.model_metrics:
            if (
                entry.enter_count + entry.wait_count + entry.abstain_count
                != self.matched_case_count
            ):
                raise ValueError("PAPER/SHADOW model decision counts do not reconcile")
            if not math.isclose(
                entry.action_coverage,
                entry.enter_count / self.matched_case_count,
                abs_tol=1e-12,
            ):
                raise ValueError("model decision coverage does not reconcile")
        if self.reason_codes != sorted(set(self.reason_codes)):
            raise ValueError("counterfactual reason codes must be sorted and unique")
        if self.assessment == "INCONCLUSIVE" and not self.reason_codes:
            raise ValueError("inconclusive evaluation requires a reason")
        if self.assessment == "COUNTERFACTUAL_RESEARCH_ONLY" and self.reason_codes:
            raise ValueError("research comparison with sufficient support needs no reason")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("counterfactual report fingerprint mismatch")
        return self


def _summary(
    model: RegisteredPaperShadowModel,
    cases: list[MatchedMarketObservation],
    cost_bps: float,
) -> PaperShadowModelMetrics:
    actions = [
        next(d.action for d in case.decisions if d.model_id == model.model_id)
        for case in cases
    ]
    realized = [
        case.observed_forward_return_bps - cost_bps
        for case, action in zip(cases, actions, strict=True)
        if action == "ENTER"
    ]
    cumulative = peak = drawdown = 0.0
    for gain in (
        case.observed_forward_return_bps - cost_bps if action == "ENTER" else 0.0
        for case, action in zip(cases, actions, strict=True)
    ):
        cumulative += gain
        peak = max(peak, cumulative)
        drawdown = max(drawdown, peak - cumulative)
    count = actions.count("ENTER")
    return PaperShadowModelMetrics(
        model_id=model.model_id,
        role=model.role,
        registry_fingerprint=model.registry_fingerprint,
        total_cases=len(cases),
        enter_count=count,
        wait_count=actions.count("WAIT"),
        abstain_count=actions.count("ABSTAIN"),
        action_coverage=count / len(cases),
        hypothetical_mean_net_bps=sum(realized) / count if count else None,
        hypothetical_total_net_bps=sum(realized),
        hypothetical_max_drawdown_bps=drawdown,
    )


def evaluate_matched_paper_shadow(
    enrollment: PaperShadowEnrollment,
    observations: list[MatchedMarketObservation],
    *,
    evaluated_at: datetime,
    capture_origin: Origin,
) -> MatchedPaperShadowCounterfactualReport:
    """Compare matched decisions offline; no broker request, fill or LIVE control."""
    plan = PaperShadowEnrollment.model_validate(enrollment.model_dump(mode="python"))
    _aware(evaluated_at)
    ordered = sorted(
        [MatchedMarketObservation.model_validate(case.model_dump(mode="python"))
         for case in observations],
        key=lambda case: (case.decision_at, case.case_id),
    )
    if len(ordered) < 2:
        raise ValueError("at least two matched observations are required")
    if len(ordered) > 100000:
        raise ValueError("matched cohort exceeds bounded evaluation capacity")
    if len({case.case_id for case in ordered}) != len(ordered):
        raise ValueError("duplicate source case in PAPER/SHADOW cohort")
    if plan.registered_at >= ordered[0].decision_at:
        raise ValueError("PAPER/SHADOW enrollment must predate all model decisions")
    if max(case.future_outcome_available_at for case in ordered) > evaluated_at:
        raise ValueError("unavailable future market outcomes cannot be evaluated")
    known = {model.model_id: model for model in plan.models}
    expected = set(known)
    for case in ordered:
        if {decision.model_id for decision in case.decisions} != expected:
            raise ValueError("PAPER/SHADOW candidate roster differs from frozen enrollment")
        for decision in case.decisions:
            if decision.registry_fingerprint != known[decision.model_id].registry_fingerprint:
                raise ValueError("model decision registry lineage mismatches frozen plan")
    champion_id = next(
        model.model_id for model in plan.models if model.role == "PAPER_CHAMPION"
    )
    rows = []
    for case in sorted(ordered, key=lambda case: case.case_id):
        actions = {d.model_id: d.action for d in case.decisions}
        payload = {
            "case_id": case.case_id,
            "champion_action": actions[champion_id],
            "shadow_disagreements": sum(
                actions[model.model_id] != actions[champion_id]
                for model in plan.models if model.role == "SHADOW_CHALLENGER"
            ),
            "source_input_fingerprint": case.input_fingerprint,
            "future_outcome_available_at": _iso(case.future_outcome_available_at),
        }
        rows.append(PaperShadowCaseResult(
            **payload, row_fingerprint=canonical_fingerprint(payload)
        ))
    metrics = [
        _summary(model, ordered, plan.assumed_round_trip_cost_bps)
        for model in plan.models
    ]
    reasons = []
    if len(ordered) < plan.minimum_cases:
        reasons.append("INSUFFICIENT_MATCHED_CASES")
    if any(
        metric.enter_count < plan.minimum_enter_decisions for metric in metrics
    ):
        reasons.append("INSUFFICIENT_ENTER_DECISIONS")
    if capture_origin == "CI_SYNTHETIC":
        reasons.append("CI_FIXTURE_NOT_REAL_RUNTIME")
    payload = {
        "contract_version": VERSION,
        "enrollment_fingerprint": plan.enrollment_fingerprint,
        "capture_origin": capture_origin,
        "evaluated_at": _iso(evaluated_at),
        "matched_case_count": len(ordered),
        "case_rows": [row.model_dump(mode="json") for row in rows],
        "model_metrics": [metric.model_dump(mode="json") for metric in metrics],
        "cases_with_any_shadow_disagreement": sum(
            row.shadow_disagreements > 0 for row in rows
        ),
        "assessment": "INCONCLUSIVE" if reasons else "COUNTERFACTUAL_RESEARCH_ONLY",
        "reason_codes": sorted(reasons),
        "actual_paper_orders_submitted": False,
        "actual_shadow_orders_submitted": False,
        "actual_paper_fills_verified": False,
        "original_market_data_custody_verified": False,
        "genuine_live_runtime_soak_verified": False,
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return MatchedPaperShadowCounterfactualReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Offline, read-only V4 matched PAPER/SHADOW research comparison"
    )
    parser.add_argument("enrollment", type=Path)
    parser.add_argument("matched_observations", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--evaluated-at", required=True)
    parser.add_argument(
        "--capture-origin", choices=("CI_SYNTHETIC", "RUNTIME_EXPORT"),
        default="CI_SYNTHETIC",
    )
    args = parser.parse_args()
    result = evaluate_matched_paper_shadow(
        PaperShadowEnrollment.model_validate_json(
            args.enrollment.read_text(encoding="utf-8")
        ),
        [
            MatchedMarketObservation.model_validate(row)
            for row in json.loads(args.matched_observations.read_text(encoding="utf-8"))
        ],
        evaluated_at=datetime.fromisoformat(args.evaluated_at),
        capture_origin=args.capture_origin,
    )
    fd = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as target:
        target.write(result.model_dump_json(indent=2) + "\n")
    print(result.report_fingerprint)


if __name__ == "__main__":
    main()
