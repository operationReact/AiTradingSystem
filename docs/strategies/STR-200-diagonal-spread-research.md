# STR-200 — Diagonal Spread (Strategy 31/43)

**State: `DATA_BLOCKED`.** Deterministic **research-only** directional long *debit diagonal* on genuine NIFTY index options. No broker orders are created.

## Correct difference from a calendar spread

The two contracts have the same source-listed underlying, option type (**both CE or both PE**), and valid actual option lot size, but **different expiries AND different strikes**. The selected long-debit diagonal variants have a farther-expiring **in-the-money purchased option** paired with a nearer-expiring **out-of-the-money written option**:

- **Call diagonal**: BUY one farther-expiry **lower-strike ITM CE** and SELL one nearer-expiry **higher-strike OTM CE**, both source-listed and of the same official lot. Require `farLongStrike < authenticSpot < nearShortStrike`.
- **Put diagonal**: BUY one farther-expiry **higher-strike ITM PE** and SELL one nearer-expiry **lower-strike OTM PE**. Require `nearShortStrike < authenticSpot < farLongStrike`.

This is deliberately one **long directional diagonal** subset; other diagonal combinations (credit, inverted, short diagonal, diagonal roll structures) are separate strategies, not silently included. Expiry horizons are configurable within source-verified near 3–35 calendar days, far 10–90 days and 7–60-day separation; the exact official expiry master is required upstream.

## Net debit and realistic limitations

At the true original-as-of source option quotes, model the near sold option at **its bid minus sell slippage**, and far long option at **its ask plus buy slippage**, then account for nonzero combined Indian option transaction charges:

`netDebit = lot × [farAsk × (1+buySlippageBps/10000) − nearBid × (1−sellSlippageBps/10000)] + modeledFees`.

The research-only filter rejects nonpositive net debit or debit beyond a configured premium-allocation budget. The near-versus-far source annualized IV percentage ratio, short premium/long premium coverage ratio and a simple near-expiry IV one-sigma movement relative to verified spot are **heuristic research screens**, not a guarantee that the IV term structure or directional view is profitable. IV must be independently observed and cannot be fabricated from a placeholder LTP.

**Unlike a same-expiry vertical spread, the diagonal has two expiries.** On the near leg's expiry, the farther option retains uncertain time value and mark-to-market value; settlement, hedge continuity, broker writing margin and early exit can materially affect P&L. The model **does not invent a fixed same-expiry maximum realized profit/loss or static intrinsic break-even**. Initial net debit is **not** a verified margin or liquidation-risk cap.

## Strict original as-of and rights checks

- Independent licenses/rights for authentic NIFTY index, NSE option-chain, listed instrument master and source-reported option implied volatility **annualized as percentages**, for **each distinct expiry**.
- At least 35 genuine verified completed daily NIFTY 15:30 IST source closes, strictly ordered with original exchange-close and first-known publication timestamps and one real provider. No lookahead/future-known or duplicated observations. Actual NSE holiday and special-session calendar remains an upstream requirement.
- Three contemporaneous, same-provider source quotes: underlying spot, near-short option and far-long option. Each must preserve original exchange and first-known timestamps, with no quote older than 180 seconds and no overall cross-quote exchange-time skew above 90 seconds.
- Real positive and noncrossed bid/ask, authentic **bid-side depth** on the short near option, **ask-side depth** on the far purchased option, genuine open interest and independently sourced valid IV for both; both strikes must fall within configured distance from the verified underlying and be strictly on their appropriate ITM/OTM sides.
- Missing licensed rights, true listed expiries/lots/strikes, original quote data/IV, credible fees or completed history results in `BLOCKED_RIGHTS` or `BLOCKED_DATA`. Weak quantitative screening returns `NO_SIGNAL`, not a trade.

All decisions—including `RESEARCH_CANDIDATE`—retain `nearExpiryResidualLongMarkUnknown=true`, `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Test evidence and blockers

15 Java/JUnit scenarios cover valid CE and PE source-listed different-strike/different-expiry candidates; opposite-direction put rejection; wrong option type, strike, lot, expiry order and source verification; actual spot required between strikes; original completed historical session and quote as-of; independent IV rights; thin/crossed/stale/cross-provider quotes and correct side depth; absent/invalid IV, negative debit, budget, IV-term-structure ratio, short-premium coverage, high near IV move, nonzero fee assumption, repeatability and unconditional non-execution. **All numerical quotes in these tests are fictional fixtures, not actual NSE data or a backtest.**

Still absent: **authentic licensed F&O multi-expiry chain and IV, original-time history, actual NSE lot/expiry/calendar metadata, real India fees and broker short option margin/settlement, incomplete-leg exposure controls, genuine out-of-sample walk-forward costs and marks, PAPER UI and ledger acceptance, separately authorized LIVE permissions and audited kill switches**. Status remains `DATA_BLOCKED`.

Related: [STR-199 Calendar Spread](STR-199-calendar-spread-research.md).
