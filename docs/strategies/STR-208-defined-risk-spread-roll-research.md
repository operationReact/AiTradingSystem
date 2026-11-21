# STR-208 — Defined Risk Spread Roll (Strategy 39/43)

**Status `DATA_BLOCKED`, NON-EXECUTABLE source-attested research.**

The scope is deliberately precise: roll a *BROKER-CONFIRMED EXISTING LONG NIFTY bear-put debit vertical* (BUY high-strike PE / SELL low-strike PE) into a hypothetical **later-expiry** long NIFTY bear-put debit vertical, with exactly the same official option lot size and original independently listed NIFTY instrument identities. It **does not trade**, does not recommend a guaranteed profitable roll, and does not claim an observed fill, margin reservation or actual realized portfolio returns.

## Genuine data rights

Require six independent, actually permitted data and research rights: reconciled broker existing two-leg option positions with original entry debit and signed matched long/short lots; source-verified current NIFTY index; original first-known completed NIFTY closes; licensed original live NSE-listed option chain; original provider-supplied put deltas and annualized IVs; and official NSE option expiry/lot instrument master. A caller's `sourceVerified=true` marker is a necessary input attestation, **not proof a production provider is connected**.

- Both existing vertical legs are authentic NIFTY PEs, have **the same original expiry**, official lots and at least the minimum put-wing width, with a higher-strike long PE and lower-strike short PE.
- Both candidate later-dated vertical legs are equally genuine NIFTY PEs, the same new expiry, same official lot as the old vertical, correct long-higher/short-lower ordering and a strictly later expiry by at least the policy extension. No mixed index, expiry or lot sizes.
- Broker-confirmed current position must have a **positive long-lot count equal to the magnitude of the paired short-lot count**, original identified keys, original actual positive entry debit below original vertical width, and broker first-known timestamp prior to snapshot. No orphan shorts or mixed portfolios.
- Original chain quotes for all **four** unique contracts include provider, original exchange and first-known times, true BID/ASK, original per-unit negative put delta, annualized IV, open interest and *directional depth covering the entire existing position*: sell old long at BID; buy-to-close old short at ASK; buy new long at ASK; sell new short at BID. Never substitute LTP, ask-side fill for selling, fabricate order-book depth or assign synthetic Greeks. Quotes must match the same provider and be fresh, synchronized, and originally known.
- At least **35 original, completed, strictly ascending NIFTY closes** from this provider at proper exchange-close timestamps, each originally first-known before the current market snapshot. Compute the worst observed day decline from the actual completed close series. A policy minimum down-move is required for this specific research scenario. That past decline is **not** an estimate of future option success.

## Deterministic four-leg accounting (hypothetical only)

For verified existing lots `L`, official lot units `Q=L×lotSize`, and directional slippage on true source quotes:

`oldCloseCredit = Q×(oldLongBidAfterSellSlippage−oldShortAskAfterBuySlippage)`.

`newOpenDebit = Q×(newLongAskAfterBuySlippage−newShortBidAfterSellSlippage)`.

`netRollDebit = newOpenDebit−oldCloseCredit+positiveFourLegFees`.

`newWingWidth = Q×(newLongStrike−newShortStrike)`.

`modeledNewVerticalMaximumLoss = newOpenDebit+positiveFourLegFees`.

`modeledNewVerticalMaximumGain = newWingWidth−modeledNewVerticalMaximumLoss`.

`modeledWholeLifecycleNetDebit = originalBrokerReportedEntryDebit+netRollDebit`.

These results are *gross-index-option-expiry* spread approximations for a **future hypothetical new position**. They do not represent a cost-certain order, actual futures/stock hedges, actual Indian taxes, broker margin/collateral sufficiency, liquidation outcomes, a real executed broker ledger, historical P&L, or a guaranteed roll. The total lifecycle debit includes actual original reported debit but hypothetical later cash flows; it is **not** a proven maximum lifetime drawdown. Real partial fills, spread-leg risk, market movements, lack of margin, order cancellations and forced exits can create different outcomes. Use original source quotes and portfolio models for actual rigorous validation before activation.

The new spread must be a **strictly positive debit below its gross expiry width**, its modeled new maximum loss must be within a cap and its modeled maximum gain after fees strictly positive. The signed **net roll debit** (including credit if negative) must not exceed the declared positive-roll budget. Insufficient historical downside, model cost, empty liquidity, bad expiry, stale provenance or mixed broker confirmation fail closed or produce `NO_ROLL`. No strategy trade can be authorized by test fixtures.

## Evidence and acceptance

- Source: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoDefinedRiskSpreadRollV1.java`
- Unit tests: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoDefinedRiskSpreadRollV1Test.java`
- Catalog: `backend/src/main/resources/mind/capability-manifest.v1.json`, `strategy.STR-208`.
- 22 deterministic JUnit scenarios with deliberately **fictional fixture instruments and quotes**, including original broker right and lot balance; exact four-leg quote-side arithmetic; two-lot portfolio cap; expiry extension; official lot/strike; source delta/IV/OI/depth; duplicate identities; history first-known and provider consistency; downside gate; maximum-loss budget; non-executable state.

Every outcome unconditionally returns `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`. The manifest remains `DATA_BLOCKED`. The strategy does not modify broker flags, login, orders, AWS, SSM, workflows, app UI, deployment or APK.

**Still needed:** genuinely licensed original-time NSE F&O master and live/historical chain, reconciled broker option holdings/fill ledger/cost basis and cash/margin positions, original-first-known complete historic series with exchange calendar and corporate action handling; real legging and cancel/rollback/risk control, actual Indian fees, actual after-cost out-of-sample and walk-forward evidence, independent paper multi-leg execution/settlement and UI evidence; separate user-authorized live F&O write rights, order kill switches and margin guardrails. **38/43 code catalog does not mean 38 production-ready trading strategies.**
