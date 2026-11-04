"""V85 offline Ed25519 check of an externally signed V84 research source claim.

This program NEVER creates, imports or stores any private signing key.
An independently pinned signer public key is required at verification.
A valid signature proves only an operator signed this local source/model
checksum claim; licensing, profitable PAPER soak, AWS and capital remain false.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
import sys
from pathlib import Path

from .research_artifact_inference import InferenceUnavailable
from .research_image_context import _canonical, _read, verify_context
from .research_release_bundle import ReleaseBundleBlocked

CONTRACT = "v4-research-context-signature-claim.v1"
MAX_CLAIM = 4096
MAX_PUBLIC_PEM = 8192
SIG_BYTES = 64
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
DENIED_AUTHORITIES = (
    "source_rights_verified",
    "independent_model_custody_verified",
    "deployment_verified",
    "paper_shadow_soak_accepted",
    "broker_reconciliation_accepted",
    "live_execution_enabled",
    "can_authorize_capital",
)


def _hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_claim(context: Path, code_sha: str) -> dict:
    """Reverify the full V84 Git context and the V80 actual model receipt."""
    evidence = verify_context(context, code_sha)
    return {
        "contract_version": CONTRACT,
        "code_sha": code_sha,
        "context_evidence_fingerprint": evidence["evidence_fingerprint"],
        "bundle_receipt_fingerprint": evidence["bundle_receipt_fingerprint"],
        **{flag: False for flag in DENIED_AUTHORITIES},
    }


def emit_claim(context: Path, code_sha: str, destination: Path) -> dict:
    """Emit only bounded public source hash metadata for an external signer."""
    claim = source_claim(context, code_sha)
    if destination.exists() or destination.is_symlink():
        raise ReleaseBundleBlocked("signed-claim destination already exists")
    if not destination.parent.is_dir():
        raise ReleaseBundleBlocked("signed-claim parent missing")
    if context.resolve() == destination.resolve() or (
        context.resolve() in destination.resolve().parents
    ):
        raise ReleaseBundleBlocked("signed claim must be outside model image context")
    data = _canonical(claim) + b"\n"
    if len(data) > MAX_CLAIM:
        raise ReleaseBundleBlocked("signed-claim data outside bound")
    # O_EXCL prevents replacing an existing claim during approval review.
    import os

    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
    return claim


def pinned_public_key(public_pem: Path, expected_key_sha256: str):
    """Use DER SubjectPublicKeyInfo for an unambiguous Ed25519 key fingerprint."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    if not SHA256.fullmatch(expected_key_sha256):
        raise ReleaseBundleBlocked("trusted signer public key SHA256 pin missing")
    raw = _read(public_pem, MAX_PUBLIC_PEM, allow_empty=False)
    key = serialization.load_pem_public_key(raw)
    if not isinstance(key, Ed25519PublicKey):
        raise ReleaseBundleBlocked("non-Ed25519 signer key")
    der = key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    fingerprint = _hash(der)
    if not hmac.compare_digest(fingerprint, expected_key_sha256):
        raise ReleaseBundleBlocked("independent signer key fingerprint mismatch")
    return key


def verify_signed_claim(
    context: Path,
    code_sha: str,
    claim_path: Path,
    signature_path: Path,
    public_pem: Path,
    expected_key_sha256: str,
) -> dict:
    """Verify independently pinned signer over exact canonical V84/V80 bytes."""
    from cryptography.exceptions import InvalidSignature

    # No signer-provided path can be inside the Docker context.
    resolved_context = context.resolve()
    for path in (claim_path, signature_path, public_pem):
        resolved = path.resolve()
        if resolved == resolved_context or resolved_context in resolved.parents:
            raise ReleaseBundleBlocked("verification material inside model context")
    expected = source_claim(context, code_sha)
    claim_bytes = _read(claim_path, MAX_CLAIM, allow_empty=False)
    if claim_bytes != _canonical(expected) + b"\n":
        raise ReleaseBundleBlocked("signed claim differs from verified local model source")

    key = pinned_public_key(public_pem, expected_key_sha256)
    signature = _read(signature_path, SIG_BYTES, allow_empty=False)
    if len(signature) != SIG_BYTES:
        raise ReleaseBundleBlocked("Ed25519 signature length mismatch")
    try:
        key.verify(signature, claim_bytes)
    except InvalidSignature as error:
        raise ReleaseBundleBlocked("signed model source claim mismatch") from error
    return {
        "contract_version": CONTRACT,
        "code_sha": code_sha,
        "context_evidence_fingerprint": expected["context_evidence_fingerprint"],
        "bundle_receipt_fingerprint": expected["bundle_receipt_fingerprint"],
        "signer_public_key_sha256": expected_key_sha256,
        "signature_valid": True,
        **{flag: False for flag in DENIED_AUTHORITIES},
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Offline external Ed25519 research source check")
    commands = parser.add_subparsers(dest="command", required=True)
    emit = commands.add_parser("emit-claim")
    check = commands.add_parser("verify")
    for cmd in (emit, check):
        cmd.add_argument("--context", type=Path, required=True)
        cmd.add_argument("--code-sha", required=True)
        cmd.add_argument("--claim", type=Path, required=True)
    check.add_argument("--signature", type=Path, required=True)
    check.add_argument("--public-key", type=Path, required=True)
    check.add_argument("--expected-public-key-sha256", required=True)
    args = parser.parse_args(argv)

    try:
        if args.command == "emit-claim":
            claim = emit_claim(args.context, args.code_sha, args.claim)
            result = claim["context_evidence_fingerprint"]
            message = "V85 unsigned research claim emitted; signing/release NOT authorized"
        else:
            verification = verify_signed_claim(
                args.context, args.code_sha, args.claim, args.signature,
                args.public_key, args.expected_public_key_sha256,
            )
            result = verification["context_evidence_fingerprint"]
            message = "V85 independently pinned signature verified; image release NOT authorized"
    except (Exception, InferenceUnavailable):
        # Fail closed without printing private directories, signer PEM, model
        # files, tokens, remote responses or untrusted exception text.
        print("V85 research source signature BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print(message)
    print("context_evidence_fingerprint=" + result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
