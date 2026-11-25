from datetime import UTC, datetime, timedelta

import pyarrow.parquet as pq
import pytest

from aitrading_intelligence.feature_dataset_v2 import (
    export_feature_dataset_v2,
    feature_dataset_v2_fingerprint,
)
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
CREATED_AT = AS_OF + timedelta(minutes=5)
CODE_SHA = "a" * 40


def _base_row(symbol: str, close: float) -> FeatureRow:
    payload = {
        "feature_schema_version": "features.market.v1",
        "symbol": symbol,
        "as_of": AS_OF.isoformat(),
        "data_cutoff": (AS_OF - timedelta(seconds=20)).isoformat(),
        "features": {
            "market.close": close,
            "market.return_1": 0.002,
            "market.volume": 1_500_000.0,
        },
        "provenance": [f"market:fixture:{symbol}"],
    }
    return FeatureRow(**payload, fingerprint=canonical_fingerprint(payload))


def _membership(symbol: str, sector: str) -> IndiaUniverseMembership:
    return IndiaUniverseMembership(
        instrument_id=f"NSE:{symbol}",
        exchange="NSE",
        symbol=symbol,
        sector=sector,
        index_memberships=["NIFTY_500"],
        effective_from=AS_OF - timedelta(days=365),
        available_at=AS_OF - timedelta(minutes=2),
        source="universe-fixture",
        source_id=f"membership-{symbol}",
    )


def _universe():
    return build_india_universe_snapshot(
        universe_id="NSE_LIQUID_RESEARCH",
        as_of=AS_OF,
        memberships=[
            _membership("RELIANCE", "ENERGY"),
            _membership("HDFCBANK", "FINANCIALS"),
        ],
    )


def _context(*, include_future_corruption: bool = False):
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
            context_key="volatility.INDIA_VIX.level",
            event_at=AS_OF - timedelta(seconds=5),
            available_at=AS_OF - timedelta(seconds=4),
            value=13.2,
            source="context-fixture",
            source_id="vix",
        ),
    ]
    if include_future_corruption:
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
    return build_india_market_context_snapshot(as_of=AS_OF, observations=observations)


def _rows(*, context=None):
    universe = _universe()
    shared_context = context or _context()
    return [
        build_feature_row_v2(
            base_row=_base_row("RELIANCE", 1450.0),
            universe=universe,
            context=shared_context,
            expected_context_keys=[
                "benchmark.NIFTY_50.return_5m",
                "volatility.INDIA_VIX.level",
            ],
        ),
        build_feature_row_v2(
            base_row=_base_row("HDFCBANK", 980.0),
            universe=universe,
            context=shared_context,
            expected_context_keys=[
                "benchmark.NIFTY_50.return_5m",
                "volatility.INDIA_VIX.level",
            ],
        ),
    ]


def test_export_writes_versioned_parquet_and_manifest(tmp_path) -> None:
    rows = _rows()
    manifest = export_feature_dataset_v2(
        rows,
        output_dir=tmp_path,
        dataset_version="india-feature-v2-2026-11-02",
        code_sha=CODE_SHA,
        source_versions={"indiaContext": "india-market-context.v1"},
        created_at=CREATED_AT,
    )

    assert manifest.dataset_schema_version == "feature-dataset.v2"
    assert manifest.feature_schema_version == "features.market.v2"
    assert manifest.row_count == 2
    assert manifest.universe_snapshot_count == 1
    assert manifest.market_context_snapshot_count == 1
    assert manifest.status == "RESEARCH_DATASET_ONLY"
    assert manifest.can_authorize_capital is False
    assert (tmp_path / "features_v2.parquet").is_file()
    assert (tmp_path / "manifest.json").is_file()

    table = pq.read_table(tmp_path / "features_v2.parquet")
    assert table.num_rows == 2
    assert set(table.column("symbol").to_pylist()) == {"RELIANCE", "HDFCBANK"}
    metadata = table.schema.metadata or {}
    assert metadata[b"dataset_schema_version"] == b"feature-dataset.v2"
    assert metadata[b"feature_schema_version"] == b"features.market.v2"
    assert metadata[b"dataset_fingerprint"].decode() == manifest.dataset_fingerprint


def test_same_rows_are_reproducible_regardless_of_input_order(tmp_path) -> None:
    rows = _rows()
    first_dir = tmp_path / "first"
    second_dir = tmp_path / "second"

    first = export_feature_dataset_v2(
        rows,
        output_dir=first_dir,
        dataset_version="repro-v2",
        code_sha=CODE_SHA,
        created_at=CREATED_AT,
    )
    second = export_feature_dataset_v2(
        list(reversed(rows)),
        output_dir=second_dir,
        dataset_version="repro-v2",
        code_sha=CODE_SHA,
        created_at=CREATED_AT,
    )

    assert first == second
    assert first.dataset_fingerprint == feature_dataset_v2_fingerprint(rows)
    assert pq.read_table(first_dir / "features_v2.parquet").to_pylist() == pq.read_table(
        second_dir / "features_v2.parquet"
    ).to_pylist()
    assert (first_dir / "manifest.json").read_bytes() == (
        second_dir / "manifest.json"
    ).read_bytes()


def test_duplicate_decision_key_fails_closed(tmp_path) -> None:
    row = _rows()[0]
    competing = row.model_copy(
        update={
            "fingerprint": "f" * 64,
            "base_feature_row_fingerprint": "e" * 64,
        }
    )

    with pytest.raises(ValueError, match="decision keys must be unique"):
        export_feature_dataset_v2(
            [row, competing],
            output_dir=tmp_path,
            dataset_version="duplicate-key",
            code_sha=CODE_SHA,
            created_at=CREATED_AT,
        )


def test_one_decision_time_requires_one_shared_context_snapshot(tmp_path) -> None:
    first, second = _rows()
    second = second.model_copy(
        update={
            "market_context_snapshot_fingerprint": "d" * 64,
            "fingerprint": "c" * 64,
        }
    )

    with pytest.raises(ValueError, match="one market-context snapshot"):
        export_feature_dataset_v2(
            [first, second],
            output_dir=tmp_path,
            dataset_version="mixed-context",
            code_sha=CODE_SHA,
            created_at=CREATED_AT,
        )


def test_duplicate_base_feature_lineage_fails_closed(tmp_path) -> None:
    first, second = _rows()
    second = second.model_copy(
        update={
            "base_feature_row_fingerprint": first.base_feature_row_fingerprint,
            "fingerprint": "b" * 64,
        }
    )

    with pytest.raises(ValueError, match="base FeatureRow V1 fingerprints must be unique"):
        export_feature_dataset_v2(
            [first, second],
            output_dir=tmp_path,
            dataset_version="duplicate-base",
            code_sha=CODE_SHA,
            created_at=CREATED_AT,
        )


def test_future_context_mutation_cannot_change_historical_dataset_fingerprint() -> None:
    before_context = _context(include_future_corruption=False)
    after_context = _context(include_future_corruption=True)

    assert before_context == after_context
    before_rows = _rows(context=before_context)
    after_rows = _rows(context=after_context)

    assert after_rows == before_rows
    assert feature_dataset_v2_fingerprint(after_rows) == feature_dataset_v2_fingerprint(
        before_rows
    )


def test_manifest_creation_cannot_precede_dataset_cutoff(tmp_path) -> None:
    with pytest.raises(ValueError, match="created_at cannot precede"):
        export_feature_dataset_v2(
            _rows(),
            output_dir=tmp_path,
            dataset_version="invalid-created-at",
            code_sha=CODE_SHA,
            created_at=AS_OF - timedelta(hours=1),
        )
