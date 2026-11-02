from __future__ import annotations

import json
from collections import Counter
from collections.abc import Iterable
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Literal

import pyarrow as pa
import pyarrow.parquet as pq
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .feature_v2 import FEATURE_SCHEMA_V2_VERSION, FeatureRowV2
from .fingerprints import canonical_fingerprint

FEATURE_DATASET_V2_SCHEMA_VERSION = "feature-dataset.v2"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
GitSha = Annotated[str, Field(pattern=r"^[a-f0-9]{40}$")]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


class FeatureDatasetV2Manifest(BaseModel):
    """Immutable manifest for a materialized point-in-time FeatureRow V2 dataset."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    dataset_schema_version: Literal["feature-dataset.v2"] = (
        FEATURE_DATASET_V2_SCHEMA_VERSION
    )
    feature_schema_version: Literal["features.market.v2"] = FEATURE_SCHEMA_V2_VERSION
    dataset_version: Annotated[str, Field(min_length=1, max_length=160)]
    created_at: datetime
    data_cutoff: datetime
    code_sha: GitSha
    row_count: Annotated[int, Field(gt=0)]
    dataset_fingerprint: Hash64
    source_versions: dict[str, str] = Field(default_factory=dict, max_length=64)
    universe_snapshot_count: Annotated[int, Field(gt=0)]
    market_context_snapshot_count: Annotated[int, Field(gt=0)]
    missing_feature_counts: dict[str, Annotated[int, Field(gt=0)]] = Field(
        default_factory=dict,
        max_length=512,
    )
    quality_flag_counts: dict[str, Annotated[int, Field(gt=0)]] = Field(
        default_factory=dict,
        max_length=512,
    )
    status: Literal["RESEARCH_DATASET_ONLY"] = "RESEARCH_DATASET_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("created_at", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @model_validator(mode="after")
    def creation_must_not_precede_data(self) -> FeatureDatasetV2Manifest:
        if self.created_at < self.data_cutoff:
            raise ValueError("created_at cannot precede the dataset data_cutoff")
        return self


def _ordered_rows(rows: Iterable[FeatureRowV2]) -> list[FeatureRowV2]:
    return sorted(
        rows,
        key=lambda row: (
            row.as_of,
            row.exchange,
            row.symbol,
            row.instrument_id,
            row.fingerprint,
        ),
    )


def _validate_rows(rows: list[FeatureRowV2]) -> None:
    if not rows:
        raise ValueError("cannot export an empty FeatureRow V2 dataset")
    if any(row.feature_schema_version != FEATURE_SCHEMA_V2_VERSION for row in rows):
        raise ValueError("mixed feature schemas are not supported")

    row_fingerprints = [row.fingerprint for row in rows]
    if len(set(row_fingerprints)) != len(row_fingerprints):
        raise ValueError("FeatureRow V2 fingerprints must be unique")

    base_fingerprints = [row.base_feature_row_fingerprint for row in rows]
    if len(set(base_fingerprints)) != len(base_fingerprints):
        raise ValueError("base FeatureRow V1 fingerprints must be unique")

    decision_keys = [(row.exchange, row.symbol, row.as_of) for row in rows]
    if len(set(decision_keys)) != len(decision_keys):
        raise ValueError("exchange/symbol/as_of decision keys must be unique")

    by_decision_time: dict[datetime, list[FeatureRowV2]] = {}
    for row in rows:
        if row.data_cutoff > row.as_of:
            raise ValueError("FeatureRow V2 data_cutoff cannot exceed as_of")
        by_decision_time.setdefault(row.as_of, []).append(row)

    for decision_rows in by_decision_time.values():
        universes = {row.universe_snapshot_fingerprint for row in decision_rows}
        contexts = {row.market_context_snapshot_fingerprint for row in decision_rows}
        if len(universes) != 1:
            raise ValueError(
                "all rows at one decision time must share one universe snapshot"
            )
        if len(contexts) != 1:
            raise ValueError(
                "all rows at one decision time must share one market-context snapshot"
            )


def feature_dataset_v2_fingerprint(rows: Iterable[FeatureRowV2]) -> str:
    """Return the canonical dataset fingerprint after enforcing dataset invariants."""

    materialized = _ordered_rows(rows)
    _validate_rows(materialized)
    return canonical_fingerprint(
        [row.model_dump(mode="json") for row in materialized]
    )


def export_feature_dataset_v2(
    rows: Iterable[FeatureRowV2],
    *,
    output_dir: Path,
    dataset_version: str,
    code_sha: str,
    source_versions: dict[str, str] | None = None,
    created_at: datetime | None = None,
) -> FeatureDatasetV2Manifest:
    """Write deterministic FeatureRow V2 research evidence to Parquet + manifest."""

    materialized = _ordered_rows(rows)
    _validate_rows(materialized)
    created = created_at or datetime.now(UTC)
    _require_aware(created)
    data_cutoff = max(row.data_cutoff for row in materialized)
    if created < data_cutoff:
        raise ValueError("created_at cannot precede the dataset data_cutoff")

    dataset_fingerprint = canonical_fingerprint(
        [row.model_dump(mode="json") for row in materialized]
    )
    missing_feature_counts = Counter(
        feature for row in materialized for feature in row.missing_features
    )
    quality_flag_counts = Counter(
        flag for row in materialized for flag in row.quality_flags
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    parquet_path = output_dir / "features_v2.parquet"
    manifest_path = output_dir / "manifest.json"

    records = [
        {
            "feature_schema_version": row.feature_schema_version,
            "instrument_id": row.instrument_id,
            "exchange": row.exchange,
            "symbol": row.symbol,
            "sector": row.sector,
            "index_memberships_json": json.dumps(
                row.index_memberships,
                separators=(",", ":"),
            ),
            "as_of": row.as_of,
            "data_cutoff": row.data_cutoff,
            "base_feature_row_fingerprint": row.base_feature_row_fingerprint,
            "universe_snapshot_fingerprint": row.universe_snapshot_fingerprint,
            "market_context_snapshot_fingerprint": (
                row.market_context_snapshot_fingerprint
            ),
            "features_json": json.dumps(
                row.features,
                sort_keys=True,
                separators=(",", ":"),
            ),
            "missing_features_json": json.dumps(
                row.missing_features,
                separators=(",", ":"),
            ),
            "quality_flags_json": json.dumps(
                row.quality_flags,
                separators=(",", ":"),
            ),
            "provenance_json": json.dumps(
                row.provenance,
                separators=(",", ":"),
            ),
            "row_fingerprint": row.fingerprint,
            "status": row.status,
            "can_authorize_capital": row.can_authorize_capital,
        }
        for row in materialized
    ]
    table = pa.Table.from_pylist(records)
    metadata = dict(table.schema.metadata or {})
    metadata[b"dataset_schema_version"] = FEATURE_DATASET_V2_SCHEMA_VERSION.encode(
        "utf-8"
    )
    metadata[b"feature_schema_version"] = FEATURE_SCHEMA_V2_VERSION.encode("utf-8")
    metadata[b"dataset_version"] = dataset_version.encode("utf-8")
    metadata[b"dataset_fingerprint"] = dataset_fingerprint.encode("utf-8")
    metadata[b"code_sha"] = code_sha.encode("utf-8")
    table = table.replace_schema_metadata(metadata)
    pq.write_table(table, parquet_path, compression="zstd")

    manifest = FeatureDatasetV2Manifest(
        dataset_version=dataset_version,
        created_at=created,
        data_cutoff=data_cutoff,
        code_sha=code_sha,
        row_count=len(materialized),
        dataset_fingerprint=dataset_fingerprint,
        source_versions=dict(sorted((source_versions or {}).items())),
        universe_snapshot_count=len(
            {row.universe_snapshot_fingerprint for row in materialized}
        ),
        market_context_snapshot_count=len(
            {row.market_context_snapshot_fingerprint for row in materialized}
        ),
        missing_feature_counts=dict(sorted(missing_feature_counts.items())),
        quality_flag_counts=dict(sorted(quality_flag_counts.items())),
    )
    manifest_path.write_text(
        json.dumps(manifest.model_dump(mode="json"), sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest
