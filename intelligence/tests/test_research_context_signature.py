"""V85 actual Ed25519 crypto checks against a V84 + V80 synthetic local bundle.

Temporary keys in tests ONLY. No private key committed, stored or used in the
production verifier; no AWS/ECR/Docker/broker activity occurs.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from test_research_artifact_inference import (
    trained_artifacts as trained_artifacts,  # required synthetic fixture
)
from test_research_image_context import (
    committed_repository as committed_repository,  # required fixture
)
from test_research_image_context import (
    prepared_input as prepared_input,  # required synthetic bundle fixture
)

from aitrading_intelligence.research_context_signature import (
    DENIED_AUTHORITIES,
    ReleaseBundleBlocked,
    emit_claim,
    main,
    verify_signed_claim,
)
from aitrading_intelligence.research_image_context import prepare_context


@pytest.fixture
def signed_context(prepared_input, tmp_path):
    repo, sha, bundle = prepared_input
    context = tmp_path / "prepared-private-context"
    prepare_context(repo, bundle, context, sha)
    claim_path = tmp_path / "external-approval-claim.json"
    claim = emit_claim(context, sha, claim_path)
    private = Ed25519PrivateKey.generate()
    public = private.public_key()
    public_pem = tmp_path / "pinned-reviewer-public.pem"
    public_pem.write_bytes(public.public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    key_sha = hashes.Hash(hashes.SHA256())
    key_sha.update(public.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    trusted_key_sha = key_sha.finalize().hex()
    signature_path = tmp_path / "reviewer-approval-signature.ed25519"
    signature_path.write_bytes(private.sign(claim_path.read_bytes()))
    return {
        "context": context, "sha": sha, "claim": claim_path, "sig": signature_path,
        "key": public_pem, "key_sha": trusted_key_sha, "claim_data": claim,
    }


def _verify(data):
    return verify_signed_claim(
        data["context"], data["sha"], data["claim"], data["sig"],
        data["key"], data["key_sha"],
    )


def test_externally_generated_signature_can_only_attest_exact_local_hashes(signed_context):
    result = _verify(signed_context)
    assert result["signature_valid"] is True
    assert result["code_sha"] == signed_context["sha"]
    assert result["context_evidence_fingerprint"] == (
        signed_context["claim_data"]["context_evidence_fingerprint"]
    )
    assert all(result[flag] is False for flag in DENIED_AUTHORITIES)
    assert all(signed_context["claim_data"][flag] is False for flag in DENIED_AUTHORITIES)
    contents = signed_context["claim"].read_text()
    assert "private_key" not in contents
    assert "AI_RESEARCH_WORKER_TOKEN" not in contents


def test_missing_wrong_or_substituted_independent_signer_key_fails(signed_context, tmp_path):
    data = dict(signed_context)
    for bad in ("", "a" * 64, data["key_sha"].upper()):
        data["key_sha"] = bad
        with pytest.raises(ReleaseBundleBlocked):
            _verify(data)
    other = Ed25519PrivateKey.generate().public_key()
    other_path = tmp_path / "substituted-public-key.pem"
    other_path.write_bytes(other.public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    data["key"] = other_path
    data["key_sha"] = signed_context["key_sha"]
    with pytest.raises(ReleaseBundleBlocked, match="independent signer key"):
        _verify(data)


def test_signature_claim_and_context_mutations_are_rejected(signed_context):
    data = signed_context
    signature = data["sig"].read_bytes()
    data["sig"].write_bytes(b"\0" * 64)
    with pytest.raises(ReleaseBundleBlocked, match="signed model source"):
        _verify(data)
    data["sig"].write_bytes(signature[:20])
    with pytest.raises(ReleaseBundleBlocked, match="signature length"):
        _verify(data)
    data["sig"].write_bytes(signature)
    assert _verify(data)["signature_valid"]

    original = data["claim"].read_bytes()
    tampered = json.loads(original)
    tampered["can_authorize_capital"] = True
    data["claim"].write_text(json.dumps(tampered))
    with pytest.raises(ReleaseBundleBlocked, match="differs"):
        _verify(data)
    data["claim"].write_bytes(original)

    source = data["context"] / "src/aitrading_intelligence/__init__.py"
    source.write_text("MODEL = 'tampered AFTER approval'\n")
    with pytest.raises(ReleaseBundleBlocked):
        _verify(data)


def test_claim_output_is_private_not_overwritten_or_in_image_context(signed_context):
    data = signed_context
    with pytest.raises(ReleaseBundleBlocked, match="already exists"):
        emit_claim(data["context"], data["sha"], data["claim"])
    with pytest.raises(ReleaseBundleBlocked, match="outside model image"):
        emit_claim(data["context"], data["sha"], data["context"] / "new-approval.json")
    assert _verify(data)["signature_valid"]


def test_cli_logs_never_expose_private_key_or_model_paths(signed_context, capsys):
    data = signed_context
    result = main([
        "verify", "--context", str(data["context"]), "--code-sha", data["sha"],
        "--claim", str(data["claim"]), "--signature", str(data["sig"]),
        "--public-key", str(data["key"]), "--expected-public-key-sha256", "0" * 64,
    ])
    stdout, stderr = capsys.readouterr()
    assert result == 2
    assert "BLOCKED" in stderr
    assert str(data["context"]) not in stderr
    assert str(data["key"]) not in stderr
    assert "AI_RESEARCH_WORKER_TOKEN" not in stdout + stderr
