# V4-09 — Exact instrument identity and pre-decision feature freeze

This increment hardens the merged [PR #402](https://github.com/operationReact/AiTradingSystem/pull/402) V60 six-feature projection. It addresses two causality issues before integrating the actual Java-to-Python trained inference worker.

## Exact instrument identity

A ticker string alone cannot identify a particular tradable instrument over time. The previous-candle query is now constrained by **all** of the current V59 candle's instrument ID, symbol token, trading symbol, exchange and candle interval, plus the chronology and acquisition cutoffs. The earlier candle must be at least **one full declared candle interval** before the current candle (ONE_MINUTE through ONE_DAY are explicitly handled); a just-started partial bar is not eligible for the previous-bar return. The model feature fingerprint now includes the frozen current candle's instrument ID and token. This prevents a feature from silently using another instrument's previous price with the same ticker symbol.

The V59 source contract does not independently attest original exchange token and legal source custody. This patch remains a guard against internal identifier confusion, not an exchange signature.

## No post-decision feature backfill

V60 `POST .../features` must run before any V56 matched-case model decisions for the same session/case; it may never retrofit feature values to make a past model decision look causally supported. V60 reads the frozen V59 source with PostgreSQL `FOR UPDATE`, then checks for a previously recorded V56 matched case. V56 decision append reads that same V59 source with `FOR SHARE` until its transaction completes. Both operations first take the V58 session safety shared lock, ensuring consistent lock order with pause/quarantine.

This means a concurrent model append and feature projection serialize on the V59 source row. If the decision commits first, the feature POST rejects it; if the feature projection commits first, the decision may follow. We use READ COMMITTED so the V56-existence check sees the latest committed records after obtaining the source lock.

A feature projection created before decisions remains immutable and readable through GET; subsequent POST after a decision is rejected even if the original data has not changed. No model inference/stock order is started here.

## Acceptance tests

PostgreSQL/Flyway integration tests cover: same-instrument prior candle, different symbol token with matching ticker (rejected), incomplete prior candle interval (rejected), POST after completed V56 decision with no feature record (rejected), POST replay after already frozen V60 feature and committed V56 decision (rejected), GET still reads the original frozen projection, and the existing malformed/stale/late news/market, PAUSE and ADMIN RBAC regression suite.

This is a V4-09 **safety hardening step only**, not proof of genuine licensed NSE/BSE data, verified model runtime, economic edge, PAPER/SHADOW soak or LIVE authorization. Java remains sole broker/risk authority; Python scores research evidence only.
