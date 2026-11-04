"""V80: stage only verified research model artifacts for an opt-in Docker build.

No AWS calls, token/secret handling, model promotion or capital authority.
Source model rights and independently signed custody still require a separate
operator's verified evidence, and are deliberately always recorded as false.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path

from pydantic import ValidationError

from .research_artifact_inference import (
    InferenceManifest,
    InferenceUnavailable,
    inspect_worker_roster,
)

CONTRACT = "v4-research-release-bundle.v1"
RECEIPT_NAME = "release-receipt.json"
MANIFEST_NAME = "manifest.json"
SHA40 = re.compile(r"[a-f0-9]{40}\Z")
SHA64 = re.compile(r"[a-f0-9]{64}\Z")
MAX_MANIFEST = 64 * 1024
MAX_ARTIFACT = 16 * 1024 * 1024
MAX_RECEIPT = 24 * 1024
AUTHORITY_FALSE = (
    "independent_source_rights_verified",
    "independent_model_custody_verified",
    "deployment_verified",
    "broker_reconciliation_accepted",
    "paper_shadow_soak_accepted",
    "live_execution_enabled",
    "can_authorize_capital",
)


class ReleaseBundleBlocked(ValueError):
    """Raise only a generic, safe diagnostic from untrusted release inputs."""


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical(obj: dict) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def _safe_file(path: Path, max_bytes: int) -> bytes:
    # Check each existing parent, not only the final component, for symlink escapes.
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ReleaseBundleBlocked("untrusted symbolic link")
    mode = path.lstat().st_mode
    if not stat.S_ISREG(mode):
        raise ReleaseBundleBlocked("release input must be a regular file")
    if path.stat().st_size <= 0 or path.stat().st_size > max_bytes:
        raise ReleaseBundleBlocked("release input size outside bound")
    data = path.read_bytes()
    if not 0 < len(data) <= max_bytes:
        raise ReleaseBundleBlocked("release input changed during read")
    return data


def _manifest_entries(directory: Path) -> tuple[InferenceManifest, bytes]:
    raw = _safe_file(directory / MANIFEST_NAME, MAX_MANIFEST)
    manifest = InferenceManifest.model_validate_json(raw)
    filenames = [member.artifact_filename for member in manifest.models]
    if len(filenames) != len(set(filenames)):
        raise ReleaseBundleBlocked("duplicate artifact filename")
    if any(name in (MANIFEST_NAME, RECEIPT_NAME) for name in filenames):
        raise ReleaseBundleBlocked("reserved artifact filename")
    return manifest, raw


def _receipt(roster, manifest: InferenceManifest, raw: bytes, code_sha: str) -> dict:
    models = [
        {
            "model_version_id": m.model_version_id,
            "model_family": m.family,
            "artifact_sha256": m.artifact_sha256,
            "artifact_fingerprint": m.artifact_fingerprint,
        }
        for m in sorted(manifest.models, key=lambda entry: entry.model_version_id)
    ]
    if _digest(raw) != roster.manifest_sha256 or not roster.local_artifacts_validated:
        raise ReleaseBundleBlocked("roster manifest identity changed")
    identities = {(m.model_version_id, m.model_family, m.artifact_fingerprint)
                  for m in roster.models}
    if identities != {(m["model_version_id"], m["model_family"], m["artifact_fingerprint"])
                      for m in models}:
        raise ReleaseBundleBlocked("model identity does not match inspected roster")

    receipt = {
        "contract_version": CONTRACT,
        "code_sha": code_sha,
        "manifest_sha256": _digest(raw),
        "model_count": len(models),
        "models": models,
        **{flag: False for flag in AUTHORITY_FALSE},
    }
    return {**receipt, "receipt_fingerprint": _digest(_canonical(receipt))}


def verify_bundle(directory: Path, code_sha: str) -> dict:
    """Verify exact file inventory, hashes, schema and sanitized receipt.

    A valid receipt proves consistency of local bytes, not independent custody,
    data licenses, real AWS deployment or authority to place trades.
    """
    if not SHA40.fullmatch(code_sha):
        raise ReleaseBundleBlocked("invalid source commit")
    manifest, raw = _manifest_entries(directory)
    expected = {MANIFEST_NAME, RECEIPT_NAME} | {
        model.artifact_filename for model in manifest.models
    }
    actual = {member.name for member in directory.iterdir()}
    if actual != expected:
        raise ReleaseBundleBlocked("unexpected release bundle inventory")
    for model in manifest.models:
        artifact = _safe_file(directory / model.artifact_filename, MAX_ARTIFACT)
        if _digest(artifact) != model.artifact_sha256:
            raise ReleaseBundleBlocked("artifact fingerprint mismatch")

    raw_receipt = _safe_file(directory / RECEIPT_NAME, MAX_RECEIPT)
    provided = json.loads(raw_receipt)
    if not isinstance(provided, dict) or set(provided) != (
        {"contract_version", "code_sha", "manifest_sha256", "model_count",
         "models", "receipt_fingerprint"} | set(AUTHORITY_FALSE)
    ):
        raise ReleaseBundleBlocked("release receipt schema invalid")
    if any(provided[flag] is not False for flag in AUTHORITY_FALSE):
        raise ReleaseBundleBlocked("release receipt exceeds research-only authority")
    if provided["code_sha"] != code_sha:
        raise ReleaseBundleBlocked("release source commit mismatch")

    local_roster = inspect_worker_roster(directory / MANIFEST_NAME)
    expected_receipt = _receipt(local_roster, manifest, raw, code_sha)
    if provided != expected_receipt:
        raise ReleaseBundleBlocked("release receipt evidence mismatch")
    return expected_receipt


def stage_bundle(source_manifest: Path, destination: Path, code_sha: str) -> dict:
    """Create a new private local build context; never overwrite prior evidence."""
    if not SHA40.fullmatch(code_sha):
        raise ReleaseBundleBlocked("invalid source commit")
    if source_manifest.name != MANIFEST_NAME:
        raise ReleaseBundleBlocked("manifest filename is not canonical")
    source = source_manifest.parent
    manifest, original_manifest = _manifest_entries(source)
    # Validate real pinned trained model bytes BEFORE copying anything.
    inspect_worker_roster(source_manifest)
    if destination.exists() or destination.is_symlink():
        raise ReleaseBundleBlocked("release staging target already exists")
    if not destination.parent.is_dir():
        raise ReleaseBundleBlocked("release staging parent missing")
    if destination.parent.resolve() == source.resolve():
        raise ReleaseBundleBlocked("cannot stage into source model directory")

    temp: Path | None = None
    try:
        temp = Path(tempfile.mkdtemp(prefix=".research-staging-", dir=destination.parent))
        os.chmod(temp, 0o700)
        files = [(MANIFEST_NAME, MAX_MANIFEST)] + [
            (entry.artifact_filename, MAX_ARTIFACT) for entry in manifest.models
        ]
        for name, limit in files:
            content = _safe_file(source / name, limit)
            (temp / name).write_bytes(content)
            os.chmod(temp / name, 0o600)
        staged_manifest, raw = _manifest_entries(temp)
        if raw != original_manifest:
            raise ReleaseBundleBlocked("source manifest changed during staging")
        roster = inspect_worker_roster(temp / MANIFEST_NAME)
        receipt = _receipt(roster, staged_manifest, raw, code_sha)
        (temp / RECEIPT_NAME).write_bytes(_canonical(receipt) + b"\n")
        os.chmod(temp / RECEIPT_NAME, 0o600)
        verify_bundle(temp, code_sha)
        if destination.exists():
            raise ReleaseBundleBlocked("concurrent release staging detected")
        temp.rename(destination)
        temp = None
        return receipt
    finally:
        if temp is not None:
            import shutil
            shutil.rmtree(temp)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Strict research-only artifact release bundle")
    sub = parser.add_subparsers(dest="command", required=True)
    stage = sub.add_parser("stage")
    stage.add_argument("--source-manifest", type=Path, required=True)
    stage.add_argument("--code-sha", required=True)
    check = sub.add_parser("verify")
    check.add_argument("--bundle", type=Path, required=True)
    check.add_argument("--code-sha", required=True)
    args = parser.parse_args(argv)

    try:
        if args.command == "stage":
            # Fixed destination is deliberately covered by intelligence/.gitignore
            # and excluded from the everyday Python Docker build context.
            destination = Path(__file__).resolve().parents[2] / ".research-release"
            report = stage_bundle(args.source_manifest, destination, args.code_sha)
        else:
            report = verify_bundle(args.bundle, args.code_sha)
    except (ReleaseBundleBlocked, InferenceUnavailable, ValidationError, OSError,
            ValueError, TypeError, KeyError, AttributeError, json.JSONDecodeError):
        print("V80 model release bundle BLOCKED (sanitized)", file=sys.stderr)
        return 2

    print("V80 research model bundle locally validated; release NOT authorized")
    print("bundle_receipt_fingerprint=" + report["receipt_fingerprint"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
