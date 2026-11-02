from datetime import UTC, datetime

import pytest

from aitrading_intelligence.india_context import build_india_market_context_snapshot
from aitrading_intelligence.nifty50_history_source import parse_nifty50_history


def _csv() -> bytes:
    return (
        "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)\n"
        "01-Jan-2019,10881.70,10923.60,10807.10,10910.10,159404542,8688.26\n"
        "02-Jan-2019,10868.85,10895.35,10735.05,10792.50,309665939,15352.25\n"
    ).encode()


def test_parses_official_nifty50_history_and_emits_benchmark_context() -> None:
    history = parse_nifty50_history(_csv())

    assert history.contract_version == "nifty50-history.v1"
    assert history.status == "RESEARCH_MARKET_CONTEXT_ONLY"
    assert history.can_authorize_capital is False
    assert history.source_sha256
    assert history.history_fingerprint

    observations = history.context_observations()
    second_day = [
        observation
        for observation in observations
        if observation.available_at == datetime(2019, 1, 2, 10, 30, tzinfo=UTC)
    ]
    metrics = {observation.context_key: observation.value for observation in second_day}
    assert metrics["benchmark.NIFTY50.close"] == pytest.approx(10792.50)
    assert metrics["benchmark.NIFTY50.shares_traded"] == pytest.approx(309665939)
    assert metrics["benchmark.NIFTY50.turnover_cr"] == pytest.approx(15352.25)
    assert metrics["benchmark.NIFTY50.return_1d"] == pytest.approx(10792.50 / 10910.10 - 1.0)


def test_future_index_row_cannot_mutate_earlier_context_snapshot() -> None:
    history = parse_nifty50_history(_csv())

    snapshot = build_india_market_context_snapshot(
        as_of=datetime(2019, 1, 1, 12, 0, tzinfo=UTC),
        observations=history.context_observations(),
        source_versions={"nifty50": history.history_fingerprint},
    )

    assert snapshot.metrics["benchmark.NIFTY50.close"] == pytest.approx(10910.10)
    assert snapshot.metrics["benchmark.NIFTY50.turnover_cr"] == pytest.approx(8688.26)
    assert "benchmark.NIFTY50.return_1d" not in snapshot.metrics
    assert snapshot.data_cutoff == datetime(2019, 1, 1, 10, 30, tzinfo=UTC)


def test_rejects_header_drift() -> None:
    source = _csv().replace("Turnover (₹ Cr)".encode(), b"Turnover", 1)

    with pytest.raises(ValueError, match="unexpected NIFTY 50 CSV headers"):
        parse_nifty50_history(source)


def test_rejects_inconsistent_ohlc() -> None:
    source = _csv().replace(b"10923.60", b"10800.00", 1)

    with pytest.raises(ValueError, match="high is inconsistent"):
        parse_nifty50_history(source)


def test_rejects_duplicate_trading_dates() -> None:
    source = (
        "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)\n"
        "01-Jan-2019,10881.70,10923.60,10807.10,10910.10,159404542,8688.26\n"
        "01-Jan-2019,10881.70,10923.60,10807.10,10910.10,159404542,8688.26\n"
    ).encode()

    with pytest.raises(ValueError, match="duplicate trading dates"):
        parse_nifty50_history(source)
