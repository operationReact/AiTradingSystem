"""V84 real synthetic model bundle plus exact local Git commit image-source tests.

No Docker engine, ECR, AWS credentials, provider market data or broker calls.
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest
from test_research_artifact_inference import MODEL_ID, SHADOW_ID, _create_manifest
from test_research_artifact_inference import (
    trained_artifacts as trained_artifacts,  # pytest fixture re-export
)

from aitrading_intelligence.research_image_context import (
    EVIDENCE,
    ReleaseBundleBlocked,
    main,
    prepare_context,
    verify_context,
)
from aitrading_intelligence.research_release_bundle import stage_bundle


def _git(repo: Path, *args: str) -> str:
    proc = subprocess.run(
        ["git", *args], cwd=repo, check=True, capture_output=True, text=True,
    )
    return proc.stdout.strip()


@pytest.fixture
def committed_repository(tmp_path):
    repo = tmp_path / "source-repository"
    intelligence = repo / "intelligence"
    src = intelligence / "src/aitrading_intelligence"
    src.mkdir(parents=True)
    (src / "__init__.py").write_text("VALUE = 1\n")
    (intelligence / "README.md").write_text("Offline mock repository\n")
    (intelligence / "pyproject.toml").write_text("[project]\nname = 'research'\n")
    (intelligence / "Dockerfile.research-release").write_text("FROM scratch\n")
    (intelligence / "Dockerfile.research-release.dockerignore").write_text("**\n")
    _git(repo, "init", "-q")
    _git(repo, "config", "user.name", "Synthetic CI")
    _git(repo, "config", "user.email", "synthetic@invalid.example")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "reviewed source snapshot")
    return repo, _git(repo, "rev-parse", "HEAD")


@pytest.fixture
def prepared_input(trained_artifacts, committed_repository, tmp_path):
    repo, sha = committed_repository
    source = tmp_path / "actual-synthetic-model-files"
    source.mkdir()
    cat, light = trained_artifacts
    manifest = _create_manifest(source, [
        (MODEL_ID, "CATBOOST", cat), (SHADOW_ID, "LIGHTGBM", light),
    ])
    bundle = repo / "intelligence" / ".research-release"
    stage_bundle(manifest, bundle, sha)
    return repo, sha, bundle


def test_exact_git_archive_prevents_dirty_tracked_and_untracked_code_leakage(
    prepared_input, tmp_path,
):
    repo, sha, bundle = prepared_input
    src = repo / "intelligence/src/aitrading_intelligence"
    (src / "__init__.py").write_text("VALUE = 'MALICIOUS_UNCOMMITTED'\n")
    (src / "untracked-secret.py").write_text("PASSWORD='NOT_FOR_IMAGES'\n")
    context = tmp_path / "private-build-context"
    report = prepare_context(repo, bundle, context, sha)
    assert verify_context(context, sha) == report
    assert (context / "src/aitrading_intelligence/__init__.py").read_text() == "VALUE = 1\n"
    assert not (context / "src/aitrading_intelligence/untracked-secret.py").exists()
    assert report["code_sha"] == sha
    assert report["deployment_verified"] is False
    assert report["can_authorize_capital"] is False
    assert len(report["source_files"]) == 5
    assert (context / "Dockerfile.research-release").read_text() == "FROM scratch\n"
    assert (context / ".research-release/manifest.json").exists()
    assert (context / ".research-release/release-receipt.json").exists()
    assert context.stat().st_mode & 0o777 == 0o700
    assert all(p.stat().st_mode & 0o777 == 0o600
               for p in context.rglob("*") if p.is_file())
    assert "MALICIOUS" not in (context / EVIDENCE).read_text()
    assert "PASSWORD" not in (context / EVIDENCE).read_text()


def test_context_tampering_and_extra_files_fail_even_after_preparation(
    prepared_input, tmp_path,
):
    repo, sha, bundle = prepared_input
    target = tmp_path / "out"
    prepare_context(repo, bundle, target, sha)
    source = target / "src/aitrading_intelligence/__init__.py"
    source.write_text("VALUE = 99\n")
    with pytest.raises(ReleaseBundleBlocked, match="committed source file changed"):
        verify_context(target, sha)
    source.write_text("VALUE = 1\n")
    assert verify_context(target, sha)["source_files"]
    (target / "src/aitrading_intelligence/rogue.py").write_text("evil\n")
    with pytest.raises(ReleaseBundleBlocked, match="unexpected build context"):
        verify_context(target, sha)


def test_context_receipt_does_not_allow_deployment_or_capital(
    prepared_input, tmp_path,
):
    repo, sha, bundle = prepared_input
    target = tmp_path / "out"
    prepare_context(repo, bundle, target, sha)
    evidence = target / EVIDENCE
    document = json.loads(evidence.read_text())
    document["can_authorize_capital"] = True
    evidence.write_text(json.dumps(document))
    with pytest.raises(ReleaseBundleBlocked, match="research-only authority"):
        verify_context(target, sha)
    document["can_authorize_capital"] = False
    document["source_files"][0]["sha256"] = "0" * 64
    evidence.write_text(json.dumps(document))
    with pytest.raises(ReleaseBundleBlocked):
        verify_context(target, sha)


def test_sha_mismatch_existing_context_and_inside_git_checkout_rejected(
    prepared_input, tmp_path,
):
    repo, sha, bundle = prepared_input
    output = tmp_path / "different"
    with pytest.raises(ReleaseBundleBlocked, match="expected Git HEAD"):
        prepare_context(repo, bundle, output, "b" * 40 if sha != "b" * 40 else "a" * 40)
    assert not output.exists()
    prepare_context(repo, bundle, output, sha)
    with pytest.raises(ReleaseBundleBlocked, match="already exists"):
        prepare_context(repo, bundle, output, sha)
    with pytest.raises(ReleaseBundleBlocked, match="outside repository"):
        prepare_context(repo, bundle, repo / "private-context", sha)


def test_git_committed_symlink_never_enters_model_image(
    prepared_input, tmp_path,
):
    repo, _sha, _bundle = prepared_input
    src = repo / "intelligence/src/aitrading_intelligence/__init__.py"
    src.unlink()
    external = tmp_path / "external-secret"
    external.write_text("not safe\n")
    src.symlink_to(external)
    _git(repo, "add", "-u")
    _git(repo, "commit", "-qm", "committed symlink source rejected")
    newer_sha = _git(repo, "rev-parse", "HEAD")

    cat = tmp_path / "actual-synthetic-model-files/manifest.json"
    bundle = tmp_path / "second-verified-bundle"
    stage_bundle(cat, bundle, newer_sha)
    output = tmp_path / "symlink-out"
    with pytest.raises(ReleaseBundleBlocked, match="untrusted Git archive entry"):
        prepare_context(repo, bundle, output, newer_sha)
    assert not output.exists()


def test_cli_never_prints_private_operator_context_paths(tmp_path, capsys):
    secret_path = tmp_path / "CONFIDENTIAL-DO-NOT-LOG"
    result = main(["verify", "--context", str(secret_path), "--code-sha", "a" * 40])
    out = capsys.readouterr()
    assert result == 2
    assert "BLOCKED" in out.err
    assert "CONFIDENTIAL" not in out.err
