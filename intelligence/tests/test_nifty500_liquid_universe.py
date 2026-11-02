from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.datasets import MarketBar
from aitrading_intelligence.feature_v2 import build_feature_row_v2
from aitrading_intelligence.features import build_feature_row
from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    build_india_market_context_snapshot,
)
from aitrading_intelligence.nifty500_liquid_universe import (
    NIFTY500_LIQUID_UNIVERSE_ID,
    NIFTY500_LIQUID_UNIVERSE_SOURCE,
    build_nifty500_liquidity_qualified_universe,
)
from aitrading_intelligence.universe_identity_bridge import (
    MAPPING_RULE_VERSION,
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)


def _binding(
    *,
    isin: str,
    symbol: str,
    sector: str,
    catalogue_instrument_id: int,
    symbol_token: str,
    available_at: datetime,
    fingerprint_digit: str,
) -> Nifty500LiquidityIdentityBinding:
    return Nifty500LiquidityIdentityBinding(
        canonical_instrument_id=f"NSE:{isin}",
        isin=isin,
        symbol=symbol,
        series="EQ",
        sector=sector,
        expected_trading_symbol=f"{symbol}-EQ",
        catalogue_instrument_id=catalogue_instrument_id,
        broker_trading_symbol=f"{symbol}-EQ",
        broker_symbol_token=symbol_token,
        liquidity_evidence_fingerprint="a" * 64,
        sector_mapping_evidence_fingerprint="b" * 64,
        membership_effective_from=AS_OF - timedelta(days=1),
        available_at=available_at,
        mapping_rule=MAPPING_RULE_VERSION,
        binding_fingerprint=fingerprint_digit * 64,
    )


def _bridge(*, as_of: datetime = AS_OF) -> Nifty500LiquidityIdentityBridge:
    bindings = sorted(
        [
            _binding(
                isin="INE009A01021",
                symbol="INFY",
                sector="Information Technology",
                catalogue_instrument_id=1003,
                symbol_token="1594",
                available_at=AS_OF - timedelta(minutes=5),
                fingerprint_digit="2",
            ),
            _binding(
                isin="INE040A01034",
                symbol="HDFCBANK",
                sector="Financial Services",
                catalogue_instrument_id=1002,
                symbol_token="1333",
                available_at=AS_OF - timedelta(minutes=10),
                fingerprint_digit="1",
            ),
        ],
        key=lambda item: item.canonical_instrument_id,
    )
    return Nifty500LiquidityIdentityBridge(
        as_of=as_of,
        data_cutoff=AS_OF - timedelta(minutes=5),
        mapping_rule=MAPPING_RULE_VERSION,
        source_catalogue_version="12345678-1234-1234-1234-123456789abc",
        source_catalogue_fingerprint="c" * 64,
        source_liquidity_snapshot_fingerprint="d" * 64,
        source_sector_enrichment_fingerprint="e" * 64,
        bindings=bindings,
        binding_count=len(bindings),
        snapshot_fingerprint="f" * 64,
        status="RESEARCH_IDENTITY_BRIDGE_ONLY",
        can_authorize_capital=False,
    )


def test_materializes_final_liquidity_qualified_nifty500_universe() -> None:
    bridge = _bridge()
    universe = build_nifty500_liquidity_qualified_universe(bridge=bridge)

    assert universe.contract_version == "india-universe-snapshot.v1"
    assert universe.universe_id == NIFTY500_LIQUID_UNIVERSE_ID
    assert universe.as_of == AS_OF
    assert universe.data_cutoff == bridge.data_cutoff
    assert universe.status == "RESEARCH_CONTEXT_ONLY"
    assert universe.can_authorize_capital is False
    assert [member.instrument_id for member in universe.members] == [
        "NSE:INE009A01021",
        "NSE:INE040A01034",
    ]

    infy = universe.members[0]
    assert infy.symbol == "INFY"
    assert infy.series == "EQ"
    assert infy.sector == "Information Technology"
    assert infy.index_memberships == ["NIFTY_500"]
    assert infy.effective_from == AS_OF - timedelta(days=1)
    assert infy.available_at == AS_OF - timedelta(minutes=5)
    assert infy.source == NIFTY500_LIQUID_UNIVERSE_SOURCE
    assert infy.source_id == "2" * 64

    assert universe.source_versions == {
        "identity-mapping-rule": MAPPING_RULE_VERSION,
        "instrument-catalogue": "c" * 64,
        "liquidity-qualified-catalogue": "d" * 64,
        "nifty500-liquidity-identity-bridge": "f" * 64,
        "nifty500-sector-enrichment": "e" * 64,
    }
    assert universe.snapshot_fingerprint


def test_final_universe_feeds_feature_row_v2_with_canonical_isin_identity() -> None:
    universe = build_nifty500_liquidity_qualified_universe(bridge=_bridge())
    bar = MarketBar(
        symbol="INFY",
        interval="5m",
        observed_at=AS_OF - timedelta(minutes=1),
        event_at=AS_OF - timedelta(minutes=1, seconds=2),
        ingested_at=AS_OF - timedelta(minutes=1),
        open=1495.0,
        high=1505.0,
        low=1490.0,
        close=1500.0,
        volume=1_000_000.0,
        source="fixture",
    )
    base = build_feature_row(symbol="INFY", as_of=AS_OF, bars=[bar])
    context = build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=[
            IndiaContextObservation(
                context_key="benchmark.NIFTY50.close",
                event_at=AS_OF - timedelta(minutes=6),
                available_at=AS_OF - timedelta(minutes=5),
                value=25_000.0,
                source="fixture",
                source_id="nifty50-close",
            )
        ],
        source_versions={"benchmark": "fixture.v1"},
    )

    row = build_feature_row_v2(
        base_row=base,
        universe=universe,
        context=context,
        expected_context_keys=["benchmark.NIFTY50.close"],
        required_context_keys=["benchmark.NIFTY50.close"],
    )

    assert row.instrument_id == "NSE:INE009A01021"
    assert row.symbol == "INFY"
    assert row.sector == "Information Technology"
    assert row.index_memberships == ["NIFTY_500"]
    assert row.universe_snapshot_fingerprint == universe.snapshot_fingerprint
    assert row.can_authorize_capital is False


def test_future_universe_cannot_be_used_for_an_earlier_feature_decision() -> None:
    future_as_of = AS_OF + timedelta(minutes=1)
    future_bridge = _bridge(as_of=future_as_of).model_copy(
        update={"data_cutoff": AS_OF - timedelta(minutes=5)}
    )
    future_universe = build_nifty500_liquidity_qualified_universe(bridge=future_bridge)
    bar = MarketBar(
        symbol="INFY",
        interval="5m",
        observed_at=AS_OF - timedelta(minutes=1),
        event_at=AS_OF - timedelta(minutes=1, seconds=2),
        ingested_at=AS_OF - timedelta(minutes=1),
        open=1495.0,
        high=1505.0,
        low=1490.0,
        close=1500.0,
        volume=1_000_000.0,
        source="fixture",
    )
    base = build_feature_row(symbol="INFY", as_of=AS_OF, bars=[bar])
    context = build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=[
            IndiaContextObservation(
                context_key="benchmark.NIFTY50.close",
                event_at=AS_OF - timedelta(minutes=6),
                available_at=AS_OF - timedelta(minutes=5),
                value=25_000.0,
                source="fixture",
                source_id="nifty50-close",
            )
        ],
    )

    with pytest.raises(ValueError, match="universe snapshot must match the feature decision time"):
        build_feature_row_v2(
            base_row=base,
            universe=future_universe,
            context=context,
        )
