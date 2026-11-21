# STR-210 — Portfolio Protective Overlay Research (Strategy 41/43)

**Status: `DATA_BLOCKED`; deterministic research only; NON-EXECUTABLE.** This evaluator considers a hypothetical **PURCHASE** of one of two genuine out-of-the-money NIFTY puts for an already **broker-confirmed long NSE cash-equity portfolio**. Unlike STR-205's covariance beta and STR-206's single worst-tail scenario, STR-210 compares candidate put strikes against **three distinct original-source historical worst NIFTY daily declines**, with each portfolio equity's actual return measured on the **same historical dates** using **currently broker-confirmed shares and prices**. It minimizes the sum of three conditional scenario residual losses under premium, quote depth and friction controls. These are **three stress scenarios**, not a three-day holding period, performance backtest or probability distribution.

## Required original source evidence

Six separately attested research rights: verified broker long-cash-equity holdings, authentic historical NIFTY index closes, authentic historically matched NSE equity closes, licensed authentic listed option chain, original per-unit put delta and annualized IV, and official NSE instrument master. Callers' `sourceVerified` and `brokerVerified` claims are **inputs**, not evidence that production accounts or NSE/Angel/Upstox are actually integrated. Tests use **fictional source-shaped data**.

- Positive original broker long-equity share positions only; distinct valid NSE cash instrument identities, real first-known broker position timestamps, synchronized genuine current cash-equity mark prices and authenticated current NIFTY index spot; no short stock, F&O holdings, cash, missing other existing portfolio hedge instruments or margin inference.
- At least **65 chronological original-first-known completed matched NIFTY index and every held equity daily close sessions**, all at independently verified actual 15:30 IST close, from the same provider and same date, with unique exact instruments for each daily record. Neither future-known nor cross-provider historical bars, missing equities, duplicated keys or stale historical windows pass.
- Exactly **two source-listed out-of-money NIFTY put candidates**, different unique official NSE_FO identities and strikes, **same expiry and same official option lot size**, actual listed-from dates, tick-aligned strikes and valid 7–90-day expiry bounds. Each requires the correct source-original independently verified **bid/ask, negative per-unit put delta in (-1,0), positive annualized IV, original OI and true ask-depth** (no fake LTP/Greeks/order book), matching provider and first-known timestamps.
- Research policy restricts cash quote age and synchronization, option spread, OI, IV, integer purchased lots, *ASK-side* available depth, buy slippage, nonzero modeled per-buy fees, total premium as fraction of **current cash equity value**, original observed tail-shock severity and minimum aggregate conditional scenario loss reduction.

## Honest research calculation

For each historical completed NIFTY return, compute `indexReturn = indexClose[t]/indexClose[t-1]-1`. Select the **three most negative index-return sessions** in the independently completed chronological source. When tied, choose earlier observed source sessions. For each distinct selected session, calculate each current long-stock's genuine **same-session** return (not a cherry-picked independent stock crash day):

`currentEquityValue = SUM(verifiedShares[i]×originalCurrentStockPrice[i])`

`scenarioEquityLoss[k] = max(0, -SUM(currentStockValue[i] × originalEquityReturn[i, selectedIndexTailDay[k]]))`

`hypotheticalIndexAtPutExpiry[k] = originalCurrentIndexSpot × (1+originalIndexReturn[selectedTailDay[k]])`

**Explicitly fictional persistence assumption:** the historic **one-day NIFTY** shock is applied to the present genuine index spot and *assumed to persist until the candidate's future expiry*. The model does **not** price the put on that original historical tail day, prove that real NIFTY/index-stock relationships persist, account for time value/IV repricing or estimate the chance of future crashes.

For each permitted source-authenticated candidate and each integer lot quantity limited by authenticated **ASK depth** and risk policy:

`hypotheticalDebit = (ASK×(1+buySlippageBps/10000)×sourceOfficialLot×lots)+positiveModeledFee`

`hypotheticalIntrinsic[k] = max(0, originalStrike−hypotheticalIndexAtPutExpiry[k])×officialLot×lots`

`scenarioResidualLoss[k] = max(0, scenarioEquityLoss[k]+hypotheticalDebit−hypotheticalIntrinsic[k])`

`sumThreeResidualLosses = SUM(scenarioResidualLoss[three distinct tail days])`

The evaluator chooses the candidate/quantity minimizing **aggregate three-scenario residual loss**, breaks equal residual ties by lower estimated debit, and rejects the overlay if the aggregate reduction is below an explicit research threshold. **The same premium debit is charged as a cost in each mutually exclusive scenario, including where the put expires worthless** for separate scenario comparisons. These residuals **must not be summed as realized wealth or expected returns**. Total combined unhedged or hedged loss is a *scenario score*, not an actual lifetime drawdown, VaR/expected shortfall, profit forecast or insurance value.

**Strict additional notional guard:** for every candidate and integer lot count, verified current NIFTY spot × official put lot × candidate lots cannot exceed the current source-confirmed value of the held long-equity portfolio. This is a conservative index-equivalent notional ceiling, **not** evidence of matching portfolio beta or an actual risk hedge.

Cash equities do not mechanically track the NIFTY index; individual-stock basis, dividends, price gaps, corporate actions, cash weight, option liquidity and actual settlement can produce materially different results. An index put only pays its true terminal intrinsic in the hypothetical matched-expiry scenario; **it can expire worthless and the portfolio can lose significantly even if a modeled scenario shows high nominal offset**. The conditional option purchase debit is not an actual fill or Indian statutory charges model. Existing positions, portfolio concentration, basis, futures/short calls, taxes and broker margin are not neutralized here.

## Implementation evidence and what remains

- Evaluator: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoPortfolioProtectiveOverlayV1.java`
- Unit tests: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoPortfolioProtectiveOverlayV1Test.java`
- Catalog: `backend/src/main/resources/mind/capability-manifest.v1.json` — `strategy.STR-210`
- **23 deterministic JUnit tests** use fictional original-time-shaped marks, book and instrument data to check source gates, true synchronized history, entire current holdings basket, nonduplicate source identities, real official matching put lots, OTM/expiry/tick, provider matching, Greeks/IV/OI, ask depth and spread, no scenario loss, upside-only NIFTY, genuine quote timestamp, premium budget, strict whole-portfolio index-equivalent notional ceiling, premium loss even if the put expires worthless, same-index-tail matching and unconditional order prohibition. Tests **are not proof of authentic NSE history or empirical strategy returns**.

Every decision unconditionally: `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`. Capability manifest remains **`DATA_BLOCKED`**.

Prior to independent PAPER acceptance, establish authentic licensed NIFTY/equity historical price and corporate-action-adjustment provenance, actual authenticated broker holdings and current marks, real exchange-listed NSE put chain, genuine official lot/expiry and original Greeks/IV/depth, official exchange holiday/calendar, original first-known unbiased out-of-sample multi-day and intraday crash validation, changing equity/index basis, observed expiry/exit option marks, full charges/slippage/settlement and no duplicate hedge protection, paper broker ledger and Flutter UI. LIVE additionally requires separately accepted NSE F&O rights and user consent, premium/cash and position caps, kill switches and trustworthy broker execution ledgers. **No broker orders, flags, credentials, AWS/SSM resources, deployment or APK change in this PR.**
