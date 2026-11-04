"""V88 offline trust-governance preflight for independently signed research claims.

The policy fingerprint MUST come from a separate trusted reviewer/channel.
The supplied policy is not proof of reviewer identity, licensing, release,
a deployed model or broker/capital authority. No signer private keys here.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

from .research_context_signature import (
    DENIED_AUTHORITIES,
    ReleaseBundleBlocked,
    verify_signed_claim,
)
from .research_image_context import _canonical, _read

CONTRACT = "v4-research-external-signer-trust.v1"
PURPOSE = "RESEARCH_CONTEXT_CHECKSUM_VALIDATION_ONLY"
REVIEWER_ROLE = "independent_research_claim_reviewer"
MAX_POLICY_BYTES = 16384
MAX_KEYS = 8
MAX_POLICY_AGE = timedelta(days=7)
MAX_KEY_WINDOW = timedelta(days=90)
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
TIME_FORMAT = "%Y-%m-%dT%H:%M:%SZ"
DENIED_POLICY_AUTHORITIES = (
    "model_image_publish_allowed",
    "research_worker_activation_allowed",
    "source_rights_verified",
    "independent_model_custody_verified",
    "deployment_verified",
    "paper_shadow_soak_accepted",
    "broker_reconciliation_accepted",
    "live_execution_enabled",
    "can_authorize_capital",
)


def _timestamp(value: str) -> datetime:
    if not isinstance(value, str) or len(value) != 20:
        raise ReleaseBundleBlocked("trust-policy timestamp invalid")
    try:
        return datetime.strptime(value, TIME_FORMAT).replace(tzinfo=UTC)
    except ValueError as error:
        raise ReleaseBundleBlocked("trust-policy timestamp invalid") from error


def _window(start: object, end: object, max_duration: timedelta) -> tuple[datetime, datetime]:
    since = _timestamp(start)
    until = _timestamp(end)
    if since >= until or until - since > max_duration:
        raise ReleaseBundleBlocked("trust-policy window invalid")
    return since, until


def verify_trusted_policy(
    trust_policy: Path,
    expected_policy_sha256: str,
    expected_key_sha256: str,
    *,
    context: Path,
    now: datetime | None = None,
) -> dict:
    """Validate a short-lived, independently pinned, canonical public policy.

    Only the exact pinned policy bytes can approve a key, and only while the
    policy and key are currently valid and the key is explicitly active.
    The caller must independently obtain AND refresh the policy fingerprint.
    """
    if not isinstance(expected_policy_sha256, str) or not SHA256.fullmatch(
        expected_policy_sha256
    ):
        raise ReleaseBundleBlocked("independent trust-policy pin missing")
    if not isinstance(expected_key_sha256, str) or not SHA256.fullmatch(
        expected_key_sha256
    ):
        raise ReleaseBundleBlocked("independent signer key fingerprint missing")
    resolved_context = context.resolve()
    resolved_policy = trust_policy.resolve()
    if resolved_policy == resolved_context or resolved_context in resolved_policy.parents:
        raise ReleaseBundleBlocked("trust policy cannot reside within model image context")
    contents = _read(trust_policy, MAX_POLICY_BYTES, allow_empty=False)
    fingerprint = hashlib.sha256(contents).hexdigest()
    if not hmac.compare_digest(fingerprint, expected_policy_sha256):
        raise ReleaseBundleBlocked("independent trust-policy fingerprint mismatch")
    try:
        policy = json.loads(contents)
    except (UnicodeDecodeError, ValueError) as error:
        raise ReleaseBundleBlocked("trust-policy JSON invalid") from error
    if not isinstance(policy, dict) or contents != _canonical(policy) + b"\n":
        raise ReleaseBundleBlocked("trust policy is not canonical")
    required = {
        "contract_version", "purpose", "valid_from_utc", "valid_until_utc",
        "reviewers", *DENIED_POLICY_AUTHORITIES,
    }
    if set(policy) != required or policy["contract_version"] != CONTRACT or (
        policy["purpose"] != PURPOSE
    ) or any(policy[flag] is not False for flag in DENIED_POLICY_AUTHORITIES):
        raise ReleaseBundleBlocked("trust policy has unexpected authority or schema")
    instant = now or datetime.now(UTC)
    if instant.tzinfo is None or instant.utcoffset() is None:
        raise ReleaseBundleBlocked("trust evaluation requires timezone-aware clock")
    instant = instant.astimezone(UTC)
    policy_start, policy_end = _window(
        policy["valid_from_utc"], policy["valid_until_utc"], MAX_POLICY_AGE
    )
    if not policy_start <= instant < policy_end:
        raise ReleaseBundleBlocked("policy is not valid at verification time")
    reviewers = policy["reviewers"]
    if not isinstance(reviewers, list) or not (1 <= len(reviewers) <= MAX_KEYS):
        raise ReleaseBundleBlocked("invalid number of policy reviewers")
    seen: set[str] = set()
    selected = None
    for reviewer in reviewers:
        if not isinstance(reviewer, dict) or set(reviewer) != {
            "public_key_sha256", "role", "status", "valid_from_utc",
            "valid_until_utc",
        }:
            raise ReleaseBundleBlocked("invalid trust reviewer schema")
        key_fp = reviewer["public_key_sha256"]
        if not isinstance(key_fp, str) or not SHA256.fullmatch(key_fp) or key_fp in seen:
            raise ReleaseBundleBlocked("invalid or duplicate trusted key")
        seen.add(key_fp)
        if reviewer["role"] != REVIEWER_ROLE or reviewer["status"] not in (
            "active", "revoked"
        ):
            raise ReleaseBundleBlocked("invalid signer role or status")
        key_start, key_end = _window(
            reviewer["valid_from_utc"], reviewer["valid_until_utc"], MAX_KEY_WINDOW
        )
        if key_fp == expected_key_sha256:
            selected = (reviewer["status"], key_start, key_end)
    if selected is None or selected[0] != "active":
        raise ReleaseBundleBlocked("signer unlisted or revoked under pinned policy")
    if not selected[1] <= instant < selected[2]:
        raise ReleaseBundleBlocked("signer approval not currently valid")
    return {
        "contract_version": CONTRACT,
        "trust_policy_sha256": fingerprint,
        "signer_public_key_sha256": expected_key_sha256,
        "independent_policy_pin_matches": True,
        "signer_active_in_pinned_policy": True,
        "signer_revoked_in_pinned_policy": False,
        **{authority: False for authority in DENIED_POLICY_AUTHORITIES},
    }


def verify_governed_claim(
    context: Path,
    code_sha: str,
    claim_path: Path,
    signature_path: Path,
    public_pem: Path,
    expected_key_sha256: str,
    trust_policy: Path,
    expected_policy_sha256: str,
    *,
    now: datetime | None = None,
) -> dict:
    """Enforce key status and a separately anchored policy, then V85 crypto."""
    policy_result = verify_trusted_policy(
        trust_policy, expected_policy_sha256, expected_key_sha256,
        context=context, now=now,
    )
    verified = verify_signed_claim(
        context, code_sha, claim_path, signature_path, public_pem,
        expected_key_sha256,
    )
    if not verified.get("signature_valid") or any(
        verified.get(flag) is not False for flag in DENIED_AUTHORITIES
    ):
        raise ReleaseBundleBlocked("unexpected V85 signature authority")
    return {
        **verified,
        "trust_policy_contract": CONTRACT,
        "trust_policy_sha256": policy_result["trust_policy_sha256"],
        "signer_active_in_pinned_policy": True,
        "signer_revoked_in_pinned_policy": False,
        "trust_governed_checksum_claim_verified": True,
        "model_image_publish_allowed": False,
        "research_worker_activation_allowed": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Offline V88 governance over V85 signed research checksum claim"
    )
    parser.add_argument("--context", required=True, type=Path)
    parser.add_argument("--code-sha", required=True)
    parser.add_argument("--claim", required=True, type=Path)
    parser.add_argument("--signature", required=True, type=Path)
    parser.add_argument("--public-key", required=True, type=Path)
    parser.add_argument("--expected-public-key-sha256", required=True)
    parser.add_argument("--trust-policy", required=True, type=Path)
    parser.add_argument("--expected-trust-policy-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        verified = verify_governed_claim(
            args.context, args.code_sha, args.claim, args.signature,
            args.public_key, args.expected_public_key_sha256, args.trust_policy,
            args.expected_trust_policy_sha256,
        )
    except Exception:
        print("V88 external signer trust-policy verification BLOCKED", file=sys.stderr)
        return 2
    print("V88 policy-pinned research checksum signed claim verified only")
    print("context_evidence_fingerprint=" + verified["context_evidence_fingerprint"])
    print("trust_policy_sha256=" + verified["trust_policy_sha256"])
    print("image release, live execution and capital are NOT authorized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
