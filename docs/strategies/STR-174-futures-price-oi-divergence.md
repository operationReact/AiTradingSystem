# STR-174 — Futures Price / Open Interest Divergence (research-only)

**Catalog ID:** `strategy.STR-174`, fifth of 43 dedicated F&O strategies. **Readiness: DATA_BLOCKED** until authorized source integration and historical validation. This implementation is a pure Java 21 evaluator, with no broker/order code or Flutter UI changes.

## What the strategy actually observes

The same NSE futures contract can show price and open interest moving in different directions. A five-session pattern where the **futures price rises and OI falls**, or **futures price falls and OI rises**, is a *price/OI divergence observation*. Market participants sometimes interpret the former as short covering and the latter as short buildup; those are **unverified interpretations**, not independently observable position identities, recommended trade sides or guaranteed predictive edges.

The evaluator deliberately emits only `PRICE_UP_OI_DOWN`, `PRICE_DOWN_OI_UP`, or `NONE`. It cannot submit PAPER or LIVE orders; `executable=false`, `orderPlaced=false`, and `directionalBuyOrSell=false` for every result.

## Strict as-of data and rights contract

An upstream server-side, rights-gated canonical provider adapter must supply:

- **One verified listed NSE_FO future** with stable contract key, canonical NSE index/equity underlying, expiry and lot size. Expiry must remain 7–120 calendar days away at evaluation; mixing front/next futures across a rollover is prohibited.
- Exactly **five ordered, verified complete NSE exchange sessions**, same futures key and same authorized provider (UPSTOX_ANALYTICS or ANGEL_ONE_SMARTAPI). Bounded calendar gaps and weekdays are checked locally; official NSE holidays and special trading sessions must be verified by the upstream session adapter.
- **Verified finalized futures close/settlement price** and **finalized OI** for every session with their own original observed/publication timestamps, both no earlier than exchange close and no later than the evaluation instant. Provider/archive availability after the event must never be backdated in a historical replay.
- OI and traded volume in **consistent normalized underlying units**, strictly positive OI, nonnegative volume, and independently verified research-use rights for final price, finalized OI and contract discovery. Customer viewing, saving, model training and LIVE order execution have separate entitlements/consent.
- An explicitly versioned six-field research threshold configuration (cumulative price move in bps, cumulative OI change in bps, minimum 2–4 confirming steps, per-session volume/OI floor, max step price change and max step OI change).

## Deterministic scoring

- For every adjacent completed session, calculate `10,000 × (current−previous) / previous` for price and OI.
- Count **price-up/OI-down** steps and **price-down/OI-up** steps separately. Zero-change steps can be neutral; any other contrary step or appearance of the opposite type prevents qualification.
- Require the respective net cumulative price and OI moves from first to fifth to exceed explicit magnitude thresholds (with opposite signs), the confirming step count, and minimum traded-volume/OI participation for **all five sessions**.
- Reject outlier session jumps before signal evaluation. Historical OI unit changes, corporate actions, stale/future-known observedAt, unverified settlement, missing session/contract/source rights, changed futures key and publication latency violations fail closed with a precise reason code.
- No candidate is created merely because price and OI both rise, both fall, stay flat, or change inconsistently.

**Fixture threshold example (not calibrated for real trading):** minimum cumulative price move 500 bps, cumulative OI move 500 bps, three confirming steps, 2500 bps minimum session volume/OI, 2500 bps max single-session price change and 5000 bps max single-session OI change.

## Evidence and remaining tasks

The deterministic evaluator includes 21 JUnit regression tests covering both divergence patterns, correlated price/OI movements, neutral steps, contradiction, low volume, outliers, provider/contract/expiry/rights failures, incomplete session or unverified settlement, future-known observations, invalid OI/price, duplicate/out-of-order sessions, threshold validation and reproducibility.

**Not yet verified:** real licensed provider price/OI source receipts; original final-OI publication timestamps for historical bars; exchange holiday/expiry/corporate-action checks; no-lookahead walk-forward, slippage/fees, OOS statistics, actual runtime readiness, PAPER strategy validation, any order authorization, or Flutter device acceptance.

**Integration design:** [Canonical 43-strategy F&O data fan-out](../architecture/fno-canonical-strategy-feed.md). The evaluator takes authorized normalized inputs; it must not have an independent 44th WebSocket or raw broker token.

## E2E-2 source integration increment (21 November 2026)

`FnoFuturesResearchService` now invokes the **distinct**
`FnoFuturesPriceOiDivergenceV1.evaluate(...)` as STR-174. The dedicated source
is `FnoVerifiedFinalPriceOiSessionStore` and empty Flyway migration
`V100__fno_verified_final_price_oi_sessions.sql`.

- Final-price and finalized-OI original first-availability timestamps are **separate,
  required fields**. V99 bid/ask snapshots, market_candle close with fetch time, LTP
  and reconstructed post-hoc OHLC are **never** a valid stand-in.
- One NSE_FO contract (underlying, lot, expiry) is reconciled against the currently
  loaded instrument master. Multiple providers, reordered or repeated sessions,
  missing exchange-calendar days, and changed expiry/lot are rejected.
- SQL demands pre-existing immutable final-session evidence, two independently
  verified and *currently valid* `golden_source_rights_receipt` rows for
  `nse_fo_final_price` and `nse_fo_final_oi`, a compatible authoritative
  `nse_fo_trading_calendar` session record, and source evidence SHA256 digests.
  Rights, calendar verification, evidence capture and each value's original
  availability must all precede the requested as-of time. Retention must be valid.
- No data is seeded, no automatic vendor consumer, historical backfill promotion,
  user-facing signal, paper fill, live order, runtime rights grant, AWS action or
  market-data service polling is introduced. Without independently verified price
  and OI records, STR-174 remains `BLOCKED_RIGHTS` or `BLOCKED_DATA`.
- Read-only query and retention-only purge are the only runtime operations on V100.
  Rows are otherwise immutable; expired rows can be erased after their retention
  deadline.
- The research evaluator now applies 15:40 IST derivatives normal close from
  2026-08-03, and 15:45 IST from 2026-12-07 for non-expiring contracts (the
  evaluator requires at least seven days to expiry).
  NSE reference: https://noticeblue.com/circulars/b0a5e4ce-04d8-4e1c-82b9-d39257a90a1e
- The current threshold tuple 500/500/3/2500/2500/5000 is inherited from
  deterministic examples. It is **not** calibrated on real outcomes.

**Independent remaining acceptance evidence:** attested ingestion of real
final close and finalized OI with separate first-known source receipt times,
population and reconciliation of calendar and rights provenance, verified
no-lookahead replay over multiple genuine expiries, out-of-sample effect sizes,
risk/fees/slippage and separately authorized PAPER/LIVE workflows. The
`/api/admin/research/fno-43/readiness` endpoint reports five direct evaluator
paths, not five live-trading-ready strategies.
