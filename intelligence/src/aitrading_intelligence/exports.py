from __future__ import annotations

import json
from collections.abc import Iterable
from datetime import UTC, datetime
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from .datasets import FEATURE_SCHEMA_VERSION, DatasetManifest
from .features import FeatureRow
from .fingerprints import canonical_fingerprint


def export_feature_dataset(
    rows: Iterable[FeatureRow],
    *,
    output_dir: Path,
    dataset_version: str,
    code_sha: str,
    source_versions: dict[str, str] | None = None,
) -> DatasetManifest:
    materialized = sorted(rows, key=lambda row: (row.symbol, row.as_of, row.fingerprint))
    if not materialized:
        raise ValueError("cannot export an empty feature dataset")
    if any(row.feature_schema_version != FEATURE_SCHEMA_VERSION for row in materialized):
        raise ValueError("mixed feature schemas are not supported")

    output_dir.mkdir(parents=True, exist_ok=True)
    parquet_path = output_dir / "features.parquet"
    manifest_path = output_dir / "manifest.json"

    records = [
        {
            "feature_schema_version": row.feature_schema_version,
            "symbol": row.symbol,
            "as_of": row.as_of,
            "data_cutoff": row.data_cutoff,
            "features_json": json.dumps(row.features, sort_keys=True, separators=(",", ":")),
            "provenance_json": json.dumps(row.provenance, separators=(",", ":")),
            "row_fingerprint": row.fingerprint,
        }
        for row in materialized
    ]
    table = pa.Table.from_pylist(records)
    metadata = dict(table.schema.metadata or {})
    metadata[b"feature_schema_version"] = FEATURE_SCHEMA_VERSION.encode("utf-8")
    metadata[b"dataset_version"] = dataset_version.encode("utf-8")
    table = table.replace_schema_metadata(metadata)
    pq.write_table(table, parquet_path, compression="zstd")

    dataset_fingerprint = canonical_fingerprint(
        [row.model_dump(mode="json") for row in materialized]
    )
    manifest = DatasetManifest(
        dataset_version=dataset_version,
        created_at=datetime.now(UTC),
        data_cutoff=max(row.data_cutoff for row in materialized),
        code_sha=code_sha,
        source_versions=source_versions or {},
        row_count=len(materialized),
        fingerprint=dataset_fingerprint,
    )
    manifest_path.write_text(
        json.dumps(manifest.model_dump(mode="json"), sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest
