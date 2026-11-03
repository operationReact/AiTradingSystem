from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .execution_plan import EXECUTION_PLAN_VERSION, ExecutionPlanV1
from .fill_simulator import FILL_SIMULATOR_VERSION, FillSimulationResultV1
from .fingerprints import canonical_fingerprint
from .state_dependent_slippage import (
    STATE_DEPENDENT_SLIPPAGE_VERSION,
    StateDependentSlippageEstimateV1,
)

PAPER_SMART_LIMIT_VERSION = "paper-smart-limit.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
TradeSide = Literal["BUY", "SELL"]
PolicyState = Literal["INSTRUCTION", "COMPLETE", "STOPPED", "INCONCLUSIVE"]
InstructionAction = Literal["INITIAL_LIMIT", "REPRICE"]


class PaperSmartLimitConfigV1(BaseModel):
    """Deterministic PAPER-only limit ladder bounded by modeled slippage."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-limit.v1"] = PAPER_SMART_LIMIT_VERSION
    initial_slippage_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 0.25
    reprice_step_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 0.25
    maximum_slippage_fraction: Annotated[float, Field(gt=0.0, le=1.0)] = 1.0
    max_reprices: Annotated[int, Field(ge=0, le=20)] = 3

    @model_validator(mode="after")
    def validate_ladder(self) -> PaperSmartLimitConfigV1:
        if self.initial_slippage_fraction > self.maximum_slippage_fraction:
            raise ValueError("initial slippage fraction cannot exceed maximum")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PaperLimitInstructionV1(BaseModel):
    """One non-executable PAPER limit instruction emitted by the policy."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-limit.v1"] = PAPER_SMART_LIMIT_VERSION
    action: InstructionAction
    instruction_index: Annotated[int, Field(ge=0, le=20)]
    side: TradeSide
    quantity: Annotated[int, Field(ge=1, le=1_000_000_000)]
    reference_price: Annotated[float, Field(gt=0.0)]
    aggressiveness_fraction: Annotated[float, Field(gt=0.0, le=1.0)]
    modeled_slippage_bps: Annotated[float, Field(ge=0.0)]
    limit_offset_bps: Annotated[float, Field(ge=0.0)]
    limit_price: Annotated[float, Field(gt=0.0)]
    execution_plan_fingerprint: Hash64
    slippage_estimate_fingerprint: Hash64
    fill_config_fingerprint: Hash64
    previous_instruction_fingerprint: Hash64 | None = None
    source_fill_result_fingerprint: Hash64 | None = None
    instruction_fingerprint: Hash64
    status: Literal["PAPER_LIMIT_INSTRUCTION_ONLY"] = "PAPER_LIMIT_INSTRUCTION_ONLY"
    paper_only: Literal[True] = True
    executable: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_instruction(self) -> PaperLimitInstructionV1:
        expected_offset = self.modeled_slippage_bps * self.aggressiveness_fraction
        if not math.isclose(
            self.limit_offset_bps,
            expected_offset,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("limit offset must equal modeled slippage times aggressiveness")
        fraction = self.limit_offset_bps / 10_000.0
        expected_price = self.reference_price * (
            1.0 + fraction if self.side == "BUY" else 1.0 - fraction
        )
        if not math.isclose(
            self.limit_price,
            expected_price,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("PAPER limit price does not match side-aware offset")
        if self.action == "INITIAL_LIMIT":
            if self.instruction_index != 0:
                raise ValueError("initial PAPER limit must have instruction_index 0")
            if self.previous_instruction_fingerprint is not None:
                raise ValueError("initial PAPER limit cannot have previous instruction lineage")
            if self.source_fill_result_fingerprint is not None:
                raise ValueError("initial PAPER limit cannot have fill-result lineage")
        else:
            if self.instruction_index == 0:
                raise ValueError("reprice instruction must follow an initial limit")
            if self.previous_instruction_fingerprint is None:
                raise ValueError("reprice instruction requires previous instruction lineage")
            if self.source_fill_result_fingerprint is None:
                raise ValueError("reprice instruction requires source fill-result lineage")
        if self.instruction_fingerprint != canonical_fingerprint(
            _instruction_payload(self)
        ):
            raise ValueError("PAPER limit instruction fingerprint mismatch")
        return self


class PaperSmartLimitDecisionV1(BaseModel):
    """Fail-closed policy decision for PAPER limit/reprice progression."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-limit.v1"] = PAPER_SMART_LIMIT_VERSION
    state: PolicyState
    reason_codes: list[str]
    execution_plan_version: Literal["execution-plan.v1"] = EXECUTION_PLAN_VERSION
    fill_simulator_version: Literal["fill-simulator.v1"] = FILL_SIMULATOR_VERSION
    slippage_model_version: Literal["state-dependent-slippage.v1"] = (
        STATE_DEPENDENT_SLIPPAGE_VERSION
    )
    policy_config_fingerprint: Hash64
    execution_plan_fingerprint: Hash64
    slippage_estimate_fingerprint: Hash64
    source_fill_result_fingerprint: Hash64 | None = None
    instruction: PaperLimitInstructionV1 | None = None
    decision_fingerprint: Hash64
    status: Literal["PAPER_SMART_LIMIT_POLICY_ONLY"] = "PAPER_SMART_LIMIT_POLICY_ONLY"
    paper_only: Literal[True] = True
    executable: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_decision(self) -> PaperSmartLimitDecisionV1:
        if self.state == "INSTRUCTION":
            if self.reason_codes:
                raise ValueError("instruction decision cannot contain reason codes")
            if self.instruction is None:
                raise ValueError("instruction decision requires a PAPER limit instruction")
        else:
            if self.instruction is not None:
                raise ValueError("terminal/inconclusive decision cannot contain instruction")
            if self.state in ("STOPPED", "INCONCLUSIVE") and not self.reason_codes:
                raise ValueError("stopped/inconclusive policy decision requires reason codes")
            if self.state == "COMPLETE" and self.reason_codes:
                raise ValueError("complete policy decision cannot contain reason codes")
        if self.instruction is not None:
            if self.instruction.execution_plan_fingerprint != self.execution_plan_fingerprint:
                raise ValueError("instruction execution-plan lineage mismatch")
            if self.instruction.slippage_estimate_fingerprint != self.slippage_estimate_fingerprint:
                raise ValueError("instruction slippage lineage mismatch")
        if self.decision_fingerprint != canonical_fingerprint(_decision_payload(self)):
            raise ValueError("PAPER smart-limit decision fingerprint mismatch")
        return self


def _instruction_payload(instruction: PaperLimitInstructionV1) -> dict[str, object]:
    return instruction.model_dump(mode="json", exclude={"instruction_fingerprint"})


def _decision_payload(decision: PaperSmartLimitDecisionV1) -> dict[str, object]:
    return decision.model_dump(mode="json", exclude={"decision_fingerprint"})


def _limit_price(side: TradeSide, reference_price: float, offset_bps: float) -> float:
    fraction = offset_bps / 10_000.0
    return reference_price * (1.0 + fraction if side == "BUY" else 1.0 - fraction)


def _checked_inputs(
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
) -> tuple[ExecutionPlanV1, StateDependentSlippageEstimateV1]:
    checked_plan = ExecutionPlanV1.model_validate(plan.model_dump(mode="python"))
    checked_slippage = StateDependentSlippageEstimateV1.model_validate(
        slippage.model_dump(mode="python")
    )
    if checked_plan.slippage_estimate_fingerprint != checked_slippage.estimate_fingerprint:
        raise ValueError("smart-limit slippage lineage does not match execution plan")
    if checked_plan.side != checked_slippage.side:
        raise ValueError("smart-limit side does not match slippage evidence")
    if checked_plan.exchange != checked_slippage.exchange:
        raise ValueError("smart-limit exchange does not match slippage evidence")
    if checked_plan.canonical_symbol != checked_slippage.symbol:
        raise ValueError("smart-limit symbol does not match slippage evidence")
    return checked_plan, checked_slippage


def _build_instruction(
    *,
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
    config: PaperSmartLimitConfigV1,
    instruction_index: int,
    quantity: int,
    aggressiveness_fraction: float,
    previous_instruction_fingerprint: str | None,
    source_fill_result_fingerprint: str | None,
) -> PaperLimitInstructionV1:
    assert slippage.estimated_slippage_bps is not None
    offset_bps = slippage.estimated_slippage_bps * aggressiveness_fraction
    payload = {
        "policy_version": PAPER_SMART_LIMIT_VERSION,
        "action": "INITIAL_LIMIT" if instruction_index == 0 else "REPRICE",
        "instruction_index": instruction_index,
        "side": plan.side,
        "quantity": quantity,
        "reference_price": plan.reference_price,
        "aggressiveness_fraction": aggressiveness_fraction,
        "modeled_slippage_bps": slippage.estimated_slippage_bps,
        "limit_offset_bps": offset_bps,
        "limit_price": _limit_price(plan.side, plan.reference_price, offset_bps),
        "execution_plan_fingerprint": plan.plan_fingerprint,
        "slippage_estimate_fingerprint": slippage.estimate_fingerprint,
        "fill_config_fingerprint": plan.fill_config_fingerprint,
        "previous_instruction_fingerprint": previous_instruction_fingerprint,
        "source_fill_result_fingerprint": source_fill_result_fingerprint,
        "status": "PAPER_LIMIT_INSTRUCTION_ONLY",
        "paper_only": True,
        "executable": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = PaperLimitInstructionV1.model_construct(
        **payload,
        instruction_fingerprint="0" * 64,
    )
    return PaperLimitInstructionV1(
        **payload,
        instruction_fingerprint=canonical_fingerprint(_instruction_payload(provisional)),
    )


def _build_decision(
    *,
    state: PolicyState,
    reasons: list[str],
    config: PaperSmartLimitConfigV1,
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
    source_fill_result_fingerprint: str | None,
    instruction: PaperLimitInstructionV1 | None,
) -> PaperSmartLimitDecisionV1:
    payload = {
        "policy_version": PAPER_SMART_LIMIT_VERSION,
        "state": state,
        "reason_codes": reasons,
        "execution_plan_version": EXECUTION_PLAN_VERSION,
        "fill_simulator_version": FILL_SIMULATOR_VERSION,
        "slippage_model_version": STATE_DEPENDENT_SLIPPAGE_VERSION,
        "policy_config_fingerprint": config.fingerprint,
        "execution_plan_fingerprint": plan.plan_fingerprint,
        "slippage_estimate_fingerprint": slippage.estimate_fingerprint,
        "source_fill_result_fingerprint": source_fill_result_fingerprint,
        "instruction": instruction,
        "status": "PAPER_SMART_LIMIT_POLICY_ONLY",
        "paper_only": True,
        "executable": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = PaperSmartLimitDecisionV1.model_construct(
        **payload,
        decision_fingerprint="0" * 64,
    )
    return PaperSmartLimitDecisionV1(
        **payload,
        decision_fingerprint=canonical_fingerprint(_decision_payload(provisional)),
    )


def build_initial_paper_smart_limit_decision(
    *,
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
    config: PaperSmartLimitConfigV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    """Convert an evaluated MARKET plan into a bounded PAPER-only limit instruction."""

    checked_plan, checked_slippage = _checked_inputs(plan, slippage)
    cfg = config or PaperSmartLimitConfigV1()
    if checked_plan.state != "PLANNED" or checked_plan.expected_cost is None:
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["EXECUTION_PLAN_INCONCLUSIVE"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=None,
            instruction=None,
        )
    if checked_plan.order_type != "MARKET":
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["MARKET_EXECUTION_PLAN_REQUIRED"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=None,
            instruction=None,
        )
    if (
        checked_slippage.state != "EVALUATED"
        or checked_slippage.estimated_slippage_bps is None
    ):
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["SLIPPAGE_EVIDENCE_INCONCLUSIVE"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=None,
            instruction=None,
        )

    initial_fraction = min(
        cfg.initial_slippage_fraction,
        cfg.maximum_slippage_fraction,
    )
    instruction = _build_instruction(
        plan=checked_plan,
        slippage=checked_slippage,
        config=cfg,
        instruction_index=0,
        quantity=checked_plan.quantity,
        aggressiveness_fraction=initial_fraction,
        previous_instruction_fingerprint=None,
        source_fill_result_fingerprint=None,
    )
    return _build_decision(
        state="INSTRUCTION",
        reasons=[],
        config=cfg,
        plan=checked_plan,
        slippage=checked_slippage,
        source_fill_result_fingerprint=None,
        instruction=instruction,
    )


def _validate_fill_against_instruction(
    *,
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
    instruction: PaperLimitInstructionV1,
    fill: FillSimulationResultV1,
) -> None:
    request = fill.request
    if request.side != instruction.side or request.side != plan.side:
        raise ValueError("PAPER fill side does not match smart-limit instruction")
    if request.order_type != "LIMIT":
        raise ValueError("PAPER smart-limit fill must be a LIMIT simulation")
    if request.quantity != instruction.quantity:
        raise ValueError("PAPER fill quantity does not match smart-limit instruction")
    if not math.isclose(
        request.reference_price,
        instruction.reference_price,
        rel_tol=1e-12,
        abs_tol=1e-12,
    ):
        raise ValueError("PAPER fill reference price does not match instruction")
    if request.limit_price is None or not math.isclose(
        request.limit_price,
        instruction.limit_price,
        rel_tol=1e-12,
        abs_tol=1e-12,
    ):
        raise ValueError("PAPER fill limit price does not match instruction")
    if request.config_fingerprint != plan.fill_config_fingerprint:
        raise ValueError("PAPER fill config lineage does not match execution plan")
    if request.slippage_estimate_fingerprint != slippage.estimate_fingerprint:
        raise ValueError("PAPER fill slippage lineage does not match execution plan")
    if request.canonical_instrument_id != plan.canonical_instrument_id:
        raise ValueError("PAPER fill instrument does not match execution plan")
    if request.canonical_symbol != plan.canonical_symbol:
        raise ValueError("PAPER fill symbol does not match execution plan")
    if request.broker_trading_symbol != plan.broker_trading_symbol:
        raise ValueError("PAPER fill broker symbol does not match execution plan")
    if request.identity_bridge_fingerprint != plan.identity_bridge_fingerprint:
        raise ValueError("PAPER fill identity bridge does not match execution plan")
    if request.identity_binding_fingerprint != plan.identity_binding_fingerprint:
        raise ValueError("PAPER fill identity binding does not match execution plan")


def advance_paper_smart_limit_decision(
    *,
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
    previous_decision: PaperSmartLimitDecisionV1,
    fill_result: FillSimulationResultV1,
    config: PaperSmartLimitConfigV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    """Advance one PAPER fill outcome through the bounded deterministic reprice policy."""

    checked_plan, checked_slippage = _checked_inputs(plan, slippage)
    cfg = config or PaperSmartLimitConfigV1()
    checked_previous = PaperSmartLimitDecisionV1.model_validate(
        previous_decision.model_dump(mode="python")
    )
    checked_fill = FillSimulationResultV1.model_validate(
        fill_result.model_dump(mode="python")
    )
    if checked_previous.policy_config_fingerprint != cfg.fingerprint:
        raise ValueError("smart-limit policy config changed between decisions")
    if checked_previous.execution_plan_fingerprint != checked_plan.plan_fingerprint:
        raise ValueError("previous smart-limit decision belongs to another execution plan")
    if checked_previous.slippage_estimate_fingerprint != checked_slippage.estimate_fingerprint:
        raise ValueError("previous smart-limit decision uses different slippage evidence")
    if checked_previous.state != "INSTRUCTION" or checked_previous.instruction is None:
        raise ValueError("only an instruction decision can be advanced")
    instruction = checked_previous.instruction
    _validate_fill_against_instruction(
        plan=checked_plan,
        slippage=checked_slippage,
        instruction=instruction,
        fill=checked_fill,
    )

    source_fill = checked_fill.result_fingerprint
    if checked_fill.state == "INCONCLUSIVE":
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["FILL_SIMULATION_INCONCLUSIVE"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=source_fill,
            instruction=None,
        )
    if checked_fill.state == "FULLY_FILLED":
        return _build_decision(
            state="COMPLETE",
            reasons=[],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=source_fill,
            instruction=None,
        )

    assert checked_slippage.estimated_slippage_bps is not None
    if (
        checked_fill.arrival_gap_bps is not None
        and checked_fill.arrival_gap_bps > checked_slippage.estimated_slippage_bps
    ):
        return _build_decision(
            state="STOPPED",
            reasons=["ARRIVAL_GAP_EXCEEDS_SLIPPAGE_BUDGET"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=source_fill,
            instruction=None,
        )

    next_index = instruction.instruction_index + 1
    if next_index > cfg.max_reprices:
        return _build_decision(
            state="STOPPED",
            reasons=["MAX_REPRICES_REACHED"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=source_fill,
            instruction=None,
        )
    next_fraction = min(
        cfg.initial_slippage_fraction + next_index * cfg.reprice_step_fraction,
        cfg.maximum_slippage_fraction,
    )
    if next_fraction <= instruction.aggressiveness_fraction + 1e-15:
        return _build_decision(
            state="STOPPED",
            reasons=["MAX_AGGRESSIVENESS_REACHED"],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=source_fill,
            instruction=None,
        )
    if checked_fill.remaining_quantity <= 0:
        return _build_decision(
            state="COMPLETE",
            reasons=[],
            config=cfg,
            plan=checked_plan,
            slippage=checked_slippage,
            source_fill_result_fingerprint=source_fill,
            instruction=None,
        )

    next_instruction = _build_instruction(
        plan=checked_plan,
        slippage=checked_slippage,
        config=cfg,
        instruction_index=next_index,
        quantity=checked_fill.remaining_quantity,
        aggressiveness_fraction=next_fraction,
        previous_instruction_fingerprint=instruction.instruction_fingerprint,
        source_fill_result_fingerprint=source_fill,
    )
    if checked_plan.side == "BUY" and next_instruction.limit_price <= instruction.limit_price:
        raise ValueError("BUY reprice must become strictly more aggressive")
    if checked_plan.side == "SELL" and next_instruction.limit_price >= instruction.limit_price:
        raise ValueError("SELL reprice must become strictly more aggressive")
    return _build_decision(
        state="INSTRUCTION",
        reasons=[],
        config=cfg,
        plan=checked_plan,
        slippage=checked_slippage,
        source_fill_result_fingerprint=source_fill,
        instruction=next_instruction,
    )


__all__ = [
    "PAPER_SMART_LIMIT_VERSION",
    "PaperLimitInstructionV1",
    "PaperSmartLimitConfigV1",
    "PaperSmartLimitDecisionV1",
    "advance_paper_smart_limit_decision",
    "build_initial_paper_smart_limit_decision",
]
