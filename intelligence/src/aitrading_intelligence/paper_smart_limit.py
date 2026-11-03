from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .execution_plan import ExecutionPlanV1
from .fill_simulator import FillSimulationResultV1
from .fingerprints import canonical_fingerprint

PAPER_SMART_LIMIT_VERSION = "paper-smart-limit.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
TradeSide = Literal["BUY", "SELL"]
PaperAction = Literal[
    "START_LIMIT",
    "REPRICE_LIMIT",
    "COMPLETE",
    "STOP",
    "INCONCLUSIVE",
]


class PaperSmartLimitConfigV1(BaseModel):
    """Deterministic PAPER-only limit/reprice policy.

    The policy never falls back to MARKET and never submits a broker order. Price
    aggression is bounded by the execution plan's expected slippage evidence and,
    for an original LIMIT plan, by that original limit as an additional hard bound.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-limit.v1"] = PAPER_SMART_LIMIT_VERSION
    initial_fraction_of_expected_slippage: Annotated[float, Field(gt=0.0, le=1.0)] = 0.50
    reprice_step_fraction_of_expected_slippage: Annotated[
        float,
        Field(gt=0.0, le=1.0),
    ] = 0.25
    max_expected_slippage_multiplier: Annotated[float, Field(gt=0.0, le=5.0)] = 1.0
    absolute_max_concession_bps: Annotated[float, Field(gt=0.0, le=500.0)] = 50.0
    max_reprices: Annotated[int, Field(ge=0, le=20)] = 3

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PaperSmartLimitDecisionV1(BaseModel):
    """One non-executable decision in a chained PAPER smart-limit policy."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-limit.v1"] = PAPER_SMART_LIMIT_VERSION
    action: PaperAction
    reason_codes: list[str]
    side: TradeSide
    execution_plan_fingerprint: Hash64
    candidate_fingerprint: Hash64
    cost_model_fingerprint: Hash64 | None = None
    slippage_estimate_fingerprint: Hash64
    fill_config_fingerprint: Hash64
    config_fingerprint: Hash64
    prior_decision_fingerprint: Hash64 | None = None
    prior_fill_result_fingerprint: Hash64 | None = None
    attempt_index: Annotated[int, Field(ge=0)]
    reprice_count: Annotated[int, Field(ge=0)]
    requested_quantity: Annotated[int, Field(ge=1)]
    remaining_quantity: Annotated[int, Field(ge=0)]
    reference_price: Annotated[float, Field(gt=0.0)]
    expected_slippage_bps: Annotated[float | None, Field(ge=0.0)] = None
    maximum_concession_bps: Annotated[float | None, Field(ge=0.0)] = None
    previous_limit_price: Annotated[float | None, Field(gt=0.0)] = None
    proposed_limit_price: Annotated[float | None, Field(gt=0.0)] = None
    proposed_concession_bps: float | None = None
    decision_fingerprint: Hash64
    status: Literal["PAPER_SMART_LIMIT_POLICY_ONLY"] = "PAPER_SMART_LIMIT_POLICY_ONLY"
    paper_only: Literal[True] = True
    executable: Literal[False] = False
    market_fallback_allowed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("proposed_concession_bps")
    @classmethod
    def concession_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("proposed concession must be finite")
        return value

    @model_validator(mode="after")
    def validate_decision(self) -> PaperSmartLimitDecisionV1:
        active = self.action in ("START_LIMIT", "REPRICE_LIMIT")
        if active:
            if self.reason_codes:
                raise ValueError("active PAPER limit decision cannot contain reason codes")
            if self.remaining_quantity <= 0:
                raise ValueError("active PAPER limit decision requires remaining quantity")
            required = (
                self.cost_model_fingerprint,
                self.expected_slippage_bps,
                self.maximum_concession_bps,
                self.proposed_limit_price,
                self.proposed_concession_bps,
            )
            if any(value is None for value in required):
                raise ValueError("active PAPER limit decision requires complete price evidence")
            assert self.maximum_concession_bps is not None
            assert self.proposed_concession_bps is not None
            if self.proposed_concession_bps > self.maximum_concession_bps + 1e-9:
                raise ValueError("proposed PAPER limit exceeds maximum concession")
        else:
            if not self.reason_codes:
                raise ValueError("terminal PAPER limit decision requires reason codes")
            if self.proposed_limit_price is not None or self.proposed_concession_bps is not None:
                raise ValueError("terminal PAPER limit decision cannot propose another price")
        if self.action == "COMPLETE" and self.remaining_quantity != 0:
            raise ValueError("complete PAPER limit decision requires zero remaining quantity")
        if self.action == "START_LIMIT":
            if self.attempt_index != 0 or self.reprice_count != 0:
                raise ValueError("initial PAPER limit decision must start at attempt zero")
            if self.prior_decision_fingerprint is not None:
                raise ValueError("initial PAPER limit decision cannot have prior decision")
            if self.prior_fill_result_fingerprint is not None:
                raise ValueError("initial PAPER limit decision cannot have prior fill")
        if self.action == "REPRICE_LIMIT":
            if self.attempt_index < 1 or self.reprice_count < 1:
                raise ValueError("reprice decision must advance attempt and reprice counters")
            if self.prior_decision_fingerprint is None or self.prior_fill_result_fingerprint is None:
                raise ValueError("reprice decision requires prior decision and fill lineage")
        if self.decision_fingerprint != canonical_fingerprint(_decision_payload(self)):
            raise ValueError("PAPER smart-limit decision fingerprint mismatch")
        return self


def _decision_payload(decision: PaperSmartLimitDecisionV1) -> dict[str, object]:
    return decision.model_dump(mode="json", exclude={"decision_fingerprint"})


def _concession_bps(side: TradeSide, reference: float, price: float) -> float:
    move = (price / reference) - 1.0
    return move * 10_000.0 if side == "BUY" else -move * 10_000.0


def _price_for_concession(side: TradeSide, reference: float, bps: float) -> float:
    fraction = bps / 10_000.0
    price = reference * (1.0 + fraction if side == "BUY" else 1.0 - fraction)
    if price <= 0.0 or not math.isfinite(price):
        raise ValueError("PAPER smart-limit price must remain positive and finite")
    return round(price, 8)


def _apply_original_limit_bound(plan: ExecutionPlanV1, proposed: float) -> float:
    if plan.order_type != "LIMIT":
        return proposed
    assert plan.limit_price is not None
    if plan.side == "BUY":
        return min(proposed, plan.limit_price)
    return max(proposed, plan.limit_price)


def _validate_plan(plan: ExecutionPlanV1) -> ExecutionPlanV1:
    checked = ExecutionPlanV1.model_validate(plan.model_dump(mode="python"))
    if checked.state != "PLANNED" or checked.expected_cost is None:
        return checked
    if checked.expected_cost.slippage_estimate_fingerprint != checked.slippage_estimate_fingerprint:
        raise ValueError("execution plan slippage lineage mismatch")
    if checked.expected_cost.fill_config_fingerprint != checked.fill_config_fingerprint:
        raise ValueError("execution plan fill-config lineage mismatch")
    return checked


def _validate_prior_decision(
    *,
    plan: ExecutionPlanV1,
    prior: PaperSmartLimitDecisionV1,
    config: PaperSmartLimitConfigV1,
) -> PaperSmartLimitDecisionV1:
    checked = PaperSmartLimitDecisionV1.model_validate(prior.model_dump(mode="python"))
    if checked.execution_plan_fingerprint != plan.plan_fingerprint:
        raise ValueError("prior PAPER decision execution-plan lineage mismatch")
    if checked.candidate_fingerprint != plan.candidate_fingerprint:
        raise ValueError("prior PAPER decision candidate lineage mismatch")
    if checked.slippage_estimate_fingerprint != plan.slippage_estimate_fingerprint:
        raise ValueError("prior PAPER decision slippage lineage mismatch")
    if checked.fill_config_fingerprint != plan.fill_config_fingerprint:
        raise ValueError("prior PAPER decision fill-config lineage mismatch")
    if checked.config_fingerprint != config.fingerprint:
        raise ValueError("prior PAPER decision policy-config mismatch")
    if checked.action not in ("START_LIMIT", "REPRICE_LIMIT"):
        raise ValueError("only an active PAPER limit decision can be continued")
    return checked


def _validate_fill_against_prior(
    *,
    plan: ExecutionPlanV1,
    prior: PaperSmartLimitDecisionV1,
    fill: FillSimulationResultV1,
) -> FillSimulationResultV1:
    checked = FillSimulationResultV1.model_validate(fill.model_dump(mode="python"))
    request = checked.request
    if request.side != plan.side:
        raise ValueError("PAPER fill side does not match execution plan")
    if request.order_type != "LIMIT":
        raise ValueError("PAPER smart-limit policy only accepts LIMIT fill evidence")
    if request.quantity != prior.remaining_quantity:
        raise ValueError("PAPER fill quantity does not match prior remaining quantity")
    if not math.isclose(request.reference_price, plan.reference_price, abs_tol=1e-12):
        raise ValueError("PAPER fill reference price does not match execution plan")
    if request.limit_price is None or prior.proposed_limit_price is None:
        raise ValueError("PAPER fill and prior decision require limit price")
    if not math.isclose(request.limit_price, prior.proposed_limit_price, abs_tol=1e-12):
        raise ValueError("PAPER fill limit price does not match prior decision")
    if request.canonical_instrument_id != plan.canonical_instrument_id:
        raise ValueError("PAPER fill instrument lineage mismatch")
    if request.canonical_symbol != plan.canonical_symbol:
        raise ValueError("PAPER fill symbol lineage mismatch")
    if request.broker_trading_symbol != plan.broker_trading_symbol:
        raise ValueError("PAPER fill broker-symbol lineage mismatch")
    if request.identity_bridge_fingerprint != plan.identity_bridge_fingerprint:
        raise ValueError("PAPER fill identity-bridge lineage mismatch")
    if request.identity_binding_fingerprint != plan.identity_binding_fingerprint:
        raise ValueError("PAPER fill identity-binding lineage mismatch")
    if request.slippage_estimate_fingerprint != plan.slippage_estimate_fingerprint:
        raise ValueError("PAPER fill slippage lineage mismatch")
    if request.config_fingerprint != plan.fill_config_fingerprint:
        raise ValueError("PAPER fill simulator-config lineage mismatch")
    return checked


def _build_decision(**payload: object) -> PaperSmartLimitDecisionV1:
    provisional = PaperSmartLimitDecisionV1.model_construct(
        **payload,
        decision_fingerprint="0" * 64,
    )
    return PaperSmartLimitDecisionV1(
        **payload,
        decision_fingerprint=canonical_fingerprint(_decision_payload(provisional)),
    )


def decide_paper_smart_limit(
    *,
    plan: ExecutionPlanV1,
    config: PaperSmartLimitConfigV1 | None = None,
    prior_decision: PaperSmartLimitDecisionV1 | None = None,
    prior_fill: FillSimulationResultV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    """Produce the next deterministic PAPER-only limit action; never submit an order."""

    cfg = config or PaperSmartLimitConfigV1()
    checked_plan = _validate_plan(plan)
    expected_cost = checked_plan.expected_cost

    common: dict[str, object] = {
        "policy_version": PAPER_SMART_LIMIT_VERSION,
        "side": checked_plan.side,
        "execution_plan_fingerprint": checked_plan.plan_fingerprint,
        "candidate_fingerprint": checked_plan.candidate_fingerprint,
        "cost_model_fingerprint": (
            expected_cost.cost_model_fingerprint if expected_cost is not None else None
        ),
        "slippage_estimate_fingerprint": checked_plan.slippage_estimate_fingerprint,
        "fill_config_fingerprint": checked_plan.fill_config_fingerprint,
        "config_fingerprint": cfg.fingerprint,
        "requested_quantity": checked_plan.quantity,
        "reference_price": checked_plan.reference_price,
        "status": "PAPER_SMART_LIMIT_POLICY_ONLY",
        "paper_only": True,
        "executable": False,
        "market_fallback_allowed": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }

    if checked_plan.state != "PLANNED" or expected_cost is None:
        return _build_decision(
            **common,
            action="INCONCLUSIVE",
            reason_codes=["EXECUTION_PLAN_INCONCLUSIVE"],
            prior_decision_fingerprint=None,
            prior_fill_result_fingerprint=None,
            attempt_index=0,
            reprice_count=0,
            remaining_quantity=checked_plan.quantity,
            expected_slippage_bps=None,
            maximum_concession_bps=None,
            previous_limit_price=None,
            proposed_limit_price=None,
            proposed_concession_bps=None,
        )

    expected_slippage = expected_cost.expected_slippage_bps
    maximum_concession = min(
        expected_slippage * cfg.max_expected_slippage_multiplier,
        cfg.absolute_max_concession_bps,
    )

    if prior_decision is None and prior_fill is None:
        initial_concession = min(
            expected_slippage * cfg.initial_fraction_of_expected_slippage,
            maximum_concession,
        )
        proposed = _apply_original_limit_bound(
            checked_plan,
            _price_for_concession(
                checked_plan.side,
                checked_plan.reference_price,
                initial_concession,
            ),
        )
        concession = _concession_bps(
            checked_plan.side,
            checked_plan.reference_price,
            proposed,
        )
        if concession > maximum_concession + 1e-9:
            raise ValueError("original execution-plan limit exceeds smart-limit concession budget")
        return _build_decision(
            **common,
            action="START_LIMIT",
            reason_codes=[],
            prior_decision_fingerprint=None,
            prior_fill_result_fingerprint=None,
            attempt_index=0,
            reprice_count=0,
            remaining_quantity=checked_plan.quantity,
            expected_slippage_bps=expected_slippage,
            maximum_concession_bps=maximum_concession,
            previous_limit_price=None,
            proposed_limit_price=proposed,
            proposed_concession_bps=concession,
        )

    if prior_decision is None or prior_fill is None:
        raise ValueError("continuing PAPER smart-limit policy requires prior decision and fill")

    checked_prior = _validate_prior_decision(plan=checked_plan, prior=prior_decision, config=cfg)
    checked_fill = _validate_fill_against_prior(
        plan=checked_plan,
        prior=checked_prior,
        fill=prior_fill,
    )
    next_attempt = checked_prior.attempt_index + 1
    previous_limit = checked_prior.proposed_limit_price
    assert previous_limit is not None

    terminal_common = {
        **common,
        "prior_decision_fingerprint": checked_prior.decision_fingerprint,
        "prior_fill_result_fingerprint": checked_fill.result_fingerprint,
        "attempt_index": next_attempt,
        "reprice_count": checked_prior.reprice_count,
        "expected_slippage_bps": expected_slippage,
        "maximum_concession_bps": maximum_concession,
        "previous_limit_price": previous_limit,
        "proposed_limit_price": None,
        "proposed_concession_bps": None,
    }

    if checked_fill.state == "FULLY_FILLED":
        return _build_decision(
            **terminal_common,
            action="COMPLETE",
            reason_codes=["PAPER_FILL_COMPLETE"],
            remaining_quantity=0,
        )
    if checked_fill.state == "INCONCLUSIVE":
        return _build_decision(
            **terminal_common,
            action="INCONCLUSIVE",
            reason_codes=["PAPER_FILL_INCONCLUSIVE"],
            remaining_quantity=checked_fill.remaining_quantity,
        )

    remaining = checked_fill.remaining_quantity
    if remaining <= 0:
        return _build_decision(
            **terminal_common,
            action="COMPLETE",
            reason_codes=["PAPER_FILL_COMPLETE"],
            remaining_quantity=0,
        )
    if checked_prior.reprice_count >= cfg.max_reprices:
        return _build_decision(
            **terminal_common,
            action="STOP",
            reason_codes=["MAX_REPRICES_REACHED"],
            remaining_quantity=remaining,
        )

    current_concession = _concession_bps(
        checked_plan.side,
        checked_plan.reference_price,
        previous_limit,
    )
    step_bps = expected_slippage * cfg.reprice_step_fraction_of_expected_slippage
    next_concession = min(current_concession + step_bps, maximum_concession)
    proposed = _apply_original_limit_bound(
        checked_plan,
        _price_for_concession(
            checked_plan.side,
            checked_plan.reference_price,
            next_concession,
        ),
    )
    proposed_concession = _concession_bps(
        checked_plan.side,
        checked_plan.reference_price,
        proposed,
    )
    if proposed_concession > maximum_concession + 1e-9:
        raise ValueError("reprice exceeds smart-limit concession budget")

    more_aggressive = (
        proposed > previous_limit + 1e-12
        if checked_plan.side == "BUY"
        else proposed < previous_limit - 1e-12
    )
    if not more_aggressive:
        return _build_decision(
            **terminal_common,
            action="STOP",
            reason_codes=["LIMIT_BOUND_REACHED"],
            remaining_quantity=remaining,
        )

    return _build_decision(
        **common,
        action="REPRICE_LIMIT",
        reason_codes=[],
        prior_decision_fingerprint=checked_prior.decision_fingerprint,
        prior_fill_result_fingerprint=checked_fill.result_fingerprint,
        attempt_index=next_attempt,
        reprice_count=checked_prior.reprice_count + 1,
        remaining_quantity=remaining,
        expected_slippage_bps=expected_slippage,
        maximum_concession_bps=maximum_concession,
        previous_limit_price=previous_limit,
        proposed_limit_price=proposed,
        proposed_concession_bps=proposed_concession,
    )


__all__ = [
    "PAPER_SMART_LIMIT_VERSION",
    "PaperSmartLimitConfigV1",
    "PaperSmartLimitDecisionV1",
    "decide_paper_smart_limit",
]
