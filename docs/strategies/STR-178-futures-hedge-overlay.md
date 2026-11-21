# STR-178 — Futures Hedge Overlay (research-only)

**MintTick Mind catalog:** `strategy.STR-178`, F&O strategy 9 of 43. **Status: DATA_BLOCKED**, even when Java unit tests pass. This is a read-only, deterministic index-futures hedge **scenario screen**. It is not a direct order path, portfolio rebalancer, guaranteed risk reduction, or authorized source entitlement.

## Distinct purpose

STR-170–177 observe price, open-interest, carry and related features of futures. STR-178 instead evaluates **existing portfolio index-beta risk** and estimates the feasibility of hedging that exposure using the **same NSE index's futures contract**.

- **Positive beta-adjusted index exposure:** model short index futures (not cash-stock or index spot sale).
- **Negative beta-adjusted index exposure:** model long index futures.
- **Already neutral, too small for one lot, missing margin or market depth, extreme quoted spread, adverse stress economics:** do **not** suggest a hedge.
- **Opposite index or single-stock future:** reject. A future contract for a different index is not a validated cross-asset hedge; this evaluator does not silently infer an empirical hedge ratio.

The output is a **hypothetical** `RESEARCH_CANDIDATE`, `NO_HEDGE`, `INFEASIBLE`, `BLOCKED_RIGHTS`, or `BLOCKED_DATA`. Every result has `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Authorized input requirements

1. One exact provider-verified `NSE_FO` **index future**, canonical `NSE_INDEX` identifier, source-listed expiry (3–100 days remaining) and actual lot size. Use the *same underlying index key* as the verified portfolio beta model.
2. Portfolio risk snapshot: **signed beta-adjusted index exposure in INR** with reconciled cash holdings, existing options/futures and other existing hedges **already included**; independently reviewed beta model, original calculation and first-observed timestamps no older than five minutes. This is NOT just gross portfolio value.
3. Current authorized provider `UPSTOX_V3` or `ANGEL_ONE_SMARTAPI` index-futures L1 **bid and ask**, exchange and observation times, current OI, depth in underlying units, verified active session and source provenance. No synthetic, delayed or mixed quote source. Exchange event must be at most 15 seconds old.
4. Independently verified **internal research** rights for futures quotes, instrument discovery and portfolio research. Distinct customer-display, storage, ML-training, LIVE order rights and account trading-segment approval are NOT granted or inferred.
5. Actual broker/free-cash balance, initial margin per lot, **stressed variation-margin reserve per lot**, all verified and >0 where required.
6. Reviewed stress scenario with explicitly calibrated signed index shock (bps), absolute basis-risk reserve per lot (INR), complete round-trip execution cost per lot, maximum room-level hedge lots, allowed bid/ask spread and minimum economic stress-risk reduction (bps).

## Feasibility calculation

- For a **short** index future, estimate one-lot notional using the source **BID × source-listed lot size**. For a **long**, use the source **ASK × lot size**. This is an estimate and ignores adverse impact beyond declared reserves.
- Evaluate integer lots from **1 to maxHedgeLots (max 100)**, enforcing whole-lot order-book depth on the relevant side, available funds for **initial + variation-stress margin** and no crossing from positive to negative portfolio beta (or vice versa). Ignore any size that would overhedge.
- `riskBeforeINR = abs(signedBetaExposureINR) × shockBps / 10000`.
- `residualSignedBetaExposureINR = signedExposure − sign(signedExposure) × lots × oneLotNotionalINR`.
- `stressedRiskAfterINR = abs(residualSignedBetaExposureINR) × shockBps / 10000 + lots × (basisStressPerLotINR + roundTripCostsPerLotINR)`.
- Choose the **best** whole-lot count (the lowest modeled stressedRiskAfter) among eligible candidates. Require stressed loss improvement and explicit minimum **`(riskBefore − stressedRiskAfter) / riskBefore × 10000`** basis points for a research candidate.
- Failure to fund one eligible lot yields `INFEASIBLE`; reduction below the policy hurdle yields `NO_HEDGE`.

**These are simplified one-factor index-shock stress equations**, not a real-world risk model or covariance estimate. They assume linear beta exposure and the futures sensitivity to its reference index. They do not capture jump risk, index tracking error, correlation break, liquidation and spread changes, dynamic margin add-ons, option convexity, basis widening, exchange settlement timing, expiry rolls, trading freezes or partial/mismatched fills. The model therefore must never authorize a hedge trade by itself.

## Deterministic test fixtures (not production risk limits)

22 JUnit tests cover positive and negative exposures, index neutrality, overhedging refusal, max-lots/available-capital restrictions, per-side order-book depth, spread limits, adverse basis shock and fees, minimum-stress reduction hurdle, all three data-use rights, portfolio reconciliation and existing hedge inclusion, beta model, stale and future-known positions, wrong index or equity future, instrument validity, provider/market-session and quote time checks, missing OI/negative depth, funding/stress attestation, invalid scenarios, determinism and strict nonexecution.

The positive fixture models **₹10 lakh long index-beta exposure**, **₹20,000 bid**, **25 shares per lot** (₹5 lakh index-future notional), **2 hypothetical short futures lots**, a **5% index shock**, and **₹1,500 per-lot basis/cost stress**. The model computes ₹50,000 prehedge stress and ₹3,000 stressed after-hedge cost, but this is only a mathematical fixture: it is NOT a verified broker hedge, actual P&L, or proof of a 94% hedge effectiveness in NSE markets.

## Pending production and PAPER acceptance

- A live rights-verified source adapter must provide actual exchange-index contract/lot/expiry, bid/ask and order-book depth, exchange timestamps, OI, cash availability and verified **broker margin calculation**. Respect common provider subscription caps and reconnect behavior.
- The portfolio engine must calculate and audit **correct current beta risk**, include existing derivates/options and enforce room-level exposure policies; portfolio value alone is not enough. A one-factor model is inadequate for many options or cross-sector portfolios.
- Test under **real historical index/portfolio co-moves**, estimate changing basis/correlation and imperfect execution, stress gaps, price impact, fees, and margin hikes. Perform no-lookahead out-of-sample and walk-forward validation.
- Require audited, separately authorized **PAPER** risk engine/positions/reconciliation and independent exchange/account LIVE execution approval before any actual orders.
- Flutter/Android/iOS/web UI and deployment evidence are not part of this backend-only pull request.

The research implementation belongs in the shared rights-gated [F&O canonical strategy feed](../architecture/fno-canonical-strategy-feed.md). It must not independently create an additional raw WebSocket connection.

## Source-backed research integration — 21 November 2026

`FnoFuturesResearchService.evaluateAll` now calls the **distinct**
`FnoFuturesHedgeOverlayV1.evaluate` and uses existing Upstox V3
exchange-timed index-futures quotes, plus the new separately attested
`FnoIndexHedgeResearchStore` for source-reconciled portfolio exposure,
existing hedges, bid/ask book depth, OI, margin stress, costs and
room-level lot limits.

V103 creates an **empty, account-scoped, immutable** evidence table. It
requires at least two currently verified provider-specific independent
`MODEL_INFERENCE` rights receipts for the futures-depth and reconciled
portfolio-beta source domains. It does not automatically ingest positions,
model beta, assume bank cash, set risk limits, authorize trading or
manufacture account balances. Explicit account scope and source-use flags
default to unconfigured / `false`. Snapshot/custody proof is at most
one-day retained, with only retention-expired deletion permitted.

The adapter checks current source-master expiry/lot/underlying,
open F&O exchange session, a fresh live futures BID/ASK, exact quote
price agreement with attested depth, <=10 second exchange-time skew
and matching provider/account/index identity before supplying the
read-only hedge evaluator. A research candidate is an *illustrative
post-cost stress reduction*; it never requests broker orders.

**Known unresolved evidence:** actual independently verified broker
account positions, existing options/futures overlays, approved current
beta model and rebalance math, live source-backed depth/OI/margin,
fresh source rights grants, realistic fee/risk scenarios, genuine
no-lookahead walk-forward outcomes, broker reconciliation and separately
approved PAPER/LIVE trading. No production provider account is certified
by deterministic Java fixtures. The catalog remains `DATA_BLOCKED`.
