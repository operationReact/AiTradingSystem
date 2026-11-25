from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .execution_plan import ExecutionPlanV1, build_execution_plan
from .execution_quality import ExecutionQualityEvidenceV1
from .fill_simulator import FillSimulationConfigV1
from .fingerprints import canonical_fingerprint
from .india_fee_tax import IndiaFeeTaxScheduleV1
from .state_dependent_slippage import StateDependentSlippageEstimateV1
from .universe_identity_bridge import Nifty500LiquidityIdentityBridge

PAPER_SMART_LIMIT_VERSION = "paper-smart-limit.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
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
    """One non-executable PAPER limit instruction with its exact LIMIT plan."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_version: Literal["paper-smart-limit.v1"] = PAPER_SMART_LIMIT_VERSION
    action: InstructionAction
    instruction_index: Annotated[int, Field(ge=0, le=20)]
    aggressiveness_fraction: Annotated[float, Field(gt=0.0, le=1.0)]
    modeled_slippage_bps: Annotated[float, Field(ge=0.0)]
    limit_offset_bps: Annotated[float, Field(ge=0.0)]
    limit_price: Annotated[float, Field(gt=0.0)]
    source_execution_plan_fingerprint: Hash64
    source_quality_fingerprint: Hash64 | None = None
    previous_instruction_fingerprint: Hash64 | None = None
    recommended_plan: ExecutionPlanV1
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
        if self.recommended_plan.state != "PLANNED":
            raise ValueError("smart-limit recommended plan must be PLANNED")
        if self.recommended_plan.order_type != "LIMIT":
            raise ValueError("smart-limit recommended plan must be LIMIT")
        if not math.isclose(
            self.recommended_plan.limit_price or 0.0,
            self.limit_price,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("instruction limit price must match recommended plan")
        expected_price = _limit_price(
            self.recommended_plan.side,
            self.recommended_plan.reference_price,
            self.limit_offset_bps,
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
            if self.source_quality_fingerprint is not None:
                raise ValueError("initial PAPER limit cannot have quality lineage")
        else:
            if self.instruction_index == 0:
                raise ValueError("reprice instruction must follow an initial limit")
            if self.previous_instruction_fingerprint is None:
                raise ValueError("reprice instruction requires previous instruction lineage")
            if self.source_quality_fingerprint is None:
                raise ValueError("reprice instruction requires execution-quality lineage")
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
    policy_config_fingerprint: Hash64
    root_execution_plan_fingerprint: Hash64
    source_execution_plan_fingerprint: Hash64
    source_quality_fingerprint: Hash64 | None = None
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
            if (
                self.instruction.source_execution_plan_fingerprint
                != self.source_execution_plan_fingerprint
            ):
                raise ValueError("instruction source execution-plan lineage mismatch")
            if self.instruction.source_quality_fingerprint != self.source_quality_fingerprint:
                raise ValueError("instruction execution-quality lineage mismatch")
        else:
            if self.instruction is not None:
                raise ValueError("terminal/inconclusive decision cannot contain instruction")
            if self.state in ("STOPPED", "INCONCLUSIVE") and not self.reason_codes:
                raise ValueError("stopped/inconclusive policy decision requires reason codes")
            if self.state == "COMPLETE" and self.reason_codes:
                raise ValueError("complete policy decision cannot contain reason codes")
        if self.decision_fingerprint != canonical_fingerprint(_decision_payload(self)):
            raise ValueError("PAPER smart-limit decision fingerprint mismatch")
        return self


def _instruction_payload(instruction: PaperLimitInstructionV1) -> dict[str, object]:
    return instruction.model_dump(mode="json", exclude={"instruction_fingerprint"})


def _decision_payload(decision: PaperSmartLimitDecisionV1) -> dict[str, object]:
    return decision.model_dump(mode="json", exclude={"decision_fingerprint"})


def _limit_price(side: Literal["BUY", "SELL"], reference_price: float, offset_bps: float) -> float:
    fraction = offset_bps / 10_000.0
    price = reference_price * (1.0 + fraction if side == "BUY" else 1.0 - fraction)
    if price <= 0.0:
        raise ValueError("PAPER smart-limit price must remain positive")
    return price


def _checked_inputs(
    plan: ExecutionPlanV1,
    slippage: StateDependentSlippageEstimateV1,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    fee_schedule: IndiaFeeTaxScheduleV1,
    fill_config: FillSimulationConfigV1,
) -> tuple[
    ExecutionPlanV1,
    StateDependentSlippageEstimateV1,
    Nifty500LiquidityIdentityBridge,
    IndiaFeeTaxScheduleV1,
    FillSimulationConfigV1,
]:
    checked_plan = ExecutionPlanV1.model_validate(plan.model_dump(mode="python"))
    checked_slippage = StateDependentSlippageEstimateV1.model_validate(
        slippage.model_dump(mode="python")
    )
    checked_bridge = Nifty500LiquidityIdentityBridge.model_validate(
        identity_bridge.model_dump(mode="python")
    )
    checked_schedule = IndiaFeeTaxScheduleV1.model_validate(
        fee_schedule.model_dump(mode="python")
    )
    checked_fill_config = FillSimulationConfigV1.model_validate(
        fill_config.model_dump(mode="python")
    )
    if checked_plan.slippage_estimate_fingerprint != checked_slippage.estimate_fingerprint:
        raise ValueError("smart-limit slippage lineage does not match execution plan")
    if checked_plan.identity_bridge_fingerprint != checked_bridge.snapshot_fingerprint:
        raise ValueError("smart-limit identity bridge lineage does not match execution plan")
    if checked_plan.fee_schedule_fingerprint != checked_schedule.schedule_fingerprint:
        raise ValueError("smart-limit fee schedule lineage does not match execution plan")
    if checked_plan.fill_config_fingerprint != checked_fill_config.fingerprint:
        raise ValueError("smart-limit fill config lineage does not match execution plan")
    if checked_plan.side != checked_slippage.side:
        raise ValueError("smart-limit side does not match slippage evidence")
    if checked_plan.exchange != checked_slippage.exchange:
        raise ValueError("smart-limit exchange does not match slippage evidence")
    if checked_plan.canonical_symbol != checked_slippage.symbol:
        raise ValueError("smart-limit symbol does not match slippage evidence")
    return (
        checked_plan,
        checked_slippage,
        checked_bridge,
        checked_schedule,
        checked_fill_config,
    )


def _build_limit_plan(
    source_plan: ExecutionPlanV1,
    *,
    quantity: int,
    limit_price: float,
    slippage: StateDependentSlippageEstimateV1,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    fee_schedule: IndiaFeeTaxScheduleV1,
    fill_config: FillSimulationConfigV1,
) -> ExecutionPlanV1:
    return build_execution_plan(
        candidate_fingerprint=source_plan.candidate_fingerprint,
        side=source_plan.side,
        order_type="LIMIT",
        quantity=quantity,
        reference_price=source_plan.reference_price,
        limit_price=limit_price,
        slippage=slippage,
        identity_bridge=identity_bridge,
        fee_schedule=fee_schedule,
        fill_config=fill_config,
    )


def _build_instruction(
    *,
    source_plan: ExecutionPlanV1,
    recommended_plan: ExecutionPlanV1,
    modeled_slippage_bps: float,
    aggressiveness_fraction: float,
    instruction_index: int,
    source_quality_fingerprint: str | None,
    previous_instruction_fingerprint: str | None,
) -> PaperLimitInstructionV1:
    offset_bps = modeled_slippage_bps * aggressiveness_fraction
    payload = {
        "policy_version": PAPER_SMART_LIMIT_VERSION,
        "action": "INITIAL_LIMIT" if instruction_index == 0 else "REPRICE",
        "instruction_index": instruction_index,
        "aggressiveness_fraction": aggressiveness_fraction,
        "modeled_slippage_bps": modeled_slippage_bps,
        "limit_offset_bps": offset_bps,
        "limit_price": recommended_plan.limit_price,
        "source_execution_plan_fingerprint": source_plan.plan_fingerprint,
        "source_quality_fingerprint": source_quality_fingerprint,
        "previous_instruction_fingerprint": previous_instruction_fingerprint,
        "recommended_plan": recommended_plan,
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
    root_plan_fingerprint: str,
    source_plan_fingerprint: str,
    source_quality_fingerprint: str | None,
    instruction: PaperLimitInstructionV1 | None,
) -> PaperSmartLimitDecisionV1:
    payload = {
        "policy_version": PAPER_SMART_LIMIT_VERSION,
        "state": state,
        "reason_codes": reasons,
        "policy_config_fingerprint": config.fingerprint,
        "root_execution_plan_fingerprint": root_plan_fingerprint,
        "source_execution_plan_fingerprint": source_plan_fingerprint,
        "source_quality_fingerprint": source_quality_fingerprint,
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
    identity_bridge: Nifty500LiquidityIdentityBridge,
    fee_schedule: IndiaFeeTaxScheduleV1,
    fill_config: FillSimulationConfigV1,
    config: PaperSmartLimitConfigV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    """Convert one evaluated MARKET plan into a bounded PAPER LIMIT plan."""

    cfg = config or PaperSmartLimitConfigV1()
    (
        checked_plan,
        checked_slippage,
        checked_bridge,
        checked_schedule,
        checked_fill_config,
    ) = _checked_inputs(
        plan,
        slippage,
        identity_bridge,
        fee_schedule,
        fill_config,
    )
    if checked_plan.state != "PLANNED" or checked_plan.expected_cost is None:
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["EXECUTION_PLAN_INCONCLUSIVE"],
            config=cfg,
            root_plan_fingerprint=checked_plan.plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=None,
            instruction=None,
        )
    if checked_plan.order_type != "MARKET":
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["MARKET_EXECUTION_PLAN_REQUIRED"],
            config=cfg,
            root_plan_fingerprint=checked_plan.plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=None,
            instruction=None,
        )
    if checked_slippage.state != "EVALUATED" or checked_slippage.estimated_slippage_bps is None:
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["SLIPPAGE_EVIDENCE_INCONCLUSIVE"],
            config=cfg,
            root_plan_fingerprint=checked_plan.plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=None,
            instruction=None,
        )

    fraction = cfg.initial_slippage_fraction
    offset_bps = checked_slippage.estimated_slippage_bps * fraction
    limit_price = _limit_price(checked_plan.side, checked_plan.reference_price, offset_bps)
    recommended_plan = _build_limit_plan(
        checked_plan,
        quantity=checked_plan.quantity,
        limit_price=limit_price,
        slippage=checked_slippage,
        identity_bridge=checked_bridge,
        fee_schedule=checked_schedule,
        fill_config=checked_fill_config,
    )
    instruction = _build_instruction(
        source_plan=checked_plan,
        recommended_plan=recommended_plan,
        modeled_slippage_bps=checked_slippage.estimated_slippage_bps,
        aggressiveness_fraction=fraction,
        instruction_index=0,
        source_quality_fingerprint=None,
        previous_instruction_fingerprint=None,
    )
    return _build_decision(
        state="INSTRUCTION",
        reasons=[],
        config=cfg,
        root_plan_fingerprint=checked_plan.plan_fingerprint,
        source_plan_fingerprint=checked_plan.plan_fingerprint,
        source_quality_fingerprint=None,
        instruction=instruction,
    )


def advance_paper_smart_limit_decision(
    *,
    previous_decision: PaperSmartLimitDecisionV1,
    execution_quality: ExecutionQualityEvidenceV1,
    slippage: StateDependentSlippageEstimateV1,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    fee_schedule: IndiaFeeTaxScheduleV1,
    fill_config: FillSimulationConfigV1,
    config: PaperSmartLimitConfigV1 | None = None,
) -> PaperSmartLimitDecisionV1:
    """Reprice only after exact TCA evidence for the previous LIMIT plan."""

    cfg = config or PaperSmartLimitConfigV1()
    previous = PaperSmartLimitDecisionV1.model_validate(
        previous_decision.model_dump(mode="python")
    )
    quality = ExecutionQualityEvidenceV1.model_validate(
        execution_quality.model_dump(mode="python")
    )
    if previous.policy_config_fingerprint != cfg.fingerprint:
        raise ValueError("smart-limit policy config changed between decisions")
    if previous.state != "INSTRUCTION" or previous.instruction is None:
        raise ValueError("only an instruction decision can be advanced")
    current_plan = previous.instruction.recommended_plan
    if quality.execution_plan_fingerprint != current_plan.plan_fingerprint:
        raise ValueError("execution-quality evidence belongs to another LIMIT plan")
    (
        checked_plan,
        checked_slippage,
        checked_bridge,
        checked_schedule,
        checked_fill_config,
    ) = _checked_inputs(
        current_plan,
        slippage,
        identity_bridge,
        fee_schedule,
        fill_config,
    )
    source_quality = quality.quality_fingerprint
    if quality.state == "INCONCLUSIVE":
        return _build_decision(
            state="INCONCLUSIVE",
            reasons=["EXECUTION_QUALITY_INCONCLUSIVE"],
            config=cfg,
            root_plan_fingerprint=previous.root_execution_plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=source_quality,
            instruction=None,
        )
    if quality.state == "FULLY_FILLED":
        return _build_decision(
            state="COMPLETE",
            reasons=[],
            config=cfg,
            root_plan_fingerprint=previous.root_execution_plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=source_quality,
            instruction=None,
        )
    if (
        quality.arrival_gap_bps is not None
        and quality.arrival_gap_bps > (previous.instruction.modeled_slippage_bps)
    ):
        return _build_decision(
            state="STOPPED",
            reasons=["ARRIVAL_GAP_EXCEEDS_SLIPPAGE_BUDGET"],
            config=cfg,
            root_plan_fingerprint=previous.root_execution_plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=source_quality,
            instruction=None,
        )

    remaining_quantity = checked_plan.quantity - quality.filled_quantity
    if remaining_quantity <= 0:
        return _build_decision(
            state="COMPLETE",
            reasons=[],
            config=cfg,
            root_plan_fingerprint=previous.root_execution_plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=source_quality,
            instruction=None,
        )

    current_instruction = previous.instruction
    next_index = current_instruction.instruction_index + 1
    if next_index > cfg.max_reprices:
        return _build_decision(
            state="STOPPED",
            reasons=["MAX_REPRICES_REACHED"],
            config=cfg,
            root_plan_fingerprint=previous.root_execution_plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=source_quality,
            instruction=None,
        )
    next_fraction = min(
        cfg.maximum_slippage_fraction,
        current_instruction.aggressiveness_fraction + cfg.reprice_step_fraction,
    )
    if next_fraction <= current_instruction.aggressiveness_fraction + 1e-15:
        return _build_decision(
            state="STOPPED",
            reasons=["MAX_AGGRESSIVENESS_REACHED"],
            config=cfg,
            root_plan_fingerprint=previous.root_execution_plan_fingerprint,
            source_plan_fingerprint=checked_plan.plan_fingerprint,
            source_quality_fingerprint=source_quality,
            instruction=None,
        )

    offset_bps = current_instruction.modeled_slippage_bps * next_fraction
    limit_price = _limit_price(checked_plan.side, checked_plan.reference_price, offset_bps)
    recommended_plan = _build_limit_plan(
        checked_plan,
        quantity=remaining_quantity,
        limit_price=limit_price,
        slippage=checked_slippage,
        identity_bridge=checked_bridge,
        fee_schedule=checked_schedule,
        fill_config=checked_fill_config,
    )
    instruction = _build_instruction(
        source_plan=checked_plan,
        recommended_plan=recommended_plan,
        modeled_slippage_bps=current_instruction.modeled_slippage_bps,
        aggressiveness_fraction=next_fraction,
        instruction_index=next_index,
        source_quality_fingerprint=source_quality,
        previous_instruction_fingerprint=current_instruction.instruction_fingerprint,
    )
    if checked_plan.side == "BUY" and instruction.limit_price <= current_instruction.limit_price:
        raise ValueError("BUY reprice must become strictly more aggressive")
    if checked_plan.side == "SELL" and instruction.limit_price >= current_instruction.limit_price:
        raise ValueError("SELL reprice must become strictly more aggressive")
    return _build_decision(
        state="INSTRUCTION",
        reasons=[],
        config=cfg,
        root_plan_fingerprint=previous.root_execution_plan_fingerprint,
        source_plan_fingerprint=checked_plan.plan_fingerprint,
        source_quality_fingerprint=source_quality,
        instruction=instruction,
    )


__all__ = [
    "PAPER_SMART_LIMIT_VERSION",
    "PaperLimitInstructionV1",
    "PaperSmartLimitConfigV1",
    "PaperSmartLimitDecisionV1",
    "advance_paper_smart_limit_decision",
    "build_initial_paper_smart_limit_decision",
]
