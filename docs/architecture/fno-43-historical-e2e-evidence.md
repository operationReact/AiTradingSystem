# MintTick F&O 43 — actual historical evidence and acceptance gate

## What the reported 0/43 means

The existing 43-strategy research CI suite compiles deterministic evaluators
and exercises fictional unit-test fixtures. It is deliberately NOT a real
historical replay. An original provider chain, a successful EC2 deploy, or
live WebSocket health is also not historical strategy E2E.

## Owner-private read-only history probe

The new infra/ec2-paper/fno43_historical_e2e_probe.py runs only with an
approved GitHub one-file workflow on the exact existing EC2 host. It reads
the owner-private, expiring personal-canonical-daily.sqlite via SQLite
mode=ro and PRAGMA query_only=ON. There is no provider REST/WebSocket call,
broker trade, AWS resource mutation, market-data export or raw price output.

The check validates source expiry, private file permissions, SQLite quick_check,
3,929 rows, 276 contracts, 3,608 private Upstox candles, 321 NSE December
candles, unique contract-day observations, original session-date labels,
OHLC, volume, OI, exchange contract expiry and source class.

The original collected pilot had 4,251 observations. December raw data
contained 321 duplicate contract-days and was quarantined before separate
NSE comparison and a 3,929-row research-only canonical store. The store's
original receipt states backtestingApproved=false. Its timestamp is a daily
SESSION LABEL at midnight, not the original first-known time. Only an expiring
seven-day private cache exists; it must not be silently renewed or exported.

**Valid historical data integrity is not E2E replay.** The cache does not
contain signed source-use rights, original publication timestamps, complete
index/equity candles, historical synchronized option/future bid/ask/depth,
IV/Greeks, broker positions, audited evaluator invocations or after-cost OOS
walk-forward results. Therefore the proof stays 0/43 even if all cached rows
pass integrity checks.

The protected probe emits a 43-row strategy blocker matrix with strategy ID,
family, and missing domains; it does not claim that strategies in the same
family have identical prerequisites:

| Strategy IDs | Family | Missing examples |
| --- | --- | --- |
| 170–180 | Futures | Original-time spot/futures L1, same-session source and financing |
| 181–191 | Directional options | Index/equity as-of history, option L1 and depth |
| 192–203 | Volatility/options | Genuine IV/Greeks, multiple legs, event/position fields |
| 204–212 | Portfolio/hedging | Broker as-of positions, depth, Greeks and costs |

The authoritative, more specific input list for EACH evaluator is in
backend/src/main/resources/mind/capability-manifest.v1.json.

## Runbook

1. Merge the script, tests, workflow and this ADR after all exact-head CI checks.
2. On dev, separately create ONE approved request file at
   infra/ec2-paper/requests/fno43-historical-e2e-probe.json using the exact
   JSON object defined in the script. That push alone triggers the protected
   SSM read-only job. Merge alone does not read private host data.
3. Verify the run receipt with the observed 3,929-row cache integrity counters
   or the exact unavailable/expired reason; never infer absent records as zero.
4. For actual E2E, obtain independently authorized original historical index,
   option, futures, OI and bid/ask/depth/IV/Greeks/holdings domains required
   by each strategy, including original first-known and source-rights receipts.
5. Replay only earlier-known market events through the actual Java evaluator,
   with independent source verification, train/OOS chronological separation,
   costs, slippage, taxes and liquidity/impact, and verified expiry calendar.
6. Persist independent private source/replay hashes and per-strategy acceptance
   results. Count only reproducible, original-data, after-cost OOS proofs.
   PAPER fill/ledger validation and LIVE broker order validation are separate.

The current read-only probe NEVER raises the 0/43 to a positive number by
itself. That would require qualifying market inputs, a real replay runner,
and independent outcome evidence. The existing rights and expiry controls
remain unchanged, as does the ban on live orders from an audit.

Tracking: https://github.com/operationReact/AiTradingSystem/issues/1658


## First actual evaluator replay bridge (STR-173 and STR-176)

FnoFuturesHistoricalReplayBridgeV1 adds bounded, deterministic,
**actual Java evaluator calls** for STR-173 (five completed futures OI
sessions) and STR-176 (seven completed futures OHLCV/OI sessions). It
supports up to 64 strictly increasing historical decision windows,
one provider and instrument per replay, independently supplied original
first-known OI/price timestamps, session/contract identities, normalized
underlying-unit volume/OI, original research source rights, and a
SHA-256 receipt reference per window.

The bridge uses the existing unchanged research evaluator thresholds.
It distinguishes BLOCKED_NOT_E2E from
EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E, and emits a deterministic
digest and research-only decision reasons. The synthetic CI test
executes the actual STR-173/176 evaluator methods and checks future
data rejection, duplicate session/date, cross-provider data, bad OHLC,
missing rights, rollover and determinism; it does not access SSM,
market data, broker accounts or place trades.

This is genuine **adapter implementation progress (2/43)**,
NOT 2 independently verified historical market E2E successes.
The owner-private pilot's 3,929 session-labeled bars do not have
attested original first-known publication times or independent
source-use rights, so they cannot be fed into this bridge as
"verified" rows by inferring those timestamps from midnight labels.
A source adapter must first obtain original custody and licensed
rights evidence; thereafter extend to a bounded historical
walk-forward/OOS, bid-ask and statutory-cost evaluator ledger before
any strategy may qualify as historical E2E.
