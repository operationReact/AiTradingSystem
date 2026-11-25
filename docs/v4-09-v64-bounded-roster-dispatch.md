# V4-09 / V64 — One-shot, bounded enrolled research roster dispatch

## Scope and authority

V64 integrates already merged V61/V62/V63 contracts:
- V61 independently commits one exact session/case/model reservation before HTTP
  and records at most one immutable terminal receipt per model.
- #409 Java-to-Python CatBoost/LightGBM client validates registered artifact
  identity, source-bound features, token, canonical response hash and false
  capital/promotion flags.
- V62 may finalize a complete matched V56 observation only when every original
  enrolled model has a fresh, valid, matching success receipt.
- V63 presents actual missing/unknown/success/expired per-model diagnostics.

The new **ADMIN-only**, **separately disabled-by-default** endpoint is:

`POST /api/admin/model-governance/paper-shadow/sessions/{sessionId}/cases/{caseKey}/dispatch-research-roster`

To make this call explicitly available, the operator must configure BOTH
`app.research.roster-dispatch.enabled=true` and
`app.research.artifact-worker.enabled=true`, together with the private approved
Python worker URL, secure token and operator-controlled allowlisted trained
model manifest. No secrets, model weights or licensed data are committed to
Git/Flutter/Notion. End-to-end deployment and subscriptions are NOT activated
by this change.

## Execution sequence

1. ADMIN authorization first. Read Java V63 original enrolled roster, V58
   safety revision, V59 frozen market/news source and V60 features.
2. Refuse all sessions except ACTIVE with exactly 2–10 original, unique,
   CatBoost/LightGBM champion/challenger versions, exactly one PAPER champion.
   Any previously attempted model, unresolved/pending receipt, previously
   finalized case or missing source/feature evidence is an immediate failure.
3. Dispatch each exact original model at most once via the existing #409 Spring
   service proxy, with inherited ADMIN authentication. Max four concurrent calls
   per case, and at most eight within the JVM. Each model's V61 reservation
   commits before HTTP, and no outer DB transaction or row lock spans transport.
   Invocation budget is 45 seconds; the worker's existing bounded HTTP timeout
   is 10 seconds; all outcomes persist through the existing V61 ledger.
4. Re-read the V63 immutable evidence; if ANY outcome is missing, rejected,
   uncertain, stale, mismatched or expired, report
   `INCOMPLETE_NO_RETRY`. Never manufacture model decisions, return a cached
   prior success, retry UNKNOWN or place a PAPER or LIVE order. A new future
   prospective *case* is required; repeated requests for an attempted case
   are rejected.
5. Only if every registered model is a fresh, identity-bound `SUCCEEDED`
   and the V58 revision remained unchanged, invoke V62's final PostgreSQL
   transaction. V62 rechecks exact original source/roster/causal timestamps,
   holds the appropriate DB safety locks, and atomically appends original
   V56 matched decision evidence with immutable source custody link. Returned
   status is `FINALIZED_RESEARCH_ONLY` with all authority flags false.

## Tests

- Unit: disabled default; exact-one-call-per-model; security context carried to
  concurrent workers; full-roster-only finalization; partial UNKNOWN; previous
  reservation refusal; PAUSE before/after dispatch; unexpected worker error.
- Spring/real PostgreSQL: only ADMIN can reach the route and default disabled
  cannot reserve any model version.
- Exact-head Phase 0 Java 21/PostgreSQL/Docker, Phase 4, Phase 8, dependency
  security and delivery-policy checks required before merging.

## Remaining blockers

No independent licensed Indian-market/news source custody, exact production
Python deployment/manifest attestation, model quality/transaction costs,
qualified PAPER/SHADOW soak, broker readiness or iOS runtime acceptance is
proved by synthetic tests or successful CI. LIVE capital remains disabled.
V4-09 is IN PROGRESS; V64 does not certify a commercial trading release.

If a large roster exhausts 60-second V61 receipt validity, finalization must
fail closed. A subsequent soak/load-test phase must characterize throughput,
latency and success rates under the intended 2–10-model concurrency before
claiming operational acceptance. Canceled/uncertain invocations may leave
unresolved reservations; these must never be replayed.
