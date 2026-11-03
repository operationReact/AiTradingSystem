from __future__ import annotations

import importlib.util
from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError

from aitrading_intelligence.india_fee_tax import (
    build_india_fee_tax_book,
    build_india_fee_tax_schedule,
)
from aitrading_intelligence.india_independent_holdout import (
    IndiaHoldoutReport,
    _build_examples,
    evaluate_india_independent_holdout,
)
from aitrading_intelligence.market_source import MarketResearchExport
from aitrading_intelligence.pytorch_target import ForwardReturnTargetSpec

IST = ZoneInfo("Asia/Kolkata")
TRAIN_DAYS = (date(2026, 10, 26), date(2026, 10, 27))
HOLDOUT_DAYS = (date(2026, 10, 29), date(2026, 10, 30))


def _export(days: tuple[date, ...], *, source: str = "ANGEL_ONE_SMARTAPI"):
    candles = []
    close = 100.0
    for day in days:
        start = datetime(day.year, day.month, day.day, 9, 15, tzinfo=IST)
        for offset in range(45):
            candle_start = start + timedelta(minutes=5 * offset)
            available = candle_start + timedelta(minutes=5)
            previous = close
            direction = 1 if offset % 4 in (0, 1) else -1
            close = close * (1 + direction * (0.013 if offset % 3 else 0.008))
            candles.append({
                "candleTime": candle_start.isoformat(),
                "availableAt": available.isoformat(),
                "open": previous,
                "high": max(previous, close) * 1.001,
                "low": min(previous, close) * 0.999,
                "close": close,
                "volume": 2000 + 50 * (offset % 6),
                "source": source,
                "fetchedAt": (available + timedelta(hours=1)).isoformat(),
            })
    beginning = datetime.fromisoformat(candles[0]["candleTime"])
    ending = datetime.fromisoformat(candles[-1]["availableAt"])
    return MarketResearchExport.model_validate({
        "contractVersion": "market-history.v1",
        "generatedAt": datetime(2026, 11, 3, 12, tzinfo=UTC).isoformat(),
        "instrumentId": 1234,
        "exchange": "NSE",
        "tradingSymbol": "RELIANCE-EQ",
        "symbolToken": "2885",
        "catalogueVersion": "12345678-1234-1234-1234-123456789abc",
        "interval": "FIVE_MINUTE",
        "fromInclusive": beginning.isoformat(),
        "toExclusive": ending.isoformat(),
        "dataCutoff": ending.isoformat(),
        "providerSources": [source],
        "candles": candles,
    })


def _fee_book():
    schedule = build_india_fee_tax_schedule(
        schedule_id="test-nse-intraday-2026",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=1.0,
        exchange_transaction_charge_bps=0.5,
        stt_buy_bps=0.0,
        stt_sell_bps=1.0,
        sebi_turnover_charge_bps=0.1,
        stamp_duty_buy_bps=0.3,
        gst_rate_percent=18.0,
        source_reference="synthetic-config-for-regression-tests-not-statutory-fact",
    )
    return build_india_fee_tax_book([schedule])


def _target(cost_bps: float = 25.0):
    return ForwardReturnTargetSpec(
        horizon_bars=1,
        minimum_edge_bps=20.0,
        round_trip_cost_bps=cost_bps,
    )


def _run(*, training=None, holdout=None, target=None, fee_book=None, pytorch=False):
    return evaluate_india_independent_holdout(
        training or _export(TRAIN_DAYS),
        holdout or _export(HOLDOUT_DAYS),
        fee_book or _fee_book(),
        target or _target(),
        include_pytorch=pytorch,
    )


def test_intraday_horizon_does_not_cross_market_session_boundary() -> None:
    target = _target()
    examples = _build_examples(_export(HOLDOUT_DAYS), target)

    assert len(examples) > 30
    assert all(
        example.as_of.astimezone(IST).date()
        == example.target_observed_at.astimezone(IST).date()
        for example in examples
    )
    assert any(
        example.as_of.astimezone(IST).date() == HOLDOUT_DAYS[0]
        for example in examples
    )
    assert any(
        example.as_of.astimezone(IST).date() == HOLDOUT_DAYS[1]
        for example in examples
    )


def test_market_holdout_trains_models_on_earlier_cohort_only() -> None:
    result = _run()

    assert result.status == "RESEARCH_HOLDOUT_METRICS_ONLY"
    assert result.exchange == "NSE"
    assert result.training_rows >= 60
    assert result.holdout_rows >= 30
    assert result.holdout_sessions == 2
    assert result.training_last_label_available_at < result.holdout_first_decision_at
    assert {m.model_family for m in result.results} == {"CATBOOST", "LIGHTGBM"}
    assert all(m.holdout_rows == result.holdout_rows for m in result.results)
    assert all(m.brier_score >= 0 and m.log_loss >= 0 for m in result.results)
    assert result.external_market_source_verified is False
    assert result.holdout_pre_registration_verified is False
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_holdout_repeatability_does_not_select_a_live_champion() -> None:
    one = _run()
    two = _run()
    assert one.report_fingerprint == two.report_fingerprint
    assert one.results == two.results


def test_insufficient_holdout_market_sessions_are_explicitly_inconclusive() -> None:
    result = _run(holdout=_export((HOLDOUT_DAYS[0],)))

    assert result.status == "INCONCLUSIVE"
    assert "INSUFFICIENT_HOLDOUT_SESSIONS" in result.reason_codes
    assert result.results == []


def test_training_and_holdout_declared_time_windows_cannot_overlap() -> None:
    holdout_data = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    holdout_data["fromInclusive"] = datetime(
        2026, 10, 27, 12, 0, tzinfo=IST
    ).isoformat()
    with pytest.raises(ValueError, match="must not overlap"):
        _run(holdout=MarketResearchExport.model_validate(holdout_data))


def test_holdout_refuses_different_instrument_or_exchange() -> None:
    holdout_data = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    holdout_data["instrumentId"] = 999
    with pytest.raises(ValueError, match="same cash-equity instrument"):
        _run(holdout=MarketResearchExport.model_validate(holdout_data))
    holdout_data["instrumentId"] = 1234
    holdout_data["exchange"] = "BSE"
    with pytest.raises(ValueError, match="same NSE/BSE"):
        _run(holdout=MarketResearchExport.model_validate(holdout_data))


def test_holdout_refuses_understated_india_fee_and_slippage_assumptions() -> None:
    with pytest.raises(ValueError, match="understates configured India fees"):
        _run(target=_target(cost_bps=1))


def test_holdout_refuses_missing_trade_date_fee_schedule() -> None:
    schedule = build_india_fee_tax_schedule(
        schedule_id="future",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2027, 1, 1),
        brokerage_rate_bps=1.0,
        exchange_transaction_charge_bps=0.1,
        stt_buy_bps=0,
        stt_sell_bps=0.5,
        sebi_turnover_charge_bps=0.1,
        stamp_duty_buy_bps=0.2,
        gst_rate_percent=18,
        source_reference="test-only",
    )
    with pytest.raises(ValueError, match="exactly one India fee/tax schedule"):
        _run(fee_book=build_india_fee_tax_book([schedule]))


def test_candle_cannot_be_fetched_before_first_market_availability() -> None:
    payload = _export(HOLDOUT_DAYS).model_dump(mode="json", by_alias=True)
    payload["candles"][0]["fetchedAt"] = payload["candles"][0]["candleTime"]
    with pytest.raises(ValueError, match="cannot be fetched before"):
        _run(holdout=MarketResearchExport.model_validate(payload))


def test_holdout_result_fingerprint_is_tamper_evident() -> None:
    result = _run()
    payload = result.model_dump(mode="json")
    payload["training_rows"] += 1
    with pytest.raises(ValidationError, match="independent holdout report fingerprint"):
        IndiaHoldoutReport.model_validate(payload)


def test_optional_pytorch_uses_the_same_independent_holdout() -> None:
    if importlib.util.find_spec("torch") is None:
        pytest.skip("PyTorch is installed only by the dedicated research workflow")
    result = _run(pytorch=True)
    assert {m.model_family for m in result.results} == {
        "CATBOOST", "LIGHTGBM", "PYTORCH"
    }
    assert result.promotion_allowed is False
    assert result.external_market_source_verified is False
