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


## Rights-attested PostgreSQL replay source for STR-176

Fno176VerifiedHistoricalReplayService now reads the existing V102
fno_verified_completed_ohlcv_oi_session table through
FnoVerifiedCompletedOhlcvOiStore.readExactReplayContract.
The SQL requires genuine independently stored original OHLCV and OI
first-available timestamps before each decision's as-of, separately
verified and still valid MODEL_INFERENCE source rights receipts, a
source-verified NSE trading-calendar record and unexpired storage.
The replay read retains immutable original OHLCV, OI and listing
source digests. This is NOT the 3,929-row private SQLite pilot.

The service additionally requires the independently configured
fno-research research/completed-OHLCV/contract-discovery flags, validates
officially matched one-contract session identity, exact provider,
calendar continuity, retained original hashes, and a bounded
7-session lookback. It calls the exact STR-176 Java evaluator
through the source-independent replay bridge. If rights, custody,
contract metadata or calendar gaps are missing, it returns an
explicit BLOCKED reason without fallback candles.

STR-176 can evaluate completed OHLCV/OI without an L1 closing quote.
The adapter passes null for closeQuoteAt rather than inventing a
closing tick. STR-173 remains stricter: it still requires actual
source-timestamped near-close quotes, and cannot use V102 alone.

This is a **production-compilable source-to-evaluator path** with
synthetic SQL/mocking tests, not evidence that eligible V102 rows are
actually present on EC2. The result always retains
independentlyVerifiedHistoricalE2E=false, PAPER=false, LIVE=false:
after-cost out-of-sample evaluation, real source custody proof
inspection and broker execution need separate acceptance.


## All 43 typed historical research evaluator dispatch paths

Fno43HistoricalReplayDispatchV1 now has **43 compile-time references** to
all 43 distinct deterministic Java research evaluator methods in
STR-170..STR-212. The exact number and type of evaluator parameters is
mapped per strategy and checked using reflection in dedicated JUnit tests.
Every strategy's input domains are exact strings from the active capability
manifest, checked per historical as-of window before invocation.

The dispatcher is bounded to 64 strictly increasing historical decisions per
call. Each required input domain has an external source-custody claim containing
the original first-known, capture and rights-verification timestamps, source
and rights-receipt digests. It fails closed on a missing/swapped manifest domain,
source provenance known only later than the hypothetical decision, invalid
digest, unknown strategy, malformed typed data or evaluator exception. It
never logs source objects or broker positions and has no public endpoint,
AWS access, provider downloads or trading route. The deterministic digest
covers each research decision and custody proof; it is not a source-signed
attestation.

**This is a 43/43 _typed routing_ integration, not 43/43 historical market
E2E.** CI invokes research evaluators with fictional typed fixtures and
checks the actual class signatures and source guards. A caller-supplied
proof flag is insufficient to prove data licensing or original publication;
the independently protected source SQL/rights gates still need to provide
the authentic data and custody. Only STR-176 is connected directly to the
rights-attested V102 original PostgreSQL completed-futures source;
STR-173 has its separate typed window adapter and additionally needs
original near-close L1 quotes. The remaining 41 strategies still need
source-specific original historical adapters (multi-leg quotes, IV/Greeks,
underlying histories, account-signed broker position snapshots and official
calendar events, according to their individual requiredInputs).

A positive historical E2E count additionally requires actually executed
real original-data windows, audited point-in-time provenance, independently
reproducible after-cost walk-forward/OOS outcomes and independent approval.
The existing 0/43 real-data E2E, 0/43 PAPER ledger and 0/43 LIVE broker
execution counters remain unchanged. Never promote routing/compilation or
fictitious JUnit fixtures to a successfully backtested strategy.


## Genuine-source replay access for NIFTY long options STR-181 and STR-182

\`Fno181182VerifiedHistoricalReplayService\` supplies original-custody
\`fno_verified_nifty_daily_close\` and
\`fno_verified_nifty_long_option_quote\` V106 rows to the **actual**
\`FnoLongCallV1\` and \`FnoLongPutV1\` Java evaluators at a historical
point in time. Both require explicit original rights flags that default
false. The SQL source enforces independent MODEL_INFERENCE permission
receipts for completed NIFTY closes, option-chain quotes/depth/OI,
underlying exchange ticks, contract master, fee and risk sources,
plus recorded original as-of timestamps and retention. It is not
the owner-private midnight-labelled futures SQLite dataset.

The source adapter independently rejects missing official completed
trading sessions (35 minimum), nonconsecutive or multiple providers,
future-known daily closes, late option/underlying quotes, mixed expiry,
invalid contract strike/tick/lot, excess quote skew, insufficient
depth/OI and unverified cost/capital inputs. The original option
instrument key is an exact parameter; it never selects a convenient
near-money strike or fabricates expiry metadata. Queries are read-only,
bounded, and invoked only behind the existing ADMIN/OPERATOR
\`GET /api/admin/research/fno-43/historical-replay\` route. That
route returns fixed reason codes and aggregate session counts, with
Cache-Control no-store; it returns no quote, broker account or source
hash and does not submit/cancel orders.

This extends genuinely **source-connected historical research** from
STR-174/176/177 to STR-181/182 where rows and rights actually exist;
it does NOT mean V106 data is populated on the deployed host.
The dedicated tests use synthetic source fixtures and never represent
actual replay against licensed historical market events.

The dispatcher still has 43/43 typed structural evaluator paths, but
only the source-specific adapters can read original receipts.
**Independently accepted after-cost market E2E remains 0/43**
until original eligible data and walk-forward/transaction cost
evidence actually pass independent review.


## Original as-of two-option debit spreads STR-183 / STR-184

\`Fno183184VerifiedHistoricalReplayService\` reuses the unchanged
\`FnoDebitSpreadResearchService\` for historical decisions, with an exact
two-key original NSE_FO option pair and independent V106 completed NIFTY
closes plus V108 real debit spread source evidence. The SQL readers
require provider-matched original timestamps and separately VERIFIED
MODEL_INFERENCE rights for the underlying tick, both option quotes,
option contract master and fee/risk receipts. The original research
service enforces complete 35-session calendar, true simultaneous
underlying+both option bid/ask, adequate full-lot depth/OI, cost and
capital/budget prerequisites, expiry/lot/strike consistency and
after-cost hypothetical payout logic.

\`ADMIN/OPERATOR\` \`GET /api/admin/research/fno-43/historical-debit-spread\`
accepts exact historical \`asOf\` and two distinct original option keys;
returns only fixed research reason codes and aggregate counts with
\`Cache-Control: no-store\`. No price data, account positions, private
source receipts or orders are exposed. The other debit spread strategy
is given deliberately blank keys and cannot silently choose an
alternative contract or place an order.

This adds source-connected historical evaluator paths for STR-183/184.
The source fixtures in CI are synthetic: a green job does not prove
that V106/V108 original market rows exist in deployed PostgreSQL or
that a rights-certified after-cost historical out-of-sample outcome
has been observed. The historical E2E accepted counter remains 0/43
until independently validated source/performance/transaction receipts
qualify, and paper/live broker validation is separate.

## Forward-observed, source-verified V102 ingestion boundary (2026-11-24)

The exact-host source audit #45344281862 established that the owner PAPER
PostgreSQL V102 `fno_verified_completed_ohlcv_oi_session` has **zero rows**.
This is the blocking genuine-source prerequisite for STR-176, not a UI or
research evaluator problem.

`Fno176OriginalObservedIngestor` is a **dormant, explicitly unwired**
source-intake adapter boundary, not a scheduled historical downloader. It
accepts three original, bounded raw packet byte arrays (OHLCV, final OI,
official contract master) from a future independently reviewed authenticated
provider acquisition adapter. Its mandatory
`IndependentlyVerifiedSource` interface must inspect original provider
bytes, exact contract identity, session date, lot/underlying, OI unit
normalization and calendar/rights receipt IDs. No default permissive
implementation exists and no controller, scheduler, SSM token getter or
broker order path invokes this ingestion class.

The import checks *both* current `RAW_RETENTION` and `MODEL_INFERENCE`
rights separately for OHLCV, OI and official NSE calendar before invoking
the independent verifier. The actual PostgreSQL `INSERT ... SELECT`
rechecks separately keyed immutable inference rights, existing verified
NSE calendar receipt, grant validity and revocations at database wall time,
and retained data window. It refuses sessions from today or future and
refuses conflicting provider/contract/session records. Raw provider
packets never leave memory; SHA-256 digests of those bytes are recorded,
not arbitrary input digests. A successful insert means **source packet
custody accepted**, not a completed strategy backtest.

**Original publication-time protection:** Upstox's historical candle
timestamp documents the start of the candle interval. That timestamp is
not proof of the exact first time the completed candle or OI was
published. The intake therefore accepts **no arbitrary client-supplied
`ohlcv_first_available_at` or `oi_first_available_at`**. Its database
`clock_timestamp()` is conservatively recorded into V102 as the
first *observed by MintTick* time for both feeds. This does **not**
purport to be the vendor's actual first publication. It prevents a
future point-in-time replay from using a later-fetched candle at an
earlier hypothetical decision. Independently attested earlier source
publication evidence requires a separate reviewed ingestion path and
cannot be manufactured from an old daily session label.

CI:
- `Fno176OriginalObservedIngestorTest` checks deny-by-default rights,
  unchanged packet bytes, validated values/identity, rejections,
  SHA-256 digests, DB-accepted-time evidence and no PAPER/LIVE.
- `Fno176OriginalObservedIngestorIT` checks real runner-local PostgreSQL
  append-only insert/rights-joined readback using **synthetic** provider,
  calendar and rights fixtures. These are not external permissions.
- `minttick-str176-forward-observed-source-intake.yml` explicitly rejects
  operational price shortcuts, broker operations, retroactive first-known
  timestamps and hidden auto-enable paths.

**Not yet done:** a genuinely authenticated and independently authorized
source adapter for real completed NSE_FO candles, independently verified
original contract master and final OI; real captured records on the PAPER
host; seven consecutive rights-qualified sessions; actual STR-176 historical
decision replay; transaction-cost/walk-forward OOS; independent acceptance.
The historical strategy certification counter remains **0/43** until
those sources, outcomes and checks have actually passed. This PR does
not deploy or enable provider collection and MUST NOT be described as
populating the real V102 table.
