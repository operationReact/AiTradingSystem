"""V80 real synthetic CatBoost/LightGBM artifact release guard contracts.

These tests never publish model weights, touch AWS or authorize capital.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from test_research_artifact_inference import (
    MODEL_ID,
    SHADOW_ID,
    _create_manifest,
)
from test_research_artifact_inference import (
    trained_artifacts as trained_artifacts,  # pytest fixture used by name
)

from aitrading_intelligence.research_release_bundle import (
    AUTHORITY_FALSE,
    RECEIPT_NAME,
    ReleaseBundleBlocked,
    main,
    stage_bundle,
    verify_bundle,
)

SHA = "a" * 40


@pytest.fixture
def source_manifest(trained_artifacts, tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    cat, light = trained_artifacts
    return _create_manifest(source, [
        (MODEL_ID, "CATBOOST", cat),
        (SHADOW_ID, "LIGHTGBM", light),
    ])


def test_actual_trained_artifact_bundle_is_private_hash_pinned_and_research_only(
    source_manifest, tmp_path
):
    release = tmp_path / ".research-release"
    report = stage_bundle(source_manifest, release, SHA)
    verified = verify_bundle(release, SHA)
    assert report == verified
    assert report["code_sha"] == SHA
    assert report["model_count"] == 2
    assert all(report[f] is False for f in AUTHORITY_FALSE)
    assert {m["model_family"] for m in report["models"]} == {"CATBOOST", "LIGHTGBM"}
    assert {m["model_version_id"] for m in report["models"]} == {MODEL_ID, SHADOW_ID}
    assert {p.name for p in release.iterdir()} == {
        "manifest.json", MODEL_ID + ".json", SHADOW_ID + ".json", RECEIPT_NAME
    }
    assert release.stat().st_mode & 0o777 == 0o700
    assert all(p.stat().st_mode & 0o777 == 0o600 for p in release.iterdir())
    content = (release / RECEIPT_NAME).read_text()
    assert "can_authorize_capital" in content
    assert "/source/" not in content
    assert "model_json" not in content
    assert "AI_RESEARCH_WORKER_TOKEN" not in content


def test_reject_changed_artifact_receipt_and_extra_file(source_manifest, tmp_path):
    release = tmp_path / "release"
    stage_bundle(source_manifest, release, SHA)
    original = (release / (MODEL_ID + ".json")).read_bytes()
    (release / (MODEL_ID + ".json")).write_bytes(original + b" ")
    with pytest.raises(ReleaseBundleBlocked):
        verify_bundle(release, SHA)
    (release / (MODEL_ID + ".json")).write_bytes(original)
    assert verify_bundle(release, SHA)["model_count"] == 2

    receipt = json.loads((release / RECEIPT_NAME).read_text())
    receipt["can_authorize_capital"] = True
    (release / RECEIPT_NAME).write_text(json.dumps(receipt))
    with pytest.raises(ReleaseBundleBlocked, match="research-only"):
        verify_bundle(release, SHA)
    receipt["can_authorize_capital"] = False
    (release / RECEIPT_NAME).write_text(json.dumps(receipt))

    (release / "unauthorized-copy.json").write_text("secret")
    with pytest.raises(ReleaseBundleBlocked, match="inventory"):
        verify_bundle(release, SHA)


def test_cannot_fake_source_code_sha_or_edit_receipt(source_manifest, tmp_path):
    release = tmp_path / "release"
    stage_bundle(source_manifest, release, SHA)
    with pytest.raises(ReleaseBundleBlocked, match="commit"):
        verify_bundle(release, "b" * 40)

    receipt = json.loads((release / RECEIPT_NAME).read_text())
    receipt["receipt_fingerprint"] = "0" * 64
    (release / RECEIPT_NAME).write_text(json.dumps(receipt))
    with pytest.raises(ReleaseBundleBlocked, match="receipt"):
        verify_bundle(release, SHA)


def test_source_manifest_and_symlinks_fail_before_staging(source_manifest, tmp_path):
    release = tmp_path / "stage"
    target = source_manifest.parent / (MODEL_ID + ".json")
    target.write_bytes(target.read_bytes() + b"\n")
    with pytest.raises(ValueError):
        stage_bundle(source_manifest, release, SHA)
    assert not release.exists()

    release = tmp_path / "other"
    target = source_manifest.parent / (SHADOW_ID + ".json")
    target.unlink()
    target.symlink_to(source_manifest)
    with pytest.raises(ValueError):
        stage_bundle(source_manifest, release, SHA)
    assert not release.exists()


def test_never_overwrite_previous_receipt_or_follow_symlink(source_manifest, tmp_path):
    release = tmp_path / "release"
    release.mkdir()
    sentinel = release / "KEEP"
    sentinel.write_text("do not overwrite")
    with pytest.raises(ReleaseBundleBlocked, match="already exists"):
        stage_bundle(source_manifest, release, SHA)
    assert sentinel.read_text() == "do not overwrite"

    release_alias = tmp_path / "release-alias"
    release_alias.symlink_to(source_manifest.parent, target_is_directory=True)
    with pytest.raises(ReleaseBundleBlocked, match="already exists"):
        stage_bundle(source_manifest, release_alias, SHA)


def test_cli_sanitizes_invalid_artifact_path_and_source_token(capsys, tmp_path):
    result = main(["verify", "--bundle", str(tmp_path / "missing-secret-folder"),
                   "--code-sha", "a" * 40])
    assert result == 2
    output = capsys.readouterr()
    assert "missing-secret-folder" not in output.err
    assert "BLOCKED" in output.err


def test_release_dockerfile_is_explicit_and_does_not_embed_token_or_reuse_default_image():
    root = Path(__file__).resolve().parents[1]
    release_docker = (root / "Dockerfile.research-release").read_text()
    default_docker = (root / "Dockerfile").read_text()
    ignore = (root / ".dockerignore").read_text()
    special_ignore = (root / "Dockerfile.research-release.dockerignore").read_text()
    gitignore = (root.parent / ".gitignore").read_text()

    assert "COPY --chown=65532:65532 .research-release/ /opt/research/" in release_docker
    assert "research_release_bundle verify" in release_docker
    assert "research_startup_gate && exec uvicorn" in release_docker
    assert 'USER 65532:65532' in release_docker
    assert 'AI_RESEARCH_STRICT_BOOT_REQUIRED=true' in release_docker
    assert 'RESEARCH_SOURCE_SHA' in release_docker
    assert 'AWS_SECRET_ACCESS_KEY' not in release_docker
    assert ".research-release" not in default_docker
    assert ".research-release/" in ignore
    assert "!.research-release/**" in special_ignore
    assert "intelligence/.research-release/" in gitignore
