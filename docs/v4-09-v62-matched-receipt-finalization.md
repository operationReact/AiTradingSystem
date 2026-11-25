# V4-09 / V62 — Exact-roster V61-to-original-V56 matched PAPER/SHADOW custody

## Problem solved by this bounded change

A previous milestone merged the authenticated Python CatBoost/LightGBM
research client (#406), the durable per-model V61 reservation/outcome ledger
(#407), and single-model ADMIN/PAPER-gated worker dispatch (#409).
One model scoring successfully is **not** a matched research event. The Java
intake must never construct a champion/challenger comparison from missing,
rejected, uncertain, expired, revoked, wrong-model, or wrong-source results.

V62 adds immutable `v4_research_matched_case_link`, keyed by
`(session_id, case_key)`, with the original V56 case fingerprint, roster hash,
model count, original enrollment/source identity, frozen feature hash, actor
and timestamp. A database trigger rejects UPDATE/DELETE.

`ResearchMatchedDecisionFinalizeService.finalizeCase` executes in **one
PostgreSQL transaction**, with V58 shared safety lock, PAPER mode and ADMIN
authorization. It reads only immutable V61 receipts plus the registered V56
model roster and original DB-frozen V59 source/V60 features. It demands:
- Exactly the registered champion and each challenger, all 2-10 members.
- Valid `SUCCEEDED/VALIDATED_RESEARCH_RESPONSE` receipts for every member.
- No UNKNOWN/TIMEOUT/REJECTED/pending or manually missing receipts.
- Exact registered model IDs, versions/families/artifact and V61 reservation
  identity, one common frozen original input and canonical feature fingerprint.
- Model-produced evaluated-at timestamp after its reservation/source availability
  and before current observation; receipts stored before V61 60-second expiry.
- Active V58 session, no global/model emergency quarantine, no stale/future
  data, and no direct user-provided inference or feature payload.

After checks, V62 passes the validated decisions through the **existing
authoritative V56 matched observation validator**, atomically appends the
original `v4_matched_case`/model decisions and one permanent V62 source link.
Any failure rolls both back. Exact replays remain stable and produce no
duplicate case/link; conflicting identities are rejected.

The ADMIN endpoint:
`POST /api/admin/model-governance/paper-shadow/sessions/{sessionId}/cases/{caseKey}/finalize-research-evidence`

It performs no Python call, retry, browser session action, fake market source,
PAPER order placement, broker execution, LIVE switch or model promotion.

## Tests and acceptance

PostgreSQL integration tests must demonstrate complete roster acceptance,
immutable V62 triggers, consistent replay, missing or UNKNOWN member
rejection, paused session rejection, frozen feature tampering detection,
ADMIN role enforcement and zero correlated PAPER orders.

Synthetic test insertions and mocked Python contract scores **do not prove**
actual source licensing, live worker deployment, genuine independent model
accuracy, trader PnL, fees, PAPER/SHADOW soak or broker readiness. V4-09
is still IN PROGRESS after database/CI acceptance.

Next implementation must exercise the real deployed Python worker against
controlled original licensed-market/news source captures, automatic
bounded enrolled-roster dispatch with no retries and provenance-evidenced
PAPER/SHADOW sessions. LIVE capital and paid-source activation remain gated.
