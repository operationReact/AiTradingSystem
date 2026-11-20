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

The PR includes 19 JUnit regression tests covering both divergence patterns, correlated price/OI movements, neutral steps, contradiction, low volume, outliers, provider/contract/expiry/rights failures, incomplete session or unverified settlement, future-known observations, invalid OI/price, duplicate/out-of-order sessions, threshold validation and reproducibility.

**Not yet verified:** real licensed provider price/OI source receipts; original final-OI publication timestamps for historical bars; exchange holiday/expiry/corporate-action checks; no-lookahead walk-forward, slippage/fees, OOS statistics, actual runtime readiness, PAPER strategy validation, any order authorization, or Flutter device acceptance.

**Integration design:** [Canonical 43-strategy F&O data fan-out](../architecture/fno-canonical-strategy-feed.md). The evaluator takes authorized normalized inputs; it must not have an independent 44th WebSocket or raw broker token.
