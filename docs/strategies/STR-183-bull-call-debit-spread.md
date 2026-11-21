# STR-183 — Bull Call Debit Spread (research-only)

**Catalog:** `strategy.STR-183` (14/43). **Status: DATA_BLOCKED.** The implemented evaluator does not connect a licensed market feed or authorize any order.

## Mechanics and distinct payoff

A bullish vertical **call debit spread** buys one **lower-strike CE** and sells one **higher-strike CE**, each on the same verified NIFTY underlying, **same expiration and lot size**. The resulting credit for the higher call partially offsets the lower call premium, but caps maximum model gain. A new completed EMA8/EMA21 bullish crossing is necessary, not sufficient, and reuses the existing directional research signal.

For `lowerStrike < higherStrike` and equal lots:

- `longCost = longCE.ask × (1 + buySlippageBps / 10000)`
- `shortCredit = shortCE.bid × (1 − sellSlippageBps / 10000)`
- `debitPerUnit = longCost − shortCredit`; must be strictly between 0 and `higherStrike − lowerStrike`.
- `maxModeledLoss/lot = debitPerUnit × lotSize + combinedBothLegFeesPerLot`
- `maxModeledGain/lot = (strikeWidth − debitPerUnit) × lotSize − combinedBothLegFeesPerLot`
- `modeledBreakEvenAtExpiry = lowerStrike + debitPerUnit + feesPerLot / lotSize`
- `maxTheoreticalRewardRiskBps = 10000 × maxModeledGain / maxModeledLoss`

The labels *max gain* and *max loss* apply to a **hypothetical fully-established, held-to-expiry two-leg spread** using modeled Indian fees. Neither exchange fills nor multi-leg atomic execution, early liquidation, physical/expiry settlement or margin rules are proven. Do not treat a non-atomic proposed spread as a guaranteed risk-defined executable order. The underlying can rise without the spread earning a profit.

## Fail-closed research data requirements

- Both source-listed `NSE_FO` **CE** contracts confirmed against one `NSE_INDEX|Nifty 50` underlying, listing date, distinct ordered strikes, tick size, matching verified lot and expiry; illustrative research 3–45 calendar DTE.
- At least **35 original completed NSE daily index closes**, in ascending sessions, exchange close at 15:30 IST, same source provider and original first-known timestamps no more than 36h after close; the last close must be known before all three spot and option quotes. The adapter must independently validate holidays/special sessions and source timestamp rights, beyond weekday sanity checks.
- Three individually timestamped **same-provider** snapshots for underlying and both options, all within 180 seconds at evaluation, maximum three-leg exchange skew 90 seconds, market-hours observations, no future-known quote.
- Independent rights for NIFTY research, options research and instrument discovery. Both legs must have positive and noncrossed bid/ask, sufficient **bid and ask depth** and OI in actual lot units, controlled individual bid/ask spread and underlying-to-strike distance. Missing source data => explicit BLOCKED, never synthetic chain/Greeks.
- Explicit nonzero round-trip estimated fees/taxes/slippage for the entire pair, research capital and maximum allowable allocation. Rejected if modeled debit plus costs exceeds capital allocation, or max modeled gain/reward-risk fails the research hurdle.

Outcomes: `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`. **All** return `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Tests and remaining acceptance

11 deterministic JUnit tests cover bullish/bearish/flat conditions, no-order outputs, same expiry/lot/call identity and ordered strike, independent source rights, spread liquidity, provider mismatch, three-leg time skew, historical as-of lookahead, crossing/debit economics, premium budget, positive statutory cost requirement, fee-adjusted maximum reward and deterministic reproducibility. Fixtures use illustrative numbers, not real NSE prices. Java/backend CI is separate from test-code existence.

**Outstanding:** rights-verified canonical live option chain with two synchronizable CE quotes and true original timestamps, licensed historical replay and independent NSE session/expiry/lot confirmations, actual Indian taxes/exercise/expiry risk, realistic two-leg fill/slippage and no-lookahead walk-forward, PAPER ledger/reconciliation acceptance, Flutter and account-level LIVE F&O permissions. No order endpoints or trading flags changed.

Related: [STR-181](STR-181-long-call.md), [canonical F&O feed](../architecture/fno-canonical-strategy-feed.md).
