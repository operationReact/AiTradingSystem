# STR-180 — Expiry Basis Reversion (research-only)

**Catalog:** `strategy.STR-180`, eleventh of 43 dedicated F&O strategies; final futures-family entry. **Status: DATA_BLOCKED** until licensed real source data, honest historical as-of replay and PAPER acceptance are independently proven.

## Economic distinction from STR-170 and STR-175

STR-170 estimates **one instantaneous** single-stock cash/futures carry gap, and STR-175 asks whether a cash-stock purchase plus futures short is feasible after capital, delivery, depth and additional costs. STR-180 instead studies **longitudinal convergence** of one **unchanged futures contract's observed futures-versus-spot closing basis to an explicit theoretical carry fair value** over five completed exchange sessions as expiry approaches.

Near-expiry futures and cash-stock prices often become closer, but convergence is **not assured**, and an apparent spread is not risk-free arbitrage. Trading restrictions, dividends, forced physical settlement, margin hikes, stock-specific events, broker charges, time skew and insufficient liquidity can dominate the apparent pattern. **Never substitute the NIFTY cash index as a directly deliverable asset** or imply an ordinary overnight short sale is permissible. There is no entry/exit/order or profit claim in the evaluator.

## Point-in-time source contract

- One verified, *deliverable* `NSE_EQ` equity and matching `NSE_FO` single-stock future, source-listed underlying/expiry/lot identity, and listed-from date. No rolling between near and next month, no stitched contracts, no index-spot substitution.
- **Five consecutive, independently verified completed NSE cash and F&O trading sessions** (sanity checks: chronological weekdays, date gaps 1–5 days). A separately licensed adapter must verify official NSE holiday/special-session, trading calendar, listed contracts, suspensions and original settlement publication times. At evaluation, expiry must be **3–14 calendar days away** for this research-only near-expiry screen, excluding the closest physical-delivery cutoff days. This is not a broker's actual cutoff policy.
- Each completed session must contain **same-provider** source-verified near-close bid/ask for both legs, with an actual exchange timestamp in the last five minutes through **15:30 IST**, absolute cross-leg skew ≤2 minutes, positive noncrossed quotes, and an explicit maximum bid/ask spread. This is near-close **indicative midquote**, not an exchange settlement or a guaranteed executable fill.
- Each leg must also have separately preserved **first-observed quote** and **first-known finalized-close** timestamps. Final observations must be on or after 15:30 IST, no earlier than that leg's quote observation, within 36 hours of session close, and not after evaluation `asOf`. A retrospective downloader must never fabricate the timestamp at which a historical quote/settlement was first available.
- Independent rights attestation for spot quotes, futures quotes and contract research, and separately verified financing, expected dividend events, corporate actions, physical-delivery status, statutory and slippage costs. Display, retention, model training and LIVE F&O execution are separately gated.

## Deterministic methodology

For each completed session `i` of the **same actual futures contract**:

1. `spotMid_i = (spotBid_i + spotAsk_i) / 2`; `futureMid_i = (futureBid_i + futureAsk_i) / 2`.
2. `observedBasisBps_i = 10000 × (futureMid_i − spotMid_i) / spotMid_i`.
3. `theoreticalCarryBps_i = (financingAnnualBps − dividendAnnualBps) × daysToExpiry_i / 365`.
4. `signedResidualBps_i = observedBasisBps_i − theoreticalCarryBps_i`. Negative values mean below this *assumed* fair value; positive values mean above. The simplified linear carry estimate **does not** capture dividend timing, repo financing, stock lend, calendar or corporate events accurately without validated external assumptions.
5. Require all five signed residuals to have the **same nonzero sign**. Count absolute-residual shrink steps among four adjacent comparisons. At least a separately set **3 of 4** steps must shrink, and the **final two** must shrink consecutively. Require a minimum cumulative first-to-last absolute compression.
6. Require the final **remaining absolute residual after estimated two-leg round-trip costs** to exceed an explicit research hurdle. If there is too little residual, this is *not* an unexploited opportunity even if the historical spread narrowed. Guard against abnormal daily midquote jumps.
7. Return only `ABOVE_FAIR_CONVERGING`, `BELOW_FAIR_CONVERGING`, or `NONE`. This describes a completed historical narrowing pattern and **not a prediction of future convergence**.

Possible outcomes: `BLOCKED_RIGHTS` (missing research-use rights), `BLOCKED_DATA` (unverified as-of close, stale data, wrong future/expiry, missing dividend or cost evidence, sequence/quote error), `NO_SIGNAL` (no signed convergence, insufficient narrowing, too little after-cost residual), `RESEARCH_CANDIDATE` (hypothetical observed pattern only). Every outcome has `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Tests and remaining evidence

23 deterministic JUnit tests cover above/below theoretical fair-value convergence, insufficient narrowing, insufficient total compression, net residual cost hurdle, fair-value crossing, independent rights, deliverability/index substitution/contract/expiry/lot, provider or future-month switches, unverified completed exchange sessions, separate historical final publication and quote first-seen timestamps, preclose/future-known/late observations, quote skew and bid/ask spreads, price outliers, duplicated/reordered sessions, stale last observation, dividend/corporate-action/physical-delivery/cost attestation, invalid threshold ranges, and reproducible no-order decisions.

**Illustrative fixture (not calibrated India production thresholds):** futures price ~104 down to ~100.85 versus spot around 100, five trading sessions, financing 500 bps per annum, zero expected annual dividend yield, 20 bps all-in round-trip charges, three required narrowing steps, 150 bps minimum historical compression, 30 bps remaining after-cost residual minimum, 50 bps maximum spread per leg, 2500 bps max daily mid move. The positive test verifies **mathematical research classification**, not actual market behavior or profit.

**Required next work:** integrate one authorized canonical source adapter for genuine NSE_EQ and NSE_FO closing data, corporate actions, actual dividend events, observed settlement prices, underlying continuity, same-provider rights and source timestamps; run no-lookahead multi-expiry historical replay and walk-forward under realistic Indian costs and liquidity/physical delivery regimes; add independent PAPER-only lifecycle, account risk, audit and Flutter acceptance. No data source, broker order, production deployment, SSM credential, LIVE permission, Flutter/Android APK, or trading flag is changed by this backend-only PR.

See [canonical F&O strategy feed](../architecture/fno-canonical-strategy-feed.md) and [STR-170](STR-170-futures-basis-carry.md) for the complementary instantaneous carry model.
