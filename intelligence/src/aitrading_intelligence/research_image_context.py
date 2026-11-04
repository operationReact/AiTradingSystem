"""V84 offline Git-commit-bound source + verified model release build context.

No Docker execution, model signing, AWS API, model publishing or capital authority.
Source bytes come from the exact Git HEAD archive, never a mutable worktree.
The local checksum evidence is NOT an independently signed provenance claim.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

from .research_release_bundle import ReleaseBundleBlocked, verify_bundle

CONTRACT = "v4-research-source-image-context.v1"
EVIDENCE = "source-context-evidence.json"
SOURCE_PREFIX = "intelligence/"
REQUIRED = frozenset({
    "pyproject.toml",
    "README.md",
    "Dockerfile.research-release",
    "Dockerfile.research-release.dockerignore",
})
SOURCE_DIR = "src/"
BUNDLE_DIR = ".research-release"
MAX_SOURCE_FILES = 500
MAX_SOURCE_BYTES = 24 * 1024 * 1024
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_EVIDENCE_BYTES = 128 * 1024
SHA40 = re.compile(r"[a-f0-9]{40}\Z")
SHA256 = re.compile(r"[a-f0-9]{64}\Z")


def _hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical(data: dict) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def _read(path: Path, bound: int, *, allow_empty: bool = True) -> bytes:
    for item in (path, *path.parents):
        if item.is_symlink():
            raise ReleaseBundleBlocked("symbolic link in release context")
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ReleaseBundleBlocked("unexpected nonregular release context member")
    before = path.stat().st_size
    if before > bound or (before == 0 and not allow_empty):
        raise ReleaseBundleBlocked("release context file outside size bound")
    contents = path.read_bytes()
    if len(contents) != before:
        raise ReleaseBundleBlocked("release context member changed while reading")
    return contents


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, timeout=15, check=True,
    )
    return result.stdout.decode("utf-8").strip()


def _check_checkout(repo: Path, expected_sha: str) -> None:
    if not SHA40.fullmatch(expected_sha):
        raise ReleaseBundleBlocked("exact Git source SHA required")
    if not repo.is_dir() or repo.is_symlink():
        raise ReleaseBundleBlocked("repository location invalid")
    if Path(_git(repo, "rev-parse", "--show-toplevel")).resolve() != repo.resolve():
        raise ReleaseBundleBlocked("source directory is not the repository root")
    actual = _git(repo, "rev-parse", "--verify", "HEAD")
    if actual != expected_sha:
        raise ReleaseBundleBlocked("source checkout is not the expected Git HEAD")


def _allow_source(name: str) -> bool:
    # Git archive is rooted at repository root, not at intelligence directory.
    if not name.startswith(SOURCE_PREFIX):
        return False
    relative = name[len(SOURCE_PREFIX):]
    parts = PurePosixPath(relative)
    if parts.is_absolute() or ".." in parts.parts or "." in parts.parts:
        return False
    return relative in REQUIRED or relative.startswith(SOURCE_DIR)


def _write_private(root: Path, relative: str, data: bytes) -> None:
    path = root / relative
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(0o600)


def _extract_exact_git_archive(repo: Path, sha: str, root: Path) -> list[dict]:
    # Read the committed Git tree directly. Neither dirty tracked files nor
    # untracked (or gitignored) local code can enter this Docker build context.
    names: set[str] = set()
    files: list[dict] = []
    total = 0
    process = subprocess.Popen(
        ["git", "archive", "--format=tar", sha, "--",
         "intelligence/src", "intelligence/pyproject.toml",
         "intelligence/README.md", "intelligence/Dockerfile.research-release",
         "intelligence/Dockerfile.research-release.dockerignore"],
        cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    try:
        if process.stdout is None:
            raise ReleaseBundleBlocked("Git archive unavailable")
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for member in archive:
                if member.isdir():
                    continue
                if not member.isfile() or not _allow_source(member.name):
                    raise ReleaseBundleBlocked("untrusted Git archive entry")
                relative = member.name[len(SOURCE_PREFIX):]
                if relative in names or len(names) >= MAX_SOURCE_FILES:
                    raise ReleaseBundleBlocked("duplicate or oversized source inventory")
                if member.size > MAX_FILE_BYTES or total + member.size > MAX_SOURCE_BYTES:
                    raise ReleaseBundleBlocked("Git archive exceeds source limits")
                handle = archive.extractfile(member)
                if handle is None:
                    raise ReleaseBundleBlocked("missing Git archive source")
                content = handle.read(MAX_FILE_BYTES + 1)
                if len(content) != member.size:
                    raise ReleaseBundleBlocked("incomplete committed source entry")
                _write_private(root, relative, content)
                files.append({
                    "path": relative,
                    "size": member.size,
                    "sha256": _hash(content),
                })
                total += member.size
                names.add(relative)
        if process.wait(timeout=15) != 0:
            raise ReleaseBundleBlocked("committed Git archive failed")
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        if process.stdout is not None:
            process.stdout.close()
    if not REQUIRED.issubset(names) or not any(x.startswith(SOURCE_DIR) for x in names):
        raise ReleaseBundleBlocked("required committed build sources missing")
    return sorted(files, key=lambda entry: entry["path"])


def _safe_inventory(root: Path) -> set[str]:
    if not root.is_dir() or root.is_symlink():
        raise ReleaseBundleBlocked("release context directory invalid")
    members: set[str] = set()
    for item in root.rglob("*"):
        if item.is_symlink() or not (item.is_dir() or item.is_file()):
            raise ReleaseBundleBlocked("unexpected context file type")
        if item.is_file():
            members.add(item.relative_to(root).as_posix())
    return members


def verify_context(context: Path, code_sha: str) -> dict:
    """Check copied model release and committed source inventory offline.

    The evidence is an untrusted local checksum receipt; there is no digital
    signature, AWS attestation, model source-rights or trading authorization.
    """
    if not SHA40.fullmatch(code_sha):
        raise ReleaseBundleBlocked("invalid Git context SHA")
    evidence_bytes = _read(context / EVIDENCE, MAX_EVIDENCE_BYTES, allow_empty=False)
    data = json.loads(evidence_bytes)
    if not isinstance(data, dict) or set(data) != {
        "contract_version", "code_sha", "source_files",
        "bundle_receipt_fingerprint", "deployment_verified",
        "can_authorize_capital", "evidence_fingerprint",
    } or data["contract_version"] != CONTRACT or data["code_sha"] != code_sha:
        raise ReleaseBundleBlocked("source context evidence mismatch")
    if data["deployment_verified"] is not False or data["can_authorize_capital"] is not False:
        raise ReleaseBundleBlocked("source context exceeds research-only authority")
    items = data["source_files"]
    if not isinstance(items, list) or not 5 <= len(items) <= MAX_SOURCE_FILES:
        raise ReleaseBundleBlocked("source inventory size invalid")
    source_set: set[str] = set()
    for record in items:
        if not isinstance(record, dict) or set(record) != {"path", "sha256", "size"}:
            raise ReleaseBundleBlocked("source entry schema invalid")
        name, size, digest = record["path"], record["size"], record["sha256"]
        if not isinstance(name, str) or not _allow_source(SOURCE_PREFIX + name):
            raise ReleaseBundleBlocked("untrusted source inventory path")
        if name in source_set or not isinstance(size, int) or isinstance(size, bool):
            raise ReleaseBundleBlocked("duplicate or invalid source inventory")
        if not 0 <= size <= MAX_FILE_BYTES or not isinstance(digest, str) or (
            not SHA256.fullmatch(digest)
        ):
            raise ReleaseBundleBlocked("source identity format invalid")
        content = _read(context / name, MAX_FILE_BYTES)
        if len(content) != size or _hash(content) != digest:
            raise ReleaseBundleBlocked("committed source file changed")
        source_set.add(name)
    if source_set != {item["path"] for item in sorted(items, key=lambda v: v["path"])}:
        raise ReleaseBundleBlocked("source inventory invalid")
    if not REQUIRED.issubset(source_set) or not any(p.startswith(SOURCE_DIR) for p in source_set):
        raise ReleaseBundleBlocked("committed source set incomplete")
    if items != sorted(items, key=lambda entry: entry["path"]):
        raise ReleaseBundleBlocked("noncanonical source inventory order")
    model_receipt = verify_bundle(context / BUNDLE_DIR, code_sha)
    if data["bundle_receipt_fingerprint"] != model_receipt["receipt_fingerprint"]:
        raise ReleaseBundleBlocked("source and model receipt pairing mismatch")
    expected = set(source_set) | {EVIDENCE} | {
        BUNDLE_DIR + "/" + member.name for member in (context / BUNDLE_DIR).iterdir()
    }
    if _safe_inventory(context) != expected:
        raise ReleaseBundleBlocked("unexpected build context inventory")
    unsealed = {k: v for k, v in data.items() if k != "evidence_fingerprint"}
    if _hash(_canonical(unsealed)) != data["evidence_fingerprint"]:
        raise ReleaseBundleBlocked("source context checksum mismatch")
    return data


def prepare_context(repo: Path, bundle: Path, destination: Path, code_sha: str) -> dict:
    """Create a new private Git-archive-only Docker context, without building."""
    repo = repo.resolve()
    destination = destination.absolute()
    _check_checkout(repo, code_sha)
    if repo == destination or repo in destination.parents:
        raise ReleaseBundleBlocked("build context must live outside repository")
    if destination.exists() or destination.is_symlink():
        raise ReleaseBundleBlocked("build context target already exists")
    if not destination.parent.is_dir():
        raise ReleaseBundleBlocked("build context parent unavailable")
    model_receipt = verify_bundle(bundle, code_sha)

    staging: Path | None = None
    try:
        staging = Path(tempfile.mkdtemp(prefix=".research-build-context-", dir=destination.parent))
        staging.chmod(0o700)
        source_files = _extract_exact_git_archive(repo, code_sha, staging)
        dest_bundle = staging / BUNDLE_DIR
        dest_bundle.mkdir(mode=0o700)
        for member in sorted(bundle.iterdir(), key=lambda item: item.name):
            if not member.is_file() or member.is_symlink():
                raise ReleaseBundleBlocked("untrusted model release entry")
            _write_private(staging, BUNDLE_DIR + "/" + member.name,
                           _read(member, MAX_SOURCE_BYTES, allow_empty=False))
        if verify_bundle(dest_bundle, code_sha) != model_receipt:
            raise ReleaseBundleBlocked("copy did not preserve model release")
        evidence = {
            "contract_version": CONTRACT,
            "code_sha": code_sha,
            "source_files": source_files,
            "bundle_receipt_fingerprint": model_receipt["receipt_fingerprint"],
            "deployment_verified": False,
            "can_authorize_capital": False,
        }
        evidence["evidence_fingerprint"] = _hash(_canonical(evidence))
        _write_private(staging, EVIDENCE, _canonical(evidence) + b"\n")
        verify_context(staging, code_sha)
        if destination.exists() or destination.is_symlink():
            raise ReleaseBundleBlocked("concurrent build context creation")
        staging.rename(destination)
        staging = None
        return evidence
    finally:
        if staging is not None:
            shutil.rmtree(staging)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Offline Git-pinned research Docker context")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("--repository-root", type=Path, required=True)
    prepare.add_argument("--model-bundle", type=Path, required=True)
    prepare.add_argument("--destination", type=Path, required=True)
    check = commands.add_parser("verify")
    check.add_argument("--context", type=Path, required=True)
    for sub in (prepare, check):
        sub.add_argument("--code-sha", required=True)
    opts = parser.parse_args(argv)
    try:
        if opts.command == "prepare":
            report = prepare_context(
                opts.repository_root, opts.model_bundle, opts.destination, opts.code_sha,
            )
        else:
            report = verify_context(opts.context, opts.code_sha)
    except (ReleaseBundleBlocked, ValueError, OSError, KeyError, TypeError,
            AttributeError, subprocess.SubprocessError, tarfile.TarError):
        print("V84 research image source context BLOCKED (sanitized)", file=sys.stderr)
        return 2

    print("V84 Git-commit-bound local context verified; image NOT built or published")
    print("context_evidence_fingerprint=" + report["evidence_fingerprint"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
