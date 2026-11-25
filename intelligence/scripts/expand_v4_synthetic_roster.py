"""Extend a private V66 two-model fixture to ten synthetic registry identities.

Copies the SAME trained lab weights into distinct trusted allowlist IDs. This
tests Java orchestration load/concurrency only, not independent ML quality.
Never reads market data, broker accounts, arbitrary directories or remote URLs.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from aitrading_intelligence.research_artifact_inference import (
    InferenceManifest,
)


def extend(manifest_path: Path) -> None:
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise ValueError("Private CI model manifest must exist and not be a symlink")
    if manifest_path.stat().st_size > 64 * 1024:
        raise ValueError("Synthetic manifest is too large")
    current = InferenceManifest.model_validate_json(manifest_path.read_bytes())
    if len(current.models) != 2:
        raise ValueError("V70 requires the original exact two trained lab artifacts")
    by_family = {model.family: model for model in current.models}
    if set(by_family) != {"CATBOOST", "LIGHTGBM"}:
        raise ValueError("Both real trained model families are required")

    entries = [model.model_dump() for model in current.models]
    for value in range(3, 11):
        base = by_family["CATBOOST" if value % 2 else "LIGHTGBM"]
        entry = base.model_dump()
        entry["model_version_id"] = (
            f"{value:08x}-0000-4000-8000-{value:012x}"
        )
        entries.append(entry)
    expanded = InferenceManifest.model_validate({
        "contract_version": current.contract_version,
        "models": entries,
    })
    manifest_path.write_text(
        json.dumps(expanded.model_dump(mode="json"), sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )
    manifest_path.chmod(0o600)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: expand_v4_synthetic_roster.py <private-manifest.json>")
    extend(Path(sys.argv[1]))
