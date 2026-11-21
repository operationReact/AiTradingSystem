# STR-206 — Tail Risk Put Hedge Research (Strategy 37/43)

**Status: `DATA_BLOCKED`.** This is a deterministic *retrospective research-only* scenario evaluator. It replays the worst **source-original, completed historical NIFTY daily decline** alongside the *same session's actual historical moves* in broker-confirmed currently held long equities. It then hypothetically purchases only **listed, out-of-the-money NIFTY puts** and compares an explicitly fictional **at-expiry intrinsic payoff** to the observed-tail scenario loss and an *estimated* premium debit. This is **NOT actual performance**, a price prediction, portfolio insurance, a trading signal, a historical broker backtest, or a PAPER/LIVE authorization.

## Required source evidence and validity

Six independently verified research rights must be supplied by the caller: broker long-equity holdings, original exchange-time NIFTY index history, original synchronized equity history, genuine listed option chain, genuine original provider option delta/annualized IV, and actual source-listed instrument master.

- At least 65 completed, ascending, original-first-known NIFTY and **each currently held security's** daily closing records (60+ consecutive historical returns; the policy floor is 61 days). Each security must be present exactly once at each identical session, from the same provider, originally first-known after market close and before the current market snapshot. No stale, after-as-of, missing, duplicated, cross-provider, unverified or synthetic historical records. An official exchange holiday calendar and corporate-action-adjusted source remain outstanding for production use.
- Every current holding must be **broker-confirmed**, be a positive long cash-equity share quantity, and have a valid broker first-known timestamp no later than the source market snapshot. This research does not represent short equities, already-open derivatives, pledged margin, existing insurance, cash weight or portfolio flow history.
- The NIFTY option must be **authentically listed NSE_FO out-of-the-money PE** (`strike < observed index spot`), valid tick/official lot, source-verified listing/expiry and a bounded 7–45 calendar-day time-to-expiry. Source-original, same-provider nonstale **BID/ASK, put delta (-1 < delta < 0), annualized IV, OI and ASK-side depth**, first-known exchange timestamps, spread controls and source rights are mandatory. No modeled option chain, fabricated Greeks or synthesized premiums.
- A bounded, explicitly configured research policy limits source staleness/skew, OI, buy slippage, eligible maximum lots, premium budget, tail-shock band, and required modeled scenario reduction. Nonzero modeled order fees are required.

## What the deterministic evaluator actually calculates

For each pair of historical, genuinely known completed source closes:

`indexReturn[t] = indexClose[t] / indexClose[t-1] - 1`

Choose **one single** historical index day with the largest observed decline among the supplied observations. Use the **same historical date's matched equity returns** for each currently held stock, not independent cherry-picked worst security days.

At the currently verified holdings/stock marks, define:
- `portfolioValueNow = SUM(confirmedLongShares[i] × authenticCurrentEquityPrice[i])`
- `hypotheticalShockPortfolioChange = SUM(currentValue[i] × actualSourceEquityReturn[i,chosenDay])`
- `hypotheticalUnhedgedLoss = max(0, -hypotheticalShockPortfolioChange)`
- `hypotheticalExpiryIndexLevel = authenticCurrentIndexSpot × (1 + worstHistoricalIndexReturn)`
- `putIntrinsicPerLotAtHypotheticalExpiry = max(0, verifiedListedStrike - hypotheticalExpiryIndexLevel) × officialContractLot`
- `hypotheticalPurchaseDebit = boughtLots × officialContractLot × sourceASK × (1 + modeledBuySlippageBps/10000) + modeledPositiveFees`
- `hypotheticalResidualScenarioLoss = max(0, hypotheticalUnhedgedLoss - [putExpiryIntrinsic - hypotheticalPurchaseDebit])`

**Crucial:** the expiry-level assumption means that, solely for scenario comparison, the chosen historic *one-day* drop is assumed to persist until this real put's **future expiry**. This is not an option mark on the crash day, not future probability, not a claim that the market will stay down, and not guaranteed crash protection. Real options can expire worthless, option Greeks/IV/time-value change, the index may rebound, stock/index relationships may break, and a single close-to-close worst day misses overnight and intraday gaps or multi-day drawdowns.

The evaluator considers **long put purchases only**, integer lots bounded by real available **ASK depth**, original option contract lot, maximum lots and the portfolio premium budget. It chooses the affordable number giving minimum hypothetical residual scenario loss, without adding unnecessarily more lots after hitting a zero-loss floor. It rejects no original index decline, a shock outside policy bounds, zero matched portfolio loss, an OTM put strike not reached at the hypothetical expiry scenario, premiums exceeding any modeled intrinsic benefit, or insufficient net protection. Buy-side debit and fees are **not observed executions**. The calculation does not account for exit quotes, taxes/levies, market impact, dividends, borrow, futures hedges, corporate events, broker fills, positions, or actual settlement-ledger timing.

## Safety and outstanding work

Every decision, including `RESEARCH_CANDIDATE`, is unconditionally `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`. The manifest stays `DATA_BLOCKED`. The source-verified booleans **are caller-supplied research attestations**, not proof that production datasets, broker data rights or official NSE instruments are actually available. Test fixtures are deliberately fictional and prove only deterministic code paths.

Before PAPER: verify original licensed market data and actual broker holdings, real official option/lot/expiry master, source as-of snapshots and chain Greeks/IV/OI/depth; handle stock corporate actions, turnover and market holidays, distinct crash sizes and time-to-expiry outcomes; perform independently timed **out-of-sample and walk-forward** stress studies with real exchange-time option quotes, true after-cost fill/settlement ledgers, slippage and gaps; prove multi-asset correlation breakdown and portfolio risk controls. Before LIVE: accepted PAPER ledger/Flutter integration and separately verified live F&O broker entitlements, risk limits, cash and premium caps, kill switches and authorization. This PR touches no orders, AWS/SSM, broker flags, credentials, deployment or APK.

## Evidence

- `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoTailRiskPutHedgeV1.java`
- `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoTailRiskPutHedgeV1Test.java`
- `backend/src/main/resources/mind/capability-manifest.v1.json`, `strategy.STR-206`
- 21 JUnit deterministic cases using **fictional synthetic fixtures**. These are not empirical hedge returns or authentic NSE market observations.
