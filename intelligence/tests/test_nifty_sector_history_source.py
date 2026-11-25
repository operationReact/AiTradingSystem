from datetime import UTC, datetime

import pytest

from aitrading_intelligence.india_context import build_india_market_context_snapshot
from aitrading_intelligence.nifty_sector_history_source import parse_sector_index_history


def _csv(*, future_row: bool = False) -> bytes:
    rows = [
        "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)",
        "01-Nov-2026,50000.00,50500.00,49700.00,50300.00,1000000,4500.00",
        "02-Nov-2026,50300.00,51000.00,50100.00,50800.00,1200000,5200.00",
    ]
    if future_row:
        rows.append("03-Nov-2026,50800.00,90000.00,100.00,89000.00,999999999,99999.00")
    return ("\n".join(rows) + "\n").encode()


def test_parses_whitelisted_sector_index_and_emits_causal_context() -> None:
    history = parse_sector_index_history(_csv(), index_code="NIFTY BANK")

    assert history.contract_version == "nifty-sector-history.v1"
    assert history.index_code == "NIFTY_BANK"
    assert history.can_authorize_capital is False

    observations = history.context_observations()
    second_day = [
        observation
        for observation in observations
        if observation.available_at == datetime(2026, 11, 2, 10, 30, tzinfo=UTC)
    ]
    metrics = {observation.context_key: observation.value for observation in second_day}
    assert metrics["sector.NIFTY_BANK.close"] == pytest.approx(50800.0)
    assert metrics["sector.NIFTY_BANK.turnover_cr"] == pytest.approx(5200.0)
    assert metrics["sector.NIFTY_BANK.return_1d"] == pytest.approx(50800.0 / 50300.0 - 1.0)


def test_future_sector_row_cannot_mutate_earlier_context_or_causal_source_version() -> None:
    baseline = parse_sector_index_history(_csv(), index_code="NIFTY_BANK")
    future = parse_sector_index_history(_csv(future_row=True), index_code="NIFTY_BANK")
    as_of = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)

    before = build_india_market_context_snapshot(
        as_of=as_of,
        observations=baseline.context_observations(),
        source_versions={"sector.NIFTY_BANK": baseline.causal_source_fingerprint(as_of=as_of)},
    )
    after = build_india_market_context_snapshot(
        as_of=as_of,
        observations=future.context_observations(),
        source_versions={"sector.NIFTY_BANK": future.causal_source_fingerprint(as_of=as_of)},
    )

    assert after == before
    assert baseline.history_fingerprint != future.history_fingerprint
    assert baseline.causal_source_fingerprint(as_of=as_of) == future.causal_source_fingerprint(
        as_of=as_of
    )


def test_same_csv_under_different_whitelisted_index_has_distinct_lineage() -> None:
    bank = parse_sector_index_history(_csv(), index_code="NIFTY_BANK")
    it = parse_sector_index_history(_csv(), index_code="NIFTY_IT")

    assert bank.history_fingerprint != it.history_fingerprint
    assert bank.context_observations()[0].context_key.startswith("sector.NIFTY_BANK.")
    assert it.context_observations()[0].context_key.startswith("sector.NIFTY_IT.")


def test_rejects_non_whitelisted_index_code() -> None:
    with pytest.raises(ValueError, match="unsupported NSE sector-index code"):
        parse_sector_index_history(_csv(), index_code="NIFTY_FAKE_SECTOR")


def test_rejects_header_drift_and_duplicate_dates() -> None:
    with pytest.raises(ValueError, match="unexpected sector-index CSV headers"):
        parse_sector_index_history(
            _csv().replace("Turnover (₹ Cr)".encode(), b"Turnover", 1),
            index_code="NIFTY_BANK",
        )

    duplicate = (
        "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)\n"
        "01-Nov-2026,50000,50500,49700,50300,1000,4500\n"
        "01-Nov-2026,50300,51000,50100,50800,1200,5200\n"
    ).encode()
    with pytest.raises(ValueError, match="duplicate trading dates"):
        parse_sector_index_history(duplicate, index_code="NIFTY_BANK")
