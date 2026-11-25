from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID

import pytest

from aitrading_intelligence.india_cross_section import build_india_cross_section_snapshot
from aitrading_intelligence.market_depth_source import (
    AngelOneMarketDepthEvidence,
    AngelOneMarketDepthResearchExport,
)
from aitrading_intelligence.market_history_cross_section import (
    build_market_history_cross_section_inputs,
)
from aitrading_intelligence.market_source import MarketResearchExport, ResearchCandle
from aitrading_intelligence.production_cross_section import (
    assemble_production_cross_section_observations,
)
from aitrading_intelligence.production_universe import (
    build_liquidity_qualified_india_universe_snapshot,
)
from aitrading_intelligence.universe_identity_bridge import (
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 2, 16, 0, tzinfo=UTC)
CATALOGUE_VERSION = "00000000-0000-0000-0000-000000000001"
SYMBOLS = (
    ("AAA", "INE000A01001", 101, "1001", "ENERGY"),
    ("BBB", "INE000A01002", 102, "1002", "FINANCIALS"),
    ("CCC", "INE000A01003", 103, "1003", "IT"),
)


def _hash(char: str) -> str:
    return char * 64


def _bridge() -> Nifty500LiquidityIdentityBridge:
    bindings = []
    for index, (symbol, isin, instrument_id, token, sector) in enumerate(SYMBOLS, start=1):
        bindings.append(
            Nifty500LiquidityIdentityBinding(
                canonical_instrument_id=f"NSE:{isin}",
                isin=isin,
                symbol=symbol,
                series="EQ",
                sector=sector,
                expected_trading_symbol=f"{symbol}-EQ",
                catalogue_instrument_id=instrument_id,
                broker_trading_symbol=f"{symbol}-EQ",
                broker_symbol_token=token,
                liquidity_evidence_fingerprint=f"{index:064x}",
                sector_mapping_evidence_fingerprint=f"{index + 10:064x}",
                membership_effective_from=AS_OF - timedelta(days=60),
                available_at=AS_OF - timedelta(minutes=10),
                binding_fingerprint=f"{index + 20:064x}",
            )
        )
    bindings.sort(key=lambda row: row.canonical_instrument_id)
    return Nifty500LiquidityIdentityBridge(
        as_of=AS_OF,
        data_cutoff=AS_OF - timedelta(minutes=10),
        source_catalogue_version=CATALOGUE_VERSION,
        source_catalogue_fingerprint=_hash("a"),
        source_liquidity_snapshot_fingerprint=_hash("b"),
        source_sector_enrichment_fingerprint=_hash("c"),
        bindings=bindings,
        binding_count=3,
        snapshot_fingerprint=_hash("d"),
    )


def _market_export(
    symbol: str,
    instrument_id: int,
    token: str,
    *,
    future_candle: bool = False,
) -> MarketResearchExport:
    candles = []
    start = AS_OF - timedelta(days=20)
    for offset in range(21):
        event_at = start + timedelta(days=offset, hours=-6)
        available_at = start + timedelta(days=offset, minutes=-30)
        price = 100.0 + offset
        candles.append(
            ResearchCandle(
                candle_time=event_at,
                available_at=available_at,
                open=price - 0.5,
                high=price + 1.0,
                low=price - 1.0,
                close=price,
                volume=1000.0 + offset,
                source="ANGEL_ONE_SMARTAPI_HISTORICAL",
                fetched_at=available_at,
            )
        )
    if future_candle:
        candles.append(
            ResearchCandle(
                candle_time=AS_OF + timedelta(minutes=1),
                available_at=AS_OF + timedelta(minutes=2),
                open=999.0,
                high=1000.0,
                low=998.0,
                close=999.0,
                volume=9999.0,
                source="ANGEL_ONE_SMARTAPI_HISTORICAL",
                fetched_at=AS_OF + timedelta(minutes=2),
            )
        )
    return MarketResearchExport(
        contract_version="market-history.v1",
        generated_at=AS_OF + (timedelta(minutes=2) if future_candle else timedelta()),
        instrument_id=instrument_id,
        exchange="NSE",
        trading_symbol=f"{symbol}-EQ",
        symbol_token=token,
        catalogue_version=UUID(CATALOGUE_VERSION),
        interval="ONE_DAY",
        from_inclusive=AS_OF - timedelta(days=30),
        to_exclusive=AS_OF + timedelta(days=1),
        data_cutoff=max(row.available_at for row in candles),
        provider_sources=["ANGEL_ONE_SMARTAPI_HISTORICAL"],
        candles=candles,
    )


def _market_exports(*, future_candle: bool = False) -> list[MarketResearchExport]:
    return [
        _market_export(symbol, instrument_id, token, future_candle=future_candle)
        for symbol, _isin, instrument_id, token, _sector in SYMBOLS
    ]


def _depth_export() -> AngelOneMarketDepthResearchExport:
    available_at = AS_OF - timedelta(seconds=10)
    evidence = []
    for index, (symbol, _isin, instrument_id, token, _sector) in enumerate(SYMBOLS, start=1):
        evidence.append(
            AngelOneMarketDepthEvidence(
                instrument_id=instrument_id,
                catalogue_version=CATALOGUE_VERSION,
                exchange="NSE",
                trading_symbol=f"{symbol}-EQ",
                symbol_token=token,
                event_at=available_at - timedelta(seconds=2),
                available_at=available_at,
                ltp=Decimal("100.00"),
                best_bid_price=Decimal("99.95"),
                best_bid_quantity=100,
                best_bid_orders=2,
                best_ask_price=Decimal("100.05"),
                best_ask_quantity=120,
                best_ask_orders=3,
                midpoint=Decimal("100.00"),
                spread_bps=Decimal("10.000000"),
                source_id=f"{index + 30:064x}",
            )
        )
    return AngelOneMarketDepthResearchExport(
        generated_at=available_at,
        data_cutoff=available_at,
        row_count=3,
        evidence=evidence,
    )


def test_daily_history_and_depth_build_fully_observed_cross_section() -> None:
    bridge = _bridge()
    history = build_market_history_cross_section_inputs(
        identity_bridge=bridge,
        market_exports=_market_exports(),
        as_of=AS_OF,
    )
    assert len(history.inputs) == 3
    assert all(row.adv_notional > 0 for row in history.inputs)
    assert all(row.relative_volume > 1 for row in history.inputs)
    assert all(row.return_1 > 0 for row in history.inputs)

    joined = assemble_production_cross_section_observations(
        identity_bridge=bridge,
        market_history=history,
        market_depth_exports=[_depth_export()],
        as_of=AS_OF,
    )
    assert [row.spread_bps for row in joined.observations] == [10.0, 10.0, 10.0]
    assert all("CAUSAL_DAILY_MARKET_HISTORY" in row.quality_flags for row in joined.observations)
    assert all(
        "OBSERVED_ANGEL_ONE_FULL_DEPTH_SPREAD" in row.quality_flags
        for row in joined.observations
    )

    universe = build_liquidity_qualified_india_universe_snapshot(bridge=bridge, as_of=AS_OF)
    cross_section = build_india_cross_section_snapshot(
        universe=universe,
        as_of=AS_OF,
        observations=joined.observations,
    )
    assert cross_section.covered_members == 3
    assert cross_section.median_spread_bps == pytest.approx(10.0)
    assert cross_section.median_adv_notional > 0
    assert cross_section.median_relative_volume > 1


def test_future_daily_candle_cannot_change_historical_market_inputs() -> None:
    bridge = _bridge()
    baseline = build_market_history_cross_section_inputs(
        identity_bridge=bridge,
        market_exports=_market_exports(),
        as_of=AS_OF,
    )
    mutated = build_market_history_cross_section_inputs(
        identity_bridge=bridge,
        market_exports=_market_exports(future_candle=True),
        as_of=AS_OF,
    )
    assert mutated == baseline
    assert mutated.snapshot_fingerprint == baseline.snapshot_fingerprint


def test_history_identity_mismatch_and_missing_member_fail_closed() -> None:
    bridge = _bridge()
    exports = _market_exports()
    bad = exports[0].model_copy(update={"symbol_token": "WRONG"})
    with pytest.raises(ValueError, match="symbol token"):
        build_market_history_cross_section_inputs(
            identity_bridge=bridge,
            market_exports=[bad, *exports[1:]],
            as_of=AS_OF,
        )

    with pytest.raises(ValueError, match="missing market-history export"):
        build_market_history_cross_section_inputs(
            identity_bridge=bridge,
            market_exports=exports[:-1],
            as_of=AS_OF,
        )


def test_production_join_requires_exact_history_bridge_lineage() -> None:
    bridge = _bridge()
    history = build_market_history_cross_section_inputs(
        identity_bridge=bridge,
        market_exports=_market_exports(),
        as_of=AS_OF,
    )
    tampered = history.model_copy(update={"identity_bridge_fingerprint": _hash("e")})
    with pytest.raises(ValueError, match="do not belong"):
        assemble_production_cross_section_observations(
            identity_bridge=bridge,
            market_history=tampered,
            market_depth_exports=[_depth_export()],
            as_of=AS_OF,
        )
