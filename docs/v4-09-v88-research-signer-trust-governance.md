# V4-09 / V88 — offline independently pinned research signer trust policy

V85 validates the Ed25519 signature over a V84 Git source/V80 model receipt checksum, but cannot determine whether the signer remains approved, has expired, or has been revoked. V88 adds an optional separate offline signer governance step. It is not a deployment step.

## Independent trust boundary

The operator must obtain (1) the reviewer's public Ed25519 PEM and exact DER/SPKI SHA-256 fingerprint, and (2) a separately reviewed canonical trust policy and its SHA-256 fingerprint, **through an independently trusted channel**. Trust is NOT established by the policy path or a SHA-256 that an untrusted party supplies alongside the policy. The application has no centrally authenticated policy-root key, reviewer registry or signed timestamp authority.

A reviewer must authenticate the signer and role, check the policy revision and independently distribute and rotate the trusted policy fingerprint whenever a key is revoked or rotated. A **previously approved policy pin does not automatically learn later revocations**. Compromised or stale external trust channels can defeat the governance check; this remains an operator responsibility.

The verifier requires:
- Exact, bounded canonical JSON (ASCII sorted keys, compact separators, one final newline), separately pinned with SHA-256.
- Exact research-checksum-only purpose, reviewer role and schema; all release, model custody, data rights, trading and capital authority flags must be false.
- At most eight unique fingerprinted reviewer keys; only an explicitly listed, active, nonrevoked key currently inside its allowed key window may sign. Validity is checked against the verifier's local clock, without a trusted independent time source.
- Policy validity is no more than seven days; individual reviewer-key windows are no more than 90 days. Expired and future policy/keys are rejected.
- The policy file must reside outside the V84 private model Docker context; no signer private key ever enters the application or CI.
- After policy verification, the existing V85 cryptographic verifier rechecks the V84 source inventory, V80 bundle receipt, canonical claim bytes and Ed25519 signature.

The successful verdict describes a **signature over a local checksum claim under an independently pinned policy snapshot**, not an AWS release, model licensing or operational approval.

## Illustrative policy schema only

The following fake public-key fingerprint is not a real trusted signer, and the sample policy is **not usable as a production authorization**. The real policy must be generated as canonical compact JSON with exactly one trailing newline, independently reviewed, and pinned outside this repository.

    {
      "contract_version": "v4-research-external-signer-trust.v1",
      "purpose": "RESEARCH_CONTEXT_CHECKSUM_VALIDATION_ONLY",
      "valid_from_utc": "2026-11-05T00:00:00Z",
      "valid_until_utc": "2026-11-06T00:00:00Z",
      "reviewers": [{
        "public_key_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "role": "independent_research_claim_reviewer",
        "status": "active",
        "valid_from_utc": "2026-11-05T00:00:00Z",
        "valid_until_utc": "2026-11-06T00:00:00Z"
      }],
      "model_image_publish_allowed": false,
      "research_worker_activation_allowed": false,
      "source_rights_verified": false,
      "independent_model_custody_verified": false,
      "deployment_verified": false,
      "paper_shadow_soak_accepted": false,
      "broker_reconciliation_accepted": false,
      "live_execution_enabled": false,
      "can_authorize_capital": false
    }

## Operator-only offline usage

After independently obtaining the V85 external Ed25519 signature, reviewer public key, trust policy and **separate trusted policy fingerprint**, run the release-extra verification command in the secured operator environment:

    python -m aitrading_intelligence.research_signer_trust \
      --context "$CONTEXT" --code-sha "$COMMIT" \
      --claim /private/research-approval/context-claim.json \
      --signature /private/research-approval/context-claim.ed25519 \
      --public-key /trusted/reviewer-public-ed25519.pem \
      --expected-public-key-sha256 TRUSTED_REVIEWER_DER_SPKI_SHA256 \
      --trust-policy /trusted/reviewer-trust-policy.json \
      --expected-trust-policy-sha256 INDEPENDENTLY_PINNED_POLICY_SHA256

V85 verify remains a crypto check ONLY; the V88 command is the additional trust-governance check. No cloud networking, signer private key, remote authorization, model data or brokerage access occurs in this command.

## Validation and still-blocked operations

The model test suite uses disposable trained CatBoost/LightGBM fixtures and ephemeral Ed25519 keys. Tests exercise real source-model-signature verification plus revoked/expired/future keys, key not in policy, tampered policy pin, duplicate reviewers, noncanonical JSON, altered authority and context confinement. A separate no-AWS read-only CI workflow checks policy invariants. Passing tests do not attest an actual independent reviewer.

There is still no genuine approved policy pin or external signer identity, licensed model custody, immutable image release, applied AWS worker HTTPS setup, running Python task, accepted PAPER/SHADOW market soak, broker reconciliation or LIVE/capital authority. Worker desiredCount stays zero and Java research opt-ins OFF. No resource is created, started or modified by V88.
