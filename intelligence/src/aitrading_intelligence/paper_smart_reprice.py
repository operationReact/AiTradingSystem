from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .execution_plan import ExecutionPlanV1
from .fill_simulator import FillSimulationResultV1
from .fingerprints import canonical_fingerprint

PAPER_SMART_REPRICE_VERSION = "paper-smart-reprice.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
PolicyState = Literal["PLACE_LIMIT", "REPRICE_LIMIT", "STOP", "INCONCLUSIVE"]
TradeSide = Literal["BUY", "SELL"]


class PaperSmartRepriceConfigV1(BaseModel):
    """Deterministic PAPER-only limit/reprice policy.

    The policy can only concede price inside the expected state-dependent slippage budget
    already frozen into execution-plan.v1. It never falls back to MARKET and never submits
    an order to a broker.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-reprice.v1"] = PAPER_SMART_REPRICE_VERSION
    initial_concession_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 0.50
    reprice_step_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 0.25
    maximum_concession_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 1.0
    max_reprices: Annotated[int, Field(ge=0, le=20)] = 3
    tick_size_inr: Annotated[float, Field(gt=0.0, le=100.0)] = 0.05

    @model_validator(mode="after")
    def validate_policy_bounds(self) -> PaperSmartRepriceConfigV1:
        if self.initial_concession_fraction > self.maximum_concession_fraction:
            raise ValueError("initial concession cannot exceed maximum concession")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PaperSmartLimitDecisionV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-reprice.v1"] = PAPER_SMART_REPRICE_VERSION
    state: PolicyState
    reason_codes: list[str]
    side: TradeSide
    execution_plan_fingerprint: Hash64
    cost_model_fingerprint: Hash64 | None = None
    slippage_estimate_fingerprint: Hash64
    fill_config_fingerprint: Hash64
    previous_decision_fingerprint: Hash64 | None = None
    prior_fill_result_fingerprint: Hash64 | None = None
    policy_config_fingerprint: Hash64
    attempt_index: Annotated[int, Field(ge=0)]
    requested_quantity: Annotated[int, Field(ge=1)]
    quantity_to_submit: Annotated[int | None, Field(ge=1)] = None
    reference_price: Annotated[float, Field(gt=0.0)]
    previous_limit_price: Annotated[float | None, Field(gt=0.0)] = None
    proposed_limit_price: Annotated[float | None, Field(gt=0.0)] = None
    expected_slippage_bps: Annotated[float | None, Field(ge=0.0)] = None
    concession_bps: Annotated[float | None, Field(ge=0.0)] = None
    maximum_concession_bps: Annotated[float | None, Field(ge=0.0)] = None
    decision_fingerprint: Hash64
    status: Literal["PAPER_SMART_LIMIT_POLICY_ONLY"] = "PAPER_SMART_LIMIT_POLICY_ONLY"
    paper_only: Literal[True] = True
    executable: Literal[False] = False
    broker_submission_allowed: Literal[False] = False
    market_fallback_allowed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_decision(self) -> PaperSmartLimitDecisionV1:
        action_fields = (
            self.quantity_to_submit,
            self.proposed_limit_price,
            self.expected_slippage_bps,
            self.concession_bps,
            self.maximum_concession_bps,
        )
        if self.state in ("PLACE_LIMIT", "REPRICE_LIMIT"):
            if any(value is None for value in action_fields):
                raise ValueError("actionable PAPER smart-limit decision requires complete price evidence")
            if self.state == "PLACE_LIMIT":
                if self.attempt_index != 0:
                    raise ValueError("initial smart-limit decision must use attempt_index zero")
                if self.previous_decision_fingerprint is not None:
                    raise ValueError("initial smart-limit decision cannot have previous decision lineage")
                if self.prior_fill_result_fingerprint is not None:
                    raise ValueError("initial smart-limit decision cannot have prior fill lineage")
                if self.previous_limit_price is not None:
                    raise ValueError("initial smart-limit decision cannot have previous limit price")
            else:
                if self.attempt_index < 1:
                    raise ValueError("reprice decision requires positive attempt_index")
                if self.previous_decision_fingerprint is None:
                    raise ValueError("reprice decision requires previous decision lineage")
                if self.prior_fill_result_fingerprint is None:
                    raise ValueError("reprice decision requires prior fill lineage")
                if self.previous_limit_price is None:
                    raise ValueError("reprice decision requires previous limit price")
        else:
            if any(value is not None for value in action_fields):
                raise ValueError("non-actionable PAPER decision cannot expose an order instruction")
            if not self.reason_codes:
                raise ValueError("non-actionable PAPER decision requires reason codes")

        if self.decision_fingerprint != canonical_fingerprint(_decision_payload(self)):
            raise ValueError("PAPER smart-limit decision fingerprint mismatch")
        return self


def _decision_payload(decision: PaperSmartLimitDecisionV1) -> dict[str, object]:
    return decision.model_dump(mode="json", exclude={"decision_fingerprint"})


def _checked_plan(plan: ExecutionPlanV1) -> ExecutionPlanV1:
    return ExecutionPlanV1.model_validate(plan.model_dump(mode="python"))


def _checked_previous(
    decision: PaperSmartLimitDecisionV1,
) -> PaperSmartLimitDecisionV1:
    return PaperSmartLimitDecisionV1.model_validate(decision.model_dump(mode="python"))


def _checked_fill(fill: FillSimulationResultV1) -> FillSimulationResultV1:
    return FillSimulationResultV1.model_validate(fill.model_dump(mode="python"))


def _price_from_concession(
    *,
    side: TradeSide,
    reference_price: float,
    concession_bps: float,
    tick_size: float,
) -> float:
    multiplier = 1.0 + concession_bps / 10_000.0 if side == "BUY" else 1.0 - concession_bps / 10_000.0
    raw_price = reference_price * multiplier
    if side == "BUY":
        ticks = math.floor((raw_price / tick_size) + 1e-12)
    else:
        ticks = math.ceil((raw_price / tick_size) - 1e-12)
    return max(tick_size, ticks * tick_size)


def _apply_plan_hard_limit(
    *,
    plan: ExecutionPlanV1,
    proposed_limit: float,
) -> float:
    if plan.order_type != "LIMIT" or plan.limit_price is None:
        return proposed_limit
    if plan.side == "BUY":
        return min(proposed_limit, plan.limit_price)
    return max(proposed_limit, plan.limit_price)


def _concession_bps_from_price(
    *,
    side: TradeSide,
    reference_price: float,
    limit_price: float,
) -> float:
    move = (limit_price / reference_price) - 1.0
    adverse = move if side == "BUY" else -move
    return max(0.0, adverse * 10_000.0)


def _build_decision(**payload: object) -> PaperSmartLimitDecisionV1:
    provisional = PaperSmartLimitDecisionV1.model_construct(
        **payload,
        decision_fingerprint="0" * 64,
    )
    return PaperSmartLimitDecisionV1(
        **payload,
        decision_fingerprint=canonical_fingerprint(_decision_payload(provisional)),
    )


def _non_actionable(
    *,
    state: Literal["STOP", "INCONCLUSIVE"],
    reason: str,
    plan: ExecutionPlanV1,
    config: PaperSmartRepriceConfigV1,
    attempt_index: int,
    previous: PaperSmartLimitDecisionV1 | None = None,
    fill: FillSimulationResultV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    return _build_decision(
        state=state,
        reason_codes=[reason],
        side=plan.side,
        execution_plan_fingerprint=plan.plan_fingerprint,
        cost_model_fingerprint=(
            plan.expected_cost.cost_model_fingerprint if plan.expected_cost is not None else None
        ),
        slippage_estimate_fingerprint=plan.slippage_estimate_fingerprint,
        fill_config_fingerprint=plan.fill_config_fingerprint,
        previous_decision_fingerprint=(
            previous.decision_fingerprint if previous is not None else None
        ),
        prior_fill_result_fingerprint=(
            fill.result_fingerprint if fill is not None else None
        ),
        policy_config_fingerprint=config.fingerprint,
        attempt_index=attempt_index,
        requested_quantity=plan.quantity,
        quantity_to_submit=None,
        reference_price=plan.reference_price,
        previous_limit_price=(previous.proposed_limit_price if previous is not None else None),
        proposed_limit_price=None,
        expected_slippage_bps=None,
        concession_bps=None,
        maximum_concession_bps=None,
    )


def _validate_reprice_lineage(
    *,
    plan: ExecutionPlanV1,
    previous: PaperSmartLimitDecisionV1,
    fill: FillSimulationResultV1,
    config: PaperSmartRepriceConfigV1,
) -> None:
    if previous.execution_plan_fingerprint != plan.plan_fingerprint:
        raise ValueError("previous PAPER decision does not belong to execution plan")
    if previous.policy_config_fingerprint != config.fingerprint:
        raise ValueError("PAPER reprice config changed between attempts")
    if previous.state not in ("PLACE_LIMIT", "REPRICE_LIMIT"):
        raise ValueError("previous PAPER decision is not an actionable limit instruction")
    if previous.proposed_limit_price is None or previous.quantity_to_submit is None:
        raise ValueError("previous PAPER decision is missing limit instruction")

    request = fill.request
    if request.order_type != "LIMIT":
        raise ValueError("PAPER smart reprice requires prior LIMIT fill simulation")
    if request.side != plan.side:
        raise ValueError("prior fill side does not match execution plan")
    if request.canonical_instrument_id != plan.canonical_instrument_id:
        raise ValueError("prior fill instrument does not match execution plan")
    if request.canonical_symbol != plan.canonical_symbol:
        raise ValueError("prior fill symbol does not match execution plan")
    if request.broker_trading_symbol != plan.broker_trading_symbol:
        raise ValueError("prior fill broker symbol does not match execution plan")
    if request.identity_bridge_fingerprint != plan.identity_bridge_fingerprint:
        raise ValueError("prior fill identity bridge does not match execution plan")
    if request.identity_binding_fingerprint != plan.identity_binding_fingerprint:
        raise ValueError("prior fill identity binding does not match execution plan")
    if request.slippage_estimate_fingerprint != plan.slippage_estimate_fingerprint:
        raise ValueError("prior fill slippage lineage does not match execution plan")
    if request.config_fingerprint != plan.fill_config_fingerprint:
        raise ValueError("prior fill simulator config does not match execution plan")
    if request.quantity != previous.quantity_to_submit:
        raise ValueError("prior fill quantity does not match previous PAPER instruction")
    if request.limit_price is None or not math.isclose(
        request.limit_price,
        previous.proposed_limit_price,
        rel_tol=1e-12,
        abs_tol=1e-12,
    ):
        raise ValueError("prior fill limit price does not match previous PAPER instruction")


def decide_paper_smart_limit(
    plan: ExecutionPlanV1,
    *,
    config: PaperSmartRepriceConfigV1 | None = None,
    previous_decision: PaperSmartLimitDecisionV1 | None = None,
    prior_fill_result: FillSimulationResultV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    """Emit one deterministic PAPER limit/reprice instruction without broker submission."""

    checked_plan = _checked_plan(plan)
    cfg = config or PaperSmartRepriceConfigV1()

    if checked_plan.state != "PLANNED" or checked_plan.expected_cost is None:
        return _non_actionable(
            state="INCONCLUSIVE",
            reason="EXECUTION_PLAN_INCONCLUSIVE",
            plan=checked_plan,
            config=cfg,
            attempt_index=0,
        )

    expected_slippage = checked_plan.expected_cost.expected_slippage_bps
    if expected_slippage <= 0.0:
        return _non_actionable(
            state="INCONCLUSIVE",
            reason="NO_POSITIVE_SLIPPAGE_BUDGET",
            plan=checked_plan,
            config=cfg,
            attempt_index=0,
        )

    if previous_decision is None and prior_fill_result is None:
        attempt_index = 0
        quantity = checked_plan.quantity
        state: PolicyState = "PLACE_LIMIT"
        reasons = ["INITIAL_SMART_LIMIT"]
        previous = None
        fill = None
    elif previous_decision is None or prior_fill_result is None:
        raise ValueError("reprice evaluation requires both previous decision and prior fill")
    else:
        previous = _checked_previous(previous_decision)
        fill = _checked_fill(prior_fill_result)
        _validate_reprice_lineage(
            plan=checked_plan,
            previous=previous,
            fill=fill,
            config=cfg,
        )
        attempt_index = previous.attempt_index + 1

        if fill.state == "INCONCLUSIVE":
            return _non_actionable(
                state="INCONCLUSIVE",
                reason="PRIOR_FILL_INCONCLUSIVE",
                plan=checked_plan,
                config=cfg,
                attempt_index=attempt_index,
                previous=previous,
                fill=fill,
            )
        if fill.state == "FULLY_FILLED" or fill.remaining_quantity == 0:
            return _non_actionable(
                state="STOP",
                reason="ORDER_ALREADY_FILLED",
                plan=checked_plan,
                config=cfg,
                attempt_index=attempt_index,
                previous=previous,
                fill=fill,
            )
        if previous.attempt_index >= cfg.max_reprices:
            return _non_actionable(
                state="STOP",
                reason="MAX_REPRICES_REACHED",
                plan=checked_plan,
                config=cfg,
                attempt_index=attempt_index,
                previous=previous,
                fill=fill,
            )

        quantity = fill.remaining_quantity
        state = "REPRICE_LIMIT"
        reasons = [
            "PRIOR_PARTIAL_FILL" if fill.state == "PARTIALLY_FILLED" else "PRIOR_UNFILLED"
        ]

    maximum_concession = expected_slippage * cfg.maximum_concession_fraction
    target_concession = expected_slippage * (
        cfg.initial_concession_fraction + (attempt_index * cfg.reprice_step_fraction)
    )
    target_concession = min(target_concession, maximum_concession)
    proposed = _price_from_concession(
        side=checked_plan.side,
        reference_price=checked_plan.reference_price,
        concession_bps=target_concession,
        tick_size=cfg.tick_size_inr,
    )
    proposed = _apply_plan_hard_limit(plan=checked_plan, proposed_limit=proposed)
    actual_concession = _concession_bps_from_price(
        side=checked_plan.side,
        reference_price=checked_plan.reference_price,
        limit_price=proposed,
    )

    if actual_concession > maximum_concession + 1e-9:
        raise ValueError("tick-adjusted PAPER limit exceeds maximum slippage concession")

    if previous is not None and previous.proposed_limit_price is not None and math.isclose(
        proposed,
        previous.proposed_limit_price,
        rel_tol=1e-12,
        abs_tol=1e-12,
    ):
        return _non_actionable(
            state="STOP",
            reason="NO_FURTHER_PRICE_CONCESSION",
            plan=checked_plan,
            config=cfg,
            attempt_index=attempt_index,
            previous=previous,
            fill=fill,
        )

    return _build_decision(
        state=state,
        reason_codes=reasons,
        side=checked_plan.side,
        execution_plan_fingerprint=checked_plan.plan_fingerprint,
        cost_model_fingerprint=checked_plan.expected_cost.cost_model_fingerprint,
        slippage_estimate_fingerprint=checked_plan.slippage_estimate_fingerprint,
        fill_config_fingerprint=checked_plan.fill_config_fingerprint,
        previous_decision_fingerprint=(
            previous.decision_fingerprint if previous is not None else None
        ),
        prior_fill_result_fingerprint=(fill.result_fingerprint if fill is not None else None),
        policy_config_fingerprint=cfg.fingerprint,
        attempt_index=attempt_index,
        requested_quantity=checked_plan.quantity,
        quantity_to_submit=quantity,
        reference_price=checked_plan.reference_price,
        previous_limit_price=(previous.proposed_limit_price if previous is not None else None),
        proposed_limit_price=proposed,
        expected_slippage_bps=expected_slippage,
        concession_bps=actual_concession,
        maximum_concession_bps=maximum_concession,
    )


__all__ = [
    "PAPER_SMART_REPRICE_VERSION",
    "PaperSmartLimitDecisionV1",
    "PaperSmartRepriceConfigV1",
    "decide_paper_smart_limit",
]
