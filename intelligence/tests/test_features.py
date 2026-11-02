from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.datasets import EventRecord, FundamentalSnapshot, MarketBar
from aitrading_intelligence.features import build_feature_row


def bar(at: datetime, close: float, volume: float = 1000.0) -> MarketBar:
    return MarketBar(
        symbol="RELIANCE",
        interval="5m",
        observed_at=at,
        open=close - 1,
        high=close + 2,
        low=close - 2,
        close=close,
        volume=volume,
        source="angel-one-history",
    )


def test_feature_row_excludes_future_market_event_and_fundamental_data() -> None:
    as_of = datetime(2026, 11, 1, 9, 30, tzinfo=UTC)
    earlier = as_of - timedelta(minutes=5)
    future = as_of + timedelta(minutes=5)

    events = [
        EventRecord(
            symbol="RELIANCE",
            event_type="earnings",
            observed_at=earlier,
            effective_at=as_of,
            source_id="event-known",
            source="exchange",
            numeric_features={"surprise": 0.12},
        ),
        EventRecord(
            symbol="RELIANCE",
            event_type="earnings",
            observed_at=future,
            effective_at=future,
            source_id="event-future",
            source="exchange",
            numeric_features={"surprise": 9.99},
        ),
    ]
    fundamentals = [
        FundamentalSnapshot(
            symbol="RELIANCE",
            observed_at=earlier,
            period_end=earlier - timedelta(days=30),
            source_id="fund-known",
            source="filing",
            metrics={"roe": 0.18},
        ),
        FundamentalSnapshot(
            symbol="RELIANCE",
            observed_at=future,
            period_end=as_of,
            source_id="fund-future",
            source="filing",
            metrics={"roe": 0.99},
        ),
    ]

    row = build_feature_row(
        symbol="RELIANCE",
        as_of=as_of,
        bars=[bar(earlier, 100.0), bar(as_of, 102.0), bar(future, 500.0)],
        events=events,
        fundamentals=fundamentals,
    )

    assert row.features["market.close"] == 102.0
    assert row.features["event.earnings.surprise"] == 0.12
    assert row.features["fundamental.roe"] == 0.18
    assert row.data_cutoff <= as_of
    assert "event:exchange:event-future" not in row.provenance
    assert "fundamental:filing:fund-future" not in row.provenance


def test_feature_fingerprint_is_deterministic_for_same_point_in_time_inputs() -> None:
    as_of = datetime(2026, 11, 1, 9, 30, tzinfo=UTC)
    bars = [bar(as_of - timedelta(minutes=5), 100.0), bar(as_of, 101.0)]

    first = build_feature_row(symbol="RELIANCE", as_of=as_of, bars=bars)
    second = build_feature_row(symbol="RELIANCE", as_of=as_of, bars=reversed(bars))

    assert first.fingerprint == second.fingerprint
    assert first.features == second.features


def test_feature_row_requires_timezone_aware_as_of() -> None:
    naive = datetime(2026, 11, 1, 9, 30)
    with pytest.raises(ValueError, match="timezone-aware"):
        build_feature_row(symbol="RELIANCE", as_of=naive, bars=[])


def test_market_bar_rejects_inconsistent_ohlc() -> None:
    with pytest.raises(ValueError, match="OHLC"):
        MarketBar(
            symbol="RELIANCE",
            interval="5m",
            observed_at=datetime(2026, 11, 1, 9, 30, tzinfo=UTC),
            open=100,
            high=99,
            low=98,
            close=100,
            volume=1000,
            source="test",
        )
