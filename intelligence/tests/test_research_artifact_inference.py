from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from aitrading_intelligence.app import app
from aitrading_intelligence.research_artifact_inference import (
    BoundedInferenceRequest,
    BoundedInferenceResponse,
    EnabledModel,
    InferenceUnavailable,
    infer_from_trained_artifact,
    load_allowlisted_model,
    score_allowlisted_request,
)
from test_catboost_model import (
    _config as cat_config,
    _examples as cat_examples,
    _series as cat_series,
)
from test_lightgbm_model import (
    _config as light_config,
    _examples as light_examples,
)
from aitrading_intelligence.catboost_model import train_catboost_challenger
from aitrading_intelligence.lightgbm_model import train_lightgbm_challenger


MODEL_ID = "00000000-0000-4000-8000-000000000001"
SHADOW_ID = "00000000-0000-4000-8000-000000000002"
SOURCE_FINGERPRINT = "a" * 64
TOKEN = "a-strong-research-worker-token-" + "7" * 32


@pytest.fixture(scope="module")
def trained_artifacts():
    cat = train_catboost_challenger(cat_examples()[0], config=cat_config())
    light = train_lightgbm_challenger(light_examples()[0], config=light_config())
    return cat, light


def _request(artifact, model_id=MODEL_ID, **updates):
    # Fully out of sample relative to training and after available market/news inputs.
    at = artifact.training_as_of + timedelta(minutes=10)
    payload = {
        "model_version_id": model_id,
        "case_key": "research-candle-001",
        "symbol": "RELIANCE",
        "source_input_fingerprint": SOURCE_FINGERPRINT,
        "feature_as_of": at,
        "latest_feature_available_at": at - timedelta(seconds=2),
        "features": {name: float(cat_series()[-1].features[name])
                     for name in artifact.feature_names},
    }
    payload.update(updates)
    return BoundedInferenceRequest.model_validate(payload)


def _create_manifest(tmp_path, artifacts):
    from aitrading_intelligence.research_artifact_inference import VERSION

    entries = []
    for model_id, family, artifact in artifacts:
        name = model_id + ".json"
        raw = artifact.model_dump_json().encode("utf-8")
        (tmp_path / name).write_bytes(raw)
        entries.append({
            "model_version_id": model_id,
            "family": family,
            "artifact_filename": name,
            "artifact_sha256": hashlib.sha256(raw).hexdigest(),
            "artifact_fingerprint": artifact.model_fingerprint,
            "wait_below": 0.4,
            "enter_at_or_above": 0.6,
        })
    manifest = tmp_path / "manifest.json"
    manifest.write_text(
        json.dumps({"contract_version": VERSION, "models": entries}),
        encoding="utf-8",
    )
    return manifest


@pytest.mark.parametrize("which", [0, 1])
def test_real_serialized_weights_score_and_replay(trained_artifacts, tmp_path, which):
    cat, light = trained_artifacts
    artifact = [cat, light][which]
    family = ["CATBOOST", "LIGHTGBM"][which]
    model_id = [MODEL_ID, SHADOW_ID][which]
    manifest = _create_manifest(tmp_path, [(model_id, family, artifact)])
    allowlisted, loaded = load_allowlisted_model(manifest, model_id)
    assert loaded.model_fingerprint == artifact.model_fingerprint
    request = _request(loaded, model_id)
    at = request.feature_as_of + timedelta(seconds=10)
    result = infer_from_trained_artifact(request, allowlisted, loaded, evaluated_at=at)
    assert result == score_allowlisted_request(request, manifest_path=manifest, evaluated_at=at)
    assert 0 <= result.probability <= 1
    assert result.action in {"ENTER", "WAIT", "ABSTAIN"}
    assert result.status == "ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY"
    assert result.model_family == family
    assert result.model_artifact_fingerprint == artifact.model_fingerprint
    assert result.source_input_fingerprint == SOURCE_FINGERPRINT
    assert result.independent_input_custody_verified is False
    assert result.model_promotion_allowed is False
    assert result.can_authorize_capital is False
    assert BoundedInferenceResponse.model_validate_json(result.model_dump_json()) == result
    assert set(request.model_fields) == {
        "model_version_id", "case_key", "symbol", "source_input_fingerprint",
        "feature_as_of", "latest_feature_available_at", "features",
    }
    assert not {"forward_return", "label", "market_outcome"}.intersection(
        result.model_dump()
    )


def test_frozen_model_policy_changes_action_not_probability(trained_artifacts, tmp_path):
    cat, _ = trained_artifacts
    manifest = _create_manifest(tmp_path, [(MODEL_ID, "CATBOOST", cat)])
    member, loaded = load_allowlisted_model(manifest, MODEL_ID)
    request = _request(loaded)
    at = request.feature_as_of + timedelta(minutes=1)
    first = infer_from_trained_artifact(request, member, loaded, evaluated_at=at)
    changed = member.model_copy(update={
        "wait_below": 0.01,
        "enter_at_or_above": 0.99,
    })
    second = infer_from_trained_artifact(request, changed, loaded, evaluated_at=at)
    assert second.probability == first.probability
    assert second.action in {"WAIT", "ABSTAIN", "ENTER"}
    assert first.can_authorize_capital is False
    with pytest.raises(ValidationError, match="abstain band"):
        EnabledModel.model_validate({
            **member.model_dump(), "wait_below": 0.8,
            "enter_at_or_above": 0.6,
        })


def test_strict_predecision_feature_schema_causality_and_bounded_values(trained_artifacts):
    cat, _ = trained_artifacts
    request = _request(cat)
    with pytest.raises(ValidationError, match="future source"):
        _request(
            cat, latest_feature_available_at=request.feature_as_of + timedelta(seconds=1)
        )
    with pytest.raises(ValidationError, match="finite and bounded"):
        _request(cat, features={**request.features, "market.close": float("nan")})
    with pytest.raises(ValidationError, match="booleans"):
        _request(cat, features={**request.features, "market.close": True})
    with pytest.raises(ValidationError):
        _request(cat, forward_return_bps=100)
    with pytest.raises(ValidationError, match="timezone"):
        _request(cat, feature_as_of=datetime(2026, 3, 4))
    member = EnabledModel(
        model_version_id=MODEL_ID,
        family="CATBOOST",
        artifact_filename="artifact.json",
        artifact_sha256="b" * 64,
        artifact_fingerprint=cat.model_fingerprint,
    )
    with pytest.raises(ValueError, match="exact trained feature schema"):
        infer_from_trained_artifact(
            _request(cat, features={"market.close": 100.0}),
            member, cat, evaluated_at=request.feature_as_of + timedelta(seconds=2),
        )
    with pytest.raises(ValueError, match="before its trained artifact"):
        infer_from_trained_artifact(
            _request(cat, feature_as_of=cat.training_as_of),
            member, cat, evaluated_at=request.feature_as_of + timedelta(seconds=2),
        )


def test_manifest_immutable_integrity_and_path_traversal(trained_artifacts, tmp_path):
    cat, _ = trained_artifacts
    manifest = _create_manifest(tmp_path, [(MODEL_ID, "CATBOOST", cat)])
    model_file = tmp_path / (MODEL_ID + ".json")
    model_file.write_bytes(model_file.read_bytes() + b" ")
    with pytest.raises(InferenceUnavailable, match="hash mismatch"):
        load_allowlisted_model(manifest, MODEL_ID)
    with pytest.raises(InferenceUnavailable, match="not allowlisted"):
        load_allowlisted_model(manifest, SHADOW_ID)
    manifest.write_text(json.dumps({
        "contract_version": "bounded-artifact-research-inference.v1",
        "models": [{
            "model_version_id": MODEL_ID,
            "family": "CATBOOST",
            "artifact_filename": "../secrets.json",
            "artifact_sha256": "a" * 64,
            "artifact_fingerprint": cat.model_fingerprint,
        }],
    }), encoding="utf-8")
    with pytest.raises(InferenceUnavailable, match="invalid research manifest"):
        load_allowlisted_model(manifest, MODEL_ID)
    manifest.unlink()
    manifest.symlink_to(tmp_path / (MODEL_ID + ".json"))
    with pytest.raises(InferenceUnavailable, match="manifest unavailable"):
        load_allowlisted_model(manifest, MODEL_ID)


def test_research_endpoint_disabled_unless_explicitly_secured(
    monkeypatch, trained_artifacts, tmp_path
):
    cat, _ = trained_artifacts
    manifest = _create_manifest(tmp_path, [(MODEL_ID, "CATBOOST", cat)])
    request = _request(cat)
    # A newly trained artifact from the historical fixture is before current time.
    with TestClient(app) as client:
        monkeypatch.delenv("AI_RESEARCH_WORKER_TOKEN", raising=False)
        monkeypatch.delenv("AI_RESEARCH_MODEL_MANIFEST", raising=False)
        assert client.post(
            "/v1/research/artifact-inference", json=request.model_dump(mode="json"),
        ).status_code == 503

        monkeypatch.setenv("AI_RESEARCH_WORKER_TOKEN", TOKEN)
        monkeypatch.setenv("AI_RESEARCH_MODEL_MANIFEST", str(manifest))
        denied = client.post(
            "/v1/research/artifact-inference", json=request.model_dump(mode="json"),
            headers={"X-Research-Worker-Token": "wrong"},
        )
        assert denied.status_code == 401
        valid = client.post(
            "/v1/research/artifact-inference", json=request.model_dump(mode="json"),
            headers={"X-Research-Worker-Token": TOKEN},
        )
        assert valid.status_code == 200, valid.text
        response = BoundedInferenceResponse.model_validate(valid.json())
        assert response.can_authorize_capital is False
        assert response.model_artifact_fingerprint == cat.model_fingerprint
        assert "forward_return" not in valid.text
        wrong_model = client.post(
            "/v1/research/artifact-inference",
            json=_request(cat, model_id=SHADOW_ID).model_dump(mode="json"),
            headers={"X-Research-Worker-Token": TOKEN},
        )
        assert wrong_model.status_code == 503
        forged = valid.json()
        forged["can_authorize_capital"] = True
        with pytest.raises(ValidationError):
            BoundedInferenceResponse.model_validate(forged)


def test_internal_research_response_rejects_forged_fingerprint(
    trained_artifacts, tmp_path
):
    cat, _ = trained_artifacts
    manifest = _create_manifest(tmp_path, [(MODEL_ID, "CATBOOST", cat)])
    request = _request(cat)
    report = score_allowlisted_request(
        request, manifest_path=manifest, evaluated_at=request.feature_as_of
        + timedelta(seconds=2)
    )
    tampered = report.model_dump(mode="json")
    tampered["probability"] = 0.999
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        BoundedInferenceResponse.model_validate(tampered)
