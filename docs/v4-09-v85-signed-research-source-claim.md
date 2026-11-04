# V4-09/V85 — Independently pinned offline signature for research model source evidence

## Why V85 exists

V80 validates local trained model hashes. V84 creates a private Docker build context from exactly one Git HEAD commit and verifies every committed source file and model byte. But V84's `source-context-evidence.json` is **only a local checksum receipt**. A person with filesystem write access can potentially alter both the files and unsigned evidence.

V85 adds a separate, explicitly invoked **Ed25519 verifier**. The application **does not generate, store, import or accept private signing keys**; a trusted independent operator (or separate secure signing service/HSM) signs the V85 claim **outside** the application, and verification requires an independently pinned exact 64-character SHA-256 fingerprint of the Ed25519 public key in DER SubjectPublicKeyInfo form.

A cryptographically valid signature proves only that the holder of the corresponding private key signed the exact reviewed source/model checksums. It does **not** prove the model's India data/news licensing, independent model custody, statistical quality, runtime deployment, broker reconciliation, successful PAPER/SHADOW soak, LIVE authority, or the identity/approval status of the signer. All those authority flags remain `false`.

## Independent approval flow (manual, offline only)

1. Obtain an independently reviewed, exact-commit, local V84 private context (from `research_image_context prepare`). The context must contain only V84-approved committed source files, real V80-verified model bundle and checksum receipt.
2. Emit a bounded, *public metadata only* canonical claim, in a secure folder **outside** that model context. It contains no model weights, paths, private worker token, features, labels or market feed text.

```bash
python -m aitrading_intelligence.research_context_signature emit-claim \
  --context "$CONTEXT" --code-sha "$COMMIT" \
  --claim /private/research-approval/context-claim.json
```

3. Independently review the SHA and source/model identity. Authorize the signature through a separate approved signing process. **Private signing keys must never be placed in this Git repository, Python process, CI, AWS image, shared artifact folder or application configuration.** For illustration, an approved external OpenSSL 3 or HSM service can sign the canonical claim bytes using Ed25519 (the app contains no signing operation). A separate, trusted control should distribute the public key's SHA-256 fingerprint.

4. The verifier requires the canonical claim, an externally produced raw 64-byte detached Ed25519 signature, the signer **public** PEM, and the independently trusted SHA-256 of that public key's DER SubjectPublicKeyInfo bytes:

```bash
python -m aitrading_intelligence.research_context_signature verify \
  --context "$CONTEXT" --code-sha "$COMMIT" \
  --claim /private/research-approval/context-claim.json \
  --signature /private/research-approval/context-claim.ed25519 \
  --public-key /trusted/independent-reviewer-ed25519.pub.pem \
  --expected-public-key-sha256 TRUSTED_64_CHAR_PUBLIC_KEY_SHA256
```

The verifier **reruns all V84 source and V80 model bundle checks** before verifying a signature. It rejects unsigned claims, missing/alternate key pins, PEM public-key substitution, non-Ed25519 keys, signature length errors, claim field/whitespace changes, altered model/source bytes and verification materials placed inside the Docker context. Errors are fail-closed and sanitized.

The signing claim is canonical compact JSON (sorted keys, ASCII escaping, trailing newline). The signature covers these **exact file bytes**. Verification has no cloud/network access and does not send any credentials.

## Dependency and test boundary

The verifier uses the established Python `cryptography` Ed25519 implementation, installed with the separate `release` optional extra; developer synthetic tests install `dev` extras. Neither the private key nor the offline cryptographic verifier is a required production Python worker runtime dependency.

CI creates **ephemeral Ed25519 test keys in memory** to assert valid signature acceptance and reject wrong keys, modified raw signatures, changed claim authority and source files, and credential/path leakage. CI success does not amount to a signature from an actual independent model reviewer.

## Still blocked

No real independently source-rights-cleared model artifacts, production Ed25519 key identity/approval policy, independently signed claim, immutable ECR worker image, real AWS Terraform apply, DNS/ACM/OIDC operational checks, worker task rollout, V72 ADMIN roster proof, real PAPER/SHADOW trial or broker reconciliation has occurred. V76 worker stays `desired_count=0`, Java worker client/readiness opt-ins stay OFF and LIVE/capital remains denied.

**A signed metadata claim alone never starts a worker or authorizes trading.** Any eventual publish/deployment workflow requires separate explicit review, a real license/custody record, immutable digest and operational safety gates.
