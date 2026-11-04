# V4-09 — Prospective Java database-backed source capture (implementation slice)

## What is now implemented

Flyway `V59__v4_prospective_market_news_capture.sql` freezes a **specific pair of pre-existing Java-persisted** `market_candle` and `news_item` rows in one matched PAPER/SHADOW session/case *before* model decisions are recorded.

ADMIN-only:
- `POST /api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases` with `{"caseKey":"...","candleId":123,"newsId":"<UUID>"}`
- `GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases/{caseKey}`

Java loads the exact stored candle and symbol-linked news row from PostgreSQL. It requires a registered, still-enabled champion/challenger enrollment, PAPER mode, market candle event time no later than fetch, published news no later than fetch, both fetches prior to the server capture, market freshness of at most 30 minutes and news freshness of at most 24 hours. It freezes relevant candle OHLCV/source/time and news content-hash/source/time as deterministic SHA-256 lineage, records server receive time and authenticated actor, and disallows post-decision backfills or changed-source conflicting replays.

The existing V56 matched-decision append API is tightened: **when a source case has been captured**, it must match that exact symbol, market and news availability timestamps, the two frozen source fingerprints and the combined same-input fingerprint. Model predictions must occur after source capture and no later than decision time. Existing legacy V56 cases without a V58 capture remain readable and are still marked *unverified*: this additive slice does not retroactively confer source authenticity.

PostgreSQL/Flyway + MockMvc tests cover real table inserts, immutable case replay, mismatch, backdated/future and stale captures, unlinked news, ADMIN RBAC, source-then-decision sequence, and non-execution flags.

## Evidence boundaries

The new API reports:
- `javaDatabaseBound=true`
- `providerSignatureVerified=false`
- `sourceRightsVerified=false`
- `inferenceExecuted=false`
- `canAuthorizeCapital=false`

Java database custody **does not** establish: a provider signature, an NSE/BSE or Indian news redistribution licence, non-simulated market feed timeliness, independent provider reconciliation, a running model inference, registered forecast calibration, PAPER fill, or LIVE release readiness. Historical `market_candle` is updateable in the current schema; the frozen SHA-256 is the snapshot observed at capture, not a claim that the upstream database row will never change.

## Next PR

Connect a bounded, registered Python/Java inference-attempt worker to the *prospectively captured* case: securely claim work, record per-model timeouts/abstentions/errors, validate artifact fingerprint and model governance before and after inference, submit exactly one complete same-input roster to V56 and require broker orders to remain outside the intelligence process. Then couple the existing V57 PAPER order/fill lineage for actual eligible champion decisions. Verify real model weights and data rights separately from contract tests.

No production deployment, broker account mutation, source licence purchase or live capital action is part of this slice.
