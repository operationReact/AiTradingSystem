from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from test_catboost_model import _config as catboost_config
from test_lightgbm_model import _config as lightgbm_config
from test_lightgbm_model import _examples

from aitrading_intelligence.app import app
from aitrading_intelligence.catboost_model import train_catboost_challenger
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.frozen_research_inference import (
    FrozenResearchRegistry,
    ResearchArtifactManifest,
    ResearchInferenceEvidence,
    ResearchInferenceRequest,
    configured_research_registry,
)
from aitrading_intelligence.lightgbm_model import train_lightgbm_challenger

TOKEN = "research-only-internal-token-2026-verified"
A = "a" * 64


def _json_row(*, as_of: datetime | None = None, changed: dict | None = None) -> dict:
    moment = as_of or datetime(2026, 11, 3, 9, tzinfo=UTC)
    values = {
        "market.close": 100.5,
        "market.return_1": 0.01,
        "market.range_pct": 0.014,
        "market.volume_change_1": -0.2,
        "event.AI_NEWS_EVIDENCE.direction_score": 0.7,
        "event.AI_NEWS_EVIDENCE.confidence": 0.8,
        "event.AI_NEWS_EVIDENCE.materiality": 0.75,
    }
    if changed:
        values.update(changed)
    payload = {
        "feature_schema_version": "features.market.v1",
        "symbol": "RELIANCE",
        "as_of": moment.isoformat(),
        "data_cutoff": (moment - timedelta(seconds=10)).isoformat(),
        "features": values,
        "provenance": ["synthetic:test:2026-11-03"],
    }
    payload["fingerprint"] = canonical_fingerprint(payload)
    return payload


@pytest.fixture(scope="module")
def frozen_registry(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, dict]:
    root = tmp_path_factory.mktemp("research-model-serving")
    samples, _ = _examples()
    cat = train_catboost_challenger(samples, config=catboost_config())
    light = train_lightgbm_challenger(samples, config=lightgbm_config())
    entries = []
    for identifier, family, artifact in (
        ("cat-alpha-001", "CATBOOST", cat),
        ("light-alpha-001", "LIGHTGBM", light),
    ):
        path = root / f"{identifier}.json"
        path.write_text(artifact.model_dump_json(), encoding="utf-8")
        entries.append(
            {
                "model_id": identifier,
                "family": family,
                "artifact_path": str(path),
                "artifact_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "expected_model_fingerprint": artifact.model_fingerprint,
            }
        )
    manifest = root / "manifest.json"
    manifest.write_text(json.dumps({"contract_version": "v4-frozen-research-model-inference.v1",
                                    "artifacts": entries}), encoding="utf-8")
    return manifest, {"cat": cat, "light": light, "entries": entries}


def _setup(monkeypatch: pytest.MonkeyPatch, registry: Path) -> None:
    monkeypatch.setenv("APP_RESEARCH_ARTIFACT_MANIFEST", str(registry))
    monkeypatch.setenv("APP_RESEARCH_INFERENCE_TOKEN", TOKEN)
    configured_research_registry.cache_clear()


def _request(model_id: str, target: str, row: dict | None = None) -> dict:
    return {
        "model_id": model_id,
        "target_spec_fingerprint": target,
        "row": row or _json_row(),
    }


def test_real_trained_catboost_and_lightgbm_checkpoint_inference(
    frozen_registry, monkeypatch
) -> None:
    manifest, artifacts = frozen_registry
    _setup(monkeypatch, manifest)
    client = TestClient(app)
    for model_id, artifact in (
        ("cat-alpha-001", artifacts["cat"]),
        ("light-alpha-001", artifacts["light"]),
    ):
        response = client.post(
            "/v1/research/model-inference",
            json=_request(model_id, artifact.target_spec_fingerprint),
            headers={"X-Research-Service-Token": TOKEN},
        )
        assert response.status_code == 200, response.text
        data = ResearchInferenceEvidence.model_validate(response.json())
        assert data.model_id == model_id
        assert data.model_fingerprint == artifact.model_fingerprint
        assert data.target_spec_fingerprint == artifact.target_spec_fingerprint
        assert 0 <= data.probability_up <= 1
        assert data.action in ("ENTER", "WAIT", "ABSTAIN")
        assert data.status == "UNATTESTED_RESEARCH_INFERENCE_ONLY"
        assert data.trained_model_execution is True
        assert data.source_provider_verified is False
        assert data.external_timestamp_witness_verified is False
        assert data.model_promotion_allowed is False
        assert data.actual_paper_fill_verified is False
        assert data.live_order_execution_enabled is False
        assert data.can_authorize_capital is False


def test_disabled_endpoint_and_token_must_fail_closed(
    frozen_registry, monkeypatch
) -> None:
    manifest, artifacts = frozen_registry
    monkeypatch.delenv("APP_RESEARCH_ARTIFACT_MANIFEST", raising=False)
    monkeypatch.delenv("APP_RESEARCH_INFERENCE_TOKEN", raising=False)
    configured_research_registry.cache_clear()
    client = TestClient(app)
    body = _request("light-alpha-001", artifacts["light"].target_spec_fingerprint)
    assert client.post("/v1/research/model-inference", json=body).status_code == 503

    _setup(monkeypatch, manifest)
    assert client.post("/v1/research/model-inference", json=body).status_code == 401
    assert client.post("/v1/research/model-inference", json=body, headers={
        "X-Research-Service-Token": "wrong"
    }).status_code == 401


def test_unregistered_model_wrong_target_and_missing_features_rejected(
    frozen_registry, monkeypatch
) -> None:
    manifest, artifacts = frozen_registry
    _setup(monkeypatch, manifest)
    client = TestClient(app)
    headers = {"X-Research-Service-Token": TOKEN}
    assert client.post(
        "/v1/research/model-inference",
        json=_request("unregistered-foo", artifacts["light"].target_spec_fingerprint),
        headers=headers,
    ).status_code == 422
    assert client.post(
        "/v1/research/model-inference",
        json=_request("light-alpha-001", A),
        headers=headers,
    ).status_code == 422
    row = _json_row()
    row["features"].pop("market.close")
    # A stale hash is rejected before model scoring.
    assert client.post(
        "/v1/research/model-inference",
        json=_request("light-alpha-001", artifacts["light"].target_spec_fingerprint, row),
        headers=headers,
    ).status_code == 422
    row = _json_row()
    row["features"].pop("market.close")
    row["fingerprint"] = canonical_fingerprint({
        key: value for key, value in row.items() if key != "fingerprint"
    })
    response = client.post(
        "/v1/research/model-inference",
        json=_request("light-alpha-001", artifacts["light"].target_spec_fingerprint, row),
        headers=headers,
    )
    assert response.status_code == 422
    assert "implicit zero fill" in response.text


def test_no_future_features_or_forged_row_hash_or_training_overlap(
    frozen_registry, monkeypatch
) -> None:
    manifest, artifacts = frozen_registry
    _setup(monkeypatch, manifest)
    client = TestClient(app)
    headers = {"X-Research-Service-Token": TOKEN}
    row = _json_row(changed={"market.forward_return_bps": 120.0})
    response = client.post(
        "/v1/research/model-inference",
        json=_request("light-alpha-001", artifacts["light"].target_spec_fingerprint, row),
        headers=headers,
    )
    assert response.status_code == 422
    row = _json_row()
    row["fingerprint"] = A
    assert client.post(
        "/v1/research/model-inference",
        json=_request("light-alpha-001", artifacts["light"].target_spec_fingerprint, row),
        headers=headers,
    ).status_code == 422
    as_of = artifacts["light"].training_as_of - timedelta(minutes=1)
    row = _json_row(as_of=as_of)
    assert client.post(
        "/v1/research/model-inference",
        json=_request("light-alpha-001", artifacts["light"].target_spec_fingerprint, row),
        headers=headers,
    ).status_code == 422


def test_artifact_hash_and_registry_contract_prevent_checkpoint_substitution(
    frozen_registry, tmp_path, monkeypatch
) -> None:
    manifest, artifacts = frozen_registry
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    original = payload["artifacts"][0]
    entry = dict(original)
    entry["artifact_sha256"] = "f" * 64
    malicious = tmp_path / "mismatch.json"
    malicious.write_text(json.dumps({
        "contract_version": "v4-frozen-research-model-inference.v1",
        "artifacts": [entry],
    }), encoding="utf-8")
    with pytest.raises(ValueError, match="frozen registry digest"):
        FrozenResearchRegistry.from_file(malicious)

    duplicates = tmp_path / "duplicate.json"
    duplicates.write_text(json.dumps({
        "contract_version": "v4-frozen-research-model-inference.v1",
        "artifacts": [original, original],
    }), encoding="utf-8")
    with pytest.raises(ValidationError, match="unique"):
        FrozenResearchRegistry.from_file(duplicates)

    bad = dict(original)
    bad["artifact_path"] = "relative-path.json"
    with pytest.raises(ValidationError, match="absolute"):
        ResearchArtifactManifest.model_validate({
            "contract_version": "v4-frozen-research-model-inference.v1",
            "artifacts": [bad],
        })


def test_frozen_inference_report_cannot_claim_live_capital_approval(
    frozen_registry, monkeypatch
) -> None:
    manifest, artifacts = frozen_registry
    _setup(monkeypatch, manifest)
    report = FrozenResearchRegistry.from_file(manifest).infer(
        ResearchInferenceRequest.model_validate(
            _request("light-alpha-001", artifacts["light"].target_spec_fingerprint)
        )
    )
    payload = report.model_dump(mode="json")
    payload["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        ResearchInferenceEvidence.model_validate(payload)
    payload = report.model_dump(mode="json")
    payload["probability_up"] = 1 - payload["probability_up"]
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        ResearchInferenceEvidence.model_validate(payload)


def test_request_body_must_not_upload_model_bytes(frozen_registry, monkeypatch) -> None:
    manifest, artifacts = frozen_registry
    _setup(monkeypatch, manifest)
    client = TestClient(app)
    body = _request("light-alpha-001", artifacts["light"].target_spec_fingerprint)
    body["model_artifact"] = {"payload": "fake"}
    assert client.post("/v1/research/model-inference", json=body, headers={
        "X-Research-Service-Token": TOKEN
    }).status_code == 422
