from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.datasets import EventRecord, FundamentalSnapshot, MarketBar
from aitrading_intelligence.feature_dataset_v2 import feature_dataset_v2_fingerprint
from aitrading_intelligence.feature_v2 import FeatureRowV2, build_feature_row_v2
from aitrading_intelligence.features import build_feature_row
from aitrading_intelligence.india_context import (
    IndiaContextObservation,
    IndiaUniverseMembership,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)
from aitrading_intelligence.leakage_guard_v3 import verify_future_mutation_invariant

DECISION_AT = datetime(2026, 11, 2, 9, 15, tzinfo=UTC)
SYMBOLS = ("RELIANCE", "HDFCBANK")
EXPECTED_CONTEXT_KEYS = (
    "benchmark.NIFTY_50.return_5m",
    "volatility.INDIA_VIX.level",
)


def _bar(
    symbol: str,
    *,
    observed_at: datetime,
    close: float,
    volume: float,
    source: str = "market-fixture",
) -> MarketBar:
    return MarketBar(
        symbol=symbol,
        interval="5m",
        observed_at=observed_at,
        event_at=observed_at - timedelta(seconds=2),
        ingested_at=observed_at,
        open=close - 1.0,
        high=close + 2.0,
        low=close - 2.0,
        close=close,
        volume=volume,
        source=source,
    )


def _market_bars(symbol: str, *, future_corruption: bool) -> list[MarketBar]:
    base = 1450.0 if symbol == "RELIANCE" else 980.0
    rows = [
        _bar(
            symbol,
            observed_at=DECISION_AT - timedelta(minutes=5),
            close=base - 2.0,
            volume=1_000_000.0,
        ),
        _bar(
            symbol,
            observed_at=DECISION_AT - timedelta(seconds=20),
            close=base,
            volume=1_500_000.0,
        ),
    ]
    if future_corruption:
        rows.append(
            _bar(
                symbol,
                observed_at=DECISION_AT + timedelta(minutes=5),
                close=base * 25.0,
                volume=999_999_999.0,
                source="future-corruption",
            )
        )
    return rows


def _events(symbol: str, *, future_corruption: bool) -> list[EventRecord]:
    rows = [
        EventRecord(
            symbol=symbol,
            event_type="earnings",
            observed_at=DECISION_AT - timedelta(minutes=3),
            effective_at=DECISION_AT - timedelta(minutes=4),
            source_id=f"earnings-{symbol}-known",
            source="event-fixture",
            numeric_features={"materiality": 0.4},
            lineage_ids=[f"filing-{symbol}-known"],
        )
    ]
    if future_corruption:
        rows.append(
            EventRecord(
                symbol=symbol,
                event_type="earnings",
                observed_at=DECISION_AT + timedelta(minutes=1),
                effective_at=DECISION_AT,
                source_id=f"earnings-{symbol}-future",
                source="future-corruption",
                numeric_features={"materiality": 999.0},
                lineage_ids=[f"filing-{symbol}-future"],
            )
        )
    return rows


def _fundamentals(
    symbol: str,
    *,
    future_corruption: bool,
) -> list[FundamentalSnapshot]:
    rows = [
        FundamentalSnapshot(
            symbol=symbol,
            observed_at=DECISION_AT - timedelta(minutes=6),
            period_end=DECISION_AT - timedelta(days=30),
            source_id=f"fundamental-{symbol}-known",
            source="fundamental-fixture",
            metrics={"pe": 22.0, "roe": 0.17},
        )
    ]
    if future_corruption:
        rows.append(
            FundamentalSnapshot(
                symbol=symbol,
                observed_at=DECISION_AT + timedelta(minutes=2),
                period_end=DECISION_AT - timedelta(days=1),
                source_id=f"fundamental-{symbol}-future",
                source="future-corruption",
                metrics={"pe": 999.0, "roe": -50.0},
            )
        )
    return rows


def _membership(
    symbol: str,
    sector: str,
    *,
    available_at: datetime,
    source_id: str,
    index_memberships: list[str],
) -> IndiaUniverseMembership:
    return IndiaUniverseMembership(
        instrument_id=f"NSE:{symbol}",
        exchange="NSE",
        symbol=symbol,
        sector=sector,
        index_memberships=index_memberships,
        effective_from=DECISION_AT - timedelta(days=365),
        available_at=available_at,
        source="universe-fixture",
        source_id=source_id,
    )


def _memberships(*, future_corruption: bool) -> list[IndiaUniverseMembership]:
    rows = [
        _membership(
            "RELIANCE",
            "ENERGY",
            available_at=DECISION_AT - timedelta(minutes=2),
            source_id="reliance-known",
            index_memberships=["NIFTY_50", "NIFTY_500"],
        ),
        _membership(
            "HDFCBANK",
            "FINANCIALS",
            available_at=DECISION_AT - timedelta(minutes=2),
            source_id="hdfcbank-known",
            index_memberships=["NIFTY_50", "NIFTY_500"],
        ),
    ]
    if future_corruption:
        rows.extend(
            [
                _membership(
                    "RELIANCE",
                    "CORRUPTED_FUTURE_SECTOR",
                    available_at=DECISION_AT + timedelta(seconds=1),
                    source_id="reliance-future",
                    index_memberships=["CORRUPTED_FUTURE_INDEX"],
                ),
                _membership(
                    "HDFCBANK",
                    "CORRUPTED_FUTURE_SECTOR",
                    available_at=DECISION_AT + timedelta(seconds=1),
                    source_id="hdfcbank-future",
                    index_memberships=["CORRUPTED_FUTURE_INDEX"],
                ),
            ]
        )
    return rows


def _context_observations(*, future_corruption: bool) -> list[IndiaContextObservation]:
    rows = [
        IndiaContextObservation(
            context_key="benchmark.NIFTY_50.return_5m",
            event_at=DECISION_AT - timedelta(seconds=15),
            available_at=DECISION_AT - timedelta(seconds=12),
            value=0.0015,
            source="context-fixture",
            source_id="nifty-known",
            quality_flags=["PRIMARY_SOURCE"],
        ),
        IndiaContextObservation(
            context_key="volatility.INDIA_VIX.level",
            event_at=DECISION_AT - timedelta(seconds=8),
            available_at=DECISION_AT - timedelta(seconds=5),
            value=13.2,
            source="context-fixture",
            source_id="vix-known",
        ),
    ]
    if future_corruption:
        rows.extend(
            [
                IndiaContextObservation(
                    context_key="benchmark.NIFTY_50.return_5m",
                    event_at=DECISION_AT,
                    available_at=DECISION_AT + timedelta(seconds=1),
                    value=-99.0,
                    source="future-corruption",
                    source_id="nifty-future",
                ),
                IndiaContextObservation(
                    context_key="volatility.INDIA_VIX.level",
                    event_at=DECISION_AT,
                    available_at=DECISION_AT + timedelta(seconds=1),
                    value=999.0,
                    source="future-corruption",
                    source_id="vix-future",
                ),
            ]
        )
    return rows


def _build_v2_rows(*, future_corruption: bool) -> list[FeatureRowV2]:
    universe = build_india_universe_snapshot(
        universe_id="NSE_V3_LEAKAGE_TEST",
        as_of=DECISION_AT,
        memberships=_memberships(future_corruption=future_corruption),
        source_versions={"universe": "fixture.v1"},
    )
    context = build_india_market_context_snapshot(
        as_of=DECISION_AT,
        observations=_context_observations(future_corruption=future_corruption),
        source_versions={"context": "fixture.v1"},
    )

    rows: list[FeatureRowV2] = []
    for symbol in SYMBOLS:
        base = build_feature_row(
            symbol=symbol,
            as_of=DECISION_AT,
            bars=_market_bars(symbol, future_corruption=future_corruption),
            events=_events(symbol, future_corruption=future_corruption),
            fundamentals=_fundamentals(symbol, future_corruption=future_corruption),
        )
        rows.append(
            build_feature_row_v2(
                base_row=base,
                universe=universe,
                context=context,
                expected_context_keys=EXPECTED_CONTEXT_KEYS,
                required_context_keys=EXPECTED_CONTEXT_KEYS,
            )
        )
    return rows


def test_future_mutation_across_all_v3_source_families_is_invariant() -> None:
    baseline_rows = _build_v2_rows(future_corruption=False)
    mutated_rows = _build_v2_rows(future_corruption=True)

    report = verify_future_mutation_invariant(
        decision_at=DECISION_AT,
        baseline_rows=baseline_rows,
        future_mutated_rows=mutated_rows,
        mutated_source_families=[
            "MARKET",
            "EVENT",
            "FUNDAMENTAL",
            "UNIVERSE",
            "INDIA_CONTEXT",
        ],
    )

    assert mutated_rows == baseline_rows
    assert report.invariant_passed is True
    assert report.row_count == 2
    assert report.mutated_source_families == [
        "EVENT",
        "FUNDAMENTAL",
        "INDIA_CONTEXT",
        "MARKET",
        "UNIVERSE",
    ]
    assert report.baseline_dataset_fingerprint == feature_dataset_v2_fingerprint(
        baseline_rows
    )
    assert report.mutated_dataset_fingerprint == report.baseline_dataset_fingerprint
    assert report.status == "LEAKAGE_INVARIANT_EVIDENCE_ONLY"
    assert report.can_authorize_capital is False


def test_historical_payload_mutation_fails_even_with_a_stale_row_fingerprint() -> None:
    baseline_rows = _build_v2_rows(future_corruption=False)
    mutated_rows = list(baseline_rows)
    first = mutated_rows[0]
    mutated_rows[0] = first.model_copy(
        update={
            "features": {
                **first.features,
                "market.close": first.features["market.close"] * 2.0,
            }
        }
    )

    with pytest.raises(ValueError, match="historical FeatureRow V2 payload"):
        verify_future_mutation_invariant(
            decision_at=DECISION_AT,
            baseline_rows=baseline_rows,
            future_mutated_rows=mutated_rows,
            mutated_source_families=["MARKET"],
        )


def test_historical_identity_mutation_fails_closed() -> None:
    baseline_rows = _build_v2_rows(future_corruption=False)
    mutated_rows = list(baseline_rows)
    mutated_rows[0] = mutated_rows[0].model_copy(update={"symbol": "INFY"})

    with pytest.raises(ValueError, match="decision-row identity set"):
        verify_future_mutation_invariant(
            decision_at=DECISION_AT,
            baseline_rows=baseline_rows,
            future_mutated_rows=mutated_rows,
            mutated_source_families=["UNIVERSE"],
        )


def test_duplicate_mutation_source_families_are_rejected() -> None:
    rows = _build_v2_rows(future_corruption=False)
    with pytest.raises(ValueError, match="must be unique"):
        verify_future_mutation_invariant(
            decision_at=DECISION_AT,
            baseline_rows=rows,
            future_mutated_rows=rows,
            mutated_source_families=["MARKET", "MARKET"],
        )
