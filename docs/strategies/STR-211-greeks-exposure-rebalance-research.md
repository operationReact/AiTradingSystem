# STR-211 — Greeks Exposure Rebalance Research (Strategy 42/43)

**Catalog status: `DATA_BLOCKED`. Deterministic research-only, NON-EXECUTABLE.**

This evaluator models a hypothetical **BUY** of one separately authenticated, genuinely listed OTM NIFTY PUT or CALL to **reduce absolute NIFTY option-book delta**, while checking strict gamma, vega, negative theta, quote depth and premium budgets on an **existing broker-verified complete NIFTY options book**. It does **not** claim that delta, gamma, vega and theta can all be made zero, or that the wider equities/futures portfolio is hedged.

## Required independently evidenced inputs

Six distinct source rights are necessary: (1) authenticated complete current broker NIFTY option inventory, (2) licensed original completed daily NIFTY index closes, (3) original genuinely listed NSE CE/PE option chain, (4) authentic original delta and gamma, (5) authentic original vega, theta and annualized IV, and (6) official source NSE expiry/lot/tick instrument master. The caller's boolean rights and sourceVerified flags are necessary *attestations*, **not proof** of an operational brokerage connection or licensed data. Tests use deliberately fictitious source-shaped values.

- Existing position book must be independently broker-attested **complete for NIFTY options** and contain actual signed long/short lots, original broker first-known timestamps and unique official NSE_FO keys; each official source instrument requires genuine type (CE or PE), strike, tick, listed-from, expiry, positive lot and authentic NIFTY underlying. No zero lots, invented or duplicated broker positions, expired instruments or future-known inventory.
- A source-synchronized NIFTY index spot and a **complete exact original quote book** with no extra/duplicate/missing keys for all existing positions and two candidate puts/calls. Original option market quotes contain distinct real bid/ask, per-underlying-unit delta, gamma per index point, vega in **INR premium change per one IV percentage point**, theta in **INR premium change per calendar day**, annualized IV, genuine OI, bid/ask depth and original exchange/first-known timestamps. These Greek unit conventions must match the actual broker provider; the deterministic function cannot authenticate a provider's units itself.
- Exactly **one** authentic OTM listed PE and **one** authentic OTM listed CE with matching official lot and matching expiry between 7 and 45 days ahead, each with genuine ask-side depth for at least one future **buy-only** unit. The research does not short or write new calls/puts. It does not modify, close or rebalance actual existing positions.
- At least **35 originally completed and ascending NIFTY index daily close observations** with same original provider, NSE-style 15:30 IST exchange-close time, no duplicate/gap over eight calendar days, a compatible original first-known time and recent last completed close. A source-observed absolute daily NIFTY movement threshold gates the *research scenario*, but is **not a return forecast** and does not prove volatility-sensitive hedge success. Actual official exchange holiday/calendar data remains a deployment blocker.

## Exact deterministic modeling

For each verified position of signed lots `l_i`, official lot `Q_i`, and its authenticated original quote Greeks `(delta_i, gamma_i, vega_i, theta_i)`:

`PortfolioDeltaUnits = Σ(l_i × Q_i × delta_i)`

`PortfolioGammaUnitsPerIndexPoint = Σ(l_i × Q_i × gamma_i)`

`PortfolioVegaInrPerIvPoint = Σ(l_i × Q_i × vega_i)`

`PortfolioThetaInrPerCalendarDay = Σ(l_i × Q_i × theta_i)`

For each genuine candidate long PE or CE and integer `n` no larger than actual ask depth and configured lot cap:

`CandidateDebitInr = n × officialLot × genuineOptionASK × (1+buySlippageBps/10000) + strictlyPositiveFees`.

`ResidualGreek = CurrentGreek + n × candidateOfficialLot × candidateOriginalUnitGreek`.

Enumerate integer buy amounts, rejecting if genuine quote original-first-known provenance or bid/ask spread/OI/IV/Greeks are missing; premium exceeds budget; modeled absolute **residual delta, gamma, vega** exceed policy caps; residual **negative theta loss per calendar day** exceeds policy cap; or the proposed delta goes beyond zero and reverses sign. Among passing candidates choose smallest absolute residual delta, breaking ties with lower quoted debit. Only propose `RESEARCH_CANDIDATE` if relative improvement in absolute original delta exceeds a minimum; otherwise return `NO_REBALANCE`.

**Important interpretation:** These four Greeks are **local original-snapshot sensitivities** under the broker's specific Greek conventions and only a linear aggregation. They are **not** forecast P&L, a direct measure of maximum-loss risk, guaranteed expiry payoff, an observed trade, a multivariate optimum or a promise of protection against gaps, gamma explosions, volatility smile changes, index basis, changing IV, overnight theta, delivery, funding, collateral or forced settlement. Buying long options does not make existing naked short positions margin-safe. The separate short inventory could still have unbounded loss.

## Evidence

- Evaluator: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoGreeksExposureRebalanceV1.java`
- Unit tests: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoGreeksExposureRebalanceV1Test.java`
- Catalog: `backend/src/main/resources/mind/capability-manifest.v1.json` entry `strategy.STR-211`
- **22 fictional deterministic JUnit cases**, including a true quote-side buy-only integer lot choice, aggregate delta/gamma/vega/theta arithmetic, completeness of independently attested broker source book, unique official instrument master expiry and lot, NIFTY source history as-of, genuine matching original Greeks and price/book depth, gamma/vega/theta caps, original quote affordability, and invariable no-order proof.

All returned outcomes have `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`; the manifest stays `DATA_BLOCKED`. Before any actual PAPER/LIVE use obtain authentic licensed original NSE NIFTY and options historical/live source chain, verified independent Greeks units, real source official master/holiday calendar, reconciled complete broker options positions, actual order and fill premium/charges/margin/settlement, changing-Greeks stress and out-of-sample original-first-known walk-forward evidence, approved paper simulation ledger with Flutter inspection, separately user-consented LIVE NSE F&O permissions, cash/margin caps, duplicate-hedge prevention and tested emergency kill switches. **No AWS, SSM, credentials, broker permissions, order path, deployment, Flutter or APK is modified.**
