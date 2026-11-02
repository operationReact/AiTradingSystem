from __future__ import annotations

import json
from collections.abc import Iterable
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Literal

import pyarrow as pa
import pyarrow.parquet as pq
from pydantic import BaseModel, ConfigDict, Field

from .fingerprints import canonical_fingerprint
from .outcome_learning import LEARNING_RECORD_VERSION, TradeLearningRecord

LEARNING_DATASET_SCHEMA_VERSION = "trade-learning-dataset.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class LearningDatasetManifest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    schema_version: Literal["trade-learning-dataset.v1"] = LEARNING_DATASET_SCHEMA_VERSION
    dataset_version: Annotated[str, Field(min_length=1, max_length=160)]
    created_at: datetime
    data_cutoff: datetime
    code_sha: Annotated[str, Field(min_length=7, max_length=64)]
    source_record_version: Literal["trade-learning-record.v1"] = LEARNING_RECORD_VERSION
    row_count: Annotated[int, Field(gt=0)]
    dataset_fingerprint: Hash64
    source_versions: dict[str, str]
    status: Literal["RESEARCH_DATASET_ONLY"] = "RESEARCH_DATASET_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def export_learning_dataset(
    records: Iterable[TradeLearningRecord],
    *,
    output_dir: Path,
    dataset_version: str,
    code_sha: str,
    source_versions: dict[str, str] | None = None,
) -> LearningDatasetManifest:
    materialized = sorted(
        records,
        key=lambda item: (
            item.dataset_eligible_at,
            item.trade_id,
            item.learning_fingerprint,
        ),
    )
    if not materialized:
        raise ValueError("cannot export an empty learning dataset")
    if any(item.record_version != LEARNING_RECORD_VERSION for item in materialized):
        raise ValueError("mixed learning-record versions are not supported")

    trade_ids = [item.trade_id for item in materialized]
    if len(set(trade_ids)) != len(trade_ids):
        raise ValueError("learning dataset requires unique trade_id values")
    fingerprints = [item.learning_fingerprint for item in materialized]
    if len(set(fingerprints)) != len(fingerprints):
        raise ValueError("learning dataset requires unique learning fingerprints")

    output_dir.mkdir(parents=True, exist_ok=True)
    parquet_path = output_dir / "trade_learning.parquet"
    manifest_path = output_dir / "manifest.json"

    rows = []
    for item in materialized:
        rows.append(
            {
                "record_version": item.record_version,
                "trade_id": item.trade_id,
                "symbol": item.symbol,
                "dataset_eligible_at": item.dataset_eligible_at,
                "realized_pnl": item.outcome.realized_pnl,
                "realized_return": item.outcome.realized_return,
                "mae_return": item.outcome.mae_return,
                "mfe_return": item.outcome.mfe_return,
                "slippage_bps": item.outcome.slippage_bps,
                "exit_reason": item.outcome.exit_reason,
                "market_regime": item.outcome.market_regime,
                "model_name": item.prediction.model_name,
                "model_version": item.prediction.model_version,
                "model_artifact_fingerprint": item.prediction.model_artifact_fingerprint,
                "feature_schema_version": item.prediction.feature_schema_version,
                "opportunity_fingerprint": item.prediction.opportunity_fingerprint,
                "risk_decision_fingerprint": item.risk.decision_fingerprint,
                "execution_fingerprint": item.execution.execution_fingerprint,
                "gemini_evidence_fingerprint": (
                    item.gemini.evidence_fingerprint if item.gemini is not None else None
                ),
                "learning_fingerprint": item.learning_fingerprint,
                "record_json": json.dumps(
                    item.model_dump(mode="json"),
                    sort_keys=True,
                    separators=(",", ":"),
                ),
            }
        )

    table = pa.Table.from_pylist(rows)
    metadata = dict(table.schema.metadata or {})
    metadata[b"learning_dataset_schema_version"] = LEARNING_DATASET_SCHEMA_VERSION.encode(
        "utf-8"
    )
    metadata[b"dataset_version"] = dataset_version.encode("utf-8")
    table = table.replace_schema_metadata(metadata)
    pq.write_table(table, parquet_path, compression="zstd")

    dataset_fingerprint = canonical_fingerprint(
        [item.model_dump(mode="json") for item in materialized]
    )
    manifest = LearningDatasetManifest(
        dataset_version=dataset_version,
        created_at=datetime.now(UTC),
        data_cutoff=max(item.dataset_eligible_at for item in materialized),
        code_sha=code_sha,
        row_count=len(materialized),
        dataset_fingerprint=dataset_fingerprint,
        source_versions=source_versions or {},
    )
    manifest_path.write_text(
        json.dumps(manifest.model_dump(mode="json"), sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest
