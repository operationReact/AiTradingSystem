"""V88 offline public-policy trust governance and real V85 synthetic signature."""
from __future__ import annotations

import copy
import hashlib
import json
from datetime import datetime, timedelta, timezone

import pytest
from test_research_context_signature import (
    signed_context as signed_context,  # real V85/V84/V80 synthetic fixture
)

from aitrading_intelligence.research_context_signature import ReleaseBundleBlocked
from aitrading_intelligence.research_image_context import _canonical
from aitrading_intelligence.research_signer_trust import (
    CONTRACT,
    DENIED_POLICY_AUTHORITIES,
    PURPOSE,
    REVIEWER_ROLE,
    main,
    verify_governed_claim,
    verify_trusted_policy,
)

NOW = datetime(2026, 11, 5, 12, 0, tzinfo=timezone.utc)
KEY_SHA = "a" * 64


def utc(minutes: int) -> str:
    return (NOW + timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")


def example_policy(key_sha: str = KEY_SHA) -> dict:
    return {
        "contract_version": CONTRACT,
        "purpose": PURPOSE,
        "valid_from_utc": utc(-15),
        "valid_until_utc": utc(45),
        "reviewers": [{
            "public_key_sha256": key_sha,
            "role": REVIEWER_ROLE,
            "status": "active",
            "valid_from_utc": utc(-60),
            "valid_until_utc": utc(120),
        }],
        **{flag: False for flag in DENIED_POLICY_AUTHORITIES},
    }


def save_policy(path, data: dict) -> str:
    raw = _canonical(data) + b"\n"
    path.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def check_policy(path, fingerprint, key_sha=KEY_SHA, *, now=NOW):
    return verify_trusted_policy(
        path, fingerprint, key_sha, context=path.parent / "image-context",
        now=now,
    )


def test_independent_policy_pin_requires_active_nonrevoked_short_lived_key(tmp_path):
    policy_path = tmp_path / "reviewer-policy.json"
    pin = save_policy(policy_path, example_policy())
    result = check_policy(policy_path, pin)
    assert result["independent_policy_pin_matches"] is True
    assert result["signer_active_in_pinned_policy"] is True
    assert result["signer_revoked_in_pinned_policy"] is False
    assert all(result[flag] is False for flag in DENIED_POLICY_AUTHORITIES)
    with pytest.raises(ReleaseBundleBlocked, match="signer unlisted"):
        check_policy(policy_path, pin, "b" * 64)
    with pytest.raises(ReleaseBundleBlocked, match="policy is not valid"):
        check_policy(policy_path, pin, now=NOW + timedelta(hours=2))
    with pytest.raises(ReleaseBundleBlocked, match="policy is not valid"):
        check_policy(policy_path, pin, now=NOW - timedelta(hours=1))


def test_revocation_and_key_expiry_do_not_become_authority(tmp_path):
    path = tmp_path / "policy.json"
    policy = example_policy()
    policy["reviewers"][0]["status"] = "revoked"
    pin = save_policy(path, policy)
    with pytest.raises(ReleaseBundleBlocked, match="revoked"):
        check_policy(path, pin)
    policy["reviewers"][0]["status"] = "active"
    policy["reviewers"][0]["valid_until_utc"] = utc(-1)
    pin = save_policy(path, policy)
    with pytest.raises(ReleaseBundleBlocked, match="not currently valid"):
        check_policy(path, pin)
    policy["reviewers"][0]["valid_from_utc"] = utc(1)
    policy["reviewers"][0]["valid_until_utc"] = utc(5)
    pin = save_policy(path, policy)
    with pytest.raises(ReleaseBundleBlocked, match="not currently valid"):
        check_policy(path, pin)


def test_unapproved_changes_and_noncanonical_policy_fail(tmp_path):
    path = tmp_path / "policy.json"
    original = example_policy()
    pin = save_policy(path, original)
    changed = copy.deepcopy(original)
    changed["reviewers"][0]["status"] = "revoked"
    save_policy(path, changed)
    with pytest.raises(ReleaseBundleBlocked, match="fingerprint mismatch"):
        check_policy(path, pin)
    path.write_text(json.dumps(original, indent=2), encoding="utf-8")
    forged_pin = hashlib.sha256(path.read_bytes()).hexdigest()
    with pytest.raises(ReleaseBundleBlocked, match="not canonical"):
        check_policy(path, forged_pin)
    path.write_bytes(_canonical(original) + b" \n")
    forged_pin = hashlib.sha256(path.read_bytes()).hexdigest()
    with pytest.raises(ReleaseBundleBlocked, match="not canonical"):
        check_policy(path, forged_pin)
    with pytest.raises(ReleaseBundleBlocked, match="pin missing"):
        check_policy(path, "z" * 64)


def test_schema_authority_and_duplicate_key_prohibited(tmp_path):
    path = tmp_path / "policy.json"
    variants = []
    p = example_policy()
    p["can_authorize_capital"] = True
    variants.append(p)
    p = example_policy()
    p["model_image_publish_allowed"] = True
    variants.append(p)
    p = example_policy()
    p["reviewers"].append(copy.deepcopy(p["reviewers"][0]))
    variants.append(p)
    p = example_policy()
    p["reviewers"][0]["role"] = "broker_operator"
    variants.append(p)
    p = example_policy()
    p["reviewers"][0]["status"] = "deprecated"
    variants.append(p)
    p = example_policy()
    p["valid_until_utc"] = utc(60 * 24 * 9)
    variants.append(p)
    p = example_policy()
    p["reviewers"][0]["valid_until_utc"] = utc(60 * 24 * 100)
    variants.append(p)
    p = example_policy()
    p["unreviewed_extra"] = True
    variants.append(p)
    p = example_policy()
    p["reviewers"] = []
    variants.append(p)
    for invalid in variants:
        pin = save_policy(path, invalid)
        with pytest.raises(ReleaseBundleBlocked):
            check_policy(path, pin)


def test_policy_cannot_reside_in_model_context(tmp_path):
    context = tmp_path / "model-image-context"
    context.mkdir()
    path = context / "policy.json"
    pin = save_policy(path, example_policy())
    with pytest.raises(ReleaseBundleBlocked, match="within model image"):
        verify_trusted_policy(path, pin, KEY_SHA, context=context, now=NOW)


def test_full_real_synthetic_ed25519_v85_chain_still_blocks_deployment(
    signed_context, tmp_path,
):
    key = signed_context["key_sha"]
    policy_path = tmp_path / "independent-reviewer-policy.json"
    policy = example_policy(key)
    pin = save_policy(policy_path, policy)
    args = (
        signed_context["context"], signed_context["sha"],
        signed_context["claim"], signed_context["sig"],
        signed_context["key"], key, policy_path, pin,
    )
    result = verify_governed_claim(*args, now=NOW)
    assert result["signature_valid"] is True
    assert result["trust_governed_checksum_claim_verified"] is True
    assert result["trust_policy_sha256"] == pin
    assert result["model_image_publish_allowed"] is False
    assert result["research_worker_activation_allowed"] is False
    assert all(result[flag] is False for flag in DENIED_POLICY_AUTHORITIES)
    policy["reviewers"][0]["status"] = "revoked"
    revoked_pin = save_policy(policy_path, policy)
    with pytest.raises(ReleaseBundleBlocked, match="revoked"):
        verify_governed_claim(*args[:-1], revoked_pin, now=NOW)


def test_cli_fail_closed_without_paths_or_private_material(tmp_path, capsys):
    context = tmp_path / "PRIVATE_BUNDLE_SHOULD_NOT_PRINT"
    policy_path = tmp_path / "PRIVATE_POLICY_SHOULD_NOT_PRINT"
    pin = save_policy(policy_path, example_policy())
    result = main([
        "--context", str(context), "--code-sha", "b" * 40,
        "--claim", str(tmp_path / "private-claim"),
        "--signature", str(tmp_path / "private-signature"),
        "--public-key", str(tmp_path / "private-public"),
        "--expected-public-key-sha256", KEY_SHA,
        "--trust-policy", str(policy_path),
        "--expected-trust-policy-sha256", pin,
    ])
    stdout, stderr = capsys.readouterr()
    assert result == 2
    assert "BLOCKED" in stderr
    assert "PRIVATE" not in stdout + stderr
