# MintTick historical source-to-evaluator replay, STR-187–212

The existing 43/43 typed dispatcher already invokes each real strategy
evaluator with externally provided typed market inputs. It is **not**
automated historical market replay or evidence of trading profitability.

This feature adds a separate bounded, read-only route through the **existing
original rights-gated research services** for STR-187..212 (26 strategies).
Each research adapter already maps an independent source table into its
actual Java strategy evaluator, requiring source first-observed timestamps
and original rights/custody, appropriate broker account evidence and
strategy-specific NSE calendar/expiry, cash, option legs, depth, IV/Greeks
or account positions. The new service does NOT synthesize missing source
data. It calls only the research adapter associated with the requested
strategy ID and historical decision time.

Distinct families:
- STR-187/188 settled equity protective put and covered call.
- STR-189 equity collar with original independent fee/cost data.
- STR-190/191 call and put ratio spreads including uncovered tail risk.
- STR-192..195 independent long/short straddle and strangle source,
  independent per-leg IV/Greeks, depth and margin for short legs.
- STR-196..198 iron condor, iron butterfly and 1x2x1 long butterfly,
  original 3/4 distinct legs and correct payoff structure.
- STR-199/200 calendar and diagonal spreads, different expiries and
  unresolved far-leg mark explicitly preserved.
- STR-201/202 IV rank and retrospective event volatility crush from
  original 200+ NIFTY OHLC/IV and official event data.
- STR-203..206 gamma, delta, beta and tail hedges with original source
  and explicitly retrospectively evaluated account/margin/Greeks paths.
- STR-207..212 broker-held put-spread collars, spread rolls, expiry risk
  neutralization, portfolio protection, Greeks rebalancing and skew hedge.

All 26 identifiers are a static, single-strategy dispatch map (24 distinct
source services, with 187/188 and 190/191 sharing their respective source
services). Only exact **source-qualified research inputState labels**
returned by those existing, original source evaluators are eligible for
reporting a non-blocked research evaluation. Source exceptions are
redacted. Only a real, supported original source provider, an exact
matching evaluator ID/as-of and NO_SIGNAL/RESEARCH_CANDIDATE research-only
statuses can be reported as an actual evaluator invocation. The
original underlying SQL/source adapters retain responsibility for rights
and first-known gating. The registry itself cannot independently
verify original custody, source rights or profitability.

A research-only structural replay can cover up to 16 ordered historical
decision windows within the previous 366 days; it hashes fixed
reason/status/source-count summaries, not raw prices or account data.
Other strategies, duplicate or untrusted source rows, missing rights,
wrong as-of and forged readiness fail closed.

The protected GET endpoint
/api/admin/research/fno-43/remaining-original-replay requires
ADMIN/OPERATOR and returns no-store, sanitized original history/quote
counts and fixed reasons only. It cannot place broker orders or expose
positions, balances, prices, account IDs, option contracts or source
hashes. It is not a replacement for distinct public/trading UIs.

**Critical acceptance limitation:** the new CI uses synthetic/mock
service outputs and tests the actual compiled source->research adapter
invocation and classification; it does not prove production tables
contain eligible original data, nor an independently signed
point-in-time replay. It also does not produce a P&L ledger with
buy at authentic ask, sell at authentic bid, verified depth and fill
capacity, exchange/regulatory fees, slippage, taxes, market impact,
walk-forward out-of-sample folds, and independent result receipts.

Therefore historical independent E2E is still **0/43** until those
separate original market and after-cost proofs exist. PAPER and LIVE
broker outcomes remain independent and cannot be inferred from CI.
No provider rights or retention controls, trading flags or orders
are changed by this integration.
