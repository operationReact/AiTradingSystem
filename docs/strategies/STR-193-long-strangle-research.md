# STR-193 — Long Strangle (Strategy 24/43)

**State:** `DATA_BLOCKED`. Non-executable deterministic F&O volatility research only.

## Strategy and payoff

Buy one genuine **out-of-the-money (OTM) NIFTY call (CE)** at a **higher strike** and one genuine **OTM NIFTY put (PE)** at a **lower strike**, both of the same underlying, listed expiry and option lot size. An OTM strike check requires `PEstrike < currentNiftySpot < CEstrike`, and `PEstrike < CEstrike`. This is different from a long straddle, which requires identical strikes. If the underlying quote is absent, stale or outside the strikes, evaluation fails closed. The candidate does not assume any broker position, order or hedge is already established.

At expiry, with lot size `Q`, put strike `P`, call strike `C`, and net debit `D` that includes buying both at genuine asks, modeled slippage and nonzero combined Indian fees:

- `expiryPnl(S) = Q * [max(P−S,0) + max(S−C,0)] − D`
- `theoreticalMaxExpiryLoss = D` (both options expire worthless for `P ≤ S ≤ C`)
- `lowerBreakeven = P − D/Q`
- `upperBreakeven = C + D/Q`
- Below the put strike, potential profit is capped by underlying zero; above call strike, theoretical upside profit is unbounded.

The model estimates an **illustrative** one-standard-deviation move as `spot × mean(callIV,putIV) / 100 × sqrt(calendarDaysToExpiry / 365)`. This is a square-root-time heuristic, **not a statistical forecast, proven IV mispricing or backtest**. The model rejects cases where that illustrative move fails to exceed the nearer OTM strike gap plus premium/fees per share, or where the move/cost ratio fails the configured threshold. Actual implied volatility skew, vega, theta/time decay and realistic exercise/settlement must be independently studied before assigning any tradable merit.

The *entire* premium and combined estimated fees must fit the research premium-allocation budget. Do not mistake bounded paid-premium exposure for assured returns or a broker fill.

## Provenance, safeguards and limitations

- Licensed source permissions for index price, genuine listed option chain, contract discovery and **separately sourced option IV** in annualized **percentage** units (80 means 80%, not 0.80). Reject missing, zero, negative or implausibly high IV.
- True source-listed NIFTY CE and PE with matching expiry and lot, source verified strike and tick, valid listing date and illustrative 3–45 calendar days to expiry. The two strikes must enclose the independently quoted underlying.
- At least 35 original first-known source-published, ascending, completed daily NIFTY 15:30 IST observations; provenance consistency, no forward-known bars, timestamped quotes within 180 seconds, three-quote skew <=90 seconds, real ask-side depth/OI and noncrossed bid/ask.
- Strict source-provider matching for underlying, CE and PE; nonzero combined India brokerage, taxes, fees and modeled slippage.
- Both options are **long**: there is no naked short margin, but neither payoff nor order fill is guaranteed. This model does not book orders or claim its simulated results are live quotes.

Status is limited to `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`. All decisions must return `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Tests and release blockers

11 deterministic JUnit cases check candidate matching OTM legs and breakevens, independent source rights, invalid type/lot/expiry/strike ordering, spot not between strikes, missing/out-of-range IV, stale/crossed/cross-provider/illiquid options, future-known or duplicate historical sessions, premium budget, insufficient illustrative IV move, zero modeled fees, and non-execution/determinism. The numbers in these tests are **synthetic fixtures for validating the classifier**, not production market quotes.

**Still needed:** genuine licensed NIFTY CE/PE market and traceable IV feed, actual NSE holidays, authentic option lot/expiry and live bid/ask depth, Indian exchange settlement and charges, original-timestamp out-of-sample and walk-forward research with fill/slippage costs, PAPER ledger/UI acceptance and separate LIVE brokerage rights/kill switches.

Related: [STR-192 Long Straddle](STR-192-long-straddle-research.md).
