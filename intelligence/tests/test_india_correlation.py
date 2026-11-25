from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)
from aitrading_intelligence.india_correlation import (
    IndiaCorrelationConfig,
    IndiaReturnObservation,
    build_india_correlation_snapshot,
)

AS_OF = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)
SYMBOLS = ("TCS", "INFY", "HDFCBANK", "ICICIBANK")
SECTORS = {
    "TCS": "IT SERVICES",
    "INFY": "IT SERVICES",
    "HDFCBANK": "FINANCIALS",
    "ICICIBANK": "FINANCIALS",
}
SERIES = {
    "TCS": [0.001, 0.002, 0.003, 0.004, 0.005, 0.006],
    "INFY": [0.002, 0.004, 0.006, 0.008, 0.010, 0.012],
    "HDFCBANK": [0.003, -0.002, 0.004, -0.001, 0.005, 0.000],
    "ICICIBANK": [-0.003, 0.002, -0.004, 0.001, -0.005, 0.000],
}


def _universe():
    memberships = [
        IndiaUniverseMembership(
            instrument_id=f"NSE:{symbol}",
            exchange="NSE",
            symbol=symbol,
            sector=SECTORS[symbol],
            index_memberships=["NIFTY_500"],
            effective_from=AS_OF - timedelta(days=365),
            available_at=AS_OF - timedelta(minutes=30),
            source="universe-fixture",
            source_id=f"membership-{symbol}",
        )
        for symbol in SYMBOLS
    ]
    return build_india_universe_snapshot(
        universe_id="NSE_CORRELATION_TEST",
        as_of=AS_OF,
        memberships=memberships,
    )


def _observations(*, include_future_corruption: bool = False):
    observations = []
    start = AS_OF - timedelta(minutes=30)
    for symbol in SYMBOLS:
        for index, value in enumerate(SERIES[symbol]):
            event_at = start + timedelta(minutes=index * 5)
            observations.append(
                IndiaReturnObservation(
                    symbol=symbol,
                    event_at=event_at,
                    available_at=event_at + timedelta(seconds=20),
                    return_value=value,
                    source="return-fixture",
                    source_id=f"{symbol}-{index}",
                )
            )
        if include_future_corruption:
            observations.append(
                IndiaReturnObservation(
                    symbol=symbol,
                    event_at=AS_OF + timedelta(minutes=5),
                    available_at=AS_OF + timedelta(minutes=5, seconds=20),
                    return_value=99.0 if symbol in {"TCS", "HDFCBANK"} else -99.0,
                    source="future-corruption",
                    source_id=f"future-{symbol}",
                )
            )
    return observations


def _config() -> IndiaCorrelationConfig:
    return IndiaCorrelationConfig(
        window_observations=6,
        minimum_pair_observations=5,
        minimum_market_symbols=3,
        minimum_sector_symbols=2,
        high_correlation_threshold=0.70,
    )


def test_builds_market_and_sector_pairwise_correlation_state() -> None:
    snapshot = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=_observations(),
        config=_config(),
    )

    assert snapshot.market.symbol_count == 4
    assert snapshot.market.pair_count == 6
    assert snapshot.sectors["IT SERVICES"].mean_pairwise_correlation == pytest.approx(1.0)
    assert snapshot.sectors["IT SERVICES"].high_correlation_pair_fraction == 1.0
    assert snapshot.sectors["FINANCIALS"].mean_pairwise_correlation == pytest.approx(-1.0)
    assert snapshot.sectors["FINANCIALS"].high_correlation_pair_fraction == 0.0
    assert snapshot.data_cutoff <= AS_OF
    assert snapshot.status == "RESEARCH_CONTEXT_ONLY"
    assert snapshot.can_authorize_capital is False


def test_context_observations_are_canonical_and_consumable() -> None:
    snapshot = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=_observations(),
        config=_config(),
    )
    context_observations = snapshot.to_context_observations()
    keys = [item.context_key for item in context_observations]

    assert keys == sorted(keys)
    assert "breadth.NSE.avg_pairwise_corr_aligned6" in keys
    assert "breadth.NSE.pairwise_corr_dispersion_aligned6" in keys
    assert "sector.IT_SERVICES.avg_pairwise_corr_aligned6" in keys
    assert "sector.FINANCIALS.avg_pairwise_corr_aligned6" in keys

    context = build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=context_observations,
        source_versions={"correlation": snapshot.contract_version},
    )
    assert context.metrics["sector.IT_SERVICES.avg_pairwise_corr_aligned6"] == pytest.approx(
        1.0
    )
    assert context.can_authorize_capital is False


def test_future_returns_cannot_change_historical_correlation_state() -> None:
    baseline = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=_observations(include_future_corruption=False),
        config=_config(),
    )
    mutated = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=_observations(include_future_corruption=True),
        config=_config(),
    )

    assert mutated == baseline
    assert mutated.snapshot_fingerprint == baseline.snapshot_fingerprint
    assert mutated.to_context_observations() == baseline.to_context_observations()


def test_input_order_does_not_change_correlation_fingerprint() -> None:
    observations = _observations()
    baseline = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=observations,
        config=_config(),
    )
    reordered = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=list(reversed(observations)),
        config=_config(),
    )

    assert reordered == baseline


def test_latest_available_revision_wins_for_same_symbol_and_event_time() -> None:
    observations = _observations()
    target = observations[0]
    observations.append(
        target.model_copy(
            update={
                "available_at": target.available_at + timedelta(seconds=1),
                "return_value": target.return_value * 1.1,
                "source_id": "revised-TCS-0",
            }
        )
    )
    first = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=observations,
        config=_config(),
    )
    second = build_india_correlation_snapshot(
        universe=_universe(),
        as_of=AS_OF,
        observations=list(reversed(observations)),
        config=_config(),
    )

    assert first == second
    assert any(
        pair.left_symbol == "INFY" and pair.right_symbol == "TCS"
        for pair in first.pair_evidence
    )


def test_zero_variance_symbol_is_excluded_without_imputation() -> None:
    observations = _observations()
    start = AS_OF - timedelta(minutes=30)
    constant_symbol = "WIPRO"
    memberships = list(_universe().members) + [
        IndiaUniverseMembership(
            instrument_id="NSE:WIPRO",
            symbol=constant_symbol,
            sector="IT SERVICES",
            index_memberships=["NIFTY_500"],
            effective_from=AS_OF - timedelta(days=365),
            available_at=AS_OF - timedelta(minutes=30),
            source="universe-fixture",
            source_id="membership-WIPRO",
        )
    ]
    universe = build_india_universe_snapshot(
        universe_id="NSE_CORRELATION_TEST_WITH_CONSTANT",
        as_of=AS_OF,
        memberships=memberships,
    )
    for index in range(6):
        event_at = start + timedelta(minutes=index * 5)
        observations.append(
            IndiaReturnObservation(
                symbol=constant_symbol,
                event_at=event_at,
                available_at=event_at + timedelta(seconds=20),
                return_value=0.001,
                source="return-fixture",
                source_id=f"WIPRO-{index}",
            )
        )

    snapshot = build_india_correlation_snapshot(
        universe=universe,
        as_of=AS_OF,
        observations=observations,
        config=_config(),
    )

    assert all(
        constant_symbol not in {pair.left_symbol, pair.right_symbol}
        for pair in snapshot.pair_evidence
    )
    assert snapshot.market.symbol_count == 4


def test_insufficient_aligned_history_fails_closed() -> None:
    sparse = [
        item
        for item in _observations()
        if item.symbol != "ICICIBANK" or item.event_at >= AS_OF - timedelta(minutes=10)
    ]
    strict = _config().model_copy(update={"minimum_market_symbols": 4})

    with pytest.raises(ValueError, match="too few causally observed symbols"):
        build_india_correlation_snapshot(
            universe=_universe(),
            as_of=AS_OF,
            observations=sparse,
            config=strict,
        )
