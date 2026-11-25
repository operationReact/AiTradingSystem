# STR-207 — Put Spread Collar Research (Strategy 38/43)

**Catalog status: `DATA_BLOCKED`. Research-only, NON-EXECUTABLE.** This deterministic evaluator models the **theoretical common-expiry** payoff of a confirmed *existing LONG NIFTY futures position* hedged by a hypothetical NIFTY options 1:−1:−1 structure: **BUY higher-strike OTM PE, SELL lower-strike OTM PE, SELL higher-strike OTM CE**. All options and the future are individually source-listed, same genuine NIFTY index, exactly matching expiry and official lot, and each has original timestamp/source and independent licensed feed rights. This is not an underlying stock/cash portfolio collar, not a newly opened future order, not a broker-margin simulation and **does not protect the portfolio against extreme losses below the lower sold-put strike**.

## Real-source requirements

Six separate research permissions: genuine broker-confirmed **existing long futures position**, licensed NIFTY index completed closes, true listed options bid/ask/order book, independently sourced original delta/annualized IV, independently listed futures market prices, and official NSE instrument master. The broker must confirm the future's actual signed **positive long lots**, real average fill/entry price, genuine instrument key, originally first-known timestamp and official lot/expiry. The broker source attestation is an input to the deterministic function; an all-true fixture is **NOT** a connection to a broker.

At least **35 true completed original-first-known NIFTY daily index close observations** are required and must be strictly ordered, actual NSE exchange-close timed, match the snapshot provider and precede the evaluation. Every original NIFTY index, futures quote, bought higher PE, sold lower PE, sold higher CE and source-original delta/IV measurement must be independently attributable to the **same provider**, authenticated listing and single synchronized market snapshot (original first-known, no future-known/stale observations or mixed providers). Quotes require genuine directional bid/ask, spread limit, positive OI, and **ask depth covering the entire long-PE lot size** and **bid depths covering the entire lower PE and higher CE short lot sizes**. Missing source evidence and missing rights fail closed.

**Exact structure:** `lowerPutStrike < higherPutStrike < currentGenuineNiftySpot < shortCallStrike`. The bought-put and sold-put wing have configurable positive minimum width. Genuine option types, key uniqueness, expiry dates, listing dates, tick alignment and official lot **must match the actually owned futures**. One structure for each verified existing long NIFTY future lot ensures the short OTM call is matched to the long futures quantity *in a theoretical matched-expiry payoff*. There is no paper/live short option permission, actual margin reservation, pre-order short-call cap, or cash/futures exercise flow established here.

## Conditional common-expiry payoff, not realized broker P&L

For `Q = verifiedFutureLots × officialFutureLot`, `F_entry` = the independently broker-confirmed historical average futures entry price (not index spot), `K_boughtPE > K_soldPE`, `K_shortCE`, current authentic `ask_boughtPE`, `bid_soldPE`, `bid_soldCE` and explicitly modeled India trading friction:

`hypotheticalDebitInr = Q × (boughtPE_ASK_after_slippage - soldLowerPE_BID_after_slippage - soldCE_BID_after_slippage) + positiveModeledFees`

Debit may be negative (a modeled credit). The research gate requires it not exceed the configured maximum net debit. There is **no guarantee of zero-cost** collar, margin sufficiency or fill at these quoted levels.

At hypothetical common option/futures expiry settlement index level `S`:

`expiryPayoffInr(S) = Q × [(S − F_entry) + max(K_boughtPE − S, 0) − max(K_soldPE − S, 0) − max(S − K_shortCE, 0)] − hypotheticalDebitInr`

Compute scenario payoffs at `S=0`, `S=K_soldPE`, `S=K_boughtPE`, and `S=K_shortCE`. For a fully matched-expiry one-to-one structure, theoretical upside beyond the short-call strike is capped at the strike payoff; **downside resumes at full futures slope below the lower short-put strike** (zero-index scenario can have huge losses despite the put-spread). The `putSpreadProtectionWidthInr` is only `Q×(K_boughtPE−K_soldPE)` at expiry **below** the sold-put strike; it is not a maximum-loss bound.

**Important limitations:** The payoff is **only conditional at a single hypothetical expiration settlement**. Real NIFTY futures vary in cash/margin daily, may require liquidation or roll, options have separate opening/closing fills, slippage and statutory charges, volatility/greeks shift, and short calls/puts can require substantial margins. The model does not include interim liquidation, futures basis, funding, broker risk controls, partial fills, expiry settlement ledger, taxes/charges beyond the assumed fixed fee or any original-time out-of-sample verified profitability. An index cannot be bought as a hedge fill.

## Test evidence and trading gates

- Evaluator: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoPutSpreadCollarV1.java`
- Tests: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoPutSpreadCollarV1Test.java`
- Manifest: `backend/src/main/resources/mind/capability-manifest.v1.json` — `strategy.STR-207`
- 20 **fictional deterministic JUnit test fixtures**, including correct premium after slippage/fees, extreme-downside re-exposure, upside cap, genuine matching three legs/future, source provenance, real depth, nonzero long future position, expiry/lot/strike/bid-ask/IV/OI/time restrictions, budget and no-order invariants. Synthetic unit fixtures are **not** proof of licensed NSE data or a real trading edge.

Every decision remains `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false` and catalog `DATA_BLOCKED`. Paper requires independently verified genuine market data/master, full actual options and futures historical quote data, exchange holiday/corporate action handling, broker signed positions/ledger, official margin and short option settlement models, multi-leg partial-fill/legging controls and OOS walk-forward after-cost scenarios. Separately authorized LIVE requires current F&O write permissions, cash/collateral/margin checks, kill switches and user acceptance. **This change does not touch AWS, SSM, broker flags, order endpoints, Flutter, deployment or APK.**
