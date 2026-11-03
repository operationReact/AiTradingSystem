from __future__ import annotations

from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

INDIA_FEE_TAX_VERSION = "india-cash-equity-fees.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
TradeSide = Literal["BUY", "SELL"]
SettlementMode = Literal["DELIVERY", "INTRADAY"]
Venue = Literal["NSE"]

_ZERO = Decimal("0")
_ONE_HUNDRED = Decimal("100")
_TEN_THOUSAND = Decimal("10000")
_DEFAULT_MONEY_QUANTUM = Decimal("0.01")


def _decimal_text(value: Decimal) -> str:
    return format(value, "f")


def _quantize(value: Decimal, quantum: Decimal) -> Decimal:
    return value.quantize(quantum, rounding=ROUND_HALF_UP)


class BrokerageRuleV1(BaseModel):
    """Per-executed-order brokerage rule, independent of statutory charges."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    rate_bps: Decimal = Decimal("0")
    minimum_per_order_inr: Decimal = Decimal("0")
    maximum_per_order_inr: Decimal | None = None

    @field_validator("rate_bps", "minimum_per_order_inr")
    @classmethod
    def non_negative_values(cls, value: Decimal) -> Decimal:
        if value < _ZERO:
            raise ValueError("brokerage values cannot be negative")
        return value

    @field_validator("maximum_per_order_inr")
    @classmethod
    def maximum_must_be_non_negative(cls, value: Decimal | None) -> Decimal | None:
        if value is not None and value < _ZERO:
            raise ValueError("maximum brokerage cannot be negative")
        return value

    @model_validator(mode="after")
    def validate_bounds(self) -> BrokerageRuleV1:
        if (
            self.maximum_per_order_inr is not None
            and self.maximum_per_order_inr < self.minimum_per_order_inr
        ):
            raise ValueError("maximum brokerage cannot be below minimum brokerage")
        return self

    def calculate(self, turnover_inr: Decimal) -> Decimal:
        raw = turnover_inr * self.rate_bps / _TEN_THOUSAND
        charged = max(raw, self.minimum_per_order_inr)
        if self.maximum_per_order_inr is not None:
            charged = min(charged, self.maximum_per_order_inr)
        return charged


class GstTaxBasePolicyV1(BaseModel):
    """Explicitly declares which service-fee components enter the GST base."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    brokerage: bool = True
    exchange_transaction_charge: bool = True
    ipft: bool = True
    sebi_turnover_fee: bool = True
    dp_charge: bool = True


class IndiaCashEquityFeeScheduleV1(BaseModel):
    """Effective-dated NSE cash-equity fee/tax schedule for one broker pricing policy."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    fee_version: Literal["india-cash-equity-fees.v1"] = INDIA_FEE_TAX_VERSION
    schedule_id: Annotated[str, Field(min_length=1, max_length=128)]
    venue: Venue = "NSE"
    currency: Literal["INR"] = "INR"
    effective_from: date
    effective_until: date | None = None
    source_ids: list[Annotated[str, Field(min_length=1, max_length=160)]] = Field(
        min_length=1
    )

    brokerage: BrokerageRuleV1
    delivery_dp_sell_flat_inr: Decimal = Decimal("0")

    exchange_transaction_charge_bps: Decimal
    ipft_bps: Decimal
    sebi_turnover_fee_bps: Decimal
    gst_percent: Decimal

    delivery_stt_buy_bps: Decimal
    delivery_stt_sell_bps: Decimal
    intraday_stt_buy_bps: Decimal = Decimal("0")
    intraday_stt_sell_bps: Decimal

    delivery_stamp_buy_bps: Decimal
    delivery_stamp_sell_bps: Decimal = Decimal("0")
    intraday_stamp_buy_bps: Decimal
    intraday_stamp_sell_bps: Decimal = Decimal("0")

    gst_tax_base: GstTaxBasePolicyV1 = Field(default_factory=GstTaxBasePolicyV1)
    money_quantum_inr: Decimal = _DEFAULT_MONEY_QUANTUM
    schedule_fingerprint: Hash64
    status: Literal["FEE_TAX_CONFIG_ONLY"] = "FEE_TAX_CONFIG_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator(
        "delivery_dp_sell_flat_inr",
        "exchange_transaction_charge_bps",
        "ipft_bps",
        "sebi_turnover_fee_bps",
        "gst_percent",
        "delivery_stt_buy_bps",
        "delivery_stt_sell_bps",
        "intraday_stt_buy_bps",
        "intraday_stt_sell_bps",
        "delivery_stamp_buy_bps",
        "delivery_stamp_sell_bps",
        "intraday_stamp_buy_bps",
        "intraday_stamp_sell_bps",
    )
    @classmethod
    def rate_values_must_be_non_negative(cls, value: Decimal) -> Decimal:
        if value < _ZERO:
            raise ValueError("fee/tax rates cannot be negative")
        return value

    @field_validator("money_quantum_inr")
    @classmethod
    def money_quantum_must_be_positive(cls, value: Decimal) -> Decimal:
        if value <= _ZERO:
            raise ValueError("money_quantum_inr must be positive")
        return value

    @model_validator(mode="after")
    def validate_schedule(self) -> IndiaCashEquityFeeScheduleV1:
        if self.effective_until is not None and self.effective_until < self.effective_from:
            raise ValueError("effective_until cannot precede effective_from")
        if len(set(self.source_ids)) != len(self.source_ids):
            raise ValueError("fee/tax source_ids must be unique")
        if self.gst_percent > _ONE_HUNDRED:
            raise ValueError("gst_percent cannot exceed 100")
        if self.schedule_fingerprint != canonical_fingerprint(_schedule_payload(self)):
            raise ValueError("India fee/tax schedule fingerprint mismatch")
        return self

    def is_effective_on(self, trade_date: date) -> bool:
        if trade_date < self.effective_from:
            return False
        return self.effective_until is None or trade_date <= self.effective_until


class IndiaCashEquityFeeBreakdownV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    brokerage_inr: Decimal
    exchange_transaction_charge_inr: Decimal
    ipft_inr: Decimal
    sebi_turnover_fee_inr: Decimal
    stt_inr: Decimal
    stamp_duty_inr: Decimal
    dp_charge_inr: Decimal
    gst_taxable_base_inr: Decimal
    gst_inr: Decimal
    total_charges_inr: Decimal

    @field_validator("*")
    @classmethod
    def amounts_must_be_non_negative(cls, value: Decimal) -> Decimal:
        if value < _ZERO:
            raise ValueError("fee/tax amounts cannot be negative")
        return value


class IndiaCashEquityFeeEstimateV1(BaseModel):
    """Deterministic cost estimate bound to an exact fee schedule and trade leg."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    fee_version: Literal["india-cash-equity-fees.v1"] = INDIA_FEE_TAX_VERSION
    schedule_id: str
    schedule_fingerprint: Hash64
    trade_date: date
    side: TradeSide
    settlement_mode: SettlementMode
    quantity: Annotated[int, Field(ge=1, le=1_000_000_000)]
    price_inr: Decimal
    turnover_inr: Decimal
    breakdown: IndiaCashEquityFeeBreakdownV1
    effective_total_bps: Decimal
    estimate_fingerprint: Hash64
    status: Literal["RESEARCH_FEE_TAX_ESTIMATE_ONLY"] = (
        "RESEARCH_FEE_TAX_ESTIMATE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("price_inr", "turnover_inr")
    @classmethod
    def monetary_inputs_must_be_positive(cls, value: Decimal) -> Decimal:
        if value <= _ZERO:
            raise ValueError("price and turnover must be positive")
        return value

    @field_validator("effective_total_bps")
    @classmethod
    def effective_bps_must_be_non_negative(cls, value: Decimal) -> Decimal:
        if value < _ZERO:
            raise ValueError("effective_total_bps cannot be negative")
        return value

    @model_validator(mode="after")
    def validate_estimate(self) -> IndiaCashEquityFeeEstimateV1:
        if self.estimate_fingerprint != canonical_fingerprint(_estimate_payload(self)):
            raise ValueError("India fee/tax estimate fingerprint mismatch")
        return self


class IndiaCashEquityRoundTripFeeEstimateV1(BaseModel):
    """Entry + exit fee evidence; slippage is intentionally excluded until V3-09.2."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    fee_version: Literal["india-cash-equity-fees.v1"] = INDIA_FEE_TAX_VERSION
    schedule_fingerprint: Hash64
    settlement_mode: SettlementMode
    buy: IndiaCashEquityFeeEstimateV1
    sell: IndiaCashEquityFeeEstimateV1
    total_charges_inr: Decimal
    total_turnover_inr: Decimal
    effective_round_trip_bps: Decimal
    round_trip_fingerprint: Hash64
    status: Literal["RESEARCH_ROUND_TRIP_FEE_TAX_ESTIMATE_ONLY"] = (
        "RESEARCH_ROUND_TRIP_FEE_TAX_ESTIMATE_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_round_trip(self) -> IndiaCashEquityRoundTripFeeEstimateV1:
        if self.buy.side != "BUY" or self.sell.side != "SELL":
            raise ValueError("round-trip evidence requires BUY then SELL legs")
        if self.buy.schedule_fingerprint != self.schedule_fingerprint:
            raise ValueError("BUY fee estimate schedule lineage mismatch")
        if self.sell.schedule_fingerprint != self.schedule_fingerprint:
            raise ValueError("SELL fee estimate schedule lineage mismatch")
        if self.buy.settlement_mode != self.settlement_mode:
            raise ValueError("BUY settlement mode mismatch")
        if self.sell.settlement_mode != self.settlement_mode:
            raise ValueError("SELL settlement mode mismatch")
        if self.buy.quantity != self.sell.quantity:
            raise ValueError("round-trip fee estimate requires equal BUY/SELL quantity")
        if self.round_trip_fingerprint != canonical_fingerprint(_round_trip_payload(self)):
            raise ValueError("India round-trip fee/tax fingerprint mismatch")
        return self


def _schedule_payload(schedule: IndiaCashEquityFeeScheduleV1) -> dict[str, object]:
    payload = schedule.model_dump(mode="json", exclude={"schedule_fingerprint"})
    return payload


def build_fee_schedule(
    *,
    schedule_id: str,
    effective_from: date,
    source_ids: list[str],
    brokerage: BrokerageRuleV1,
    delivery_dp_sell_flat_inr: Decimal,
    exchange_transaction_charge_bps: Decimal,
    ipft_bps: Decimal,
    sebi_turnover_fee_bps: Decimal,
    gst_percent: Decimal,
    delivery_stt_buy_bps: Decimal,
    delivery_stt_sell_bps: Decimal,
    intraday_stt_sell_bps: Decimal,
    delivery_stamp_buy_bps: Decimal,
    intraday_stamp_buy_bps: Decimal,
    effective_until: date | None = None,
    gst_tax_base: GstTaxBasePolicyV1 | None = None,
) -> IndiaCashEquityFeeScheduleV1:
    values = {
        "schedule_id": schedule_id,
        "effective_from": effective_from,
        "effective_until": effective_until,
        "source_ids": source_ids,
        "brokerage": brokerage,
        "delivery_dp_sell_flat_inr": delivery_dp_sell_flat_inr,
        "exchange_transaction_charge_bps": exchange_transaction_charge_bps,
        "ipft_bps": ipft_bps,
        "sebi_turnover_fee_bps": sebi_turnover_fee_bps,
        "gst_percent": gst_percent,
        "delivery_stt_buy_bps": delivery_stt_buy_bps,
        "delivery_stt_sell_bps": delivery_stt_sell_bps,
        "intraday_stt_sell_bps": intraday_stt_sell_bps,
        "delivery_stamp_buy_bps": delivery_stamp_buy_bps,
        "intraday_stamp_buy_bps": intraday_stamp_buy_bps,
        "gst_tax_base": gst_tax_base or GstTaxBasePolicyV1(),
    }
    provisional = IndiaCashEquityFeeScheduleV1.model_construct(
        fee_version=INDIA_FEE_TAX_VERSION,
        venue="NSE",
        currency="INR",
        intraday_stt_buy_bps=Decimal("0"),
        delivery_stamp_sell_bps=Decimal("0"),
        intraday_stamp_sell_bps=Decimal("0"),
        money_quantum_inr=_DEFAULT_MONEY_QUANTUM,
        status="FEE_TAX_CONFIG_ONLY",
        can_authorize_capital=False,
        schedule_fingerprint="0" * 64,
        **values,
    )
    payload = _schedule_payload(provisional)
    return IndiaCashEquityFeeScheduleV1(
        **values,
        schedule_fingerprint=canonical_fingerprint(payload),
    )


def angel_one_nse_cash_equity_schedule_2026() -> IndiaCashEquityFeeScheduleV1:
    """Current 2026 NSE/Angel One cash-equity schedule, effective 1 April 2026."""

    return build_fee_schedule(
        schedule_id="ANGEL_ONE_NSE_CASH_EQUITY_2026-04-01",
        effective_from=date(2026, 4, 1),
        source_ids=[
            "NSE_FA_73061_2026-03-01",
            "NSE_STT_FINANCE_ACT_2026",
            "NSE_STAMP_DUTY_CASH_EQUITY",
            "SEBI_TURNOVER_FEE_RS10_PER_CRORE",
            "ANGEL_ONE_PRICING_2026",
            "ANGEL_ONE_DP_CHARGES_2026",
        ],
        brokerage=BrokerageRuleV1(
            rate_bps=Decimal("10"),
            minimum_per_order_inr=Decimal("5"),
            maximum_per_order_inr=Decimal("20"),
        ),
        delivery_dp_sell_flat_inr=Decimal("20"),
        exchange_transaction_charge_bps=Decimal("0.30699"),
        ipft_bps=Decimal("0.00001"),
        sebi_turnover_fee_bps=Decimal("0.01"),
        gst_percent=Decimal("18"),
        delivery_stt_buy_bps=Decimal("10"),
        delivery_stt_sell_bps=Decimal("10"),
        intraday_stt_sell_bps=Decimal("2.5"),
        delivery_stamp_buy_bps=Decimal("1.5"),
        intraday_stamp_buy_bps=Decimal("0.3"),
    )


def _bps_charge(turnover: Decimal, rate_bps: Decimal) -> Decimal:
    return turnover * rate_bps / _TEN_THOUSAND


def _stt_bps(
    schedule: IndiaCashEquityFeeScheduleV1,
    side: TradeSide,
    settlement_mode: SettlementMode,
) -> Decimal:
    if settlement_mode == "DELIVERY":
        return (
            schedule.delivery_stt_buy_bps
            if side == "BUY"
            else schedule.delivery_stt_sell_bps
        )
    return (
        schedule.intraday_stt_buy_bps
        if side == "BUY"
        else schedule.intraday_stt_sell_bps
    )


def _stamp_bps(
    schedule: IndiaCashEquityFeeScheduleV1,
    side: TradeSide,
    settlement_mode: SettlementMode,
) -> Decimal:
    if settlement_mode == "DELIVERY":
        return (
            schedule.delivery_stamp_buy_bps
            if side == "BUY"
            else schedule.delivery_stamp_sell_bps
        )
    return (
        schedule.intraday_stamp_buy_bps
        if side == "BUY"
        else schedule.intraday_stamp_sell_bps
    )


def _gst_taxable_base(
    schedule: IndiaCashEquityFeeScheduleV1,
    *,
    brokerage: Decimal,
    exchange: Decimal,
    ipft: Decimal,
    sebi: Decimal,
    dp: Decimal,
) -> Decimal:
    policy = schedule.gst_tax_base
    components = (
        brokerage if policy.brokerage else _ZERO,
        exchange if policy.exchange_transaction_charge else _ZERO,
        ipft if policy.ipft else _ZERO,
        sebi if policy.sebi_turnover_fee else _ZERO,
        dp if policy.dp_charge else _ZERO,
    )
    return sum(components, _ZERO)


def estimate_cash_equity_leg_fees(
    schedule: IndiaCashEquityFeeScheduleV1,
    *,
    trade_date: date,
    side: TradeSide,
    settlement_mode: SettlementMode,
    quantity: int,
    price_inr: Decimal,
) -> IndiaCashEquityFeeEstimateV1:
    """Estimate one executed cash-equity leg; market-impact/slippage is not included."""

    checked = IndiaCashEquityFeeScheduleV1.model_validate(
        schedule.model_dump(mode="python")
    )
    if not checked.is_effective_on(trade_date):
        raise ValueError("fee/tax schedule is not effective on trade_date")
    if quantity <= 0:
        raise ValueError("quantity must be positive")
    if price_inr <= _ZERO:
        raise ValueError("price_inr must be positive")

    turnover = Decimal(quantity) * price_inr
    brokerage = checked.brokerage.calculate(turnover)
    exchange = _bps_charge(turnover, checked.exchange_transaction_charge_bps)
    ipft = _bps_charge(turnover, checked.ipft_bps)
    sebi = _bps_charge(turnover, checked.sebi_turnover_fee_bps)
    stt = _bps_charge(turnover, _stt_bps(checked, side, settlement_mode))
    stamp = _bps_charge(turnover, _stamp_bps(checked, side, settlement_mode))
    dp = (
        checked.delivery_dp_sell_flat_inr
        if settlement_mode == "DELIVERY" and side == "SELL"
        else _ZERO
    )
    gst_base = _gst_taxable_base(
        checked,
        brokerage=brokerage,
        exchange=exchange,
        ipft=ipft,
        sebi=sebi,
        dp=dp,
    )
    gst = gst_base * checked.gst_percent / _ONE_HUNDRED

    quantum = checked.money_quantum_inr
    brokerage_q = _quantize(brokerage, quantum)
    exchange_q = _quantize(exchange, quantum)
    ipft_q = _quantize(ipft, quantum)
    sebi_q = _quantize(sebi, quantum)
    stt_q = _quantize(stt, quantum)
    stamp_q = _quantize(stamp, quantum)
    dp_q = _quantize(dp, quantum)
    gst_base_q = _quantize(gst_base, quantum)
    gst_q = _quantize(gst, quantum)
    total = sum(
        (
            brokerage_q,
            exchange_q,
            ipft_q,
            sebi_q,
            stt_q,
            stamp_q,
            dp_q,
            gst_q,
        ),
        _ZERO,
    )
    total_q = _quantize(total, quantum)
    effective_bps = total_q / turnover * _TEN_THOUSAND

    breakdown = IndiaCashEquityFeeBreakdownV1(
        brokerage_inr=brokerage_q,
        exchange_transaction_charge_inr=exchange_q,
        ipft_inr=ipft_q,
        sebi_turnover_fee_inr=sebi_q,
        stt_inr=stt_q,
        stamp_duty_inr=stamp_q,
        dp_charge_inr=dp_q,
        gst_taxable_base_inr=gst_base_q,
        gst_inr=gst_q,
        total_charges_inr=total_q,
    )
    payload = {
        "fee_version": INDIA_FEE_TAX_VERSION,
        "schedule_id": checked.schedule_id,
        "schedule_fingerprint": checked.schedule_fingerprint,
        "trade_date": trade_date.isoformat(),
        "side": side,
        "settlement_mode": settlement_mode,
        "quantity": quantity,
        "price_inr": _decimal_text(price_inr),
        "turnover_inr": _decimal_text(turnover),
        "breakdown": breakdown.model_dump(mode="json"),
        "effective_total_bps": _decimal_text(effective_bps),
        "status": "RESEARCH_FEE_TAX_ESTIMATE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaCashEquityFeeEstimateV1(
        schedule_id=checked.schedule_id,
        schedule_fingerprint=checked.schedule_fingerprint,
        trade_date=trade_date,
        side=side,
        settlement_mode=settlement_mode,
        quantity=quantity,
        price_inr=price_inr,
        turnover_inr=turnover,
        breakdown=breakdown,
        effective_total_bps=effective_bps,
        estimate_fingerprint=canonical_fingerprint(payload),
    )


def _estimate_payload(estimate: IndiaCashEquityFeeEstimateV1) -> dict[str, object]:
    return {
        "fee_version": INDIA_FEE_TAX_VERSION,
        "schedule_id": estimate.schedule_id,
        "schedule_fingerprint": estimate.schedule_fingerprint,
        "trade_date": estimate.trade_date.isoformat(),
        "side": estimate.side,
        "settlement_mode": estimate.settlement_mode,
        "quantity": estimate.quantity,
        "price_inr": _decimal_text(estimate.price_inr),
        "turnover_inr": _decimal_text(estimate.turnover_inr),
        "breakdown": estimate.breakdown.model_dump(mode="json"),
        "effective_total_bps": _decimal_text(estimate.effective_total_bps),
        "status": estimate.status,
        "promotion_allowed": estimate.promotion_allowed,
        "can_authorize_capital": estimate.can_authorize_capital,
    }


def estimate_cash_equity_round_trip_fees(
    schedule: IndiaCashEquityFeeScheduleV1,
    *,
    buy_date: date,
    sell_date: date,
    settlement_mode: SettlementMode,
    quantity: int,
    buy_price_inr: Decimal,
    sell_price_inr: Decimal,
) -> IndiaCashEquityRoundTripFeeEstimateV1:
    if sell_date < buy_date:
        raise ValueError("sell_date cannot precede buy_date")
    buy = estimate_cash_equity_leg_fees(
        schedule,
        trade_date=buy_date,
        side="BUY",
        settlement_mode=settlement_mode,
        quantity=quantity,
        price_inr=buy_price_inr,
    )
    sell = estimate_cash_equity_leg_fees(
        schedule,
        trade_date=sell_date,
        side="SELL",
        settlement_mode=settlement_mode,
        quantity=quantity,
        price_inr=sell_price_inr,
    )
    total_charges = buy.breakdown.total_charges_inr + sell.breakdown.total_charges_inr
    total_turnover = buy.turnover_inr + sell.turnover_inr
    effective_bps = total_charges / total_turnover * _TEN_THOUSAND
    payload = {
        "fee_version": INDIA_FEE_TAX_VERSION,
        "schedule_fingerprint": schedule.schedule_fingerprint,
        "settlement_mode": settlement_mode,
        "buy": buy.model_dump(mode="json"),
        "sell": sell.model_dump(mode="json"),
        "total_charges_inr": _decimal_text(total_charges),
        "total_turnover_inr": _decimal_text(total_turnover),
        "effective_round_trip_bps": _decimal_text(effective_bps),
        "status": "RESEARCH_ROUND_TRIP_FEE_TAX_ESTIMATE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaCashEquityRoundTripFeeEstimateV1(
        schedule_fingerprint=schedule.schedule_fingerprint,
        settlement_mode=settlement_mode,
        buy=buy,
        sell=sell,
        total_charges_inr=total_charges,
        total_turnover_inr=total_turnover,
        effective_round_trip_bps=effective_bps,
        round_trip_fingerprint=canonical_fingerprint(payload),
    )


def _round_trip_payload(
    estimate: IndiaCashEquityRoundTripFeeEstimateV1,
) -> dict[str, object]:
    return {
        "fee_version": INDIA_FEE_TAX_VERSION,
        "schedule_fingerprint": estimate.schedule_fingerprint,
        "settlement_mode": estimate.settlement_mode,
        "buy": estimate.buy.model_dump(mode="json"),
        "sell": estimate.sell.model_dump(mode="json"),
        "total_charges_inr": _decimal_text(estimate.total_charges_inr),
        "total_turnover_inr": _decimal_text(estimate.total_turnover_inr),
        "effective_round_trip_bps": _decimal_text(estimate.effective_round_trip_bps),
        "status": estimate.status,
        "promotion_allowed": estimate.promotion_allowed,
        "can_authorize_capital": estimate.can_authorize_capital,
    }


__all__ = [
    "INDIA_FEE_TAX_VERSION",
    "BrokerageRuleV1",
    "GstTaxBasePolicyV1",
    "IndiaCashEquityFeeBreakdownV1",
    "IndiaCashEquityFeeEstimateV1",
    "IndiaCashEquityFeeScheduleV1",
    "IndiaCashEquityRoundTripFeeEstimateV1",
    "angel_one_nse_cash_equity_schedule_2026",
    "build_fee_schedule",
    "estimate_cash_equity_leg_fees",
    "estimate_cash_equity_round_trip_fees",
]