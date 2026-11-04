# V4-09 — Java prospective research feature capture (V60)

## Why

Merged PR #400 can execute **real** frozen CatBoost/LightGBM research artifacts from an allowlisted private Python worker. PR #396 V59 established immutable Java custody of an actual `market_candle` and linked `news_item`. The missing bridge was a **frozen point-in-time feature vector** derived from existing Java tables, rather than arbitrary client-authored features.

This PR introduces `v4-java-prospective-research-features.v1` as a dedicated PostgreSQL V60 snapshot. It is not order submission, independently verified data permission, or a Java-to-Python runtime invocation.

## Strict implementation

- `POST /api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases/{caseKey}/features`: materializes **once**, with full ADMIN RBAC, deterministic hash, audit, registered model/Java PAPER-only governance, and V58 shared session safety row lock. If PAUSED/QUARANTINED, no new snapshot.
- `GET` at the same route: reads the original immutable predecision feature values, including after a session is paused; never changes a record.
- The service loads the V59 frozen market/news source IDs and hashes and independently recomputes the current source fingerprints. If the underlying candle/news changed since original capture, it **fails closed**.
- It requires a **strictly earlier** same-exchange/symbol-token/interval candle whose acquisition happened before the original source capture; missing earlier price data fails closed.
- It requires a linked `news_ai_evidence` row with source availability no earlier than the news fetch, generated timestamp no later than the V59 capture, and valid causal chronology. No historical backfilled AI opinion may be silently assumed as available.
- It computes the six base feature columns of `DEFAULT_BASELINE_FEATURES`: market.return_1 (latest close / earlier close minus 1); market.range_pct ((high-low)/latest close); market.volume_change_1 (latest/previous volume minus 1, 0 if previous volume=0); event.AI_NEWS_EVIDENCE.direction_score, confidence and materiality. No future market returns, trading labels or order outcomes enter the vector. Python still requires the **exact pinned trained feature set**, not just these six fields.
- Derived values use deterministic decimal rounding before fingerprinting and persistence. V60 freezes immutable values/feature fingerprint, previous candle and AI-evidence IDs, source input hash, decision snapshot/cutoff and recorded actor/time. Replaying cannot replace them. Capturing features after a model decision is refused, even if original V59 source exists.
- The report sets `javaDatabaseBound=true` but `providerSignatureVerified=false`, `sourceRightsVerified=false`, `modelInferenceExecuted=false`, `canAuthorizeCapital=false`.

## What remains

The next code-only vertical slice must use a trusted Java internal worker with a bounded HTTP timeout and opt-in private token to send **exactly these frozen six features**, enrolled model version UUID and original source fingerprint to PR #400's Python inference endpoint. It must verify the real model ID, artifact identity, capital false, response fingerprint and actual server timing; then durably record every model's result or failure under V56/V58, and append a matched case only after all candidate decisions are causally complete. Subsequent PAPER orders/fills, broker reconciliation and actual runtime soak remain separate operational work.

This implementation does not require paid data subscriptions or automatically enable trading. It is a fail-closed foundation for later development and actual Indian-source license acceptance. Do not mistake synthetic tests or database custody for independently verified publisher permission or economic profitability.

## CI / tests

Java 21/PostgreSQL/Flyway MockMvc regression tests validate the exact six features, immutable readback, before/after source timing and actual earlier bar, missing AI/missing previous bar, mutable-source readback, ADMIN-only access and PAUSED session refusal. All V4 full-phase tracking remains open until actual simultaneous inference and real PAPER/SHADOW acceptance are evidenced.

No broker order, LIVE switch, paid subscription or production deployment occurs in this PR.
