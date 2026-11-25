from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    IndiaUniverseMembership,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)

AS_OF = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)


def _membership(
    *,
    instrument_id: str,
    symbol: str,
    sector: str,
    available_at: datetime,
    source_id: str,
    effective_from: datetime | None = None,
    effective_to: datetime | None = None,
    indexes: list[str] | None = None,
) -> IndiaUniverseMembership:
    return IndiaUniverseMembership(
        instrument_id=instrument_id,
        exchange="NSE",
        symbol=symbol,
        series="EQ",
        sector=sector,
        index_memberships=indexes or [],
        effective_from=effective_from or AS_OF - timedelta(days=30),
        effective_to=effective_to,
        available_at=available_at,
        source="nse-research-fixture",
        source_id=source_id,
    )


def _observation(
    key: str,
    value: float,
    *,
    available_at: datetime,
    source_id: str,
    event_at: datetime | None = None,
) -> IndiaContextObservation:
    return IndiaContextObservation(
        context_key=key,
        event_at=event_at or available_at - timedelta(seconds=1),
        available_at=available_at,
        value=value,
        source="india-context-fixture",
        source_id=source_id,
    )


def test_universe_snapshot_is_causal_deterministic_and_research_only() -> None:
    records = [
        _membership(
            instrument_id="NSE:RELIANCE",
            symbol="RELIANCE",
            sector="ENERGY",
            available_at=AS_OF - timedelta(hours=2),
            source_id="reliance-v1",
            indexes=["NIFTY_50"],
        ),
        _membership(
            instrument_id="NSE:HDFCBANK",
            symbol="HDFCBANK",
            sector="FINANCIAL_SERVICES",
            available_at=AS_OF - timedelta(hours=1),
            source_id="hdfc-v1",
            indexes=["NIFTY_50", "NIFTY_BANK"],
        ),
    ]

    snapshot = build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=reversed(records),
        source_versions={"nseCatalogue": "2026-11-02"},
    )

    assert [member.instrument_id for member in snapshot.members] == [
        "NSE:HDFCBANK",
        "NSE:RELIANCE",
    ]
    assert snapshot.data_cutoff == AS_OF - timedelta(hours=1)
    assert snapshot.data_cutoff <= snapshot.as_of
    assert snapshot.status == "RESEARCH_CONTEXT_ONLY"
    assert snapshot.can_authorize_capital is False


def test_universe_future_mutation_cannot_change_historical_snapshot() -> None:
    historical = [
        _membership(
            instrument_id="NSE:RELIANCE",
            symbol="RELIANCE",
            sector="ENERGY",
            available_at=AS_OF - timedelta(hours=1),
            source_id="reliance-v1",
            indexes=["NIFTY_50"],
        )
    ]
    before = build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=historical,
    )

    future_revision = _membership(
        instrument_id="NSE:RELIANCE",
        symbol="RELIANCE",
        sector="ENERGY_RECLASSIFIED",
        available_at=AS_OF + timedelta(minutes=5),
        source_id="reliance-v2-future",
        indexes=["NIFTY_NEXT_50"],
    )
    after = build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=[*historical, future_revision],
    )

    assert after == before
    assert after.snapshot_fingerprint == before.snapshot_fingerprint


def test_universe_excludes_members_not_effective_at_decision_time() -> None:
    records = [
        _membership(
            instrument_id="NSE:ACTIVE",
            symbol="ACTIVE",
            sector="TEST",
            available_at=AS_OF - timedelta(hours=1),
            source_id="active",
        ),
        _membership(
            instrument_id="NSE:EXPIRED",
            symbol="EXPIRED",
            sector="TEST",
            available_at=AS_OF - timedelta(days=1),
            source_id="expired",
            effective_to=AS_OF,
        ),
        _membership(
            instrument_id="NSE:FUTURE",
            symbol="FUTURE",
            sector="TEST",
            available_at=AS_OF - timedelta(hours=1),
            source_id="future",
            effective_from=AS_OF + timedelta(days=1),
        ),
    ]

    snapshot = build_india_universe_snapshot(
        universe_id="TEST_UNIVERSE",
        as_of=AS_OF,
        memberships=records,
    )

    assert [member.symbol for member in snapshot.members] == ["ACTIVE"]


def test_context_snapshot_includes_nifty_sector_and_india_vix_with_lineage() -> None:
    observations = [
        _observation(
            "benchmark.NIFTY_50.level",
            25_100.0,
            available_at=AS_OF - timedelta(seconds=20),
            source_id="nifty-1",
        ),
        _observation(
            "sector.NIFTY_BANK.return_5m",
            0.003,
            available_at=AS_OF - timedelta(seconds=10),
            source_id="bank-1",
        ),
        _observation(
            "volatility.INDIA_VIX.level",
            13.4,
            available_at=AS_OF - timedelta(seconds=5),
            source_id="vix-1",
        ),
    ]

    snapshot = build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=reversed(observations),
        source_versions={"marketContext": "fixture.v1"},
    )

    assert snapshot.metrics == {
        "benchmark.NIFTY_50.level": 25_100.0,
        "sector.NIFTY_BANK.return_5m": 0.003,
        "volatility.INDIA_VIX.level": 13.4,
    }
    assert snapshot.provenance["volatility.INDIA_VIX.level"].endswith("vix-1")
    assert snapshot.data_cutoff == AS_OF - timedelta(seconds=5)
    assert snapshot.can_authorize_capital is False


def test_context_uses_latest_available_revision_and_ignores_future_data() -> None:
    records = [
        _observation(
            "benchmark.NIFTY_50.level",
            25_000.0,
            available_at=AS_OF - timedelta(minutes=2),
            source_id="nifty-old",
        ),
        _observation(
            "benchmark.NIFTY_50.level",
            25_050.0,
            available_at=AS_OF - timedelta(minutes=1),
            source_id="nifty-current",
        ),
    ]
    before = build_india_market_context_snapshot(as_of=AS_OF, observations=records)

    future = _observation(
        "benchmark.NIFTY_50.level",
        99_999.0,
        available_at=AS_OF + timedelta(seconds=1),
        source_id="nifty-future-corruption",
        event_at=AS_OF,
    )
    after = build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=[*records, future],
    )

    assert before.metrics["benchmark.NIFTY_50.level"] == 25_050.0
    assert after == before
    assert after.snapshot_fingerprint == before.snapshot_fingerprint


def test_context_rejects_noncausal_timestamp_contract() -> None:
    with pytest.raises(ValueError, match="available_at cannot precede event_at"):
        _observation(
            "volatility.INDIA_VIX.level",
            12.0,
            available_at=AS_OF - timedelta(seconds=2),
            event_at=AS_OF - timedelta(seconds=1),
            source_id="invalid",
        )


def test_context_requires_at_least_one_causally_available_metric() -> None:
    future_only = _observation(
        "benchmark.NIFTY_50.level",
        25_000.0,
        available_at=AS_OF + timedelta(minutes=1),
        source_id="future-only",
        event_at=AS_OF,
    )

    with pytest.raises(ValueError, match="no causally available"):
        build_india_market_context_snapshot(
            as_of=AS_OF,
            observations=[future_only],
        )
