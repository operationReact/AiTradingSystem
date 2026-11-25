from datetime import UTC, date, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.execution_plan import build_execution_plan
from aitrading_intelligence.execution_quality import evaluate_execution_quality
from aitrading_intelligence.fill_simulator import (
    FillSimulationConfigV1,
    simulate_fill,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_fee_tax import build_india_fee_tax_schedule
from aitrading_intelligence.market_source import MarketResearchExport, ResearchCandle
from aitrading_intelligence.state_dependent_slippage import (
    StateDependentSlippageEstimateV1,
)
from aitrading_intelligence.universe_identity_bridge import (
    MAPPING_RULE_VERSION,
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 3, 4, 30, tzinfo=UTC)
CATALOGUE_VERSION = UUID("11111111-1111-1111-1111-111111111111")
CANDIDATE_FINGERPRINT = "f" * 64


def _slippage(*, state: str = "EVALUATED", side: str = "BUY"):
    evaluated = state == "EVALUATED"
    values = {
        "slippage_version": "state-dependent-slippage.v1",
        "state": state,
        "reason_codes": [] if evaluated else ["MISSING_VOLATILITY_RANGE_FEATURE"],
        "side": side,
        "exchange": "NSE",
        "symbol": "RELIANCE",
        "as_of": AS_OF,
        "data_cutoff": AS_OF - timedelta(seconds=5),
        "config_fingerprint": "a" * 64,
        "source_spread_snapshot_fingerprint": "b" * 64,
        "source_feature_row_fingerprint": "c" * 64,
        "source_market_state_id": "d" * 64 if evaluated else None,
        "spread_bps": 4.0 if evaluated else None,
        "adv_notional": 100_000_000.0 if evaluated else None,
        "relative_volume": 1.0 if evaluated else None,
        "volatility_range_pct": 0.01 if evaluated else None,
        "spread_component_bps": 2.0 if evaluated else None,
        "liquidity_pressure": 0.0 if evaluated else None,
        "liquidity_component_bps": 0.0 if evaluated else None,
        "volatility_component_bps": 5.0 if evaluated else None,
        "estimated_slippage_bps": 7.0 if evaluated else None,
        "status": "RESEARCH_SLIPPAGE_ESTIMATE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    provisional = StateDependentSlippageEstimateV1.model_construct(
        **values,
        estimate_fingerprint="0" * 64,
    )
    return StateDependentSlippageEstimateV1(
        **values,
        estimate_fingerprint=canonical_fingerprint(
            provisional.model_dump(mode="json", exclude={"estimate_fingerprint"})
        ),
    )


def _bridge() -> Nifty500LiquidityIdentityBridge:
    available_at = AS_OF - timedelta(seconds=10)
    binding_payload = {
        "canonicalInstrumentId": "NSE:INE002A01018",
        "isin": "INE002A01018",
        "symbol": "RELIANCE",
        "series": "EQ",
        "sector": "Energy",
        "expectedTradingSymbol": "RELIANCE-EQ",
        "catalogueInstrumentId": 1,
        "brokerTradingSymbol": "RELIANCE-EQ",
        "brokerSymbolToken": "2885",
        "liquidityEvidenceFingerprint": "1" * 64,
        "sectorMappingEvidenceFingerprint": "2" * 64,
        "membershipEffectiveFrom": (AS_OF - timedelta(days=30)).isoformat(),
        "availableAt": available_at.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
    }
    binding = Nifty500LiquidityIdentityBinding(
        canonical_instrument_id="NSE:INE002A01018",
        isin="INE002A01018",
        symbol="RELIANCE",
        series="EQ",
        sector="Energy",
        expected_trading_symbol="RELIANCE-EQ",
        catalogue_instrument_id=1,
        broker_trading_symbol="RELIANCE-EQ",
        broker_symbol_token="2885",
        liquidity_evidence_fingerprint="1" * 64,
        sector_mapping_evidence_fingerprint="2" * 64,
        membership_effective_from=AS_OF - timedelta(days=30),
        available_at=available_at,
        binding_fingerprint=canonical_fingerprint(binding_payload),
    )
    bridge_payload = {
        "contractVersion": "nifty500-liquidity-identity-bridge.v1",
        "asOf": AS_OF.isoformat(),
        "dataCutoff": available_at.isoformat(),
        "mappingRule": MAPPING_RULE_VERSION,
        "sourceCatalogueVersion": str(CATALOGUE_VERSION),
        "sourceCatalogueFingerprint": "3" * 64,
        "sourceLiquiditySnapshotFingerprint": "4" * 64,
        "sourceSectorEnrichmentFingerprint": "5" * 64,
        "bindings": [binding.model_dump(mode="json")],
        "bindingCount": 1,
        "status": "RESEARCH_IDENTITY_BRIDGE_ONLY",
        "canAuthorizeCapital": False,
    }
    return Nifty500LiquidityIdentityBridge(
        as_of=AS_OF,
        data_cutoff=available_at,
        source_catalogue_version=str(CATALOGUE_VERSION),
        source_catalogue_fingerprint="3" * 64,
        source_liquidity_snapshot_fingerprint="4" * 64,
        source_sector_enrichment_fingerprint="5" * 64,
        bindings=[binding],
        binding_count=1,
        snapshot_fingerprint=canonical_fingerprint(bridge_payload),
    )


def _schedule():
    return build_india_fee_tax_schedule(
        schedule_id="NSE-INTRADAY-FIXTURE",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=10.0,
        brokerage_cap_per_order_inr=20.0,
        brokerage_minimum_per_order_inr=5.0,
        exchange_transaction_charge_bps=0.30,
        stt_buy_bps=0.0,
        stt_sell_bps=2.5,
        sebi_turnover_charge_bps=0.01,
        stamp_duty_buy_bps=0.30,
        gst_rate_percent=18.0,
        source_reference="fixture:fees",
    )


def _candle(
    minute: int,
    *,
    open_price: float,
    high: float,
    low: float,
    close: float,
    volume: float = 1_000.0,
) -> ResearchCandle:
    candle_time = AS_OF + timedelta(minutes=minute)
    return ResearchCandle(
        candleTime=candle_time,
        availableAt=candle_time + timedelta(minutes=1),
        open=open_price,
        high=high,
        low=low,
        close=close,
        volume=volume,
        source="ANGEL_ONE_SMARTAPI",
        fetchedAt=candle_time + timedelta(minutes=1),
    )


def _market_export(*, next_open: float = 100.0) -> MarketResearchExport:
    rows = [
        _candle(0, open_price=100.0, high=101.0, low=99.0, close=100.0),
        _candle(
            1,
            open_price=next_open,
            high=max(next_open + 1.0, 101.0),
            low=min(next_open - 1.0, 99.0),
            close=next_open,
        ),
        _candle(2, open_price=100.0, high=101.0, low=99.0, close=100.0),
    ]
    return MarketResearchExport(
        contractVersion="market-history.v1",
        generatedAt=AS_OF + timedelta(minutes=4),
        instrumentId=1,
        exchange="NSE",
        tradingSymbol="RELIANCE-EQ",
        symbolToken="2885",
        catalogueVersion=CATALOGUE_VERSION,
        interval="ONE_MINUTE",
        fromInclusive=AS_OF - timedelta(minutes=1),
        toExclusive=AS_OF + timedelta(minutes=10),
        dataCutoff=max(row.available_at for row in rows),
        providerSources=["ANGEL_ONE_SMARTAPI"],
        candles=rows,
    )


def _plan_and_fill(
    *,
    quantity: int = 100,
    next_open: float = 100.0,
    max_wait_bars: int = 5,
    order_type: str = "MARKET",
    limit_price: float | None = None,
    submitted_at: datetime = AS_OF,
    max_slippage_age_seconds: int = 60,
):
    slippage = _slippage()
    bridge = _bridge()
    schedule = _schedule()
    fill_config = FillSimulationConfigV1(
        latency_milliseconds=250,
        max_fill_fraction_of_bar_volume=0.10,
        max_wait_bars=max_wait_bars,
        max_slippage_age_seconds=max_slippage_age_seconds,
    )
    plan = build_execution_plan(
        candidate_fingerprint=CANDIDATE_FINGERPRINT,
        side="BUY",
        order_type=order_type,
        quantity=quantity,
        reference_price=100.0,
        limit_price=limit_price,
        slippage=slippage,
        identity_bridge=bridge,
        fee_schedule=schedule,
        fill_config=fill_config,
    )
    fill = simulate_fill(
        order_id="order-quality",
        side="BUY",
        order_type=order_type,
        quantity=quantity,
        submitted_at=submitted_at,
        reference_price=100.0,
        limit_price=limit_price,
        slippage=slippage,
        identity_bridge=bridge,
        market_export=_market_export(next_open=next_open),
        config=fill_config,
    )
    return plan, fill, schedule


def test_full_fill_reconstructs_expected_and_realized_cost_on_same_lineage() -> None:
    plan, fill, schedule = _plan_and_fill()
    quality = evaluate_execution_quality(
        plan=plan,
        fill_result=fill,
        fee_schedule=schedule,
    )

    assert quality.state == "FULLY_FILLED"
    assert quality.reason_codes == []
    assert quality.execution_plan_fingerprint == plan.plan_fingerprint
    assert quality.fill_result_fingerprint == fill.result_fingerprint
    assert quality.fee_schedule_fingerprint == schedule.schedule_fingerprint
    assert quality.cost_model_version == "execution-cost-model.v1"
    assert quality.cost_model_fingerprint == plan.expected_cost.cost_model_fingerprint
    assert quality.fill_fraction == 1.0
    assert quality.completion_shortfall_fraction == 0.0
    assert quality.expected_slippage_bps == pytest.approx(7.0)
    assert quality.realized_average_fill_price == pytest.approx(100.07)
    assert quality.realized_cost is not None
    assert quality.realized_cost.realized_slippage_bps == pytest.approx(7.0)
    assert quality.fee_tax_delta_bps == pytest.approx(0.0)
    assert quality.slippage_delta_bps == pytest.approx(0.0)
    assert quality.total_cost_delta_bps == pytest.approx(0.0)
    assert quality.status == "RESEARCH_EXECUTION_QUALITY_ONLY"
    assert quality.promotion_allowed is False
    assert quality.can_authorize_capital is False


def test_adverse_arrival_gap_is_visible_as_realized_cost_overrun() -> None:
    plan, fill, schedule = _plan_and_fill(next_open=101.0)
    quality = evaluate_execution_quality(
        plan=plan,
        fill_result=fill,
        fee_schedule=schedule,
    )

    assert quality.state == "FULLY_FILLED"
    assert quality.arrival_gap_bps == pytest.approx(100.0)
    assert quality.realized_cost is not None
    assert quality.realized_cost.realized_slippage_bps == pytest.approx(107.07)
    assert quality.slippage_delta_bps == pytest.approx(100.07)
    assert quality.total_cost_delta_bps is not None
    assert quality.total_cost_delta_bps > 99.0


def test_partial_fill_has_realized_cost_only_for_filled_quantity() -> None:
    plan, fill, schedule = _plan_and_fill(quantity=250, max_wait_bars=1)
    quality = evaluate_execution_quality(
        plan=plan,
        fill_result=fill,
        fee_schedule=schedule,
    )

    assert quality.state == "PARTIALLY_FILLED"
    assert quality.reason_codes == ["PARTIAL_FILL"]
    assert quality.filled_quantity == 100
    assert quality.fill_fraction == pytest.approx(0.4)
    assert quality.completion_shortfall_fraction == pytest.approx(0.6)
    assert quality.realized_cost is not None
    assert quality.realized_cost.filled_notional_inr == pytest.approx(10_007.0)
    assert quality.expected_total_cost_bps is not None
    assert quality.realized_cost.realized_total_cost_bps is not None


def test_unfilled_limit_preserves_expected_evidence_without_fake_realized_metrics() -> None:
    plan, fill, schedule = _plan_and_fill(
        order_type="LIMIT",
        limit_price=98.0,
    )
    quality = evaluate_execution_quality(
        plan=plan,
        fill_result=fill,
        fee_schedule=schedule,
    )

    assert quality.state == "UNFILLED"
    assert quality.reason_codes == ["NO_REALIZED_FILL"]
    assert quality.expected_total_cost_bps is not None
    assert quality.filled_quantity == 0
    assert quality.fill_fraction == 0.0
    assert quality.completion_shortfall_fraction == 1.0
    assert quality.realized_average_fill_price is None
    assert quality.realized_cost is None
    assert quality.fee_tax_delta_bps is None
    assert quality.slippage_delta_bps is None
    assert quality.total_cost_delta_bps is None


def test_inconclusive_fill_preserves_plan_expectation_but_no_realized_quality() -> None:
    plan, fill, schedule = _plan_and_fill(
        submitted_at=AS_OF + timedelta(seconds=61),
        max_slippage_age_seconds=60,
    )
    quality = evaluate_execution_quality(
        plan=plan,
        fill_result=fill,
        fee_schedule=schedule,
    )

    assert quality.state == "INCONCLUSIVE"
    assert quality.reason_codes == ["FILL_SIMULATION_INCONCLUSIVE"]
    assert quality.expected_total_cost_bps is not None
    assert quality.realized_average_fill_price is None
    assert quality.realized_cost is None
    assert quality.total_cost_delta_bps is None


def test_fill_lineage_must_match_execution_plan_exactly() -> None:
    plan, _, schedule = _plan_and_fill(quantity=101)
    _, fill, _ = _plan_and_fill(quantity=100)

    with pytest.raises(ValueError, match="fill quantity does not match execution plan"):
        evaluate_execution_quality(
            plan=plan,
            fill_result=fill,
            fee_schedule=schedule,
        )


def test_fee_schedule_lineage_must_match_plan() -> None:
    plan, fill, _ = _plan_and_fill()
    other_schedule = build_india_fee_tax_schedule(
        schedule_id="NSE-INTRADAY-OTHER",
        exchange="NSE",
        product="EQUITY_INTRADAY",
        effective_from=date(2026, 1, 1),
        brokerage_rate_bps=5.0,
        exchange_transaction_charge_bps=0.30,
        stt_buy_bps=0.0,
        stt_sell_bps=2.5,
        sebi_turnover_charge_bps=0.01,
        stamp_duty_buy_bps=0.30,
        gst_rate_percent=18.0,
        source_reference="fixture:other-fees",
    )

    with pytest.raises(ValueError, match="fee schedule ID does not match execution plan"):
        evaluate_execution_quality(
            plan=plan,
            fill_result=fill,
            fee_schedule=other_schedule,
        )