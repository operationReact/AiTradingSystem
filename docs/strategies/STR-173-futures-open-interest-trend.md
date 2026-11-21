# STR-173 — Open Interest Trend (research-only)

**Catalog:** `strategy.STR-173`, strategy 4 of 43 dedicated F&O strategies. **Current readiness: DATA_BLOCKED** until real source rights, five-session exchange OI and backtest evidence are independently verified.

## Trading hypothesis versus data observation

The same NSE futures contract can exhibit a sustained increase or decrease in **exchange-reported open interest** over completed trading sessions. OI measures outstanding contracts, not net direction. Rising OI does **not** prove bullish positions; declining OI does **not** automatically mean bearish selling. **STR-174 (Price OI Divergence)** is a separate later strategy requiring price context.

This evaluator classifies **RISING_OPEN_INTEREST**, **FALLING_OPEN_INTEREST**, or **NONE** for internal research without submitting orders or emitting a buy/sell direction.

## Input contract

An authorized, source-aware server adapter must provide:
- A genuine `NSE_FO` future, exactly one contract key and listed expiry, canonical `NSE_INDEX` or `NSE_EQ` underlying, source-verified metadata and lot size. Only periods 7–120 calendar days before expiry qualify; near-expiry migrations belong in STR-172.
- Exactly **five** consecutive, explicitly verified and completed NSE sessions in ascending order for that SAME future. Nonconsecutive dates, duplicates and weekends are rejected where locally detectable; the upstream provider adapter must still verify official exchange holidays and session continuity.
- For each session, **exchange-close OI in normalized underlying units**, total traded volume in the SAME units, provider identity, exchange event time in the final two minutes of session, and the timestamp when completed OI data actually became available. This prevents training or backtesting with future-known OI.
- Verified internal research-use and instrument discovery permissions. An account's LIVE F&O trading permission is a separate requirement.
- Explicit versioned thresholds; no assumed data rights, free volume, or default signal thresholds.

## Deterministic detection

Calculate four OI changes across the five sessions, counting positive and negative transitions. Allow flat transitions but disallow opposing-direction changes in a confirmed trend.

For each session require volume/OI to exceed a configured minimum. Exclude any single-step OI change exceeding the configured research outlier threshold. Measure total OI change in bps from the first completed session to the fifth.

- **RISING_OPEN_INTEREST:** cumulative positive OI change meets the explicit minimum, increasing-step count meets the explicit confirmation threshold (2–4 out of four), no decreasing steps, adequate volume in every session.
- **FALLING_OPEN_INTEREST:** equivalent negative cumulative OI threshold, adequate number of decreasing steps, no increasing steps, adequate volume.
- **NO_SIGNAL:** flat/mixed movement or insufficient traded volume. Invalid rights, contracts, session evidence, timestamps, or OI yield BLOCKED_RIGHTS or BLOCKED_DATA.

A fixed example for deterministic tests uses a minimum **800 bps (8%)** total OI change, three confirming transitions, **2500 bps (25%)** daily volume/OI floor and **5000 bps (50%)** maximum single-step change. **These are fixture thresholds, not calibrated production thresholds or performance claims.**

## Safety, status and validation

- Pure Java 21 evaluator: no OAuth/SSM/WebSocket, service endpoints, DB writes, scheduler, customer display, PAPER/LIVE orders or position mutations.
- All decisions have `executable=false`, `orderPlaced=false`, `directionalBuyOrSell=false`. There is no P&L calculation or claim of profitable prediction.
- Explicit as-of observation time, bounded OI/volume, closed-session validation, single-provider consistency, contract identity and expiry verification, stale-data checks and no rollover mixing.
- **19 deterministic evaluator JUnit tests** cover growth, decline, flat OI, flat-step confirmation, opposite moves, low volume, outliers, rights, contract identity, expiry, source changes, pre-close/future-known data, bad OI, gaps, stale history, thresholds and deterministic reproducibility.

## Remaining before operational readiness

1. Implement the separately reviewed [canonical authorized F&O strategy feed](../architecture/fno-canonical-strategy-feed.md) with precise contract metadata, source rights, normalized OI and original exchange/provider OI publication timestamps.
2. Verify the completed NSE session calendar, expiry roll history, source delay/disruptions and as-of historical archiving. A snapshot merely downloaded today is not retroactively known during prior sessions.
3. Run no-lookahead multi-expiry out-of-sample/walk-forward research to evaluate whether OI trend has any incremental predictive usefulness, *without* double-counting next-expiry OI migration.
4. Independently validate read-only/PAPER research pipeline, risk, acceptance, Flutter output where required. LIVE execution remains disabled pending separate broker approval.

**Client impact for this first PR:** backend pure research only; no Flutter changes or UI acceptance are claimed.

## E2E-2 source-adapter increment (21 November 2026)

A separate read-only integration now invokes the exact `FnoFuturesOpenInterestTrendV1.evaluate(...)`
from `FnoFuturesResearchService` and surfaces STR-173 in
`/api/admin/research/fno-futures/status` and the 43-strategy readiness endpoint.

- Existing instrument-master registry must have a current, loaded source digest and
  a verified same-underlying futures expiry pair. Select one contract with at least
  seven days to expiry, never splice near/far expiry OI.
- The `FnoClosingSnapshotStore.verifiedOiContractRows` SQL selects one exact
  `NSE_FO` key, bounded dates, only originally persisted records carrying the
  `INTERNAL_RESEARCH_VERIFIED` marker, and unexpired retention. An independent
  present source-use authorization flag must also be true. These gates are necessary,
  **not independently sufficient to certify source entitlement or genuine first-known provenance**.
- The adapter rejects null/ambiguous OI and volume, source mixing, duplicate
  sessions, and gaps according to the calendar. Missing rights or data yields
  BLOCKED_RIGHTS / BLOCKED_DATA with stable operator-facing codes.
- An important regression was corrected: normal NSE F&O close is 15:40 IST
  from 3 August 2026, not 15:30; the subsequent scheduled 7 December 2026
  non-expiring futures close is 15:45. The evaluator rejects pre-close data.
  Source: NSE/FAOP/74467 (29 May 2026) and exchange CAS guidance
  (https://www.nseindia.in/resources/exchange-communication-holidays).
- Provisional 800/3/2500/5000 research thresholds reuse deterministic test
  calibration. **Not optimized on real NSE data, not approved trading thresholds.**
- New integration and repository tests validate rights-denied zero read, one
  original-time five-session invocation, cross-provider and missing-session
  failures, provider-exception sanitization, scoped query retention rights and
  read-only behavior.

This does **not** establish 43 real-data E2E passes, even one real-source
walk-forward, independent provider entitlement, production deployment, PAPER
execution, approved LIVE F&O orders or profitable research outcomes. Every
strategy stays `DATA_BLOCKED` pending independently reproduced source evidence.
