from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.feature_v2 import build_feature_row_v2
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    IndiaUniverseMembership,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)

AS_OF = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)


def _base_row(symbol: str = "RELIANCE") -> FeatureRow:
    payload = {
        "feature_schema_version": "features.market.v1",
        "symbol": symbol,
        "as_of": AS_OF.isoformat(),
        "data_cutoff": (AS_OF - timedelta(seconds=20)).isoformat(),
        "features": {
            "market.close": 1450.0,
            "market.return_1": 0.002,
            "market.volume": 1_500_000.0,
        },
        "provenance": ["market:fixture:2026-11-02T09:14:40+00:00"],
    }
    return FeatureRow(**payload, fingerprint=canonical_fingerprint(payload))


def _membership(
    *,
    symbol: str = "RELIANCE",
    exchange: str = "NSE",
) -> IndiaUniverseMembership:
    return IndiaUniverseMembership(
        instrument_id=f"{exchange}:{symbol}",
        exchange=exchange,
        symbol=symbol,
        sector="ENERGY",
        index_memberships=["NIFTY_50", "NIFTY_500"],
        effective_from=AS_OF - timedelta(days=365),
        available_at=AS_OF - timedelta(minutes=2),
        source="universe-fixture",
        source_id=f"membership-{exchange}-{symbol}",
    )


def _universe(*, symbol: str = "RELIANCE"):
    return build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=[_membership(symbol=symbol)],
    )


def _context(*, include_vix: bool = True, future_corruption: bool = False):
    observations = [
        IndiaContextObservation(
            context_key="benchmark.NIFTY_50.return_5m",
            event_at=AS_OF - timedelta(seconds=12),
            available_at=AS_OF - timedelta(seconds=10),
            value=0.0015,
            source="context-fixture",
            source_id="nifty-5m",
            quality_flags=["PRIMARY_SOURCE"],
        ),
        IndiaContextObservation(
            context_key="breadth.NSE.net_breadth",
            event_at=AS_OF - timedelta(seconds=9),
            available_at=AS_OF - timedelta(seconds=8),
            value=0.24,
            source="context-fixture",
            source_id="breadth",
        ),
        IndiaContextObservation(
            context_key="liquidity.NSE.median_spread_bps",
            event_at=AS_OF - timedelta(seconds=7),
            available_at=AS_OF - timedelta(seconds=6),
            value=5.5,
            source="context-fixture",
            source_id="spread",
        ),
    ]
    if include_vix:
        observations.append(
            IndiaContextObservation(
                context_key="volatility.INDIA_VIX.level",
                event_at=AS_OF - timedelta(seconds=5),
                available_at=AS_OF - timedelta(seconds=4),
                value=13.2,
                source="context-fixture",
                source_id="vix",
            )
        )
    if future_corruption:
        observations.append(
            IndiaContextObservation(
                context_key="volatility.INDIA_VIX.level",
                event_at=AS_OF,
                available_at=AS_OF + timedelta(seconds=1),
                value=99.0,
                source="context-fixture",
                source_id="future-vix-corruption",
            )
        )
    return build_india_market_context_snapshot(
        as_of=AS_OF,
        observations=observations,
    )


def test_feature_row_v2_binds_exact_universe_and_context_lineage() -> None:
    base = _base_row()
    universe = _universe()
    context = _context()

    row = build_feature_row_v2(
        base_row=base,
        universe=universe,
        context=context,
        expected_context_keys=[
            "benchmark.NIFTY_50.return_5m",
            "breadth.NSE.net_breadth",
            "liquidity.NSE.median_spread_bps",
            "volatility.INDIA_VIX.level",
        ],
        required_context_keys=[
            "benchmark.NIFTY_50.return_5m",
            "volatility.INDIA_VIX.level",
        ],
    )

    assert row.feature_schema_version == "features.market.v2"
    assert row.instrument_id == "NSE:RELIANCE"
    assert row.exchange == "NSE"
    assert row.symbol == "RELIANCE"
    assert row.sector == "ENERGY"
    assert row.index_memberships == ["NIFTY_50", "NIFTY_500"]
    assert row.base_feature_row_fingerprint == base.fingerprint
    assert row.universe_snapshot_fingerprint == universe.snapshot_fingerprint
    assert row.market_context_snapshot_fingerprint == context.snapshot_fingerprint
    assert row.features["market.close"] == pytest.approx(1450.0)
    assert row.features["context.volatility.INDIA_VIX.level"] == pytest.approx(13.2)
    assert row.features["context.breadth.NSE.net_breadth"] == pytest.approx(0.24)
    assert row.missing_features == []
    assert "context.benchmark.NIFTY_50.return_5m:PRIMARY_SOURCE" in row.quality_flags
    assert row.data_cutoff == context.data_cutoff
    assert row.status == "RESEARCH_FEATURE_ONLY"
    assert row.can_authorize_capital is False


def test_optional_missing_context_is_explicit_and_never_zero_imputed() -> None:
    row = build_feature_row_v2(
        base_row=_base_row(),
        universe=_universe(),
        context=_context(include_vix=False),
        expected_context_keys=["volatility.INDIA_VIX.level"],
    )

    assert row.missing_features == ["context.volatility.INDIA_VIX.level"]
    assert "context.volatility.INDIA_VIX.level" not in row.features


def test_required_missing_context_fails_closed() -> None:
    with pytest.raises(ValueError, match="required context features are missing"):
        build_feature_row_v2(
            base_row=_base_row(),
            universe=_universe(),
            context=_context(include_vix=False),
            required_context_keys=["volatility.INDIA_VIX.level"],
        )


def test_symbol_must_exist_in_exact_point_in_time_universe() -> None:
    with pytest.raises(ValueError, match="exactly one point-in-time universe member"):
        build_feature_row_v2(
            base_row=_base_row(),
            universe=_universe(symbol="HDFCBANK"),
            context=_context(),
        )


def test_universe_and_context_must_share_feature_decision_time() -> None:
    base = _base_row()
    universe = _universe().model_copy(update={"as_of": AS_OF - timedelta(minutes=5)})

    with pytest.raises(ValueError, match="universe snapshot must match"):
        build_feature_row_v2(
            base_row=base,
            universe=universe,
            context=_context(),
        )


def test_future_context_mutation_cannot_change_historical_feature_row() -> None:
    base = _base_row()
    universe = _universe()
    before_context = _context(future_corruption=False)
    after_context = _context(future_corruption=True)

    assert before_context == after_context
    before = build_feature_row_v2(
        base_row=base,
        universe=universe,
        context=before_context,
    )
    after = build_feature_row_v2(
        base_row=base,
        universe=universe,
        context=after_context,
    )

    assert after == before
    assert after.fingerprint == before.fingerprint


def test_v1_feature_row_is_not_mutated_when_v2_is_built() -> None:
    base = _base_row()
    original_dump = base.model_dump(mode="json")

    row = build_feature_row_v2(
        base_row=base,
        universe=_universe(),
        context=_context(),
    )

    assert base.model_dump(mode="json") == original_dump
    assert row.base_feature_row_fingerprint == base.fingerprint
    assert row.feature_schema_version != base.feature_schema_version


def test_duplicate_context_expectations_are_rejected() -> None:
    with pytest.raises(ValueError, match="expected_context_keys must be unique"):
        build_feature_row_v2(
            base_row=_base_row(),
            universe=_universe(),
            context=_context(),
            expected_context_keys=[
                "volatility.INDIA_VIX.level",
                "volatility.INDIA_VIX.level",
            ],
        )
