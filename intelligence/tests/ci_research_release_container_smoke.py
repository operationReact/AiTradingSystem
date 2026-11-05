#!/usr/bin/env python3
"""V90 disposable-only research worker container fixture and local HTTP probe.

Never uses production models, provider data, user broker accounts, AWS credentials
or Docker registries. All trained artifacts live in a private runner temp folder
and are destroyed after the CI job. No release, deployment or capital authority.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

SHA40 = re.compile(r"[a-f0-9]{40}\Z")
CONTAINER_ID = re.compile(r"[a-f0-9]{12,64}\Z")
PROBE_SUCCESS = "V90_SYNTHETIC_LOCAL_ROSTER_AND_INFERENCE_ONLY"

# This script runs INSIDE an isolated, non-root Docker container with no network.
# Readiness and inference calls go only to its own loopback. Never send tokens
# to a host/bridge/public endpoint and never print model weights or the token.
LOCAL_PROBE = '''
import json
import os
import time
from datetime import timedelta
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from aitrading_intelligence.research_artifact_inference import (
    BoundedInferenceResponse, VerifiedWorkerRoster,
    inspect_worker_roster, load_allowlisted_model,
)

BASE = "http://127.0.0.1:8090"
TOKEN = os.environ["AI_RESEARCH_WORKER_TOKEN"]
MANIFEST = Path("/opt/research/manifest.json")

def call(path, *, token=None, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"} if payload is not None else {}
    if token is not None:
        headers["X-Research-Worker-Token"] = token
    req = Request(BASE + path, data=data, headers=headers,
                  method="POST" if data is not None else "GET")
    try:
        with urlopen(req, timeout=3) as response:
            return response.status, response.read(65536)
    except HTTPError as error:
        return error.code, b""

# Wait only for this container's own loopback health, no exposed host port.
for attempt in range(25):
    try:
        status, _ = call("/health")
        if status == 200:
            break
    except (URLError, TimeoutError):
        pass
    time.sleep(0.5)
else:
    raise SystemExit(3)

assert call("/v1/research/artifact-readiness")[0] == 401
assert call("/v1/research/artifact-readiness", token="wrong")[0] == 401
status, raw = call("/v1/research/artifact-readiness", token=TOKEN)
assert status == 200
received = VerifiedWorkerRoster.model_validate_json(raw)
expected = inspect_worker_roster(MANIFEST)
assert received == expected
assert received.local_artifacts_validated is True
assert received.independently_verified_deployment is False
assert received.source_rights_verified is False
assert received.paper_shadow_soak_accepted is False
assert received.broker_reconciliation_accepted is False
assert received.can_authorize_capital is False
assert {m.model_family for m in received.models} == {"CATBOOST", "LIGHTGBM"}

for model in received.models:
    member, artifact = load_allowlisted_model(MANIFEST, model.model_version_id)
    at = artifact.training_as_of + timedelta(minutes=10)
    snapshot = {
        "model_version_id": model.model_version_id,
        "case_key": "synthetic-research-only-001",
        "symbol": "RELIANCE",
        "source_input_fingerprint": "a" * 64,
        "feature_as_of": at.isoformat(),
        "latest_feature_available_at": (at - timedelta(seconds=2)).isoformat(),
        "features": {name: 0.1 for name in artifact.feature_names},
    }
    assert call("/v1/research/artifact-inference", payload=snapshot)[0] == 401
    assert call("/v1/research/artifact-inference", token="wrong", payload=snapshot)[0] == 401
    status, response_bytes = call(
        "/v1/research/artifact-inference", token=TOKEN, payload=snapshot,
    )
    assert status == 200
    response = BoundedInferenceResponse.model_validate_json(response_bytes)
    assert response.model_version_id == member.model_version_id
    assert response.model_family == member.family
    assert response.model_artifact_fingerprint == artifact.model_fingerprint
    assert response.status == "ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY"
    assert response.model_promotion_allowed is False
    assert response.can_authorize_capital is False

print("V90_SYNTHETIC_LOCAL_ROSTER_AND_INFERENCE_ONLY")
'''


def prepare(repo: Path, sha: str, destination: Path) -> None:
    from test_research_artifact_inference import (
        MODEL_ID,
        SHADOW_ID,
        _create_manifest,
        cat_config,
        cat_examples,
        light_config,
        light_examples,
        train_catboost_challenger,
        train_lightgbm_challenger,
    )
    from aitrading_intelligence.research_image_context import prepare_context
    from aitrading_intelligence.research_release_bundle import stage_bundle

    if not SHA40.fullmatch(sha):
        raise ValueError("invalid exact repository commit")
    repo = repo.resolve()
    destination = destination.absolute()
    if destination == repo or repo in destination.parents:
        raise ValueError("temporary fixture must be outside repository")
    if destination.exists() or destination.is_symlink() or not destination.parent.is_dir():
        raise ValueError("fixture location unavailable or already exists")
    destination.mkdir(mode=0o700)
    source = destination / "generated-synthetic-models"
    source.mkdir(mode=0o700)
    cat = train_catboost_challenger(cat_examples()[0], config=cat_config())
    light = train_lightgbm_challenger(light_examples()[0], config=light_config())
    manifest = _create_manifest(source, [
        (MODEL_ID, "CATBOOST", cat),
        (SHADOW_ID, "LIGHTGBM", light),
    ])
    bundle = destination / "synthetic-bundle"
    stage_bundle(manifest, bundle, sha)
    context = destination / "private-build-context"
    prepare_context(repo, bundle, context, sha)


def run_probe(container_id: str) -> None:
    if not CONTAINER_ID.fullmatch(container_id):
        raise ValueError("expected an exact local disposable Docker container id")
    result = subprocess.run(
        ["docker", "exec", "-i", container_id, "python", "-"],
        input=LOCAL_PROBE,
        text=True,
        capture_output=True,
        timeout=90,
        check=False,
    )
    if result.returncode != 0 or result.stdout.strip() != PROBE_SUCCESS:
        raise ValueError("in-container local HTTP research probe did not pass")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="V90 synthetic container smoke only")
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("prepare")
    stage.add_argument("--repository-root", required=True, type=Path)
    stage.add_argument("--code-sha", required=True)
    stage.add_argument("--destination", required=True, type=Path)
    probe = commands.add_parser("probe")
    probe.add_argument("--container-id", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            prepare(args.repository_root, args.code_sha, args.destination)
        else:
            run_probe(args.container_id)
    except Exception:
        # No private runner temp paths, raw container stderr, tokens or model
        # training data in CI logs, including if runtime probe fails.
        print("V90 disposable research image smoke BLOCKED", file=sys.stderr)
        return 2
    print("V90 synthetic model fixture/probe passed; release and capital NOT authorized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
