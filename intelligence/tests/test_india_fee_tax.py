from datetime import date

import pytest
from pydantic import ValidationError

from aitrading_intelligence.india_fee_tax import (
    IndiaFeeTaxBookV1,
    IndiaFeeTaxScheduleV1,
    build_india_fee_tax_book,
    build_india_fee_tax_schedule,
    resolve_india_fee_tax_schedule,
)


def _schedule(
    schedule_id: str,
    *,
    exchange: str = "NSE",
    product: str = "EQUITY_INTRADAY",
    effective_from: date = date(2026, 1, 1),
    effective_to: date | None = None,
    brokerage_rate_bps: float = 3.0,
    brokerage_cap_per_order_inr: float | None = 20.0,
    stt_buy_bps: float = 0.0,
    stt_sell_bps: float = 2.5,
    stamp_duty_buy_bps: float = 0.3,
):
    return build_india_fee_tax_schedule(
        schedule_id=schedule_id,
        exchange=exchange,
        product=product,
        effective_from=effective_from,
        effective_to=effective_to,
        brokerage_rate_bps=brokerage_rate_bps,
        brokerage_cap_per_order_inr=brokerage_cap_per_order_inr,
        exchange_transaction_charge_bps=0.3,
        stt_buy_bps=stt_buy_bps,
        stt_sell_bps=stt_sell_bps,
        sebi_turnover_charge_bps=0.01,
        stamp_duty_buy_bps=stamp_duty_buy_bps,
        gst_rate_percent=18.0,
        source_reference=f"fixture:{schedule_id}",
    )


def test_schedule_keeps_fee_tax_components_separate_and_self_verifying() -> None:
    schedule = _schedule("nse-intraday-v1")

    assert schedule.exchange == "NSE"
    assert schedule.product == "EQUITY_INTRADAY"
    assert schedule.currency == "INR"
    assert schedule.brokerage_rate_bps == pytest.approx(3.0)
    assert schedule.brokerage_cap_per_order_inr == pytest.approx(20.0)
    assert schedule.exchange_transaction_charge_bps == pytest.approx(0.3)
    assert schedule.stt_buy_bps == pytest.approx(0.0)
    assert schedule.stt_sell_bps == pytest.approx(2.5)
    assert schedule.sebi_turnover_charge_bps == pytest.approx(0.01)
    assert schedule.stamp_duty_buy_bps == pytest.approx(0.3)
    assert schedule.gst_rate_percent == pytest.approx(18.0)
    assert schedule.gst_taxable_components == [
        "BROKERAGE",
        "EXCHANGE_TRANSACTION_CHARGE",
        "SEBI_TURNOVER_CHARGE",
    ]
    assert len(schedule.schedule_fingerprint) == 64


def test_delivery_and_intraday_can_have_different_side_specific_tax_schedules() -> None:
    intraday = _schedule(
        "nse-intraday",
        product="EQUITY_INTRADAY",
        stt_buy_bps=0.0,
        stt_sell_bps=2.5,
        stamp_duty_buy_bps=0.3,
    )
    delivery = _schedule(
        "nse-delivery",
        product="EQUITY_DELIVERY",
        stt_buy_bps=10.0,
        stt_sell_bps=10.0,
        stamp_duty_buy_bps=1.5,
    )
    book = build_india_fee_tax_book([delivery, intraday])

    assert resolve_india_fee_tax_schedule(
        book,
        exchange="NSE",
        product="EQUITY_INTRADAY",
        trade_date=date(2026, 4, 1),
    ) == intraday
    assert resolve_india_fee_tax_schedule(
        book,
        exchange="NSE",
        product="EQUITY_DELIVERY",
        trade_date=date(2026, 4, 1),
    ) == delivery


def test_book_resolves_point_in_time_schedule_on_closed_boundaries() -> None:
    old = _schedule(
        "old",
        effective_from=date(2025, 1, 1),
        effective_to=date(2025, 12, 31),
    )
    current = _schedule(
        "current",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=2.5,
    )
    book = build_india_fee_tax_book([current, old])

    assert book.schedules == [old, current]
    assert resolve_india_fee_tax_schedule(
        book,
        exchange="NSE",
        product="EQUITY_INTRADAY",
        trade_date=date(2025, 12, 31),
    ).schedule_id == "old"
    assert resolve_india_fee_tax_schedule(
        book,
        exchange="NSE",
        product="EQUITY_INTRADAY",
        trade_date=date(2026, 1, 1),
    ).schedule_id == "current"


def test_book_is_input_order_invariant() -> None:
    first = _schedule(
        "nse-intraday",
        exchange="NSE",
        product="EQUITY_INTRADAY",
    )
    second = _schedule(
        "bse-delivery",
        exchange="BSE",
        product="EQUITY_DELIVERY",
    )

    left = build_india_fee_tax_book([first, second])
    right = build_india_fee_tax_book([second, first])

    assert left == right
    assert left.book_fingerprint == right.book_fingerprint


def test_overlapping_schedules_for_same_scope_are_rejected() -> None:
    first = _schedule(
        "first",
        effective_from=date(2026, 1, 1),
        effective_to=date(2026, 6, 30),
    )
    second = _schedule(
        "second",
        effective_from=date(2026, 6, 30),
        effective_to=date(2026, 12, 31),
    )

    with pytest.raises(ValueError, match="cannot overlap"):
        build_india_fee_tax_book([first, second])


def test_gap_in_schedule_history_fails_closed_at_resolution_time() -> None:
    first = _schedule(
        "first",
        effective_from=date(2026, 1, 1),
        effective_to=date(2026, 3, 31),
    )
    second = _schedule(
        "second",
        effective_from=date(2026, 5, 1),
    )
    book = build_india_fee_tax_book([first, second])

    with pytest.raises(ValueError, match="exactly one"):
        resolve_india_fee_tax_schedule(
            book,
            exchange="NSE",
            product="EQUITY_INTRADAY",
            trade_date=date(2026, 4, 15),
        )


def test_brokerage_minimum_cannot_exceed_order_cap() -> None:
    with pytest.raises(ValidationError, match="minimum cannot exceed"):
        build_india_fee_tax_schedule(
            schedule_id="invalid",
            exchange="NSE",
            product="EQUITY_INTRADAY",
            effective_from=date(2026, 1, 1),
            brokerage_rate_bps=3.0,
            brokerage_cap_per_order_inr=20.0,
            brokerage_minimum_per_order_inr=25.0,
            exchange_transaction_charge_bps=0.3,
            stt_buy_bps=0.0,
            stt_sell_bps=2.5,
            sebi_turnover_charge_bps=0.01,
            stamp_duty_buy_bps=0.3,
            gst_rate_percent=18.0,
            source_reference="fixture:invalid",
        )


def test_tampered_schedule_and_book_fingerprints_are_rejected() -> None:
    schedule = _schedule("schedule")
    schedule_payload = schedule.model_dump(mode="python")
    schedule_payload["schedule_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="schedule fingerprint mismatch"):
        IndiaFeeTaxScheduleV1.model_validate(schedule_payload)

    book = build_india_fee_tax_book([schedule])
    book_payload = book.model_dump(mode="python")
    book_payload["book_fingerprint"] = "0" * 64

    with pytest.raises(ValidationError, match="book fingerprint mismatch"):
        IndiaFeeTaxBookV1.model_validate(book_payload)
