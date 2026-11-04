# V4-11 / V63 — ADMIN connected research inference receipt inspector

## Purpose

V61 reserves exact research scoring attempts and stores a single terminal outcome.
V62 finalizes the **complete** roster only after all required evidence succeeds.
Prior to V63, operators could see recorded PAPER fills and matched decisions,
but had no connected explanation for missing, UNKNOWN, expired or rejected
per-model research attempts before V62 finalization.

## Scope of this PR

- `GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/cases/{caseKey}/research-receipts`
  (strict ADMIN read-only, no inference dispatch, no mutation).
- A single bounded 2–10 model roster drawn directly from the original V56
  enrolled versions, with V58 safety revision, V59 capture flag, V60 frozen
  feature flag, V61 individual reservation/receipt statuses and V62 finalized
  linkage. `NOT_ATTEMPTED`, `UNKNOWN_UNRESOLVED`,
  `SUCCEEDED_EXPIRED`, and the V61 terminal `SUCCEEDED/REJECTED/TIMEOUT/UNKNOWN`
  have distinct semantics. Success is **a validated research receipt**, not
  independent runtime/business acceptance.
- Identity checks bind each available reservation to exact enrollment, model
  version, artifact fingerprint and V59/V60 source and features. Missing state
  is visible instead of being silently counted as success.
- Typed Flutter ADMIN Model Control Center case-key inspector makes this
  state inspectable for both finalized and *unfinalized* cases. It refuses
  incorrect session/roster/safety revision and rejects any positive capital,
  LIVE, licensed-custody, paper-soak or independent-worker-acceptance claim.
- UI clears cached evidence after ADMIN revocation or PAUSE/RESUME/QUARANTINE;
  a bad read cannot leave a stale approved-looking display.
- PostgreSQL/Spring security integration tests cover ADMIN-only, source
  captured/frozen, missing/UNKNOWN, PAUSED state and explicit false authority;
  Flutter widget/repository tests verify missing models, authentication, LIVE
  refusal and bounded model counts.

## Deliberately incomplete

The endpoint does not call Python, submit orders, enroll new models, finalize
V62 cases, confirm feed licensing, certify actual trained-model inference,
compute verified market returns, or enable LIVE capital. The worker is still
disabled by default unless separately configured and authorized, and broker
integration/production readiness remains gated.

Next requires genuine licensed point-in-time Indian-market inputs, actual
deployed Python worker receipt lineage, bounded end-to-end model-roster
evaluation, PAPER/SHADOW performance and soak evidence, client-platform
acceptance and independent operational/broker reconciliation.

Acceptance for this PR: seven exact-head GitHub Actions gates (Flutter
client, mobile quality, golden regression, Android APK, Phase 0 Java21/
PostgreSQL, Phase 4, and Flutter delivery policy), plus any applicable
security checks. Do not claim iOS connected-device or deployed-worker
acceptance from CI build alone.
