import json
from datetime import UTC, datetime

import pyarrow.parquet as pq

from aitrading_intelligence.exports import export_feature_dataset
from aitrading_intelligence.features import FeatureRow


def feature_row() -> FeatureRow:
    as_of = datetime(2026, 11, 1, 9, 30, tzinfo=UTC)
    return FeatureRow(
        symbol="RELIANCE",
        as_of=as_of,
        data_cutoff=as_of,
        features={"market.close": 2950.0, "market.return_1": 0.01},
        provenance=["market:test:2026-11-01T09:30:00+00:00"],
        fingerprint="a" * 64,
    )


def test_export_writes_parquet_and_manifest_with_lineage(tmp_path) -> None:
    manifest = export_feature_dataset(
        [feature_row()],
        output_dir=tmp_path,
        dataset_version="reliance-2026-11-01-v1",
        code_sha="b" * 40,
        source_versions={"market": "angel-history-v1"},
    )

    parquet_path = tmp_path / "features.parquet"
    manifest_path = tmp_path / "manifest.json"
    assert parquet_path.exists()
    assert manifest_path.exists()
    assert manifest.row_count == 1
    assert manifest.data_cutoff.isoformat() == "2026-11-01T09:30:00+00:00"

    table = pq.read_table(parquet_path)
    assert table.num_rows == 1
    assert table.schema.metadata[b"feature_schema_version"] == b"features.market.v1"
    assert table.schema.metadata[b"dataset_version"] == b"reliance-2026-11-01-v1"

    saved_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert saved_manifest["dataset_version"] == "reliance-2026-11-01-v1"
    assert saved_manifest["code_sha"] == "b" * 40
    assert saved_manifest["source_versions"]["market"] == "angel-history-v1"


def test_export_fingerprint_is_independent_of_input_order(tmp_path) -> None:
    first = feature_row()
    second = first.model_copy(
        update={
            "symbol": "TCS",
            "fingerprint": "c" * 64,
            "features": {"market.close": 3500.0},
        }
    )

    left = export_feature_dataset(
        [first, second],
        output_dir=tmp_path / "left",
        dataset_version="v1",
        code_sha="d" * 40,
    )
    right = export_feature_dataset(
        [second, first],
        output_dir=tmp_path / "right",
        dataset_version="v1",
        code_sha="d" * 40,
    )

    assert left.fingerprint == right.fingerprint
