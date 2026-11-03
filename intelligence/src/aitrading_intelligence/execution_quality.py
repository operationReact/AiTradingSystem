from __future__ import annotations

import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .execution_plan import (
    EXECUTION_COST_MODEL_VERSION,
    EXECUTION_PLAN_VERSION,
    ExecutionPlanV1,
)
from .fill_simulator import FILL_SIMULATOR_VERSION, FillSimulationResultV1
from .fingerprints import canonical_fingerprint
from .india_fee_tax import INDIA_FEE_TAX_VERSION, IndiaFeeTaxScheduleV1
from .state_dependent_slippage import STATE_DEPENDENT_SLIPPAGE_VERSION

EXECUTION_QUALITY_VERSION = "execution-quality.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
QualityState = Literal["FULLY_FILLED", "PARTIALLY_FILLED", "UNFILLED", "INCONCLUSIVE"]
TradeSide = Literal["BUY", "SELL"]


class RealizedExecutionCostV1(BaseModel):
    """Realized single-order cost reconstruction over the actually filled quantity."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    cost_model_version: Literal["execution-cost-model.v1"] = EXECUTION_COST_MODEL_VERSION
    fee_tax_config_version: Literal["india-fee-tax-config.v1"] = INDIA_FEE_TAX_VERSION
    slippage_model_version: Literal["state-dependent-slippage.v1"] = (
        STATE_DEPENDENT_SLIPPAGE_VERSION
    )
    fill_simulator_version: Literal["fill-simulator.v1"] = FILL_SIMULATOR_VERSION
    fee_schedule_fingerprint: Hash64
    filled_notional_inr: Annotated[float, Field(gt=0.0)]
    brokerage_bps: Annotated[float, Field(ge=0.0)]
    exchange_transaction_charge_bps: Annotated[float, Field(ge=0.0)]
    stt_bps: Annotated[float, Field(ge=0.0)]
    sebi_turnover_charge_bps: Annotated[float, Field(ge=0.0)]
    stamp_duty_bps: Annotated[float, Field(ge=0.0)]
    gst_bps: Annotated[float, Field(ge=0.0)]
    total_fee_tax_bps: Annotated[float, Field(ge=0.0)]
    realized_slippage_bps: float
    realized_total_cost_bps: float
    realized_cost_fingerprint: Hash64

    @field_validator("realized_slippage_bps", "realized_total_cost_bps")
    @classmethod
    def realized_values_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("realized execution values must be finite")
        return value

    @model_validator(mode="after")
    def validate_realized_cost(self) -> RealizedExecutionCostV1:
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
            raise ValueError("realized fee/tax bps must equal component sum")
        expected_total = self.total_fee_tax_bps + self.realized_slippage_bps
        if not math.isclose(
            self.realized_total_cost_bps,
            expected_total,
            rel_tol=1e-12,
            abs_tol=1e-12,
        ):
            raise ValueError("realized total cost must equal fee/tax plus realized slippage")
        if self.realized_cost_fingerprint != canonical_fingerprint(
            _realized_cost_payload(self)
        ):
            raise ValueError("realized execution cost fingerprint mismatch")
        return self


class ExecutionQualityEvidenceV1(BaseModel):
    """Expected-vs-realized TCA evidence bound to one execution plan and fill result."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    execution_quality_version: Literal["execution-quality.v1"] = EXECUTION_QUALITY_VERSION
    state: QualityState
    reason_codes: list[str]
    side: TradeSide
    execution_plan_fingerprint: Hash64
    fill_result_fingerprint: Hash64
    fee_schedule_fingerprint: Hash64
    cost_model_version: Literal["execution-cost-model.v1"] | None = None
    cost_model_fingerprint: Hash64 | None = None
    requested_quantity: Annotated[int, Field(ge=1)]
    filled_quantity: Annotated[int, Field(ge=0)]
    fill_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    completion_shortfall_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    expected_fee_tax_bps: Annotated[float | None, Field(ge=0.0)] = None
    expected_slippage_bps: Annotated[float | None, Field(ge=0.0)] = None
    expected_total_cost_bps: Annotated[float | None, Field(ge=0.0)] = None
    realized_average_fill_price: Annotated[float | None, Field(gt=0.0)] = None
    realized_cost: RealizedExecutionCostV1 | None = None
    fee_tax_delta_bps: float | None = None
    slippage_delta_bps: float | None = None
    total_cost_delta_bps: float | None = None
    arrival_gap_bps: float | None = None
    quality_fingerprint: Hash64
    status: Literal["RESEARCH_EXECUTION_QUALITY_ONLY"] = (
        "RESEARCH_EXECUTION_QUALITY_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator(
        "fee_tax_delta_bps",
        "slippage_delta_bps",
        "total_cost_delta_bps",
        "arrival_gap_bps",
    )
    @classmethod
    def optional_deltas_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("execution-quality deltas must be finite")
        return value

    @model_validator(mode="after")
    def validate_quality(self) -> ExecutionQualityEvidenceV1:
        expected_fraction = self.filled_quantity / self.requested_quantity
        if not math.isclose(self.fill_fraction, expected_fraction, abs_tol=1e-12):
            raise ValueError("execution-quality fill_fraction mismatch")
        if not math.isclose(
            self.completion_shortfall_fraction,
            1.0 - self.fill_fraction,
            abs_tol=1e-12,
        ):
            raise ValueError("execution-quality completion shortfall mismatch")

        expected_metrics = (
            self.cost_model_version,
            self.cost_model_fingerprint,
            self.expected_fee_tax_bps,
            self.expected_slippage_bps,
            self.expected_total_cost_bps,
        )
        realized_metrics = (
            self.realized_average_fill_price,
            self.realized_cost,
            self.fee_tax_delta_bps,
            self.slippage_delta_bps,
            self.total_cost_delta_bps,
        )

        if self.state == "INCONCLUSIVE":
            if not self.reason_codes:
                raise ValueError("inconclusive execution quality requires reason codes")
            if any(value is not None for value in realized_metrics):
                raise ValueError("inconclusive execution quality cannot expose realized metrics")
        elif self.state == "UNFILLED":
            if not self.reason_codes:
                raise ValueError("unfilled execution quality requires reason codes")
            if self.filled_quantity != 0:
                raise ValueError("unfilled execution quality cannot contain filled quantity")
            if any(value is not None for value in realized_metrics):
                raise ValueError("unfilled execution quality cannot fabricate realized metrics")
            if any(value is None for value in expected_metrics):
                raise ValueError("unfilled execution quality still requires expected-plan evidence")
        else:
            if self.state == "FULLY_FILLED" and self.reason_codes:
                raise ValueError("fully filled execution quality cannot contain reason codes")
            if self.state == "PARTIALLY_FILLED" and not self.reason_codes:
                raise ValueError("partial execution quality requires reason codes")
            if any(value is None for value in expected_metrics):
                raise ValueError("evaluated execution quality requires expected-plan evidence")
            if any(value is None for value in realized_metrics):
                raise ValueError("filled execution quality requires realized metrics")

        if self.quality_fingerprint != canonical_fingerprint(_quality_payload(self)):
            raise ValueError("execution-quality fingerprint mismatch")
        return self


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


def _realized_cost_payload(cost: RealizedExecutionCostV1) -> dict[str, object]:
    return cost.model_dump(mode="json", exclude={"realized_cost_fingerprint"})


def _quality_payload(quality: ExecutionQualityEvidenceV1) -> dict[str, object]:
    return quality.model_dump(mode="json", exclude={"quality_fingerprint"})


def _effective_brokerage_bps(
    schedule: IndiaFeeTaxScheduleV1,
    notional_inr: float,
) -> float:
    brokerage_inr = notional_inr * schedule.brokerage_rate_bps / 10_000.0
    brokerage_inr = max(brokerage_inr, schedule.brokerage_minimum_per_order_inr)
    if schedule.brokerage_cap_per_order_inr is not None:
        brokerage_inr = min(brokerage_inr, schedule.brokerage_cap_per_order_inr)
    return brokerage_inr / notional_inr * 10_000.0


def _fee_tax_components_bps(
    schedule: IndiaFeeTaxScheduleV1,
    *,
    side: TradeSide,
    notional_inr: float,
) -> tuple[float, float, float, float, float, float, float]:
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
    total = (
        brokerage_bps
        + schedule.exchange_transaction_charge_bps
        + stt_bps
        + schedule.sebi_turnover_charge_bps
        + stamp_bps
        + gst_bps
    )
    return (
        brokerage_bps,
        schedule.exchange_transaction_charge_bps,
        stt_bps,
        schedule.sebi_turnover_charge_bps,
        stamp_bps,
        gst_bps,
        total,
    )


def _realized_slippage_bps(
    *,
    side: TradeSide,
    reference_price: float,
    average_fill_price: float,
) -> float:
    move = (average_fill_price / reference_price) - 1.0
    return move * 10_000.0 if side == "BUY" else -move * 10_000.0


def _build_realized_cost(
    *,
    plan: ExecutionPlanV1,
    fill: FillSimulationResultV1,
    schedule: IndiaFeeTaxScheduleV1,
) -> RealizedExecutionCostV1:
    assert fill.average_fill_price is not None
    filled_notional = fill.filled_quantity * fill.average_fill_price
    (
        brokerage_bps,
        exchange_bps,
        stt_bps,
        sebi_bps,
        stamp_bps,
        gst_bps,
        total_fee_tax,
    ) = _fee_tax_components_bps(
        schedule,
        side=plan.side,
        notional_inr=filled_notional,
    )
    realized_slippage = _realized_slippage_bps(
        side=plan.side,
        reference_price=plan.reference_price,
        average_fill_price=fill.average_fill_price,
    )
    payload = {
        "cost_model_version": EXECUTION_COST_MODEL_VERSION,
        "fee_tax_config_version": INDIA_FEE_TAX_VERSION,
        "slippage_model_version": STATE_DEPENDENT_SLIPPAGE_VERSION,
        "fill_simulator_version": FILL_SIMULATOR_VERSION,
        "fee_schedule_fingerprint": schedule.schedule_fingerprint,
        "filled_notional_inr": filled_notional,
        "brokerage_bps": brokerage_bps,
        "exchange_transaction_charge_bps": exchange_bps,
        "stt_bps": stt_bps,
        "sebi_turnover_charge_bps": sebi_bps,
        "stamp_duty_bps": stamp_bps,
        "gst_bps": gst_bps,
        "total_fee_tax_bps": total_fee_tax,
        "realized_slippage_bps": realized_slippage,
        "realized_total_cost_bps": total_fee_tax + realized_slippage,
    }
    provisional = RealizedExecutionCostV1.model_construct(
        **payload,
        realized_cost_fingerprint="0" * 64,
    )
    return RealizedExecutionCostV1(
        **payload,
        realized_cost_fingerprint=canonical_fingerprint(
            _realized_cost_payload(provisional)
        ),
    )


def _validate_lineage(
    plan: ExecutionPlanV1,
    fill: FillSimulationResultV1,
    schedule: IndiaFeeTaxScheduleV1,
) -> None:
    request = fill.request
    if request.side != plan.side:
        raise ValueError("fill side does not match execution plan")
    if request.order_type != plan.order_type:
        raise ValueError("fill order type does not match execution plan")
    if request.quantity != plan.quantity:
        raise ValueError("fill quantity does not match execution plan")
    if not math.isclose(request.reference_price, plan.reference_price, abs_tol=1e-12):
        raise ValueError("fill reference price does not match execution plan")
    if request.limit_price != plan.limit_price:
        raise ValueError("fill limit price does not match execution plan")
    if request.canonical_instrument_id != plan.canonical_instrument_id:
        raise ValueError("fill canonical instrument does not match execution plan")
    if request.canonical_symbol != plan.canonical_symbol:
        raise ValueError("fill canonical symbol does not match execution plan")
    if request.broker_trading_symbol != plan.broker_trading_symbol:
        raise ValueError("fill broker symbol does not match execution plan")
    if request.identity_bridge_fingerprint != plan.identity_bridge_fingerprint:
        raise ValueError("fill identity bridge lineage does not match execution plan")
    if request.identity_binding_fingerprint != plan.identity_binding_fingerprint:
        raise ValueError("fill identity binding lineage does not match execution plan")
    if request.slippage_estimate_fingerprint != plan.slippage_estimate_fingerprint:
        raise ValueError("fill slippage lineage does not match execution plan")
    if request.config_fingerprint != plan.fill_config_fingerprint:
        raise ValueError("fill simulator config does not match execution plan")
    if schedule.schedule_id != plan.fee_schedule_id:
        raise ValueError("fee schedule ID does not match execution plan")
    if schedule.schedule_fingerprint != plan.fee_schedule_fingerprint:
        raise ValueError("fee schedule fingerprint does not match execution plan")
    if schedule.exchange != plan.exchange or schedule.product != plan.product:
        raise ValueError("fee schedule scope does not match execution plan")


def evaluate_execution_quality(
    *,
    plan: ExecutionPlanV1,
    fill_result: FillSimulationResultV1,
    fee_schedule: IndiaFeeTaxScheduleV1,
) -> ExecutionQualityEvidenceV1:
    """Compare expected execution cost with realized fill evidence without trading."""

    checked_plan = ExecutionPlanV1.model_validate(plan.model_dump(mode="python"))
    checked_fill = FillSimulationResultV1.model_validate(
        fill_result.model_dump(mode="python")
    )
    checked_schedule = IndiaFeeTaxScheduleV1.model_validate(
        fee_schedule.model_dump(mode="python")
    )
    if checked_schedule.schedule_fingerprint != canonical_fingerprint(
        _schedule_payload(checked_schedule)
    ):
        raise ValueError("India fee/tax schedule fingerprint mismatch")
    _validate_lineage(checked_plan, checked_fill, checked_schedule)

    expected_cost = checked_plan.expected_cost
    if checked_plan.state != "PLANNED" or expected_cost is None:
        state: QualityState = "INCONCLUSIVE"
        reasons = ["EXECUTION_PLAN_INCONCLUSIVE"]
        cost_model_version = None
        cost_model_fingerprint = None
        expected_fee_tax = None
        expected_slippage = None
        expected_total = None
    else:
        cost_model_version = expected_cost.cost_model_version
        cost_model_fingerprint = expected_cost.cost_model_fingerprint
        expected_fee_tax = expected_cost.total_fee_tax_bps
        expected_slippage = expected_cost.expected_slippage_bps
        expected_total = expected_cost.expected_total_cost_bps
        if checked_fill.state == "INCONCLUSIVE":
            state = "INCONCLUSIVE"
            reasons = ["FILL_SIMULATION_INCONCLUSIVE"]
        elif checked_fill.state == "UNFILLED":
            state = "UNFILLED"
            reasons = ["NO_REALIZED_FILL"]
        elif checked_fill.state == "PARTIALLY_FILLED":
            state = "PARTIALLY_FILLED"
            reasons = ["PARTIAL_FILL"]
        else:
            state = "FULLY_FILLED"
            reasons = []

    realized_cost = None
    realized_average_fill_price = None
    fee_delta = None
    slippage_delta = None
    total_delta = None
    if state in ("FULLY_FILLED", "PARTIALLY_FILLED"):
        assert expected_fee_tax is not None
        assert expected_slippage is not None
        assert expected_total is not None
        assert checked_fill.average_fill_price is not None
        realized_average_fill_price = checked_fill.average_fill_price
        realized_cost = _build_realized_cost(
            plan=checked_plan,
            fill=checked_fill,
            schedule=checked_schedule,
        )
        fee_delta = realized_cost.total_fee_tax_bps - expected_fee_tax
        slippage_delta = realized_cost.realized_slippage_bps - expected_slippage
        total_delta = realized_cost.realized_total_cost_bps - expected_total

    payload = {
        "execution_quality_version": EXECUTION_QUALITY_VERSION,
        "state": state,
        "reason_codes": reasons,
        "side": checked_plan.side,
        "execution_plan_fingerprint": checked_plan.plan_fingerprint,
        "fill_result_fingerprint": checked_fill.result_fingerprint,
        "fee_schedule_fingerprint": checked_schedule.schedule_fingerprint,
        "cost_model_version": cost_model_version,
        "cost_model_fingerprint": cost_model_fingerprint,
        "requested_quantity": checked_plan.quantity,
        "filled_quantity": checked_fill.filled_quantity,
        "fill_fraction": checked_fill.fill_fraction,
        "completion_shortfall_fraction": 1.0 - checked_fill.fill_fraction,
        "expected_fee_tax_bps": expected_fee_tax,
        "expected_slippage_bps": expected_slippage,
        "expected_total_cost_bps": expected_total,
        "realized_average_fill_price": realized_average_fill_price,
        "realized_cost": realized_cost,
        "fee_tax_delta_bps": fee_delta,
        "slippage_delta_bps": slippage_delta,
        "total_cost_delta_bps": total_delta,
        "arrival_gap_bps": checked_fill.arrival_gap_bps,
        "status": "RESEARCH_EXECUTION_QUALITY_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = ExecutionQualityEvidenceV1.model_construct(
        **payload,
        quality_fingerprint="0" * 64,
    )
    return ExecutionQualityEvidenceV1(
        **payload,
        quality_fingerprint=canonical_fingerprint(_quality_payload(provisional)),
    )


__all__ = [
    "EXECUTION_QUALITY_VERSION",
    "ExecutionQualityEvidenceV1",
    "RealizedExecutionCostV1",
    "evaluate_execution_quality",
]
