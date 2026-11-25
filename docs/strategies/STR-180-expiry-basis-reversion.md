# STR-180 — Expiry Basis Reversion (research-only)

**Catalog:** `strategy.STR-180`, eleventh of 43 dedicated F&O strategies; final futures-family entry. **Status: DATA_BLOCKED** until licensed real source data, honest historical as-of replay and PAPER acceptance are independently proven.

## Economic distinction from STR-170 and STR-175

STR-170 estimates **one instantaneous** single-stock cash/futures carry gap, and STR-175 asks whether a cash-stock purchase plus futures short is feasible after capital, delivery, depth and additional costs. STR-180 instead studies **longitudinal convergence** of one **unchanged futures contract's observed futures-versus-spot closing basis to an explicit theoretical carry fair value** over five completed exchange sessions as expiry approaches.

Near-expiry futures and cash-stock prices often become closer, but convergence is **not assured**, and an apparent spread is not risk-free arbitrage. Trading restrictions, dividends, forced physical settlement, margin hikes, stock-specific events, broker charges, time skew and insufficient liquidity can dominate the apparent pattern. **Never substitute the NIFTY cash index as a directly deliverable asset** or imply an ordinary overnight short sale is permissible. There is no entry/exit/order or profit claim in the evaluator.

## Point-in-time source contract

- One verified, *deliverable* `NSE_EQ` equity and matching `NSE_FO` single-stock future, source-listed underlying/expiry/lot identity, and listed-from date. No rolling between near and next month, no stitched contracts, no index-spot substitution.
- **Five consecutive, independently verified completed NSE cash and F&O trading sessions** (sanity checks: chronological weekdays, date gaps 1–5 days). A separately licensed adapter must verify official NSE holiday/special-session, trading calendar, listed contracts, suspensions and original settlement publication times. At evaluation, expiry must be **3–14 calendar days away** for this research-only near-expiry screen, excluding the closest physical-delivery cutoff days. This is not a broker's actual cutoff policy.
- Each completed session must contain **same-provider** source-verified near-close bid/ask for both legs, with an actual exchange timestamp in the last five minutes through the common **15:30 IST cash close**, absolute cross-leg skew ≤2 minutes, positive noncrossed quotes, and an explicit maximum bid/ask spread. This is near-close **indicative midquote**, not an exchange settlement or a guaranteed executable fill.
- Each leg must also have separately preserved **first-observed quote** and **first-known finalized-close** timestamps. Final observations must be on or after each respective cash (15:30) or futures (effective 15:40/15:45) session close, no earlier than that leg's quote observation, within 36 hours of session close, and not after evaluation `asOf`. A retrospective downloader must never fabricate the timestamp at which a historical quote/settlement was first available.
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

## Read-only source integration (21 November 2026)

`FnoFuturesResearchService.evaluateAll` now contains a distinct
`FnoExpiryBasisReversionV1.evaluate` invocation. The V105 evidence table is
**empty at deployment**, and the `FnoVerifiedExpiryBasisPairStore` accesses
only independently attested source rows, never synthetic candles or a live
bid/ask converted into a retroactive close. The strategy **does not produce
orders**, account mutations, or profit forecasts.

The source adapter requires exactly one current source-listed, deliverable
`NSE_EQ` stock and matching single-stock `NSE_FO` contract, expiry/lot
and source-listed-from identity, with between 3 and 14 days to expiry. It
selects the last five strictly ordered, complete, continuous NSE trading
sessions with one provider, one contract, one verified corporate-action/cost
evidence digest, and one consistent set of reviewed financing/dividend,
physical-delivery and transaction-cost assumptions. Contradictory or missing
rights, fees, contract digest, trading dates or source publication times block.

**Cash/F&O closing-time distinction:** the paired BID/ASK quotes are actual
co-temporal *indicative near-15:30 cash-close observations* from both
markets. They are **not** the official F&O settlement close at 15:40 IST
from 2026-08-03 (15:45 for non-expiring derivatives from 2026-12-07).
Each historical spot finalized-source timestamp must be available only
after the official cash close; the distinct futures finalized-source
timestamp must be available after the actual later F&O session close.
Both official exchange calendars and their captured-as-of verification
timestamps must be independently attested.

V105 enforces three independent provider-specific, currently VERIFIED
`MODEL_INFERENCE` research-use receipts covering:
`nse_eq_near_close_quote`, `nse_fo_near_close_quote`, and
`nse_eq_carry_delivery_costs`. Source records also require original
first-observed quote and original first-known finalized-publication times;
late downloads cannot claim a first-known historical exchange timestamp.
Only rows still inside up to 90-day retention are eligible, and all rows
are immutable until retention expiry.

The server-side flag
`minttick.fno-research.expiry-basis-research-rights-verified=false`
is **default denied**. `minttick.fno-research.basis-underlying-key` must
name an actual individually listed `NSE_EQ` stock; the NIFTY index does
not qualify as deliverable cash stock. No source ingestion, rights grant
or cash/futures trade is implied by connecting the research code.

**Readiness:** 11 of 43 *distinct research evaluator paths* connected.
Independently validated real-market historical E2E, PAPER and LIVE remain
**0/43**. Genuine data ingestion, source/corporate-action proofs, full
no-lookahead replay, realistic Indian delivery/fee/margin cash constraints,
out-of-sample optimization and separate broker/PAPER order gating are
still necessary. A unit-test candidate is not operational readiness.
