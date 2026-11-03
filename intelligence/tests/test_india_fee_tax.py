from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError

from aitrading_intelligence.india_fee_tax import (
    BrokerageRuleV1,
    IndiaCashEquityFeeScheduleV1,
    angel_one_nse_cash_equity_schedule_2026,
    build_fee_schedule,
    estimate_cash_equity_leg_fees,
    estimate_cash_equity_round_trip_fees,
)

TRADE_DATE = date(2026, 11, 3)


def test_current_angel_one_nse_schedule_is_versioned_and_auditable() -> None:
    schedule = angel_one_nse_cash_equity_schedule_2026()

    assert schedule.schedule_id == "ANGEL_ONE_NSE_CASH_EQUITY_2026-04-01"
    assert schedule.effective_from == date(2026, 4, 1)
    assert schedule.exchange_transaction_charge_bps == Decimal("0.30699")
    assert schedule.ipft_bps == Decimal("0.00001")
    assert schedule.sebi_turnover_fee_bps == Decimal("0.01")
    assert schedule.gst_percent == Decimal("18")
    assert schedule.delivery_stt_buy_bps == Decimal("10")
    assert schedule.delivery_stt_sell_bps == Decimal("10")
    assert schedule.intraday_stt_sell_bps == Decimal("2.5")
    assert schedule.delivery_stamp_buy_bps == Decimal("1.5")
    assert schedule.intraday_stamp_buy_bps == Decimal("0.3")
    assert schedule.brokerage.rate_bps == Decimal("10")
    assert schedule.brokerage.minimum_per_order_inr == Decimal("5")
    assert schedule.brokerage.maximum_per_order_inr == Decimal("20")
    assert schedule.delivery_dp_sell_flat_inr == Decimal("20")
    assert len(schedule.schedule_fingerprint) == 64
    assert schedule.status == "FEE_TAX_CONFIG_ONLY"
    assert schedule.can_authorize_capital is False


def test_delivery_buy_and_sell_apply_different_statutory_bases() -> None:
    schedule = angel_one_nse_cash_equity_schedule_2026()
    buy = estimate_cash_equity_leg_fees(
        schedule,
        trade_date=TRADE_DATE,
        side="BUY",
        settlement_mode="DELIVERY",
        quantity=1_000,
        price_inr=Decimal("100"),
    )
    sell = estimate_cash_equity_leg_fees(
        schedule,
        trade_date=TRADE_DATE,
        side="SELL",
        settlement_mode="DELIVERY",
        quantity=1_000,
        price_inr=Decimal("100"),
    )

    assert buy.turnover_inr == Decimal("100000")
    assert buy.breakdown.brokerage_inr == Decimal("20.00")
    assert buy.breakdown.exchange_transaction_charge_inr == Decimal("3.07")
    assert buy.breakdown.sebi_turnover_fee_inr == Decimal("0.10")
    assert buy.breakdown.stt_inr == Decimal("100.00")
    assert buy.breakdown.stamp_duty_inr == Decimal("15.00")
    assert buy.breakdown.dp_charge_inr == Decimal("0.00")
    assert buy.breakdown.gst_inr == Decimal("4.17")
    assert buy.breakdown.total_charges_inr == Decimal("142.34")

    assert sell.breakdown.stt_inr == Decimal("100.00")
    assert sell.breakdown.stamp_duty_inr == Decimal("0.00")
    assert sell.breakdown.dp_charge_inr == Decimal("20.00")
    assert sell.breakdown.gst_inr == Decimal("7.77")
    assert sell.breakdown.total_charges_inr == Decimal("150.94")
    assert buy.schedule_fingerprint == sell.schedule_fingerprint
    assert buy.promotion_allowed is False
    assert sell.can_authorize_capital is False


def test_intraday_stt_is_sell_only_and_stamp_duty_is_buy_only() -> None:
    schedule = angel_one_nse_cash_equity_schedule_2026()
    buy = estimate_cash_equity_leg_fees(
        schedule,
        trade_date=TRADE_DATE,
        side="BUY",
        settlement_mode="INTRADAY",
        quantity=1_000,
        price_inr=Decimal("100"),
    )
    sell = estimate_cash_equity_leg_fees(
        schedule,
        trade_date=TRADE_DATE,
        side="SELL",
        settlement_mode="INTRADAY",
        quantity=1_000,
        price_inr=Decimal("100"),
    )

    assert buy.breakdown.stt_inr == Decimal("0.00")
    assert buy.breakdown.stamp_duty_inr == Decimal("3.00")
    assert buy.breakdown.dp_charge_inr == Decimal("0.00")
    assert buy.breakdown.total_charges_inr == Decimal("30.34")

    assert sell.breakdown.stt_inr == Decimal("25.00")
    assert sell.breakdown.stamp_duty_inr == Decimal("0.00")
    assert sell.breakdown.dp_charge_inr == Decimal("0.00")
    assert sell.breakdown.total_charges_inr == Decimal("52.34")


def test_round_trip_binds_both_legs_to_same_schedule() -> None:
    schedule = angel_one_nse_cash_equity_schedule_2026()
    estimate = estimate_cash_equity_round_trip_fees(
        schedule,
        buy_date=TRADE_DATE,
        sell_date=TRADE_DATE,
        settlement_mode="INTRADAY",
        quantity=1_000,
        buy_price_inr=Decimal("100"),
        sell_price_inr=Decimal("101"),
    )

    assert estimate.buy.side == "BUY"
    assert estimate.sell.side == "SELL"
    assert estimate.schedule_fingerprint == schedule.schedule_fingerprint
    assert estimate.total_charges_inr == (
        estimate.buy.breakdown.total_charges_inr
        + estimate.sell.breakdown.total_charges_inr
    )
    assert estimate.total_turnover_inr == Decimal("201000")
    assert estimate.effective_round_trip_bps > Decimal("0")
    assert len(estimate.round_trip_fingerprint) == 64
    assert estimate.promotion_allowed is False
    assert estimate.can_authorize_capital is False


def test_schedule_effective_date_fails_closed() -> None:
    schedule = angel_one_nse_cash_equity_schedule_2026()

    with pytest.raises(ValueError, match="not effective"):
        estimate_cash_equity_leg_fees(
            schedule,
            trade_date=date(2026, 3, 31),
            side="BUY",
            settlement_mode="DELIVERY",
            quantity=10,
            price_inr=Decimal("100"),
        )


def test_brokerage_rule_respects_minimum_rate_and_cap() -> None:
    rule = BrokerageRuleV1(
        rate_bps=Decimal("10"),
        minimum_per_order_inr=Decimal("5"),
        maximum_per_order_inr=Decimal("20"),
    )

    assert rule.calculate(Decimal("1000")) == Decimal("5")
    assert rule.calculate(Decimal("10000")) == Decimal("10")
    assert rule.calculate(Decimal("100000")) == Decimal("20")


def test_schedule_fingerprint_detects_rate_tampering() -> None:
    schedule = angel_one_nse_cash_equity_schedule_2026()
    payload = schedule.model_dump(mode="python")
    payload["delivery_stt_buy_bps"] = Decimal("9")

    with pytest.raises(ValidationError, match="schedule fingerprint mismatch"):
        IndiaCashEquityFeeScheduleV1.model_validate(payload)


def test_custom_schedule_requires_explicit_unique_sources() -> None:
    with pytest.raises(ValidationError, match="source_ids must be unique"):
        build_fee_schedule(
            schedule_id="CUSTOM",
            effective_from=date(2026, 1, 1),
            source_ids=["SOURCE", "SOURCE"],
            brokerage=BrokerageRuleV1(),
            delivery_dp_sell_flat_inr=Decimal("0"),
            exchange_transaction_charge_bps=Decimal("0.3"),
            ipft_bps=Decimal("0"),
            sebi_turnover_fee_bps=Decimal("0.01"),
            gst_percent=Decimal("18"),
            delivery_stt_buy_bps=Decimal("10"),
            delivery_stt_sell_bps=Decimal("10"),
            intraday_stt_sell_bps=Decimal("2.5"),
            delivery_stamp_buy_bps=Decimal("1.5"),
            intraday_stamp_buy_bps=Decimal("0.3"),
        )
