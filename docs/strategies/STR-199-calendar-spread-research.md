# STR-199 — Calendar Spread (Strategy 30/43)

**State: DATA_BLOCKED.** This is a deterministic, **research-only** *long calendar (time spread)* evaluator. It does not create broker orders or turn on PAPER/LIVE execution.

## Structure and genuinely different expiries

**SELL one nearer-expiration NIFTY CE or PE option** and **BUY one farther-expiration option with exactly the same strike, same option type, same underlying and same official lot size**. The option contracts must be distinct listed NSE instruments with real verified expiry and original source metadata. The near option must expire **before** the long farther option; a same-expiry vertical or mismatched strike is NOT a calendar spread.

The configured research horizon illustratively requires 3–35 calendar days until the near expiry, 10–90 days until far expiry, and 7–60 days between expiries. The genuine NIFTY underlying quote should be near the common strike (ATM within a configured basis-point tolerance). Both CE and PE calendar research variants are supported.

This is **not** STR-198's same-expiry 1:−2:1 butterfly and cannot reuse a standard common-expiry intrinsic profit formula.

## Net premium, time-structure screening and unresolved P&L

For option lot Q, true short-near source bid and long-far source ask, explicit buy/sell slippage and a *nonzero* full two-contract India exchange/statutory fees estimate:

- `shortCreditPerUnit = nearBid × (1 − sellSlippageBps/10000)`
- `longCostPerUnit = farAsk × (1 + buySlippageBps/10000)`
- `afterCostNetDebit = Q × (longCostPerUnit − shortCreditPerUnit) + twoLegFees`

The research rejects nonpositive debit, net debit above an explicit research budget, independently sourced `nearIV / farIV` below its configured IV-term-structure ratio, and `shortCreditPerUnit / longCostPerUnit` below its configured premium coverage threshold.

A transparent **illustrative** near-expiry one-sigma index move uses `spot × nearIV/100 × sqrt(calendarDaysToNearExpiry/365)`, screened against a configured maximum share of index spot. It is **not** a prediction of profit, a probability of a successful calendar, or a guarantee that implied volatility predicts future movement.

**There is no common expiry date for the two options.** When the near option expires, the far option still carries unknown remaining time value, IV, liquidity and marks. This evaluator therefore **does not report a supposed fixed final-expiry profit, fixed maximum realized loss or static breakeven from intrinsic-only formulas**. The debit is the initial modeled capital outlay, **not** a broker margin requirement or verified worst-case liquidation loss. Overnight gaps, settlement, calendar vol surface repricing, exercise, partial fills and forced liquidation require independent realistic validation.

Every Decision, including `BLOCKED_DATA`, `BLOCKED_RIGHTS`, `NO_SIGNAL` and `RESEARCH_CANDIDATE`, includes `nearExpiryResidualLongMarkUnknown=true`, `executable=false`, `orderPlaced=false` and `liveTradeAuthorized=false`.

## Authentic source, IV and as-of controls

- Four licensed rights: real NIFTY index data, option chain, instrument discovery and *independently licensed source-reported annualized implied volatility as a PERCENT* for both expiration series. A source IV of 35 means 35%, not 0.35%; do not fabricate one from LTP.
- 35–512 genuinely completed 15:30 IST NIFTY historical index observations in ascending date order with real `firstKnownAt` and `exchangeCloseAt`. No future-known bars, duplicate dates or mixing providers. Official NSE holiday/special-session truth must be attested upstream.
- Three contemporaneous provider-consistent original as-of quotes: actual NIFTY index plus near-expiry sold option and farther-expiry purchased option. Both listed contracts need authentic original quote/exchange time; max quote age 180 seconds and overall cross-quote exchange-time skew 90 seconds.
- Genuine positive noncrossed option bid/ask, spread sanity, **bid depth for the sold nearer option** and **ask depth for the bought farther option**, actual open interest and positive reasonable annualized option IV for each separate expiry.
- Missing licensed source rights, valid expiry metadata, authentic timestamps, original depth/OI, IV or transaction costs fails closed rather than generating a synthetic trading signal.

## Tests and blockers

14 deterministic JUnit cases validate candidate same-strike CE calendar, actual PE variant, four separate research rights, rejected wrong type/strike/lot/expiry/order, original-completed-history lookahead/duplicates, provider and independently sourced IV, direction-specific market depth, staleness/first-known/cross-expiry quote skew, ATM proximity, negative debit, full net-debit research budget, near-versus-far IV term ratio, short-premium coverage, high near IV move, missing fees and the no-order invariant. All numerical test quotes are **synthetic fixtures**, not live NSE observations.

**Still missing:** true licensed NIFTY multiple-expiry option chain and independently sourced original IV, official NSE expiry/lot and holiday calendar, real Indian transaction costs and broker near-expiry settlement/roll handling, actual fill/margin/legging exposure, original-timestamp historical OOS walk-forward term-structure evidence with IV and liquidity, approved PAPER ledger/app lifecycle, and separately approved LIVE option-writing rights, margin/risk monitoring and kill switches.

Related: [STR-198 Long Butterfly](STR-198-long-butterfly-research.md).
