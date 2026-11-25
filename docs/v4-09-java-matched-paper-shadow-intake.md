# V4-09 — Java PAPER/SHADOW matched candidate intake (implementation slice)

## Scope

This delivers the first persistent Java-side intake for the V4-09 matched cohort path after Python PR #380. It is **not** a live co-running model orchestrator and does not submit simulated PAPER orders or broker orders yet. It establishes server-timestamped, immutable storage and ADMIN-only APIs so a later authenticated inference producer and Admin UI have a durable source of model decisions.

The owner confirmed Angel One static IP configuration and requested code completion before optional news/exchange subscriptions. This code has no billable external dependencies and retains independent financial release controls.

### DB and API

Migration: `V56__v4_matched_paper_shadow_runtime_intake.sql`.

ADMIN-only endpoints:

| Operation | HTTP |
| --- | --- |
| Enroll exact registered model versions | `POST /api/admin/model-governance/paper-shadow/sessions` |
| Read immutable session and complete roster | `GET /api/admin/model-governance/paper-shadow/sessions/{id}` |
| Append one complete multi-model market decision | `POST /api/admin/model-governance/paper-shadow/sessions/{id}/observations` |
| Read one completed matched case and per-model decision | `GET /api/admin/model-governance/paper-shadow/sessions/{id}/observations/{caseKey}` |
| Query bounded recent cases | `GET /api/admin/model-governance/paper-shadow/sessions/{id}/observations?limit=50` |

Enrollment requires exactly one PAPER champion, at least one SHADOW challenger (2–10 unique registered model versions), precise registry artifact fingerprints and permitted effective governance policy. An explicit client session UUID identifies idempotent replay. Frozen enrollment fingerprint prevents changing the roster. The server records actual enrollment time and authenticated actor. Duplicate case ingestion with an unchanged fingerprint is read-only idempotent; conflicting replacements are rejected.

Every appended observation must include the identical **source input fingerprint for every registered model**; market/news timestamps must precede inference, model inference must precede or equal decision, decision must not predate enrollment or be in the future, and all models must appear exactly once. Case and decisions have immutable SHA-256 lineage. No future market outcome field is accepted. Java trading authority must be in PAPER mode, and emergency-disabled models cannot be enrolled or ingested. Database FKs bind registered models and cannot reference missing participants.

All reads explicitly return `independentlyVerifiedRuntime=false`, `paperOrdersSubmitted=false`, `canAuthorizeCapital=false`. No mutation endpoint can enable LIVE, execute a brokerage request or promote a champion. This is a user/ADMIN-supplied **documented intake**; server-side timestamp checks do **not** prove historical data or model output came from an independent licensed provider.

### Testing / acceptance

Integration test `MatchedPaperShadowControllerIT` runs against PostgreSQL/Flyway and MockMvc. Coverage includes registration, exact readback, same-event idempotent replay, rejected changed outcome/action, incomplete candidates, mismatched input identity, future timestamps, governance disable, ADMIN-only RBAC and zero broker orders. All required PR checks must pass on the exact accepted head before merge.

### Next V4-09 increments

1. Authorized runtime inference-event publisher from Java/Python with durable claim/lease, provenance signing and recorded failure/timeout/abstention; avoid operator-supplied timestamps.
2. Bind completed PAPER events to *actual persisted* `paper_order`, `paper_fill`, transaction-cost and reconciliation evidence; emit canonical PR #380 matched-observation export for offline scoring.
3. Persist real champion-versus-challenger disagreements; add source freshness, timeouts, health, pause/quarantine and reliable minimum-soak rollups.
4. Flutter Admin enrollment, current-session, matched action/disagreement and evidence-state screens with explicit empty/error/stale/unauthorized cases.
5. After implementation, procure/verify licensed data/news and broker requirements, perform real paper/shadow soak and controlled release acceptance. The owner's static IP is already configured; subscription/license activation is intentionally deferred but not waived.

Do not mark V4-09 PAPER/SHADOW complete from this slice. No model training, real inference, simulated fill or LIVE authority is provided here.
