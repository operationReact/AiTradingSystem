"""Create short-lived, wholly synthetic trained model artifacts for CI loopback HTTP.

No broker, exchange, licensed feed, credentials, real customers or external data.
Uses the same deterministic historical fixtures as the actual model regression suite.
The calling workflow must delete all generated artifacts on exit.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path

from aitrading_intelligence.baseline import DEFAULT_BASELINE_FEATURES
from aitrading_intelligence.catboost_model import train_catboost_challenger
from aitrading_intelligence.lightgbm_model import train_lightgbm_challenger
from aitrading_intelligence.research_artifact_inference import VERSION

# Only the synthetic model test fixtures are used: no production dataset.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from test_catboost_model import _config as cat_config  # noqa: E402
from test_catboost_model import _examples as cat_examples  # noqa: E402
from test_lightgbm_model import _config as light_config  # noqa: E402
from test_lightgbm_model import _examples as light_examples  # noqa: E402

MODELS = (
    ("11111111-1111-4111-8111-111111111111", "CATBOOST"),
    ("33333333-3333-4333-8333-333333333333", "LIGHTGBM"),
)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python scripts/create_v4_http_smoke_artifacts.py OUTPUT_DIR")
    os.umask(0o077)
    destination = Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=True)
    if destination.is_symlink():
        raise SystemExit("Refusing a symlinked smoke directory")
    cat = train_catboost_challenger(cat_examples()[0], config=cat_config())
    light = train_lightgbm_challenger(light_examples()[0], config=light_config())
    members = []
    for (model_id, family), artifact in zip(
        MODELS, (cat, light), strict=True
    ):
        if artifact.feature_names != list(DEFAULT_BASELINE_FEATURES):
            raise SystemExit("Synthetic model feature schema no longer matches V60")
        filename = model_id + ".json"
        payload = artifact.model_dump_json().encode("utf-8")
        (destination / filename).write_bytes(payload)
        members.append(
            {
                "model_version_id": model_id,
                "family": family,
                "artifact_filename": filename,
                "artifact_sha256": hashlib.sha256(payload).hexdigest(),
                "artifact_fingerprint": artifact.model_fingerprint,
                "wait_below": 0.4,
                "enter_at_or_above": 0.6,
            }
        )
    (destination / "manifest.json").write_text(
        json.dumps({"contract_version": VERSION, "models": members}),
        encoding="utf-8",
    )
    # No invented source custody, economic metrics or broker authority.
    print("Prepared two synthetic allowlisted CatBoost/LightGBM research artifacts")


if __name__ == "__main__":
    main()
