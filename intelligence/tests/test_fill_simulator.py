from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.fill_simulator import (
    FillSimulationConfigV1,
    simulate_fill,
)
from aitrading_intelligence.fingerprints import canonical_fingerprint
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
    fingerprint = canonical_fingerprint(
        provisional.model_dump(mode="json", exclude={"estimate_fingerprint"})
    )
    return StateDependentSlippageEstimateV1(
        **values,
        estimate_fingerprint=fingerprint,
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


def _market_export(
    *,
    candles: list[ResearchCandle] | None = None,
    trading_symbol: str = "RELIANCE-EQ",
) -> MarketResearchExport:
    rows = candles or [
        _candle(0, open_price=100.0, high=101.0, low=99.5, close=100.5),
        _candle(1, open_price=102.0, high=103.0, low=101.0, close=102.5),
        _candle(2, open_price=101.0, high=102.0, low=100.0, close=101.5),
    ]
    generated_at = max(row.available_at for row in rows) + timedelta(minutes=1)
    return MarketResearchExport(
        contractVersion="market-history.v1",
        generatedAt=generated_at,
        instrumentId=1,
        exchange="NSE",
        tradingSymbol=trading_symbol,
        symbolToken="2885",
        catalogueVersion=CATALOGUE_VERSION,
        interval="ONE_MINUTE",
        fromInclusive=AS_OF - timedelta(minutes=1),
        toExclusive=AS_OF + timedelta(minutes=10),
        dataCutoff=max(row.available_at for row in rows),
        providerSources=["ANGEL_ONE_SMARTAPI"],
        candles=rows,
    )


def test_market_order_latency_skips_current_bar_and_fills_across_capacity() -> None:
    result = simulate_fill(
        order_id="order-1",
        side="BUY",
        order_type="MARKET",
        quantity=150,
        submitted_at=AS_OF,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=FillSimulationConfigV1(
            latency_milliseconds=250,
            max_fill_fraction_of_bar_volume=0.10,
            max_wait_bars=5,
        ),
    )

    assert result.state == "FULLY_FILLED"
    assert result.reason_codes == []
    assert result.latency_skipped_bar_count == 1
    assert result.considered_bar_count == 2
    assert result.arrival_gap_bps == pytest.approx(200.0)
    assert result.filled_quantity == 150
    assert result.remaining_quantity == 0
    assert result.fill_fraction == 1.0
    assert [fill.quantity for fill in result.fills] == [100, 50]
    assert result.fills[0].candle_time == AS_OF + timedelta(minutes=1)
    assert result.fills[0].price == pytest.approx(102.0714)
    assert result.status == "RESEARCH_FILL_SIMULATION_ONLY"
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_max_wait_bars_produces_explicit_partial_fill() -> None:
    result = simulate_fill(
        order_id="order-2",
        side="BUY",
        order_type="MARKET",
        quantity=250,
        submitted_at=AS_OF,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=FillSimulationConfigV1(
            latency_milliseconds=250,
            max_fill_fraction_of_bar_volume=0.10,
            max_wait_bars=1,
        ),
    )

    assert result.state == "PARTIALLY_FILLED"
    assert result.reason_codes == ["MAX_WAIT_BARS_REACHED_WITH_REMAINDER"]
    assert result.filled_quantity == 100
    assert result.remaining_quantity == 150
    assert result.fill_fraction == pytest.approx(0.4)
    assert len(result.fills) == 1


def test_limit_order_never_fills_worse_than_limit_and_can_remain_unfilled() -> None:
    unfilled = simulate_fill(
        order_id="order-3",
        side="BUY",
        order_type="LIMIT",
        quantity=50,
        submitted_at=AS_OF,
        reference_price=100.0,
        limit_price=99.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=FillSimulationConfigV1(latency_milliseconds=250),
    )

    assert unfilled.state == "UNFILLED"
    assert unfilled.reason_codes == ["LIMIT_NOT_REACHED_OR_NO_FILL_CAPACITY"]
    assert unfilled.filled_quantity == 0
    assert unfilled.average_fill_price is None

    filled = simulate_fill(
        order_id="order-4",
        side="BUY",
        order_type="LIMIT",
        quantity=50,
        submitted_at=AS_OF,
        reference_price=100.0,
        limit_price=102.5,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=FillSimulationConfigV1(
            latency_milliseconds=250,
            max_fill_fraction_of_bar_volume=0.10,
        ),
    )

    assert filled.state == "FULLY_FILLED"
    assert filled.average_fill_price is not None
    assert filled.average_fill_price <= 102.5


def test_no_post_latency_bars_is_valid_unfilled_gap_outcome() -> None:
    result = simulate_fill(
        order_id="order-5",
        side="BUY",
        order_type="MARKET",
        quantity=10,
        submitted_at=AS_OF + timedelta(minutes=2, seconds=30),
        reference_price=101.5,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=FillSimulationConfigV1(
            latency_milliseconds=250,
            max_slippage_age_seconds=3_600,
        ),
    )

    assert result.state == "UNFILLED"
    assert result.reason_codes == ["NO_POST_LATENCY_BARS"]
    assert result.arrival_gap_bps is None
    assert result.considered_bar_count == 0


def test_inconclusive_or_stale_slippage_blocks_fill_simulation() -> None:
    inconclusive = simulate_fill(
        order_id="order-6",
        side="BUY",
        order_type="MARKET",
        quantity=10,
        submitted_at=AS_OF,
        reference_price=100.0,
        slippage=_slippage(state="INCONCLUSIVE"),
        identity_bridge=_bridge(),
        market_export=_market_export(),
    )

    assert inconclusive.state == "INCONCLUSIVE"
    assert inconclusive.reason_codes == ["SLIPPAGE_ESTIMATE_INCONCLUSIVE"]
    assert inconclusive.fills == []

    stale = simulate_fill(
        order_id="order-7",
        side="BUY",
        order_type="MARKET",
        quantity=10,
        submitted_at=AS_OF + timedelta(seconds=61),
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(),
        config=FillSimulationConfigV1(max_slippage_age_seconds=60),
    )

    assert stale.state == "INCONCLUSIVE"
    assert stale.reason_codes == ["SLIPPAGE_ESTIMATE_STALE_OR_FUTURE"]
    assert stale.filled_quantity == 0


def test_market_candle_input_order_does_not_change_simulation() -> None:
    rows = [
        _candle(0, open_price=100.0, high=101.0, low=99.5, close=100.5),
        _candle(1, open_price=102.0, high=103.0, low=101.0, close=102.5),
        _candle(2, open_price=101.0, high=102.0, low=100.0, close=101.5),
    ]
    first = simulate_fill(
        order_id="order-8",
        side="BUY",
        order_type="MARKET",
        quantity=150,
        submitted_at=AS_OF,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(candles=rows),
        config=FillSimulationConfigV1(max_fill_fraction_of_bar_volume=0.10),
    )
    second = simulate_fill(
        order_id="order-8",
        side="BUY",
        order_type="MARKET",
        quantity=150,
        submitted_at=AS_OF,
        reference_price=100.0,
        slippage=_slippage(),
        identity_bridge=_bridge(),
        market_export=_market_export(candles=list(reversed(rows))),
        config=FillSimulationConfigV1(max_fill_fraction_of_bar_volume=0.10),
    )

    assert first == second
    assert first.result_fingerprint == second.result_fingerprint


def test_identity_mismatch_and_tampered_bridge_are_rejected() -> None:
    with pytest.raises(ValueError, match="trading symbol does not match"):
        simulate_fill(
            order_id="order-9",
            side="BUY",
            order_type="MARKET",
            quantity=10,
            submitted_at=AS_OF,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=_bridge(),
            market_export=_market_export(trading_symbol="TCS-EQ"),
        )

    tampered = _bridge().model_copy(update={"snapshot_fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="identity bridge fingerprint mismatch"):
        simulate_fill(
            order_id="order-10",
            side="BUY",
            order_type="MARKET",
            quantity=10,
            submitted_at=AS_OF,
            reference_price=100.0,
            slippage=_slippage(),
            identity_bridge=tampered,
            market_export=_market_export(),
        )