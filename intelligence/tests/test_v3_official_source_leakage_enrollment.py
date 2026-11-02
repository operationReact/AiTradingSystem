from datetime import UTC, datetime, timedelta

from aitrading_intelligence.datasets import MarketBar
from aitrading_intelligence.feature_v2 import FeatureRowV2, build_feature_row_v2
from aitrading_intelligence.features import build_feature_row
from aitrading_intelligence.india_context import (
    IndiaUniverseMembership,
    build_india_market_context_snapshot,
    build_india_universe_snapshot,
)
from aitrading_intelligence.india_vix_source import parse_india_vix_history
from aitrading_intelligence.leakage_guard_v3 import verify_future_mutation_invariant
from aitrading_intelligence.nifty50_history_source import parse_nifty50_history
from aitrading_intelligence.nifty_constituent_source import (
    NiftyConstituentSnapshot,
    parse_nifty50_constituents,
    select_nifty50_snapshot,
)

DECISION_AT = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)
SYMBOLS = ("HDFCBANK", "RELIANCE")
SECTORS = {"HDFCBANK": "FINANCIALS", "RELIANCE": "ENERGY"}
EXPECTED_CONTEXT_KEYS = (
    "benchmark.NIFTY50.close",
    "benchmark.NIFTY50.return_1d",
    "benchmark.NIFTY50.shares_traded",
    "benchmark.NIFTY50.turnover_cr",
    "volatility.INDIA_VIX.close",
)


def _constituent_csv(*, future_corruption: bool) -> bytes:
    if future_corruption:
        return (
            b"Company Name,Industry,Symbol,Series,ISIN Code\n"
            b"Infosys Ltd.,Information Technology,INFY,EQ,INE009A01021\n"
        )
    return (
        b"Company Name,Industry,Symbol,Series,ISIN Code\n"
        b"HDFC Bank Ltd.,Financial Services,HDFCBANK,EQ,INE040A01034\n"
        b"Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018\n"
    )


def _nifty_history_csv(*, future_corruption: bool) -> bytes:
    rows = [
        "Date,Open,High,Low,Close,Shares Traded,Turnover (₹ Cr)",
        "01-Nov-2026,100.00,102.00,99.00,101.00,1000,100.00",
        "02-Nov-2026,101.00,104.00,100.00,103.00,1200,130.00",
    ]
    if future_corruption:
        rows.append("03-Nov-2026,103.00,1000.00,1.00,999.00,999999999,99999.00")
    return ("\n".join(rows) + "\n").encode()


def _vix_history_csv(*, future_corruption: bool) -> bytes:
    rows = [
        "Date,Open,High,Low,Close,Prev. Close,Change,% Change",
        "01-Nov-2026,12.00,14.00,11.00,13.00,12.00,1.00,8.33",
        "02-Nov-2026,13.00,15.00,12.00,14.00,13.00,1.00,7.69",
    ]
    if future_corruption:
        rows.append("03-Nov-2026,14.00,100.00,13.00,99.00,14.00,85.00,607.14")
    return ("\n".join(rows) + "\n").encode()


def _selected_constituents(*, future_corruption: bool) -> NiftyConstituentSnapshot:
    baseline = parse_nifty50_constituents(
        _constituent_csv(future_corruption=False),
        observed_at=DECISION_AT - timedelta(days=2),
    )
    snapshots = [baseline]
    if future_corruption:
        snapshots.append(
            parse_nifty50_constituents(
                _constituent_csv(future_corruption=True),
                observed_at=DECISION_AT + timedelta(days=1),
            )
        )
    return select_nifty50_snapshot(snapshots, as_of=DECISION_AT)


def _universe(*, future_corruption: bool):
    selected = _selected_constituents(future_corruption=future_corruption)
    memberships = [
        IndiaUniverseMembership(
            instrument_id=f"NSE:{row.symbol}",
            exchange="NSE",
            symbol=row.symbol,
            series=row.series,
            sector=SECTORS[row.symbol],
            index_memberships=["NIFTY50"],
            effective_from=selected.observed_at,
            available_at=selected.observed_at,
            source="NIFTY_CONSTITUENT_SNAPSHOT",
            source_id=f"{selected.snapshot_fingerprint}:{row.isin}",
        )
        for row in selected.constituents
    ]
    return build_india_universe_snapshot(
        universe_id="NSE_OFFICIAL_SOURCE_LEAKAGE_TEST",
        as_of=DECISION_AT,
        memberships=memberships,
        source_versions={
            "nifty50Constituents": selected.snapshot_fingerprint,
            "sectorTaxonomy": "fixture-sector.v1",
        },
    )


def _context(*, future_corruption: bool):
    nifty = parse_nifty50_history(
        _nifty_history_csv(future_corruption=future_corruption)
    )
    vix = parse_india_vix_history(
        _vix_history_csv(future_corruption=future_corruption)
    )
    return build_india_market_context_snapshot(
        as_of=DECISION_AT,
        observations=[*nifty.context_observations(), *vix.close_observations()],
        source_versions={
            "indiaVix": vix.causal_source_fingerprint(as_of=DECISION_AT),
            "nifty50": nifty.causal_source_fingerprint(as_of=DECISION_AT),
        },
    )


def _market_bars(symbol: str) -> list[MarketBar]:
    base = 1500.0 if symbol == "RELIANCE" else 1000.0
    return [
        MarketBar(
            symbol=symbol,
            interval="5m",
            observed_at=DECISION_AT - timedelta(minutes=10),
            event_at=DECISION_AT - timedelta(minutes=10, seconds=2),
            ingested_at=DECISION_AT - timedelta(minutes=10),
            open=base - 2.0,
            high=base + 1.0,
            low=base - 3.0,
            close=base - 1.0,
            volume=1_000_000.0,
            source="market-fixture",
        ),
        MarketBar(
            symbol=symbol,
            interval="5m",
            observed_at=DECISION_AT - timedelta(minutes=5),
            event_at=DECISION_AT - timedelta(minutes=5, seconds=2),
            ingested_at=DECISION_AT - timedelta(minutes=5),
            open=base - 1.0,
            high=base + 2.0,
            low=base - 2.0,
            close=base,
            volume=1_200_000.0,
            source="market-fixture",
        ),
    ]


def _build_rows(*, future_corruption: bool) -> list[FeatureRowV2]:
    universe = _universe(future_corruption=future_corruption)
    context = _context(future_corruption=future_corruption)
    return [
        build_feature_row_v2(
            base_row=build_feature_row(
                symbol=symbol,
                as_of=DECISION_AT,
                bars=_market_bars(symbol),
            ),
            universe=universe,
            context=context,
            expected_context_keys=EXPECTED_CONTEXT_KEYS,
            required_context_keys=EXPECTED_CONTEXT_KEYS,
        )
        for symbol in SYMBOLS
    ]


def test_official_source_future_mutations_leave_feature_rows_and_dataset_byte_stable() -> None:
    baseline = _build_rows(future_corruption=False)
    mutated = _build_rows(future_corruption=True)

    report = verify_future_mutation_invariant(
        decision_at=DECISION_AT,
        baseline_rows=baseline,
        future_mutated_rows=mutated,
        mutated_source_families=[
            "NIFTY_CONSTITUENTS",
            "NIFTY50_HISTORY",
            "INDIA_VIX_HISTORY",
        ],
    )

    assert mutated == baseline
    assert report.invariant_passed is True
    assert report.mutated_source_families == [
        "INDIA_VIX_HISTORY",
        "NIFTY50_HISTORY",
        "NIFTY_CONSTITUENTS",
    ]
    assert report.baseline_dataset_fingerprint == report.mutated_dataset_fingerprint


def test_archive_fingerprint_can_change_while_causal_source_version_stays_stable() -> None:
    baseline_nifty = parse_nifty50_history(_nifty_history_csv(future_corruption=False))
    future_nifty = parse_nifty50_history(_nifty_history_csv(future_corruption=True))
    baseline_vix = parse_india_vix_history(_vix_history_csv(future_corruption=False))
    future_vix = parse_india_vix_history(_vix_history_csv(future_corruption=True))

    assert baseline_nifty.history_fingerprint != future_nifty.history_fingerprint
    assert baseline_vix.history_fingerprint != future_vix.history_fingerprint
    assert baseline_nifty.causal_source_fingerprint(as_of=DECISION_AT) == (
        future_nifty.causal_source_fingerprint(as_of=DECISION_AT)
    )
    assert baseline_vix.causal_source_fingerprint(as_of=DECISION_AT) == (
        future_vix.causal_source_fingerprint(as_of=DECISION_AT)
    )
