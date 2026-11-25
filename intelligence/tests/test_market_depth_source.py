from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_universe_snapshot,
)
from aitrading_intelligence.india_cross_section import (
    IndiaCrossSectionMemberObservation,
    build_india_cross_section_snapshot,
)
from aitrading_intelligence.market_depth_source import (
    OBSERVED_SPREAD_FLAG,
    MarketDepthSpreadJoinConfig,
    attach_market_depth_spreads,
    parse_market_depth_research_export,
)
from aitrading_intelligence.universe_identity_bridge import (
    Nifty500LiquidityIdentityBinding,
    Nifty500LiquidityIdentityBridge,
)

AS_OF = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)
CATALOGUE_VERSION = "catalogue-v1"
SYMBOLS = (
    ("AAA", "INE000A01001", 101, "1001", "ENERGY"),
    ("BBB", "INE000A01002", 102, "1002", "FINANCIALS"),
    ("CCC", "INE000A01003", 103, "1003", "IT"),
)


def _hash(char: str) -> str:
    return char * 64


def _binding(
    symbol: str,
    isin: str,
    instrument_id: int,
    token: str,
    sector: str,
    marker: str,
) -> Nifty500LiquidityIdentityBinding:
    return Nifty500LiquidityIdentityBinding(
        canonical_instrument_id=f"NSE:{isin}",
        isin=isin,
        symbol=symbol,
        series="EQ",
        sector=sector,
        expected_trading_symbol=f"{symbol}-EQ",
        catalogue_instrument_id=instrument_id,
        broker_trading_symbol=f"{symbol}-EQ",
        broker_symbol_token=token,
        liquidity_evidence_fingerprint=_hash(marker),
        sector_mapping_evidence_fingerprint=_hash(str(int(marker) + 3)),
        membership_effective_from=AS_OF - timedelta(days=30),
        available_at=AS_OF - timedelta(minutes=5),
        binding_fingerprint=_hash(str(int(marker) + 6)),
    )


def _bridge() -> Nifty500LiquidityIdentityBridge:
    bindings = [
        _binding(*values, marker)
        for values, marker in zip(SYMBOLS, ("1", "2", "3"), strict=True)
    ]
    bindings = sorted(bindings, key=lambda item: item.canonical_instrument_id)
    return Nifty500LiquidityIdentityBridge(
        as_of=AS_OF,
        data_cutoff=AS_OF - timedelta(minutes=5),
        source_catalogue_version=CATALOGUE_VERSION,
        source_catalogue_fingerprint=_hash("a"),
        source_liquidity_snapshot_fingerprint=_hash("b"),
        source_sector_enrichment_fingerprint=_hash("c"),
        bindings=bindings,
        binding_count=len(bindings),
        snapshot_fingerprint=_hash("d"),
    )


def _base_observation(
    symbol: str,
    *,
    return_1: float,
) -> IndiaCrossSectionMemberObservation:
    return IndiaCrossSectionMemberObservation(
        exchange="NSE",
        symbol=symbol,
        event_at=AS_OF - timedelta(seconds=31),
        available_at=AS_OF - timedelta(seconds=30),
        return_1=return_1,
        adv_notional=5_000_000.0,
        spread_bps=999.0,
        relative_volume=1.2,
        source="base-cross-section-fixture",
        source_id=f"base-{symbol}",
    )


def _base_observations() -> list[IndiaCrossSectionMemberObservation]:
    return [
        _base_observation("AAA", return_1=0.01),
        _base_observation("BBB", return_1=-0.005),
        _base_observation("CCC", return_1=0.002),
    ]


def _payload(
    *,
    generated_at: datetime = AS_OF - timedelta(seconds=10),
    spread_bps: str = "10.000000",
    bid: str = "99.95",
    ask: str = "100.05",
    midpoint: str = "100.00",
    token_override: str | None = None,
    future: bool = False,
) -> dict:
    if future:
        generated_at = AS_OF + timedelta(seconds=1)
    evidence = []
    for index, (symbol, _isin, instrument_id, token, _sector) in enumerate(SYMBOLS):
        row_token = token_override if index == 0 and token_override else token
        evidence.append(
            {
                "instrumentId": instrument_id,
                "catalogueVersion": CATALOGUE_VERSION,
                "exchange": "NSE",
                "tradingSymbol": f"{symbol}-EQ",
                "symbolToken": row_token,
                "eventAt": (generated_at - timedelta(seconds=2)).isoformat(),
                "availableAt": generated_at.isoformat(),
                "ltp": "100.00",
                "bestBidPrice": bid,
                "bestBidQuantity": 100,
                "bestBidOrders": 2,
                "bestAskPrice": ask,
                "bestAskQuantity": 120,
                "bestAskOrders": 3,
                "midpoint": midpoint,
                "spreadBps": spread_bps,
                "source": "ANGEL_ONE_SMARTAPI_FULL_QUOTE",
                "sourceId": f"{index + 4:064x}",
            }
        )
    return {
        "contractVersion": "angel-one-market-depth.v1",
        "generatedAt": generated_at.isoformat(),
        "dataCutoff": generated_at.isoformat(),
        "rowCount": 3,
        "evidence": evidence,
        "status": "RESEARCH_MARKET_DEPTH_ONLY",
        "canAuthorizeCapital": False,
    }


def _universe():
    bridge = _bridge()
    return build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=[
            IndiaUniverseMembership(
                instrument_id=binding.canonical_instrument_id,
                exchange="NSE",
                symbol=binding.symbol,
                sector=binding.sector,
                index_memberships=["NIFTY_500"],
                effective_from=binding.membership_effective_from,
                available_at=binding.available_at,
                source="identity-bridge-fixture",
                source_id=binding.binding_fingerprint,
            )
            for binding in bridge.bindings
        ],
    )


def test_market_depth_spread_replaces_placeholder_and_flows_to_cross_section() -> None:
    export = parse_market_depth_research_export(_payload())
    joined = attach_market_depth_spreads(
        identity_bridge=_bridge(),
        base_observations=_base_observations(),
        market_depth_exports=[export],
        as_of=AS_OF,
    )

    assert [row.spread_bps for row in joined.observations] == [10.0, 10.0, 10.0]
    assert all(OBSERVED_SPREAD_FLAG in row.quality_flags for row in joined.observations)
    assert joined.data_cutoff == AS_OF - timedelta(seconds=10)
    assert joined.can_authorize_capital is False

    cross_section = build_india_cross_section_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=joined.observations,
    )
    assert cross_section.median_spread_bps == pytest.approx(10.0)
    assert cross_section.data_cutoff == AS_OF - timedelta(seconds=10)


def test_future_only_market_depth_cannot_change_historical_join() -> None:
    known = parse_market_depth_research_export(_payload())
    before = attach_market_depth_spreads(
        identity_bridge=_bridge(),
        base_observations=_base_observations(),
        market_depth_exports=[known],
        as_of=AS_OF,
    )
    future = parse_market_depth_research_export(
        _payload(
            future=True,
            spread_bps="100.000000",
            bid="99.50",
            ask="100.50",
            midpoint="100.00",
        )
    )
    after = attach_market_depth_spreads(
        identity_bridge=_bridge(),
        base_observations=_base_observations(),
        market_depth_exports=[known, future],
        as_of=AS_OF,
    )

    assert after == before
    assert after.snapshot_fingerprint == before.snapshot_fingerprint


def test_stale_market_depth_fails_closed() -> None:
    stale = parse_market_depth_research_export(
        _payload(generated_at=AS_OF - timedelta(seconds=121))
    )
    with pytest.raises(ValueError, match="stale"):
        attach_market_depth_spreads(
            identity_bridge=_bridge(),
            base_observations=_base_observations(),
            market_depth_exports=[stale],
            as_of=AS_OF,
            config=MarketDepthSpreadJoinConfig(max_quote_age_seconds=120),
        )


def test_market_depth_identity_mismatch_fails_closed() -> None:
    export = parse_market_depth_research_export(_payload(token_override="WRONG"))
    with pytest.raises(ValueError, match="symbol token"):
        attach_market_depth_spreads(
            identity_bridge=_bridge(),
            base_observations=_base_observations(),
            market_depth_exports=[export],
            as_of=AS_OF,
        )


def test_parser_rejects_crossed_or_inconsistent_spread_payload() -> None:
    with pytest.raises(ValueError, match="book cannot be crossed"):
        parse_market_depth_research_export(
            _payload(
                bid="100.05",
                ask="99.95",
                midpoint="100.00",
                spread_bps="0.000000",
            )
        )

    with pytest.raises(ValueError, match="spread_bps"):
        parse_market_depth_research_export(_payload(spread_bps="99.000000"))


def test_parser_rejects_capital_authority_claim() -> None:
    payload = _payload()
    payload["canAuthorizeCapital"] = True
    with pytest.raises(ValueError):
        parse_market_depth_research_export(payload)
