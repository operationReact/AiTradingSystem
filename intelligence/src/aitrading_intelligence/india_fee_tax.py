from __future__ import annotations

from collections.abc import Sequence
from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

INDIA_FEE_TAX_VERSION = "india-fee-tax-config.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Exchange = Literal["NSE", "BSE"]
CashEquityProduct = Literal["EQUITY_DELIVERY", "EQUITY_INTRADAY"]
GstTaxableComponent = Literal[
    "BROKERAGE",
    "EXCHANGE_TRANSACTION_CHARGE",
    "SEBI_TURNOVER_CHARGE",
]


class IndiaFeeTaxScheduleV1(BaseModel):
    """Point-in-time India cash-equity fee/tax schedule.

    Rates are configuration, not embedded statutory truth. All turnover rates use basis points
    and the caller must supply the schedule that was effective for the trade date.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    config_version: Literal["india-fee-tax-config.v1"] = INDIA_FEE_TAX_VERSION
    schedule_id: Annotated[str, Field(min_length=1, max_length=128)]
    exchange: Exchange
    product: CashEquityProduct
    effective_from: date
    effective_to: date | None = None
    currency: Literal["INR"] = "INR"

    brokerage_rate_bps: Annotated[float, Field(ge=0.0, le=10_000.0)]
    brokerage_cap_per_order_inr: Annotated[float | None, Field(gt=0.0)] = None
    brokerage_minimum_per_order_inr: Annotated[float, Field(ge=0.0)] = 0.0

    exchange_transaction_charge_bps: Annotated[float, Field(ge=0.0, le=1_000.0)]
    stt_buy_bps: Annotated[float, Field(ge=0.0, le=10_000.0)]
    stt_sell_bps: Annotated[float, Field(ge=0.0, le=10_000.0)]
    sebi_turnover_charge_bps: Annotated[float, Field(ge=0.0, le=1_000.0)]
    stamp_duty_buy_bps: Annotated[float, Field(ge=0.0, le=10_000.0)]
    gst_rate_percent: Annotated[float, Field(ge=0.0, le=100.0)]
    gst_taxable_components: list[GstTaxableComponent] = Field(
        default_factory=lambda: [
            "BROKERAGE",
            "EXCHANGE_TRANSACTION_CHARGE",
            "SEBI_TURNOVER_CHARGE",
        ]
    )

    source_reference: Annotated[str, Field(min_length=1, max_length=512)]
    schedule_fingerprint: Hash64

    @field_validator("gst_taxable_components")
    @classmethod
    def gst_components_must_be_canonical(
        cls,
        values: list[GstTaxableComponent],
    ) -> list[GstTaxableComponent]:
        if not values:
            raise ValueError("GST taxable components cannot be empty")
        if len(values) != len(set(values)):
            raise ValueError("GST taxable components must be unique")
        return sorted(values)

    @model_validator(mode="after")
    def validate_schedule(self) -> IndiaFeeTaxScheduleV1:
        if self.effective_to is not None and self.effective_to < self.effective_from:
            raise ValueError("effective_to cannot precede effective_from")
        if (
            self.brokerage_cap_per_order_inr is not None
            and self.brokerage_minimum_per_order_inr > self.brokerage_cap_per_order_inr
        ):
            raise ValueError("brokerage minimum cannot exceed brokerage cap")
        if self.schedule_fingerprint != canonical_fingerprint(_schedule_payload(self)):
            raise ValueError("India fee/tax schedule fingerprint mismatch")
        return self


class IndiaFeeTaxBookV1(BaseModel):
    """Immutable collection of non-overlapping point-in-time fee/tax schedules."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    config_version: Literal["india-fee-tax-config.v1"] = INDIA_FEE_TAX_VERSION
    schedules: list[IndiaFeeTaxScheduleV1] = Field(min_length=1)
    book_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_book(self) -> IndiaFeeTaxBookV1:
        if len({item.schedule_id for item in self.schedules}) != len(self.schedules):
            raise ValueError("India fee/tax schedule IDs must be unique")
        if len({item.schedule_fingerprint for item in self.schedules}) != len(self.schedules):
            raise ValueError("India fee/tax schedule fingerprints must be unique")

        expected = sorted(
            self.schedules,
            key=lambda item: (
                item.exchange,
                item.product,
                item.effective_from,
                item.effective_to or date.max,
                item.schedule_id,
            ),
        )
        if self.schedules != expected:
            raise ValueError("India fee/tax schedules must be in canonical order")
        _reject_overlapping_schedules(self.schedules)

        if self.book_fingerprint != canonical_fingerprint(_book_payload(self)):
            raise ValueError("India fee/tax book fingerprint mismatch")
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


def build_india_fee_tax_schedule(
    *,
    schedule_id: str,
    exchange: Exchange,
    product: CashEquityProduct,
    effective_from: date,
    effective_to: date | None = None,
    brokerage_rate_bps: float,
    brokerage_cap_per_order_inr: float | None = None,
    brokerage_minimum_per_order_inr: float = 0.0,
    exchange_transaction_charge_bps: float,
    stt_buy_bps: float,
    stt_sell_bps: float,
    sebi_turnover_charge_bps: float,
    stamp_duty_buy_bps: float,
    gst_rate_percent: float,
    gst_taxable_components: Sequence[GstTaxableComponent] = (
        "BROKERAGE",
        "EXCHANGE_TRANSACTION_CHARGE",
        "SEBI_TURNOVER_CHARGE",
    ),
    source_reference: str,
) -> IndiaFeeTaxScheduleV1:
    payload = {
        "config_version": INDIA_FEE_TAX_VERSION,
        "schedule_id": schedule_id,
        "exchange": exchange,
        "product": product,
        "effective_from": effective_from.isoformat(),
        "effective_to": effective_to.isoformat() if effective_to is not None else None,
        "currency": "INR",
        "brokerage_rate_bps": float(brokerage_rate_bps),
        "brokerage_cap_per_order_inr": brokerage_cap_per_order_inr,
        "brokerage_minimum_per_order_inr": float(brokerage_minimum_per_order_inr),
        "exchange_transaction_charge_bps": float(exchange_transaction_charge_bps),
        "stt_buy_bps": float(stt_buy_bps),
        "stt_sell_bps": float(stt_sell_bps),
        "sebi_turnover_charge_bps": float(sebi_turnover_charge_bps),
        "stamp_duty_buy_bps": float(stamp_duty_buy_bps),
        "gst_rate_percent": float(gst_rate_percent),
        "gst_taxable_components": sorted(gst_taxable_components),
        "source_reference": source_reference,
    }
    return IndiaFeeTaxScheduleV1(
        **payload,
        schedule_fingerprint=canonical_fingerprint(payload),
    )


def _book_payload(book: IndiaFeeTaxBookV1) -> dict[str, object]:
    return {
        "config_version": INDIA_FEE_TAX_VERSION,
        "schedules": [item.model_dump(mode="json") for item in book.schedules],
    }


def _reject_overlapping_schedules(schedules: Sequence[IndiaFeeTaxScheduleV1]) -> None:
    by_scope: dict[tuple[str, str], list[IndiaFeeTaxScheduleV1]] = {}
    for schedule in schedules:
        by_scope.setdefault((schedule.exchange, schedule.product), []).append(schedule)

    for scoped in by_scope.values():
        ordered = sorted(scoped, key=lambda item: item.effective_from)
        for previous, current in zip(ordered, ordered[1:], strict=False):
            if previous.effective_to is None or previous.effective_to >= current.effective_from:
                raise ValueError("India fee/tax schedules cannot overlap for the same scope")


def build_india_fee_tax_book(
    schedules: Sequence[IndiaFeeTaxScheduleV1],
) -> IndiaFeeTaxBookV1:
    checked = [
        IndiaFeeTaxScheduleV1.model_validate(item.model_dump(mode="python"))
        for item in schedules
    ]
    if not checked:
        raise ValueError("at least one India fee/tax schedule is required")
    canonical = sorted(
        checked,
        key=lambda item: (
            item.exchange,
            item.product,
            item.effective_from,
            item.effective_to or date.max,
            item.schedule_id,
        ),
    )
    _reject_overlapping_schedules(canonical)
    payload = {
        "config_version": INDIA_FEE_TAX_VERSION,
        "schedules": [item.model_dump(mode="json") for item in canonical],
    }
    return IndiaFeeTaxBookV1(
        schedules=canonical,
        book_fingerprint=canonical_fingerprint(payload),
    )


def resolve_india_fee_tax_schedule(
    book: IndiaFeeTaxBookV1,
    *,
    exchange: Exchange,
    product: CashEquityProduct,
    trade_date: date,
) -> IndiaFeeTaxScheduleV1:
    checked = IndiaFeeTaxBookV1.model_validate(book.model_dump(mode="python"))
    matches = [
        schedule
        for schedule in checked.schedules
        if schedule.exchange == exchange
        and schedule.product == product
        and schedule.effective_from <= trade_date
        and (schedule.effective_to is None or trade_date <= schedule.effective_to)
    ]
    if len(matches) != 1:
        raise ValueError("exactly one India fee/tax schedule must cover the trade date")
    return matches[0]
