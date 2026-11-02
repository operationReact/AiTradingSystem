from datetime import UTC, datetime, timedelta

import pytest

from aitrading_intelligence.nifty_constituent_source import (
    NIFTY_50_CONSTITUENT_URL,
    NIFTY_500_CONSTITUENT_URL,
    parse_nifty50_constituents,
    parse_nifty500_constituents,
    select_nifty50_snapshot,
    select_nifty500_snapshot,
)

OBSERVED = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)


def _csv(*, include_extra: bool = False) -> bytes:
    rows = [
        "Company Name,Industry,Symbol,Series,ISIN Code",
        "Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018",
        "HDFC Bank Ltd.,Financial Services,HDFCBANK,EQ,INE040A01034",
    ]
    if include_extra:
        rows.append("Infosys Ltd.,Information Technology,INFY,EQ,INE009A01021")
    return ("\n".join(rows) + "\n").encode()


def test_parses_official_shape_into_canonical_first_observed_snapshot() -> None:
    result = parse_nifty50_constituents(_csv(), observed_at=OBSERVED)

    assert result.contract_version == "nifty-constituent-snapshot.v1"
    assert result.index_code == "NIFTY50"
    assert result.source_url == NIFTY_50_CONSTITUENT_URL
    assert result.observed_at == OBSERVED
    assert [row.symbol for row in result.constituents] == ["HDFCBANK", "RELIANCE"]
    assert result.constituents[0].industry_label == "Financial Services"
    assert result.status == "RESEARCH_INDEX_MEMBERSHIP_ONLY"
    assert result.can_authorize_capital is False
    assert result.source_sha256
    assert result.snapshot_fingerprint


def test_nifty500_uses_same_contract_with_distinct_official_lineage() -> None:
    result = parse_nifty500_constituents(_csv(include_extra=True), observed_at=OBSERVED)

    assert result.contract_version == "nifty-constituent-snapshot.v1"
    assert result.index_code == "NIFTY500"
    assert result.source_url == NIFTY_500_CONSTITUENT_URL
    assert [row.symbol for row in result.constituents] == ["HDFCBANK", "INFY", "RELIANCE"]
    assert result.status == "RESEARCH_INDEX_MEMBERSHIP_ONLY"
    assert result.can_authorize_capital is False


def test_rejects_header_drift_instead_of_guessing_columns() -> None:
    source = _csv().replace(b"Industry", b"Sector", 1)

    with pytest.raises(ValueError, match="unexpected NIFTY constituent CSV headers"):
        parse_nifty50_constituents(source, observed_at=OBSERVED)


def test_rejects_duplicate_symbols() -> None:
    source = (
        b"Company Name,Industry,Symbol,Series,ISIN Code\n"
        b"A Ltd.,Financial Services,ABC,EQ,INE001A01010\n"
        b"B Ltd.,Industrials,ABC,EQ,INE002A01018\n"
    )

    with pytest.raises(ValueError, match="constituent symbols must be unique"):
        parse_nifty50_constituents(source, observed_at=OBSERVED)


def test_future_snapshot_cannot_mutate_historical_membership_selection() -> None:
    first = parse_nifty50_constituents(_csv(), observed_at=OBSERVED)
    future = parse_nifty50_constituents(
        _csv(include_extra=True),
        observed_at=OBSERVED + timedelta(days=30),
    )

    selected = select_nifty50_snapshot(
        [future, first],
        as_of=OBSERVED + timedelta(days=10),
    )

    assert selected == first
    assert [row.symbol for row in selected.constituents] == ["HDFCBANK", "RELIANCE"]


def test_nifty500_selection_is_causal_and_does_not_cross_index_families() -> None:
    nifty50 = parse_nifty50_constituents(_csv(), observed_at=OBSERVED - timedelta(hours=1))
    first500 = parse_nifty500_constituents(_csv(), observed_at=OBSERVED)
    future500 = parse_nifty500_constituents(
        _csv(include_extra=True),
        observed_at=OBSERVED + timedelta(days=30),
    )

    selected = select_nifty500_snapshot(
        [future500, nifty50, first500],
        as_of=OBSERVED + timedelta(days=10),
    )

    assert selected == first500
    assert selected.index_code == "NIFTY500"
    assert [row.symbol for row in selected.constituents] == ["HDFCBANK", "RELIANCE"]


def test_snapshot_is_not_backdated_before_first_collection() -> None:
    first = parse_nifty50_constituents(_csv(), observed_at=OBSERVED)

    with pytest.raises(ValueError, match="no NIFTY 50 constituent snapshot was observed by as_of"):
        select_nifty50_snapshot([first], as_of=OBSERVED - timedelta(seconds=1))


def test_nifty500_snapshot_is_not_backdated_before_first_collection() -> None:
    first = parse_nifty500_constituents(_csv(), observed_at=OBSERVED)

    with pytest.raises(ValueError, match="no NIFTY 500 constituent snapshot was observed by as_of"):
        select_nifty500_snapshot([first], as_of=OBSERVED - timedelta(seconds=1))


def test_rejects_naive_observation_time() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        parse_nifty50_constituents(_csv(), observed_at=datetime(2026, 11, 2, 12, 0))
