# V4-09 / V72 — Authenticated Worker Readiness vs Immutable Java Model Roster

## Operational goal

Previous V66–V71 CI proved real Java ↔ Python trained-artifact scoring,
PAPER/SHADOW model roster dispatch, immutable success/failure receipts,
operator PAUSE racing with in-flight responses, 10-model bounded throughput
and synthetic transport fault evidence.

A generic service `/health` does **not** prove the worker's installed
model files match a Java enrollment. V72 introduces a distinct strictly
read-only ADMIN probe to detect that mismatch, without scoring a model or
claiming the worker is production-certified.

### Private Python inventory

`GET /v1/research/artifact-readiness` requires the **same private worker
token** as the actual inference endpoint, with no public substitute.

- The worker validates its trusted local manifest, all 1–16 allowlisted
  artifact files, per-file SHA-256, exact model family, fingerprint and
  registered path restriction; any missing, corrupted, changed or symlink
  manifest rejects the entire roster (HTTP 503).
- Returns only model-version IDs, family, model artifact fingerprint,
  manifest SHA-256 and a canonical response fingerprint. No local paths,
  model weight bytes, thresholds, tokens, copyrighted source text or signals.
- Explicit evidence flags `independently_verified_deployment`,
  `source_rights_verified`, `paper_shadow_soak_accepted`,
  `broker_reconciliation_accepted`, `can_authorize_capital` are
  invariably false. A local file check is **not** a signed remote
  attestation, data license or production release authorization.

### ADMIN Java reconciliation

`GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/worker-readiness`

requires an authenticated ADMIN in PAPER mode, a genuinely enrolled
V56/V58 session, an existing Java model registry and *both* opt-in flags:

- `AI_RESEARCH_WORKER_CLIENT_ENABLED=true`
- `AI_RESEARCH_WORKER_READINESS_ENABLED=true`

Both default OFF. The private worker URL/token are existing
`AI_RESEARCH_WORKER_URL` and `AI_RESEARCH_WORKER_TOKEN` values.
Only HTTPS endpoints are permitted except explicit localhost/loopback HTTP;
never accept a caller-provided URL. Java uses HTTP/1.1, strict timeout,
no redirects and **no retries**; read-only JDBC queries finish before HTTP.
Raw remote errors and credentials are never exposed to the ADMIN response.

The Java service validates the exact closed worker JSON schema, an
unmodified canonical SHA-256 response hash, false authority/acceptance
flags and unique bounded model IDs. It reconciles every **original
enrolled version ID + model family + artifact fingerprint** against
actual worker-validated local files.

- `MATCHED_RESEARCH_ONLY`: all original enrolled models are present
  with exact registered identities; other worker-allowlisted model IDs
  are reported as a count and **do not** give capital permission.
- `MISMATCH_NO_DISPATCH_AUTHORITY`: at least one enrolled model is absent
  or differs; fail-closed diagnostic, no reservation or model dispatch.
- Disabled, unknown, malformed, tampered or unavailable worker responses
  return sanitized conflict/not-found status, never successful proof.

Neither response grants a permission to start a research job: all existing
V58, V61, V62, V64 and emergency controls still apply independently.
PAPER orders, model promotion and LIVE authorization remain unavailable.

## Acceptance and limitations

- Python TestClient tests: unauthorized request, true two-model local
  artifact validation, corrupt file, omitted manifest and response fields
  excluding secrets.
- Spring+PostgreSQL tests: ADMIN only, default-off no reservation,
  real running Python/Uvicorn worker manifest match and mismatch against
  original frozen model-version enrollment, all downstream authority
  booleans false.
- V4 actual-worker synthetic CI extends the previous protected contract
  workflow; no broker, licensed feed or production environment is used.

This is an operator-facing diagnostic **step toward** deployment readiness.
It does **not** prove independent network trust/host identity beyond
configured HTTPS transport, production persistence, exchange/news rights,
user broker authorization, model quality, real-market PAPER soak, CI/CD
deployment history, iOS runtime or LIVE safety. Do not mark V4-09 as
production accepted merely because this endpoint returns matched.
