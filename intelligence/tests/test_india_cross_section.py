from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)
from aitrading_intelligence.india_cross_section import (
    IndiaCrossSectionConfig,
    IndiaCrossSectionMemberObservation,
    build_india_cross_section_snapshot,
)

AS_OF = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)


def _membership(symbol: str, sector: str) -> IndiaUniverseMembership:
    return IndiaUniverseMembership(
        instrument_id=f"NSE:{symbol}",
        exchange="NSE",
        symbol=symbol,
        sector=sector,
        index_memberships=["NIFTY_500"],
        effective_from=AS_OF - timedelta(days=30),
        available_at=AS_OF - timedelta(hours=1),
        source="universe-fixture",
        source_id=f"membership-{symbol}",
    )


def _universe(symbols: list[tuple[str, str]]):
    return build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=[_membership(symbol, sector) for symbol, sector in symbols],
    )


def _observation(
    symbol: str,
    return_1: float,
    *,
    available_at: datetime,
    source_id: str | None = None,
    adv_notional: float = 1_000_000.0,
    spread_bps: float = 8.0,
    relative_volume: float = 1.0,
) -> IndiaCrossSectionMemberObservation:
    return IndiaCrossSectionMemberObservation(
        exchange="NSE",
        symbol=symbol,
        event_at=available_at - timedelta(seconds=1),
        available_at=available_at,
        return_1=return_1,
        adv_notional=adv_notional,
        spread_bps=spread_bps,
        relative_volume=relative_volume,
        source="cross-section-fixture",
        source_id=source_id or f"obs-{symbol}-{available_at.isoformat()}",
    )


def _base_universe():
    return _universe(
        [
            ("RELIANCE", "ENERGY"),
            ("HDFCBANK", "FINANCIAL_SERVICES"),
            ("INFY", "IT"),
            ("TCS", "IT"),
            ("ITC", "CONSUMER"),
        ]
    )


def _base_observations():
    return [
        _observation(
            "RELIANCE",
            0.010,
            available_at=AS_OF - timedelta(seconds=50),
            adv_notional=8_000_000.0,
            spread_bps=4.0,
            relative_volume=1.2,
        ),
        _observation(
            "HDFCBANK",
            -0.005,
            available_at=AS_OF - timedelta(seconds=40),
            adv_notional=10_000_000.0,
            spread_bps=5.0,
            relative_volume=0.9,
        ),
        _observation(
            "INFY",
            0.002,
            available_at=AS_OF - timedelta(seconds=30),
            adv_notional=6_000_000.0,
            spread_bps=6.0,
            relative_volume=1.4,
        ),
        _observation(
            "TCS",
            0.0,
            available_at=AS_OF - timedelta(seconds=20),
            adv_notional=5_000_000.0,
            spread_bps=7.0,
            relative_volume=0.8,
        ),
        _observation(
            "ITC",
            -0.001,
            available_at=AS_OF - timedelta(seconds=10),
            adv_notional=4_000_000.0,
            spread_bps=9.0,
            relative_volume=1.1,
        ),
    ]


def test_snapshot_computes_causal_breadth_dispersion_and_liquidity() -> None:
    snapshot = build_india_cross_section_snapshot(
        universe=_base_universe(),
        as_of=AS_OF,
        observations=_base_observations(),
    )

    assert snapshot.expected_members == 5
    assert snapshot.covered_members == 5
    assert snapshot.coverage_fraction == 1.0
    assert snapshot.advancing_members == 2
    assert snapshot.declining_members == 2
    assert snapshot.unchanged_members == 1
    assert snapshot.advance_fraction == pytest.approx(0.4)
    assert snapshot.decline_fraction == pytest.approx(0.4)
    assert snapshot.net_breadth == pytest.approx(0.0)
    assert snapshot.median_adv_notional == pytest.approx(6_000_000.0)
    assert snapshot.median_spread_bps == pytest.approx(6.0)
    assert snapshot.median_relative_volume == pytest.approx(1.1)
    assert snapshot.sector_return_mean["IT"] == pytest.approx(0.001)
    assert snapshot.data_cutoff == AS_OF - timedelta(seconds=10)
    assert snapshot.status == "RESEARCH_CONTEXT_ONLY"
    assert snapshot.can_authorize_capital is False


def test_future_member_mutation_cannot_change_historical_snapshot() -> None:
    universe = _base_universe()
    observations = _base_observations()
    before = build_india_cross_section_snapshot(
        universe=universe,
        as_of=AS_OF,
        observations=observations,
    )

    future_corruption = _observation(
        "RELIANCE",
        0.99,
        available_at=AS_OF + timedelta(seconds=1),
        source_id="future-corruption",
        adv_notional=999_999_999.0,
        spread_bps=9_999.0,
        relative_volume=99.0,
    )
    after = build_india_cross_section_snapshot(
        universe=universe,
        as_of=AS_OF,
        observations=[*observations, future_corruption],
    )

    assert after == before
    assert after.snapshot_fingerprint == before.snapshot_fingerprint


def test_latest_causally_available_revision_wins_per_symbol() -> None:
    universe = _base_universe()
    observations = _base_observations()
    observations.append(
        _observation(
            "RELIANCE",
            0.020,
            available_at=AS_OF - timedelta(seconds=5),
            source_id="reliance-latest",
            adv_notional=12_000_000.0,
            spread_bps=3.0,
            relative_volume=1.8,
        )
    )

    snapshot = build_india_cross_section_snapshot(
        universe=universe,
        as_of=AS_OF,
        observations=observations,
    )

    assert snapshot.advancing_members == 2
    assert snapshot.data_cutoff == AS_OF - timedelta(seconds=5)
    assert snapshot.sector_return_mean["ENERGY"] == pytest.approx(0.020)


def test_observations_outside_point_in_time_universe_are_ignored() -> None:
    universe = _base_universe()
    outside = _observation(
        "NOTINUNIVERSE",
        0.75,
        available_at=AS_OF - timedelta(seconds=1),
        adv_notional=50_000_000.0,
        spread_bps=1.0,
        relative_volume=5.0,
    )

    snapshot = build_india_cross_section_snapshot(
        universe=universe,
        as_of=AS_OF,
        observations=[*_base_observations(), outside],
    )

    assert snapshot.covered_members == 5
    assert snapshot.data_cutoff == AS_OF - timedelta(seconds=10)


def test_insufficient_coverage_fails_closed() -> None:
    universe = _base_universe()
    sparse = _base_observations()[:3]
    config = IndiaCrossSectionConfig(
        minimum_members=3,
        minimum_coverage_fraction=0.80,
    )

    with pytest.raises(ValueError, match="coverage"):
        build_india_cross_section_snapshot(
            universe=universe,
            as_of=AS_OF,
            observations=sparse,
            config=config,
        )


def test_universe_and_cross_section_must_share_decision_time() -> None:
    universe = _base_universe()

    with pytest.raises(ValueError, match="same decision time"):
        build_india_cross_section_snapshot(
            universe=universe,
            as_of=AS_OF + timedelta(minutes=5),
            observations=_base_observations(),
        )


def test_cross_section_metrics_flow_into_canonical_market_context() -> None:
    snapshot = build_india_cross_section_snapshot(
        universe=_base_universe(),
        as_of=AS_OF,
        observations=_base_observations(),
    )
    context = build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=snapshot.to_context_observations(),
    )

    assert context.metrics["breadth.NSE.advance_fraction"] == pytest.approx(0.4)
    assert context.metrics["breadth.NSE.return_dispersion"] > 0.0
    assert context.metrics["liquidity.NSE.median_adv_notional"] == pytest.approx(6_000_000.0)
    assert context.metrics["liquidity.NSE.median_spread_bps"] == pytest.approx(6.0)
    assert context.data_cutoff == snapshot.data_cutoff
    assert context.can_authorize_capital is False


def test_member_observation_rejects_noncausal_availability() -> None:
    with pytest.raises(ValueError, match="available_at cannot precede event_at"):
        IndiaCrossSectionMemberObservation(
            exchange="NSE",
            symbol="RELIANCE",
            event_at=AS_OF,
            available_at=AS_OF - timedelta(seconds=1),
            return_1=0.01,
            adv_notional=1_000_000.0,
            spread_bps=5.0,
            relative_volume=1.0,
            source="fixture",
            source_id="invalid",
        )
