from datetime import UTC, datetime, timedelta

from aitrading_intelligence.india_vix_source import parse_india_vix_history
from aitrading_intelligence.nifty50_history_source import parse_nifty50_history
from aitrading_intelligence.nifty_constituent_source import parse_nifty50_constituents
from aitrading_intelligence.nifty_sector_history_source import parse_sector_index_history
from aitrading_intelligence.official_source_health import build_official_source_health_report

AS_OF = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)


def _constituent_snapshot(*, observed_at: datetime = AS_OF - timedelta(days=1)):
    source = (
        b"Company Name,Industry,Symbol,Series,ISIN Code\n"
        b"HDFC Bank Ltd.,Financial Services,HDFCBANK,EQ,INE040A01034\n"
        b"Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018\n"
    )
    return parse_nifty50_constituents(source, observed_at=observed_at)


def _nifty_history(*, future_only: bool = False, future_append: bool = False):
    if future_only:
        rows = [
            "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)",
            "03-Nov-2026,103,105,102,104,1300,140",
        ]
    else:
        rows = [
            "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)",
            "01-Nov-2026,100,102,99,101,1000,100",
            "02-Nov-2026,101,104,100,103,1200,130",
        ]
        if future_append:
            rows.append("03-Nov-2026,103,105,102,104,1300,140")
    return parse_nifty50_history(("\n".join(rows) + "\n").encode())


def _vix_history(*, future_only: bool = False, future_append: bool = False):
    if future_only:
        rows = [
            "Date,Open,High,Low,Close,Prev. Close,Change,% Change",
            "03-Nov-2026,14,16,13,15,14,1,7.14",
        ]
    else:
        rows = [
            "Date,Open,High,Low,Close,Prev. Close,Change,% Change",
            "01-Nov-2026,12,14,11,13,12,1,8.33",
            "02-Nov-2026,13,15,12,14,13,1,7.69",
        ]
        if future_append:
            rows.append("03-Nov-2026,14,16,13,15,14,1,7.14")
    return parse_india_vix_history(("\n".join(rows) + "\n").encode())


def _sector_history(
    *,
    index_code: str = "NIFTY_BANK",
    future_only: bool = False,
    future_append: bool = False,
):
    if future_only:
        rows = [
            "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)",
            "03-Nov-2026,50800,51200,50600,51000,1300000,5400",
        ]
    else:
        rows = [
            "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)",
            "01-Nov-2026,50000,50500,49700,50300,1000000,4500",
            "02-Nov-2026,50300,51000,50100,50800,1200000,5200",
        ]
        if future_append:
            rows.append("03-Nov-2026,50800,51200,50600,51000,1300000,5400")
    return parse_sector_index_history(
        ("\n".join(rows) + "\n").encode(),
        index_code=index_code,
    )


def _report(
    *,
    as_of: datetime = AS_OF,
    snapshots=None,
    nifty=None,
    vix=None,
    sectors=None,
    required=("NIFTY_BANK",),
):
    return build_official_source_health_report(
        as_of=as_of,
        constituent_snapshots=snapshots if snapshots is not None else [_constituent_snapshot()],
        nifty50_history=nifty if nifty is not None else _nifty_history(),
        india_vix_history=vix if vix is not None else _vix_history(),
        sector_histories=sectors if sectors is not None else [_sector_history()],
        required_sector_indices=required,
    )


def test_current_official_feeds_are_healthy_under_shared_source_health_gate() -> None:
    report = _report()

    assert report.health == "HEALTHY"
    assert report.missing_families == []
    assert report.stale_families == []
    assert report.degraded_families == []
    assert [family.family for family in report.families] == [
        "INDIA_VIX_HISTORY",
        "NIFTY50_HISTORY",
        "NIFTY_CONSTITUENTS",
        "SECTOR_NIFTY_BANK",
    ]
    assert report.causal_sample_count == 4
    assert report.can_authorize_capital is False


def test_missing_required_sector_blocks_official_source_health() -> None:
    report = _report(sectors=[])

    assert report.health == "BLOCKED"
    assert report.missing_families == ["SECTOR_NIFTY_BANK"]
    sector = next(item for item in report.families if item.family == "SECTOR_NIFTY_BANK")
    assert sector.health == "MISSING"
    assert sector.reasons == ["NO_CAUSAL_SAMPLES"]


def test_stale_daily_feeds_block_while_constituent_snapshot_remains_fresh() -> None:
    report = _report(as_of=AS_OF + timedelta(days=6))

    assert report.health == "BLOCKED"
    assert report.stale_families == [
        "INDIA_VIX_HISTORY",
        "NIFTY50_HISTORY",
        "SECTOR_NIFTY_BANK",
    ]
    constituent = next(item for item in report.families if item.family == "NIFTY_CONSTITUENTS")
    assert constituent.health == "HEALTHY"


def test_future_only_feeds_are_missing_at_earlier_as_of_instead_of_raising() -> None:
    report = _report(
        snapshots=[_constituent_snapshot(observed_at=AS_OF + timedelta(days=1))],
        nifty=_nifty_history(future_only=True),
        vix=_vix_history(future_only=True),
        sectors=[_sector_history(future_only=True)],
    )

    assert report.health == "BLOCKED"
    assert report.missing_families == [
        "INDIA_VIX_HISTORY",
        "NIFTY50_HISTORY",
        "NIFTY_CONSTITUENTS",
        "SECTOR_NIFTY_BANK",
    ]
    assert report.causal_sample_count == 0
    assert report.data_cutoff is None


def test_future_archive_growth_cannot_mutate_historical_health_report() -> None:
    baseline = _report()
    mutated = _report(
        snapshots=[
            _constituent_snapshot(),
            _constituent_snapshot(observed_at=AS_OF + timedelta(days=1)),
        ],
        nifty=_nifty_history(future_append=True),
        vix=_vix_history(future_append=True),
        sectors=[_sector_history(future_append=True)],
    )

    assert mutated == baseline
    assert mutated.report_fingerprint == baseline.report_fingerprint


def test_required_sector_policy_rejects_duplicates_unsupported_and_unexpected_histories() -> None:
    try:
        _report(required=("NIFTY_BANK", "NIFTY BANK"))
    except ValueError as exc:
        assert "required sector indices must be unique" in str(exc)
    else:
        raise AssertionError("duplicate normalized sector rules must fail closed")

    try:
        _report(required=("NIFTY_FAKE",))
    except ValueError as exc:
        assert "unsupported required sector index" in str(exc)
    else:
        raise AssertionError("unsupported required sector must fail closed")

    try:
        _report(
            sectors=[_sector_history(), _sector_history(index_code="NIFTY_IT")],
            required=("NIFTY_BANK",),
        )
    except ValueError as exc:
        assert "sector history has no required health rule" in str(exc)
    else:
        raise AssertionError("unexpected sector history must fail closed")
