# F&O futures strategies STR-170 / STR-171 / STR-172 — readiness audit (2026-11-20)

**Scope:** audit of merged PRs #1488 (STR-170), #1490 (STR-171) and #1491 (STR-172) on `dev` (head `ddda7b0d`). No strategy was redefined. Manifest status stays `DATA_BLOCKED` for all three. Nothing in this document is a PAPER, LIVE, profitability or rights claim.

## Verification performed

| Check | Result |
| --- | --- |
| `mvn test -Dtest=FnoFuturesBasisCarryV1Test,FnoFuturesCalendarSpreadV1Test,FnoFuturesRolloverSignalV1Test` on `dev` head | 36 tests, 0 failures (STR-170: 9, STR-171: 12, STR-172: 15) |
| Production callers of the three evaluators | None. They are referenced only by their own tests and the manifest. |
| Recorded, source-verified NSE_FO observations in the repo | None. `grep` for `NSE_FO|` in fixtures/receipts finds only the STR-170 doc. |
| Provider reachability from the build sandbox (Upstox API, Upstox public instrument master, Angel One API and scrip master) | Blocked by the egress proxy (no connection). |

Because no recorded observations exist and the sandbox cannot reach either provider, **no integration test against real market data was added**. Adding fixtures that look like provider data would be fabrication, so none were written.

## Market-data dependencies, from the evaluator code

### STR-170 — Futures Basis Carry (`FnoFuturesBasisCarryV1.java`)
- Inputs: `Contract` (spot key `NSE_EQ|…`, future key `NSE_FO|…`, same underlying, expiry 3–90 days, lot size, `contractVerified`, `deliverableSpotVerified`), two `Quote`s (bid, ask, exchangeAt, observedAt; spot and future), `SourceRights` (spot use, futures use, exchange session, stock borrow), `Assumptions` (funding, dividend, borrow, round-trip costs, hurdle).
- Quote rules: bid/ask positive, ask ≥ bid, age ≤ 15 s against as-of, spot/future exchange time skew ≤ 10 s.
- Source identity: **none recorded**. The quote has no source field, so provenance cannot be checked by the evaluator.
- Index futures excluded by design (correct per doc).

### STR-171 — Futures Calendar Spread (`FnoFuturesCalendarSpreadV1.java`)
- Inputs: two `Contract`s (`NSE_FO|…`, same `NSE_EQ|…` or `NSE_INDEX|…` underlying, equal lot size, distinct expiries, near ≥3 days, far ≤180 days, gap 1–120 days), two `Quote`s with `source`, `SourceRights` (near use, far use, contract discovery, exchange session), `Assumptions`.
- Quote rules: same instrument key, same source, positive bid/ask, age ≤ 15 s, cross-leg exchange skew ≤ 10 s.
- **Source defect (line 175):** the accepted set is `UPSTOX_V3` or `ANGEL_ONE_SMARTAPI`. `UPSTOX_V3` is not emitted by any producer in the repo (the only producer strings are `UPSTOX_ANALYTICS` and `ANGEL_ONE_SMARTAPI`). With the current pipeline, STR-171 would reject every real Upstox quote. See decision D1 below.

### STR-172 — Futures Rollover Signal (`FnoFuturesRolloverSignalV1.java`)
- Inputs: two `Contract`s (near 3–15 days, far ≤100 days, same underlying and lot size), exactly **three** consecutive `CompletedSession`s, each with `closeVerified`, and per leg a `LegSnapshot` (key, source, exchangeAt, observedAt, OI units, session volume units, bid, ask), `SourceRights`, `Thresholds` (6 explicit integers).
- Session rules: weekday only, close at 15:30 IST, exchange time within the 10 minutes before close, leg timestamps within 2 minutes of each other, observedAt between close and as-of, gap 1–5 calendar days between sessions, one provider for all sessions, last session ≤5 days old.
- Source identifiers accepted (line 212): `UPSTOX_ANALYTICS` or `ANGEL_ONE_SMARTAPI`. These match existing producers.
- Holiday status is **not** verified by the evaluator (documented).

## Readiness per strategy

### STR-170 Futures Basis Carry
- **Implemented:** deterministic evaluator (cash-carry and reverse-carry gates), manifest entry, doc, 9 unit tests (pass).
- **Working (verified here):** arithmetic, rights/quote/skew/cost gates and determinism in JUnit with synthetic inputs only.
- **Blocked:** `DATA_BLOCKED` (`MISSING_DATA_F_O_CONTRACTS_OI_FEED`). No source adapter. No source field on quotes, so provenance is unverifiable. Stock-borrow rights not evidenced (reverse carry stays off).
- **Missing:** spot + futures contract master for single stocks; a backend adapter that sets `contractVerified` and `deliverableSpotVerified` from provider data; verified funding, dividend, borrow and settlement assumptions (current values are illustrative); historical as-of replay; PAPER integration.

### STR-171 Futures Calendar Spread
- **Implemented:** evaluator for both directions, manifest entry, doc, 12 unit tests (pass).
- **Working (verified here):** spread arithmetic, per-leg rights, expiry/lot/underlying checks, skew and staleness gates, determinism (JUnit, synthetic inputs).
- **Blocked:** `DATA_BLOCKED`. **Source-identifier mismatch** (`UPSTOX_V3` never produced). No futures contract master, so no two-expiry pair can be built from source. Research-rights verification for both legs has no receipts.
- **Missing:** futures instrument discovery for multiple expiries; a synchronized two-leg L1 snapshot path (the live V3 socket has no source label today); verified financing/dividend/margin/delivery costs; historical calendar replay and out-of-sample tests; PAPER lifecycle.

### STR-172 Futures Rollover Signal
- **Implemented:** evaluator (OI migration, friction, rights, session provenance), manifest entry, doc, 15 unit tests (pass).
- **Working (verified here):** the OI/volume/share formulas and every fail-closed path in JUnit with synthetic inputs.
- **Blocked:** `DATA_BLOCKED`. Requires three completed-session **closing bid/ask** snapshots per leg. Historical candles (`UpstoxAnalyticsSource.historicalEquityCandles`, line ~91, OI at column 6) provide daily OI and volume but **no bid/ask**. The platform does not persist closing quotes, so this history does not exist.
- **Missing:** a futures instrument master; a source-verified daily OI/volume/closing-L1 history adapter with publication timestamps; holiday calendar verification; OI unit conventions per provider; multi-expiry historical events for out-of-sample tests; PAPER observation lifecycle.

## Shared gaps (all three)
1. No futures instrument discovery. Existing discovery (`currentNiftyOptionExpiries`, `currentFnoOptionContracts`) is options-only, and the V3 operator starts only CE/PE pairs (`listedNearAtmKeys`). The V3 codec does accept `NSE_FO|…` keys, so subscribing to futures is possible once keys are discovered.
2. Source rights are unverified. The F&O rights gate (`minttick.upstox-fno.display-rights-verified`, default false) and the rights evidence doc deny research/display use until written provider authorization exists. Nothing here establishes research-use rights for futures.
3. No persisted, source-verified history. Nothing stores closing L1 or OI snapshots, so no as-of backtest can be run.
4. The live F&O pilot is a manual 12-second handshake (`infra/ec2-paper/fno_v3_socket_pilot.py`), not a feed that fans out to strategies. The architecture doc (`docs/architecture/fno-canonical-strategy-feed.md`) describes the target design, which is not built.

## Decisions needed from the owner
- **D1 (STR-171 source set):** keep `UPSTOX_V3`, or change it to the source label the live quote path will actually emit (for example a new `UPSTOX_V3_LIVE`). Either change alters a strategy's accepted inputs, so it needs the owner's decision.
- **D2 (rights):** confirm whether written Upstox/NSE research-use authorization exists for futures quotes, OI and volume. Without it, all three stay `BLOCKED_RIGHTS` in production.
- **D3 (history):** approve a persisted, source-labelled closing-snapshot store (for STR-171 and STR-172 history). Without stored history, STR-172 cannot run as specified.

## Remaining work for any strategy to leave DATA_BLOCKED
1. Futures contract master from a provider, with expiries, lots and provenance (new code).
2. Source-verified snapshot adapter that sets the evaluator's `providerVerified`, `contractVerified` and rights flags server-side.
3. Recorded, source-verified observations and the integration tests that use them (none exist today).
4. As-of backtests, walk-forward and out-of-sample results.
5. PAPER-only observation evidence. LIVE stays separately gated.

## Not claimed
Provider data, rights, backend deployment, PAPER results, profitability and production readiness are all **NOT VERIFIED**. All three strategies remain `DATA_BLOCKED`.
