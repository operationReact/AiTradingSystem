from datetime import UTC, date, datetime, timedelta
from uuid import UUID

import pytest

from aitrading_intelligence.catalogue_source import (
    CatalogueInstrument,
    InstrumentCatalogueResearchExport,
)
from aitrading_intelligence.liquidity_universe import (
    LiquidityQualificationConfig,
    build_liquidity_qualified_catalogue,
)
from aitrading_intelligence.market_source import MarketResearchExport, ResearchCandle

AS_OF = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)
CATALOGUE_VERSION = UUID("12345678-1234-1234-1234-123456789abc")
HASH = "a" * 64


def _instrument(
    instrument_id: int,
    exchange: str,
    symbol: str,
    token: str,
) -> CatalogueInstrument:
    return CatalogueInstrument(
        instrument_id=instrument_id,
        exchange_segment=f"{exchange}_EQ",
        exchange=exchange,
        symbol_token=token,
        trading_symbol=symbol,
        name=symbol.removesuffix("-EQ"),
        instrument_type="EQ",
        expiry=None,
        strike=None,
        lot_size=1,
        tick_size=0.05,
    )


def _catalogue() -> InstrumentCatalogueResearchExport:
    instruments = [
        _instrument(1, "BSE", "LOW-EQ", "500001"),
        _instrument(2, "NSE", "GOOD-EQ", "2001"),
        _instrument(3, "NSE", "MISSING-EQ", "3001"),
    ]
    available_at = datetime(2026, 10, 25, 10, 0, tzinfo=UTC)
    return InstrumentCatalogueResearchExport(
        contract_version="instrument-catalogue.v1",
        generated_at=AS_OF,
        as_of=AS_OF,
        data_cutoff=available_at,
        catalogue_version=CATALOGUE_VERSION,
        catalogue_available_at=available_at,
        source_url="https://example.invalid/OpenAPIScripMaster.json",
        source_accepted_count=3,
        source_rejected_count=0,
        exchanges=["BSE", "NSE"],
        instrument_type="EQ",
        row_count=3,
        catalogue_fingerprint=HASH,
        status="RESEARCH_CATALOGUE_ONLY",
        can_authorize_capital=False,
        instruments=instruments,
    )


def _daily_candle(day: date, *, close: float, volume: float) -> ResearchCandle:
    candle_time = datetime(day.year, day.month, day.day, 3, 45, tzinfo=UTC)
    available_at = datetime(day.year, day.month, day.day, 10, 0, tzinfo=UTC)
    return ResearchCandle(
        candle_time=candle_time,
        available_at=available_at,
        open=close - 1,
        high=close + 1,
        low=close - 2,
        close=close,
        volume=volume,
        source="ANGEL_ONE_SMARTAPI",
        fetched_at=available_at + timedelta(hours=1),
    )


def _market_export(
    instrument: CatalogueInstrument,
    *,
    volume: float,
    include_future: bool = False,
    future_only: bool = False,
    catalogue_version: UUID = CATALOGUE_VERSION,
) -> MarketResearchExport:
    days = [] if future_only else [date(2026, 10, 31), date(2026, 11, 1), date(2026, 11, 2)]
    candles = [_daily_candle(day, close=100.0, volume=volume) for day in days]
    generated_at = AS_OF
    from_inclusive = datetime(2026, 10, 31, 0, 0, tzinfo=UTC)
    to_exclusive = datetime(2026, 11, 3, 0, 0, tzinfo=UTC)
    if include_future or future_only:
        candles.append(_daily_candle(date(2026, 11, 3), close=999.0, volume=99_999_999))
        generated_at = datetime(2026, 11, 3, 12, 0, tzinfo=UTC)
        to_exclusive = datetime(2026, 11, 4, 0, 0, tzinfo=UTC)
    return MarketResearchExport(
        contract_version="market-history.v1",
        generated_at=generated_at,
        instrument_id=instrument.instrument_id,
        exchange=instrument.exchange,
        trading_symbol=instrument.trading_symbol,
        symbol_token=instrument.symbol_token,
        catalogue_version=catalogue_version,
        interval="ONE_DAY",
        from_inclusive=from_inclusive,
        to_exclusive=to_exclusive,
        data_cutoff=max(candle.available_at for candle in candles),
        provider_sources=["ANGEL_ONE_SMARTAPI"],
        candles=candles,
    )


def _config() -> LiquidityQualificationConfig:
    return LiquidityQualificationConfig(
        minimum_daily_bars=3,
        evaluation_bars=3,
        minimum_adv_notional=1_000_000,
        maximum_zero_volume_fraction=0.10,
        maximum_data_age_days=3,
    )


def test_qualifies_only_candidates_with_causal_liquidity_evidence() -> None:
    catalogue = _catalogue()
    low, good, _missing = catalogue.instruments
    result = build_liquidity_qualified_catalogue(
        catalogue=catalogue,
        market_exports=[
            _market_export(good, volume=20_000),
            _market_export(low, volume=100),
        ],
        as_of=AS_OF,
        config=_config(),
    )

    assert result.contract_version == "liquidity-qualified-catalogue.v1"
    assert result.accepted_instrument_ids == [good.instrument_id]
    assert result.accepted_count == 1
    assert result.rejected_count == 2
    assert result.status == "RESEARCH_LIQUIDITY_QUALIFICATION_ONLY"
    assert result.can_authorize_capital is False
    assert result.snapshot_fingerprint

    by_id = {item.instrument_id: item for item in result.evidence}
    assert by_id[good.instrument_id].accepted is True
    assert by_id[good.instrument_id].adv_notional == pytest.approx(2_000_000)
    assert by_id[low.instrument_id].rejection_reasons == ["ADV_NOTIONAL_BELOW_MINIMUM"]
    assert by_id[3].rejection_reasons == ["MISSING_MARKET_HISTORY"]


def test_market_export_order_does_not_change_snapshot() -> None:
    catalogue = _catalogue()
    low, good, _missing = catalogue.instruments
    exports = [
        _market_export(low, volume=100),
        _market_export(good, volume=20_000),
    ]

    first = build_liquidity_qualified_catalogue(
        catalogue=catalogue,
        market_exports=exports,
        as_of=AS_OF,
        config=_config(),
    )
    second = build_liquidity_qualified_catalogue(
        catalogue=catalogue,
        market_exports=list(reversed(exports)),
        as_of=AS_OF,
        config=_config(),
    )

    assert second == first
    assert second.snapshot_fingerprint == first.snapshot_fingerprint


def test_future_market_mutation_cannot_change_historical_liquidity_snapshot() -> None:
    catalogue = _catalogue()
    _low, good, _missing = catalogue.instruments

    before = build_liquidity_qualified_catalogue(
        catalogue=catalogue,
        market_exports=[_market_export(good, volume=20_000)],
        as_of=AS_OF,
        config=_config(),
    )
    after = build_liquidity_qualified_catalogue(
        catalogue=catalogue,
        market_exports=[_market_export(good, volume=20_000, include_future=True)],
        as_of=AS_OF,
        config=_config(),
    )

    assert after == before
    assert after.snapshot_fingerprint == before.snapshot_fingerprint


def test_future_only_market_export_cannot_create_liquidity_evidence() -> None:
    catalogue = _catalogue()
    good = catalogue.instruments[1]
    result = build_liquidity_qualified_catalogue(
        catalogue=catalogue,
        market_exports=[_market_export(good, volume=20_000, future_only=True)],
        as_of=AS_OF,
        config=_config(),
    )

    item = next(row for row in result.evidence if row.instrument_id == good.instrument_id)
    assert item.accepted is False
    assert item.causal_bar_count == 0
    assert item.latest_available_at is None
    assert item.rejection_reasons == ["MISSING_MARKET_HISTORY"]


def test_rejects_cross_catalogue_market_lineage() -> None:
    catalogue = _catalogue()
    good = catalogue.instruments[1]

    with pytest.raises(ValueError, match="catalogueVersion"):
        build_liquidity_qualified_catalogue(
            catalogue=catalogue,
            market_exports=[
                _market_export(
                    good,
                    volume=20_000,
                    catalogue_version=UUID("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                )
            ],
            as_of=AS_OF,
            config=_config(),
        )


def test_rejects_duplicate_market_evidence_for_one_instrument() -> None:
    catalogue = _catalogue()
    good = catalogue.instruments[1]
    export = _market_export(good, volume=20_000)

    with pytest.raises(ValueError, match="duplicate market export"):
        build_liquidity_qualified_catalogue(
            catalogue=catalogue,
            market_exports=[export, export],
            as_of=AS_OF,
            config=_config(),
        )


def test_catalogue_contract_rejects_nondeterministic_ordering() -> None:
    catalogue = _catalogue()
    payload = catalogue.model_dump(mode="json")
    payload["instruments"] = list(reversed(payload["instruments"]))

    with pytest.raises(ValueError, match="canonical deterministic ordering"):
        InstrumentCatalogueResearchExport.model_validate(payload)
