from datetime import UTC, datetime

import pytest

from aitrading_intelligence.india_context import build_india_market_context_snapshot
from aitrading_intelligence.india_vix_source import parse_india_vix_history


def _csv() -> bytes:
    return (
        b"Date,Open,High,Low,Close,Prev. Close,Change,% Change\n"
        b"03-Sep-2018,12.5975,13.7175,11.1700,13.3925,12.5975,0.8000,6.31\n"
        b"04-Sep-2018,13.4000,14.2000,12.9000,13.8000,13.3925,0.4075,3.04\n"
    )


def test_parses_official_vix_history_and_emits_causal_close_context() -> None:
    history = parse_india_vix_history(_csv())

    assert history.contract_version == "india-vix-history.v1"
    assert history.status == "RESEARCH_MARKET_CONTEXT_ONLY"
    assert history.can_authorize_capital is False
    assert history.source_sha256
    assert history.history_fingerprint
    assert [row.trading_date.isoformat() for row in history.rows] == [
        "2018-09-03",
        "2018-09-04",
    ]

    observations = history.close_observations()
    assert observations[0].context_key == "volatility.INDIA_VIX.close"
    assert observations[0].value == pytest.approx(13.3925)
    assert observations[0].available_at == datetime(2018, 9, 3, 10, 30, tzinfo=UTC)
    assert observations[0].source == "NSE_INDIA_HISTORICAL_VIX"


def test_future_vix_row_cannot_mutate_earlier_context_snapshot() -> None:
    history = parse_india_vix_history(_csv())
    observations = history.close_observations()

    snapshot = build_india_market_context_snapshot(
        as_of=datetime(2018, 9, 3, 12, 0, tzinfo=UTC),
        observations=observations,
        source_versions={"indiaVix": history.history_fingerprint},
    )

    assert snapshot.metrics == {"volatility.INDIA_VIX.close": pytest.approx(13.3925)}
    assert snapshot.data_cutoff == datetime(2018, 9, 3, 10, 30, tzinfo=UTC)


def test_rejects_header_drift() -> None:
    source = _csv().replace(b"Prev. Close", b"Previous Close", 1)

    with pytest.raises(ValueError, match="unexpected India VIX CSV headers"):
        parse_india_vix_history(source)


def test_rejects_inconsistent_ohlc() -> None:
    source = _csv().replace(b"13.7175", b"12.0000", 1)

    with pytest.raises(ValueError, match="high is inconsistent"):
        parse_india_vix_history(source)


def test_rejects_duplicate_trading_dates() -> None:
    source = (
        b"Date,Open,High,Low,Close,Prev. Close,Change,% Change\n"
        b"03-Sep-2018,12.5975,13.7175,11.1700,13.3925,12.5975,0.8000,6.31\n"
        b"03-Sep-2018,12.5975,13.7175,11.1700,13.3925,12.5975,0.8000,6.31\n"
    )

    with pytest.raises(ValueError, match="duplicate trading dates"):
        parse_india_vix_history(source)
