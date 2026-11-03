from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fill_simulator import FILL_SIMULATOR_VERSION, FillSimulationConfigV1
from .fingerprints import canonical_fingerprint
from .india_fee_tax import INDIA_FEE_TAX_VERSION, IndiaFeeTaxScheduleV1
from .state_dependent_slippage import (
    STATE_DEPENDENT_SLIPPAGE_VERSION,
    StateDependentSlippageEstimateV1,
)
from .universe_identity_bridge import (
    IDENTITY_BRIDGE_VERSION,
    MAPPING_RULE_VERSION,
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

EXECUTION_PLAN_VERSION = "execution-plan.v1"
EXECUTION_COST_MODEL_VERSION = "execution-cost-model.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
TradeSide = Literal["BUY", "SELL"]
OrderType = Literal["MARKET", "LIMIT"]
PlanState = Literal["PLANNED", "INCONCLUSIVE"]


class ExecutionCostEstimateV1(BaseModel):
    """Reconstructable single-leg expected cost bound to exact model evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    cost_model_version: Literal["execution-cost-model.v1"] = EXECUTION_COST_MODEL_VERSION
    fee_tax_config_version: Literal["india-fee-tax-config.v1"] = INDIA_FEE_TAX_VERSION
    slippage_model_version: Literal["state-dependent-slippage.v1"] = (
        STATE_DEPENDENT_SLIPPAGE_VERSION
    )
    fill_simulator_version: Literal["fill-simulator.v1"] = FILL_SIMULATOR_VERSION
    fee_schedule_fingerprint: Hash64
    slippage_estimate_fingerprint: Hash64
    slippage_config_fingerprint: Hash64
    fill_config_fingerprint: Hash64
    notional_inr: Annotated[float, Field(gt=0.0)]
    brokerage_bps: Annotated[float, Field(ge=0.0)]
    exchange_transaction_charge_bps: Annotated[float, Field(ge=0.0)]
    stt_bps: Annotated[float, Field(ge=0.0)]
    sebi_turnover_charge_bps: Annotated[float, Field(ge=0.0)]
    stamp_duty_bps: Annotated[float, Field(ge=0.0)]
    gst_bps: Annotated[float, Field(ge=0.0)]
    total_fee_tax_bps: Annotated[float, Field(ge=0.0)]
    expected_slippage_bps: Annotated[float, Field(ge=0.0)]
    expected_total_cost_bps: Annotated[float, Field(ge=0.0)]
    cost_model_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_cost(self) -> ExecutionCostEstimateV1:
        fee_sum = (
            self.brokerage_bps
            + self.exchange_transaction_charge_bps
            + self.stt_bps
            + self.sebi_turnover_charge_bps
            + self.stamp_duty_bps
            + self.gst_bps
        )
        if not math.isclose(
            self.total_fee_tax_bps,
            fee_sum,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("total fee/tax bps must equal component sum")
        expected_total = self.total_fee_tax_bps + self.expected_slippage_bps
        if not math.isclose(
            self.expected_total_cost_bps,
            expected_total,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("expected execution cost must equal fee/tax plus slippage")
        if self.cost_model_fingerprint != canonical_fingerprint(_cost_payload(self)):
            raise ValueError("execution cost model fingerprint mismatch")
        return self


class ExecutionPlanV1(BaseModel):
    """Non-executable execution intent for downstream PAPER/TCA evaluation."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    execution_plan_version: Literal["execution-plan.v1"] = EXECUTION_PLAN_VERSION
    state: PlanState
    reason_codes: list[str]
    plan_as_of: datetime
    candidate_fingerprint: Hash64
    canonical_instrument_id: Annotated[str, Field(min_length=1, max_length=80)]
    canonical_symbol: Annotated[str, Field(min_length=1, max_length=32)]
    broker_trading_symbol: Annotated[str, Field(min_length=1, max_length=100)]
    broker_symbol_token: Annotated[str, Field(min_length=1, max_length=64)]
    exchange: Literal["NSE", "BSE"]
    product: Literal["EQUITY_DELIVERY", "EQUITY_INTRADAY"]
    side: TradeSide
    order_type: OrderType
    quantity: Annotated[int, Field(ge=1, le=1_000_000_000)]
    reference_price: Annotated[float, Field(gt=0.0)]
    limit_price: Annotated[float | None, Field(gt=0.0)] = None
    identity_bridge_fingerprint: Hash64
    identity_binding_fingerprint: Hash64
    fee_schedule_id: Annotated[str, Field(min_length=1, max_length=128)]
    fee_schedule_fingerprint: Hash64
    slippage_estimate_fingerprint: Hash64
    fill_config_fingerprint: Hash64
    expected_cost: ExecutionCostEstimateV1 | None = None
    plan_fingerprint: Hash64
    status: Literal["RESEARCH_EXECUTION_PLAN_ONLY"] = "RESEARCH_EXECUTION_PLAN_ONLY"
    executable: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("plan_as_of")
    @classmethod
    def plan_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("execution plan timestamp must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_plan(self) -> ExecutionPlanV1:
        if self.order_type == "MARKET" and self.limit_price is not None:
            raise ValueError("MARKET execution plan cannot contain limit_price")
        if self.order_type == "LIMIT" and self.limit_price is None:
            raise ValueError("LIMIT execution plan requires limit_price")
        if self.state == "PLANNED":
            if self.reason_codes:
                raise ValueError("planned execution cannot contain reason codes")
            if self.expected_cost is None:
                raise ValueError("planned execution requires expected cost evidence")
        else:
            if not self.reason_codes:
                raise ValueError("inconclusive execution plan requires reason codes")
            if self.expected_cost is not None:
                raise ValueError("inconclusive execution plan cannot expose expected cost")
        if self.plan_fingerprint != canonical_fingerprint(_plan_payload(self)):
            raise ValueError("execution plan fingerprint mismatch")
        return self


def _binding_payload(binding: Nifty500LiquidityIdentityBinding) -> dict[str, object]:
    return {
        "canonicalInstrumentId": binding.canonical_instrument_id,
        "isin": binding.isin,
        "symbol": binding.symbol,
        "series": binding.series,
        "sector": binding.sector,
        "expectedTradingSymbol": binding.expected_trading_symbol,
        "catalogueInstrumentId": binding.catalogue_instrument_id,
        "brokerTradingSymbol": binding.broker_trading_symbol,
        "brokerSymbolToken": binding.broker_symbol_token,
        "liquidityEvidenceFingerprint": binding.liquidity_evidence_fingerprint,
        "sectorMappingEvidenceFingerprint": binding.sector_mapping_evidence_fingerprint,
        "membershipEffectiveFrom": binding.membership_effective_from.isoformat(),
        "availableAt": binding.available_at.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
    }


def _bridge_payload(bridge: Nifty500LiquidityIdentityBridge) -> dict[str, object]:
    return {
        "contractVersion": IDENTITY_BRIDGE_VERSION,
        "asOf": bridge.as_of.isoformat(),
        "dataCutoff": bridge.data_cutoff.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
        "sourceCatalogueVersion": bridge.source_catalogue_version,
        "sourceCatalogueFingerprint": bridge.source_catalogue_fingerprint,
        "sourceLiquiditySnapshotFingerprint": bridge.source_liquidity_snapshot_fingerprint,
        "sourceSectorEnrichmentFingerprint": bridge.source_sector_enrichment_fingerprint,
        "bindings": [item.model_dump(mode="json") for item in bridge.bindings],
        "bindingCount": bridge.binding_count,
        "status": bridge.status,
        "canAuthorizeCapital": bridge.can_authorize_capital,
    }


def _schedule_payload(schedule: IndiaFeeTaxScheduleV1) -> dict[str, object]:
    return {
        "config_version": INDIA_FEE_TAX_VERSION,
        "schedule_id": schedule.schedule_id,
        "exchange": schedule.exchange,
        "product": schedule.product,
        "effective_from": schedule.effective_from.isoformat(),
        "effective_to": (
            schedule.effective_to.isoformat() if schedule.effective_to is not None else None
        ),
        "currency": schedule.currency,
        "brokerage_rate_bps": schedule.brokerage_rate_bps,
        "brokerage_cap_per_order_inr": schedule.brokerage_cap_per_order_inr,
        "brokerage_minimum_per_order_inr": schedule.brokerage_minimum_per_order_inr,
        "exchange_transaction_charge_bps": schedule.exchange_transaction_charge_bps,
        "stt_buy_bps": schedule.stt_buy_bps,
        "stt_sell_bps": schedule.stt_sell_bps,
        "sebi_turnover_charge_bps": schedule.sebi_turnover_charge_bps,
        "stamp_duty_buy_bps": schedule.stamp_duty_buy_bps,
        "gst_rate_percent": schedule.gst_rate_percent,
        "gst_taxable_components": schedule.gst_taxable_components,
        "source_reference": schedule.source_reference,
    }


def _cost_payload(cost: ExecutionCostEstimateV1) -> dict[str, object]:
    return cost.model_dump(mode="json", exclude={"cost_model_fingerprint"})


def _plan_payload(plan: ExecutionPlanV1) -> dict[str, object]:
    return plan.model_dump(mode="json", exclude={"plan_fingerprint"})


def _resolve_binding(
    bridge: Nifty500LiquidityIdentityBridge,
    slippage: StateDependentSlippageEstimateV1,
) -> Nifty500LiquidityIdentityBinding:
    matches = [
        item
        for item in bridge.bindings
        if item.symbol == slippage.symbol and item.canonical_instrument_id.startswith(
            f"{slippage.exchange}:"
        )
    ]
    if len(matches) != 1:
        raise ValueError("slippage symbol must resolve to exactly one identity binding")
    binding = matches[0]
    if binding.binding_fingerprint != canonical_fingerprint(_binding_payload(binding)):
        raise ValueError("identity binding fingerprint mismatch")
    return binding


def _effective_brokerage_bps(
    schedule: IndiaFeeTaxScheduleV1,
    notional_inr: float,
) -> float:
    brokerage_inr = notional_inr * schedule.brokerage_rate_bps / 10_000.0
    brokerage_inr = max(brokerage_inr, schedule.brokerage_minimum_per_order_inr)
    if schedule.brokerage_cap_per_order_inr is not None:
        brokerage_inr = min(brokerage_inr, schedule.brokerage_cap_per_order_inr)
    return brokerage_inr / notional_inr * 10_000.0


def _build_expected_cost(
    *,
    schedule: IndiaFeeTaxScheduleV1,
    slippage: StateDependentSlippageEstimateV1,
    fill_config: FillSimulationConfigV1,
    side: TradeSide,
    notional_inr: float,
) -> ExecutionCostEstimateV1:
    assert slippage.estimated_slippage_bps is not None
    brokerage_bps = _effective_brokerage_bps(schedule, notional_inr)
    stt_bps = schedule.stt_buy_bps if side == "BUY" else schedule.stt_sell_bps
    stamp_bps = schedule.stamp_duty_buy_bps if side == "BUY" else 0.0
    gst_base = 0.0
    if "BROKERAGE" in schedule.gst_taxable_components:
        gst_base += brokerage_bps
    if "EXCHANGE_TRANSACTION_CHARGE" in schedule.gst_taxable_components:
        gst_base += schedule.exchange_transaction_charge_bps
    if "SEBI_TURNOVER_CHARGE" in schedule.gst_taxable_components:
        gst_base += schedule.sebi_turnover_charge_bps
    gst_bps = gst_base * schedule.gst_rate_percent / 100.0
    total_fee_tax = (
        brokerage_bps
        + schedule.exchange_transaction_charge_bps
        + stt_bps
        + schedule.sebi_turnover_charge_bps
        + stamp_bps
        + gst_bps
    )
    payload = {
        "cost_model_version": EXECUTION_COST_MODEL_VERSION,
        "fee_tax_config_version": INDIA_FEE_TAX_VERSION,
        "slippage_model_version": STATE_DEPENDENT_SLIPPAGE_VERSION,
        "fill_simulator_version": FILL_SIMULATOR_VERSION,
        "fee_schedule_fingerprint": schedule.schedule_fingerprint,
        "slippage_estimate_fingerprint": slippage.estimate_fingerprint,
        "slippage_config_fingerprint": slippage.config_fingerprint,
        "fill_config_fingerprint": fill_config.fingerprint,
        "notional_inr": notional_inr,
        "brokerage_bps": brokerage_bps,
        "exchange_transaction_charge_bps": schedule.exchange_transaction_charge_bps,
        "stt_bps": stt_bps,
        "sebi_turnover_charge_bps": schedule.sebi_turnover_charge_bps,
        "stamp_duty_bps": stamp_bps,
        "gst_bps": gst_bps,
        "total_fee_tax_bps": total_fee_tax,
        "expected_slippage_bps": slippage.estimated_slippage_bps,
        "expected_total_cost_bps": total_fee_tax + slippage.estimated_slippage_bps,
    }
    provisional = ExecutionCostEstimateV1.model_construct(
        **payload,
        cost_model_fingerprint="0" * 64,
    )
    return ExecutionCostEstimateV1(
        **payload,
        cost_model_fingerprint=canonical_fingerprint(_cost_payload(provisional)),
    )


def build_execution_plan(
    *,
    candidate_fingerprint: str,
    side: TradeSide,
    order_type: OrderType,
    quantity: int,
    reference_price: float,
    slippage: StateDependentSlippageEstimateV1,
    identity_bridge: Nifty500LiquidityIdentityBridge,
    fee_schedule: IndiaFeeTaxScheduleV1,
    fill_config: FillSimulationConfigV1,
    limit_price: float | None = None,
) -> ExecutionPlanV1:
    """Build a research-only execution plan; this function never submits an order."""

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
    if checked_bridge.snapshot_fingerprint != canonical_fingerprint(
        _bridge_payload(checked_bridge)
    ):
        raise ValueError("identity bridge fingerprint mismatch")
    if checked_schedule.schedule_fingerprint != canonical_fingerprint(
        _schedule_payload(checked_schedule)
    ):
        raise ValueError("India fee/tax schedule fingerprint mismatch")
    if checked_slippage.side != side:
        raise ValueError("execution side must match slippage side")
    if checked_slippage.exchange != checked_schedule.exchange:
        raise ValueError("slippage exchange must match fee schedule")
    if checked_bridge.as_of != checked_slippage.as_of:
        raise ValueError("identity bridge and slippage must share decision time")
    if checked_bridge.data_cutoff > checked_slippage.as_of:
        raise ValueError("identity bridge contains future evidence")
    if checked_schedule.effective_from > checked_slippage.as_of.date() or (
        checked_schedule.effective_to is not None
        and checked_slippage.as_of.date() > checked_schedule.effective_to
    ):
        raise ValueError("fee/tax schedule is not effective for execution plan date")
    if quantity <= 0:
        raise ValueError("execution quantity must be positive")
    if not math.isfinite(reference_price) or reference_price <= 0.0:
        raise ValueError("reference_price must be positive and finite")

    binding = _resolve_binding(checked_bridge, checked_slippage)
    reasons: list[str] = []
    if checked_slippage.state != "EVALUATED":
        reasons.append("SLIPPAGE_ESTIMATE_INCONCLUSIVE")
    if binding.available_at > checked_slippage.as_of:
        reasons.append("IDENTITY_BINDING_NOT_AVAILABLE_AT_PLAN_TIME")

    expected_cost = None
    state: PlanState = "INCONCLUSIVE" if reasons else "PLANNED"
    if not reasons:
        expected_cost = _build_expected_cost(
            schedule=checked_schedule,
            slippage=checked_slippage,
            fill_config=checked_fill_config,
            side=side,
            notional_inr=quantity * reference_price,
        )

    payload = {
        "execution_plan_version": EXECUTION_PLAN_VERSION,
        "state": state,
        "reason_codes": sorted(set(reasons)),
        "plan_as_of": checked_slippage.as_of,
        "candidate_fingerprint": candidate_fingerprint,
        "canonical_instrument_id": binding.canonical_instrument_id,
        "canonical_symbol": binding.symbol,
        "broker_trading_symbol": binding.broker_trading_symbol,
        "broker_symbol_token": binding.broker_symbol_token,
        "exchange": checked_schedule.exchange,
        "product": checked_schedule.product,
        "side": side,
        "order_type": order_type,
        "quantity": quantity,
        "reference_price": reference_price,
        "limit_price": limit_price,
        "identity_bridge_fingerprint": checked_bridge.snapshot_fingerprint,
        "identity_binding_fingerprint": binding.binding_fingerprint,
        "fee_schedule_id": checked_schedule.schedule_id,
        "fee_schedule_fingerprint": checked_schedule.schedule_fingerprint,
        "slippage_estimate_fingerprint": checked_slippage.estimate_fingerprint,
        "fill_config_fingerprint": checked_fill_config.fingerprint,
        "expected_cost": expected_cost,
        "status": "RESEARCH_EXECUTION_PLAN_ONLY",
        "executable": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = ExecutionPlanV1.model_construct(**payload, plan_fingerprint="0" * 64)
    return ExecutionPlanV1(
        **payload,
        plan_fingerprint=canonical_fingerprint(_plan_payload(provisional)),
    )


__all__ = [
    "EXECUTION_COST_MODEL_VERSION",
    "EXECUTION_PLAN_VERSION",
    "ExecutionCostEstimateV1",
    "ExecutionPlanV1",
    "build_execution_plan",
]
