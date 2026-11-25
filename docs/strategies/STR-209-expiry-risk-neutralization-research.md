# STR-209 — Expiry Risk Neutralization (40/43)

**Status: `DATA_BLOCKED`; NON-EXECUTABLE research only.** The deterministic evaluator models an all-leg **hypothetical close-out** of an existing, *broker-confirmed complete NIFTY options position book for ONE authentic expiry* before the *original-source verified last trading cutoff*. Its calculated delta and gamma are exposure diagnostics, **not instructions to open delta-hedging positions**. All results deny order and PAPER/LIVE authorization.

## Research scope, not execution

The evaluator requires six independent rights: (1) complete verified broker expiry-book positions, (2) licensed NIFTY index history, (3) genuine original NSE F&O chain and bid/ask market, (4) original option Greek delta/gamma and IV, (5) official listed instrument master with true lot/tick/expiry, and (6) actual original-source official expiry schedule/cutoff. Source booleans are *caller attestations*, not a live backend data connection or audited exchange feed. No provider is assumed to be operational.

- The **entire specified expiry** is broker-attested to be complete, with no missing or partially reported CE/PE contracts. The source broker positions must be original-first-known, nonzero signed positive/negative lots, independently verified, and within bounds. Reject missing, duplicated, unverified or after-snapshot positions.
- Every contract key must be a source-listed real NIFTY `NSE_FO|` CE or PE, original instrument listed date, original official lot/tick/strike and exactly the **same** officially evidenced expiry date; no silently inferred weekly expiry weekday or fabricated expiry cutoff.
- Current NIFTY index snapshot and exact one matching original option quote **for every existing broker leg** (no extras or duplicates), same declared provider, synchronized original exchange times and original first-known times. Quotes include fresh true bid/ask, source-original **per-underlying-unit delta**, original gamma **per underlying index point**, annualized IV, OI, authenticated directional bid/ask depth covering the **entire signed lot count**, and spread bounds. No synthetic Greeks, LTP substitutes or invented broker positions.
- At least **35 fully completed original-first-known NIFTY index daily closes** from the same provider, with genuine 15:30 IST market close timestamps preceding the current snapshot, ascending sessions and no duplicate/missing/unoriginal observations. Production-level independent official holiday/calendar evidence is still outstanding.
- Authentic `officialLastTradableAt` comes from licensed expiry calendar/instrument lifecycle, must match the actual contract expiry date and must be originally first-known before the current as-of; the research close window is a configurable number of **minutes before that official cutoff**. If outside the window, the outcome is `OUTSIDE_CUTOFF_WINDOW`, never a new trade.

## Hypothetical closing cash flow, not a realized fill

For each broker-confirmed existing position `signedLots`, authoritative lot size `L`:

- **Existing long** (`signedLots > 0`): theoretical `SELL to CLOSE` at genuinely sourced **BID** minus sell slippage, bounded by genuine **bid-side depth**.
- **Existing short** (`signedLots < 0`): theoretical `BUY to CLOSE` at genuinely sourced **ASK** plus buy slippage, bounded by genuine **ask-side depth**.
- `hypotheticalNetCloseDebit = SUM(-signedLots × L × sideOfBookEffectiveQuote) + SUM(strictlyPositivePerLegFees)`. Positive is modeled debit to close; negative is modeled credit. It is **not broker fill cash flow**, and any multi-leg execution may be non-atomic or unavailable. If the positive debit exceeds the configured budget, return `NO_ACTION`, never bypass safety or pretend a close occurred.

Original as-of portfolio option exposures are strictly:
- `netOriginalDeltaUnits = SUM(signedLots × genuineOfficialLot × originalUnitDelta)`.
- `netOriginalGammaUnitsPerIndexPoint = SUM(signedLots × genuineOfficialLot × originalUnitGamma)`.
- `closestStrikeDistanceBps = MIN(ABS(realStrike - realIndexSpot) / realIndexSpot × 10000)`; this is an observation only, not proof of pinning or risk magnitude.

At hypothetical expiry settlement index `S`, **gross intrinsic liability/payoff only** is `SUM(signedLots × lot × optionIntrinsic(S))`. Compute bounded study points at index **zero**, the authentic current spot, and current spot ±20%. These are **not** realized P&L, not the mark-to-market of the existing book, not exchange settlement ledgers, and do not include original position entry cost, actual fees, funding, volatility/time value, gap risk, liquidation or actual fills. `potentiallyUnlimitedUpsideShortCallLiability` checks the **net large-index call-slope**: if there are more net short CE underlying units than long CE units, upside loss is unbounded in this static expiry-payoff calculation. The finite 20% stress point is **not a maximum loss bound**.

**`RESEARCH_CLOSE_ALL` only means all original-source research gates pass and the entire source-attested expiry book has a hypothetical quoted close within the configured cutoff/cost controls.** It does not instruct the broker, authorize PAPER, cancel an order, or guarantee that a book becomes flat. If another position or futures leg exists outside the broker-attested expiry scope, these calculations make **no assertion** about the overall portfolio being neutral.

## Evidence and real deployment blockers

- Source: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoExpiryRiskNeutralizationV1.java`
- Tests: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoExpiryRiskNeutralizationV1Test.java`
- Catalog: `backend/src/main/resources/mind/capability-manifest.v1.json`, entry `strategy.STR-209`
- 20 deterministic JUnit cases, **fictional unit-test option and expiry fixtures**, not a genuine market audit. Test cases enforce complete existing broker book, original first-known source provenance, source cutoff, signed CE/PE and depth, real source Greeks and directions, full quote matching, gross downside/upside intrinsic stress and no-order invariants.

Every output reports `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`. Catalog remains `DATA_BLOCKED`. To enable independently verified PAPER, first obtain actual licensed historical and current NSE F&O book/Greeks, real authenticated broker inventory (including correct expiry and settlement), source official last-trading session proof and exchange holiday calendar, order-book depth, broker margin/charges, portfolio kill-switch and multi-leg full close/cancel/partial-fill/rollback evidence, a full paper ledger and Flutter UI acceptance. LIVE requires separately verified broker F&O sell-to-close/buy-to-cover rights, collateral, cash, margin and user authorization. Also prove original-first-known out-of-sample/walk-forward expiry close and settlement scenarios, gap and spread costs with actual exchange timestamps. **No AWS, SSM, broker flags, orders, app deployment or APK is changed.**
