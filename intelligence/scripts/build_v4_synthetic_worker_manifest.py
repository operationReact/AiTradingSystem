"""Build two ephemeral *synthetic* trained artifacts for a local HTTP smoke.

Never accepts broker credentials, market feeds, remote model URIs or a caller
supplied manifest. Output is intended for a private CI temp directory only.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

from aitrading_intelligence.baseline import DEFAULT_BASELINE_FEATURES
from aitrading_intelligence.catboost_model import train_catboost_challenger
from aitrading_intelligence.lightgbm_model import train_lightgbm_challenger

# Existing deterministic 65-candle laboratory fixtures, NOT licensed NSE data.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from test_catboost_model import _config as cat_config  # noqa: E402
from test_catboost_model import _examples as cat_examples  # noqa: E402
from test_lightgbm_model import _config as light_config  # noqa: E402
from test_lightgbm_model import _examples as light_examples  # noqa: E402

MODELS = (
    ("11111111-1111-4111-8111-111111111111", "CATBOOST"),
    ("22222222-2222-4222-8222-222222222222", "LIGHTGBM"),
)


def build(output: Path) -> Path:
    if not output.is_dir() or output.is_symlink():
        raise ValueError("CI artifact directory must exist and not be a symlink")
    cat = train_catboost_challenger(cat_examples()[0], config=cat_config())
    light = train_lightgbm_challenger(light_examples()[0], config=light_config())
    records = []
    for (model_id, family), artifact in zip(MODELS, (cat, light), strict=True):
        if tuple(artifact.feature_names) != DEFAULT_BASELINE_FEATURES:
            raise ValueError("Lab artifact does not match the six Java V60 model features")
        filename = model_id + ".json"
        raw = artifact.model_dump_json().encode("utf-8")
        target = output / filename
        target.write_bytes(raw)
        target.chmod(0o600)
        records.append(
            {
                "model_version_id": model_id,
                "family": family,
                "artifact_filename": filename,
                "artifact_sha256": hashlib.sha256(raw).hexdigest(),
                "artifact_fingerprint": artifact.model_fingerprint,
                "wait_below": 0.4,
                "enter_at_or_above": 0.6,
            }
        )
    manifest = output / "manifest.json"
    manifest.write_text(
        json.dumps(
            {"contract_version": "bounded-artifact-research-inference.v1", "models": records},
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    manifest.chmod(0o600)
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: build_v4_synthetic_worker_manifest.py <private-temp-dir>")
    build(Path(sys.argv[1]).resolve())
