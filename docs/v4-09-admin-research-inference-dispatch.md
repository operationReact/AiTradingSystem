# V4-09 / Single-model research inference dispatch — opt-in, ADMIN/PAPER only

## Actual implementation in this PR

Prior merged PRs:
- #400: trained CatBoost/LightGBM Python inference endpoint.
- #402/#405: Java V60 source-bound causal feature projection.
- #406: authenticated Java internal Python inference client, no dispatch.
- #407: immutable V61 per-model pre-dispatch reservation and one terminal receipt.

This slice introduces a **single exact-model** dispatch path:

`POST /api/admin/model-governance/paper-shadow/sessions/{sessionId}/cases/{caseKey}/models/{modelVersionId}/research-inference`

**Nothing is dispatchable by default.** Requires operator-managed
`app.research.artifact-worker.enabled=true`, the existing private HTTPS
research-worker URL and token (at least 32 characters) plus a deployed Python
allowlisted artifact manifest. Do not store secrets in Git/Flutter/Notion.
Only ADMIN can call the controller/service; the V61 reservation independently
enforces PAPER mode, ACTIVE V58 session state, allowed model, registered V56
participant identity, source-bound V59/V60 case and absence of earlier decisions.

The path reads frozen features from Java database, then calls
`ResearchInferenceAttemptLedger.reserve(...)` in its own **committed**
transaction. Only after the reservation is committed does it contact the
Python worker, without holding any database row lock or Spring transaction
across network I/O. The already-approved client bounds the HTTP call, rejects
unsafe redirects and mismatching response hashes, and never auto-retries.

A fresh, validated response may be written as one V61 `SUCCEEDED` receipt.
Rejected responses become `REJECTED`; network uncertainty becomes
`UNKNOWN`. After PAUSE/QUARANTINE, late results cannot succeed. The ledger
also cross-checks the model family against the actual Java model definition,
rather than trusting the Python family claim. Any failed DB commit leaves a
durable reservation without a receipt: **UNKNOWN, not retryable**.

The output is a safe research-only status receipt. The endpoint never creates
a V56 decision, PAPER order, broker instruction, model promotion, licensed
source assertion, deployment approval or LIVE capital grant.

## Verification

- `ResearchInferenceDispatchServiceTest`: disabled default, committed
  reservation before exactly one worker call, no retry on UNKNOWN,
  rejection of mismatched causal identities and late PAUSE.
- `ResearchInferenceDispatchControllerIT`: real Spring Boot security and
  PostgreSQL verification that non-admin users are denied and the default-off
  admin route neither dispatches nor creates reservations.

Exact-head CI still required before merge. Mocked scores and synthetic DB
fixtures are not market-data or model-performance evidence.

## Next required implementation

This path scores **one model only**. A later separate PR must implement
ADMIN/PAPER bounded **entire enrolled V56 model roster** orchestration:
respect exact champion/challenger order, enforce unanimous fresh valid
same-case V61 receipts, and only then atomically append a V56 matched
observation. Never derive order-execution or promotion authority from scores.
Add real licensed-source evidence, genuine Python deployment tests, Indian
market transaction costs, PAPER/SHADOW soak and independent broker verification
before considering acceptance. No manual rerun of a case/model reservation,
including UNKNOWN/TIMEOUT.
