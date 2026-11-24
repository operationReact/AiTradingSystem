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

## Upstox owner-personal original HTTP custody (2026-11-24)

The owner confirmed that MintTick's market-data use is private and that
the owner considers necessary personal-use rights already held. Do not
interrupt engineering to request the same permission again. This declaration
does not by itself fabricate third-party VERIFIED receipt rows or establish
independent historical first-publication time, pricing, or data quality.

The existing `UpstoxExpiredCandles` provider now carries an immutable
`SourceObservation` containing the **exact original successful HTTP response
bytes and the actual first time this client received them**. Its
`BackfillWorker` routes such replies through the strict
`SQLiteHistoricalCandleSink.upsert_with_observation` path; a target sink
that cannot preserve the original HTTP bytes blocks the job rather than
silently falling back to normalized candle-only storage.

The sink stores an append-only provider HTTP packet with its byte length,
SHA-256 and observed-at time in `fno_original_http_capture` in the same
SQLite transaction as canonical candles and the preexisting immutable
normalized SHA receipt. Exact source JSON candle rows must match the
persisted normalized bars. Existing batches with no HTTP receipt cannot
be retroactively promoted, duplicate retrieval of the same payload is
idempotent, and a changed provider response fails rather than overwrites.

`fno_independent_candle_e2e.audit` additionally re-hashes saved original
HTTP payloads, reconciles their bars to the canonical candle records,
checks capture-time ordering, rejects orphan/mutated evidence and reports
the **aggregate** `replayableOriginalHttpResponseBatches`. It deliberately
returns `originalProviderPublicationTimeVerified=false` and
`historicalStrategyE2EValidated=0`: an authentic HTTP response can be
independently replayed and audited without proving the vendor first made
that historical bar/finished-OI value available before its session-close
decision. None of these receipts is a cryptographically signed vendor
publication attestation, and none contains evidence of bid/ask fills
or after-cost OOS. Prior cached observations remain clearly legacy.

The change has no AWS/S3 calls, auto-scheduling, broker order routes or
production deployment. It preserves existing no-default-allow checks
without asking the owner to request an additional external permission.
The next certification prerequisites remain original source data with
valid exchange-calendar/contract metadata, genuine as-of feasibility,
the evaluator replay, OOS costs and independent review.

## STR-176 original-as-of daily walk-forward with after-cost link (2026-11-24)

The original `Fno176VerifiedHistoricalReplayService.inspect` evaluates only
**one** seven-session history window per call. That is not an independent
multi-decision historical backtest, train/OOS split, or after-cost ledger.
`Fno176SourceLinkedWalkForwardV1` closes that concrete engineering gap:

- It makes a separate call to the **real rights-joined V102 source and real
  STR-176 Java evaluator** for *every* official NSE trading day in a bounded
  26-calendar-day range. Decisions run at **09:00 Asia/Kolkata the following
  eligible session day**, so completed prior-day OHLCV/final-OI must already
  have been available by that historical decision instant. It never queries
  the final day then replays that hindsight snapshot on old decisions.
- The operator chooses an explicit final training day. Every genuine
  session must be present, with seven verified lookback sessions, a source
  input digest and a real evaluator result. Missing calendar, unavailable
  original source, or a blocked evaluator fails the **entire** range rather
  than cherry-picking profitable days. The output distinguishes training and
  chronological out-of-sample daily decisions.
- Only original evaluator `RESEARCH_CANDIDATE` decision dates may match
  externally provided completed fill/trade evidence. Trade records with no
  corresponding source-derived candidate or repeated entry date fail closed.
  `FnoHistoricalAfterCostWalkForwardV1` then computes actual supplied gross,
  brokerage, statutory tax, spread/slippage, impact, financing, and capital
  outcomes; rejects chronological overlap and training/OOS crosses.
  Crucially the service **never invents fills or transaction costs**.
- Authenticated ADMIN/OPERATOR-only, read-only
  `GET /api/admin/research/fno-43/str176-walkforward` exposes only
  state, fixed reason and bounded aggregate decision counts. It deliberately
  accepts no arbitrary caller-supplied fill proof. It exports no original
  futures keys, market prices, account data or original source digests.
  Its response keeps historical certification, PAPER and LIVE flags false.
- A real after-cost broker-fill/quote provenance source and independent
  market replay acceptance are still required before a strategy is certified.
  Green JUnit tests on **synthetic fixture dates** prove this code's
  fail-closed behavior, not a real profitable or independently signed replay.

The exact deployed PAPER V102 receipt from GitHub Actions #45349531935 had
`v102Rows=0`, `rightsJoinedRows=0`,
`fullyAuthorizedProviders=0`, and `verifiedCalendarRows=0`.
Until actual source records and authentic market-as-of fill evidence exist
and the runtime is deployed, this new service cannot independently certify
any strategy. **Historical independent E2E remains 0/43.**

## 2026-11-24 — Direct STR-176 futures input expansion (source, not certification)

The existing owner-private expired-NIFTY collection covered only the **last
20 calendar days before each expiry**, leaving relatively few eligible seven
completed-session STR-176 decision windows once the 7-day-to-expiry rule is
applied. New owner-private bounded collector
`str176_private_futures_capture.py` requests **up to 60 calendar days** of
historical **FUTURES ONLY**, for at most **12 expired NIFTY contract months**.
It uses the existing private owner-scoped Upstox token and approved data-use
context. It keeps actual source HTTP response bytes, SHA-256, received-at
timestamp, provider-issued contract key/expiry and lot size in a separate
append-only, private SQLite file. It never fabricates the vendor's original
historical publication time; the candle timestamp remains a session label.

The one-file exact-host request workflow is separate from code review CI;
the host collector is not triggered by a PR or ordinary merge. It uses
the existing owner PAPER EC2, a maximum of 25 GETs (one expiries request,
12 futures metadata requests, up to 12 candle requests), and no live/order
credentials. Malformed, ambiguous or double-labelled daily bars are
quarantined instead of silently picking a row or being called market-proof.
The output contains only aggregate month, row and quarantine counts,
no futures keys, tokens, market values or URLs. It is **not a V102 write**,
does not create verified third-party rights or calendar rows, and does not
change PAPER/LIVE rights.

The independent historical E2E acceptance remains:
authentic data and contract metadata, exchange-calendar and volume-unit
reconciliation, decisions calculated solely from completed prior sessions,
modeled next-session open fills with explicit documented execution costs,
chronological train/OOS, source-independent decision comparison, repeatable
immutable evidence receipts. Historical simulation does not require actual
broker fill receipts; those are a *separate* PAPER/LIVE certification gate.
No sample will count as 1/43 until the real host execution and the independent
replay/OOS tests pass all these requirements.

## STR-176 authentic owner archive → modelled after-cost OOS replay (2026-11-24)

`str176_private_futures_aftercost.py` independently reads and re-hashes
the original 60-day owner-private Upstox futures HTTP records collected
by `str176_private_futures_capture.py`. The collector is GET-only and
stores actual response bytes; this separate replay is read-only and has
no provider token, API call or broker order dependency.

The replay implements the fixed production STR-176 momentum threshold
logic for seven completed daily sessions, with independent daily data
validation and no future bar in its signals. It models an entry at the
**open two complete sessions after** the last signal bar and an exit at
the following open, not a same-bar or hindsight close fill. It applies
an explicit, fixed 40-bps per-round-trip research cost assumption
(including fees/tax/spread/slippage/impact) and aggregates gross/net P&L
and maximum drawdown. The **earliest eight expiry months** form training;
later months are untouched out-of-sample. The split is set before
looking at returns; blocked source windows and zero signals are
reported, not discarded as profitable trades. No strategy parameters
are optimized using OOS outcomes.

A passing **real host** simulation establishes that real archived
historical prices were fed through an independently implemented,
time-delayed strategy and after-cost model. It does *not* establish the
vendor's historical original publication times, independently
cross-check every Upstox monthly volume/OI source against official NSE,
verify executable historical bid/ask quotes, or prove actual broker
fills. These limitations must be measured before a full independent
historical strategy certification is accepted; PAPER/LIVE are separate.
The protected one-file SSM request now invokes **capture then replay**,
emitting only private aggregate decision, trade and after-cost counters.
A genuine market replay failure must never be counted as 1/43.

## STR-176 owner-private **actual-data retrospective simulation**, first executable pilot

The 3,929-row short-lived canonical daily research SQLite archive on the
existing PAPER EC2 has **not** populated V102 (last verified real V102 row count
zero). To stop repeatedly reporting only 0% after source-code milestones,
`str176_owner_canonical_research.py` performs an explicitly distinct,
owner-private, reproducible **retrospective research** pilot on that real cache,
while keeping true independent market E2E at zero until its higher bar passes.

A single fixed, reviewed request invokes the script over existing AWS OIDC/SSM,
never transmits source candles, account data, future contract keys, or tokens.
It first validates owner-only file permissions, SQLite integrity, original
seven-day retention expiration, exact 3,929 rows, 276 distinct contracts, 3,608
Upstox and 321 source-reconciled NSE EOD records. A 12-call maximum authenticated
Upstox expired-contract **read-only metadata** query identifies the *actual*
12 monthly NIFTY futures keys and lot sizes; futures are not guessed from
opaque contract IDs. If original metadata, retained cache, or contract cohorts
are missing, the pilot fails or reports the concrete missing source.

For each futures expiry and each legitimate seven-session source window,
a separate conservative Python numerical STR-176 momentum reference evaluator
uses only **those seven prior completed sessions** (previous six highs/lows,
last three closes, volume/OI and cost-adjusted 100-bps breakout thresholds).
A research signal is modeled entering at the **next** session's open and
exiting at the following session's open, one lot, with 30 bps assumed
round-trip friction (20 and 50 bps supported in the reference engine).
The training/OOS split is frozen as the oldest **eight** calendar expiry cohorts
vs latest **four**, with no post-hoc favorable subset selection. It
calculates non-account after-cost simulated P&L, loss count and drawdown.
Runner-local synthetic tests verify the input schema, true future metadata,
decision-before-fill, tamper rejection and expiry.

**Source-time and fill limitations:** The original Upstox daily time
`00:00+05:30` is a session label, not a first-seen publication receipt;
assuming EOD completeness before next session's open is a stated research
assumption. An NSE-reconciled EOD OHLC/OI value alone does not prove the
actual original vendor's as-of dissemination. Official NSE holiday calendars
and historical bid/ask/top-of-book fills are not present in this cache.
Modeled next-open executions plus assumed friction are **not** executed
broker orders or independently verified market fills. Python reference
logic is not by itself proof that the Java strategy implementation produced
identical decisions; separate Java-vs-reference parity and independent
replay remains necessary. Therefore, this pilot's nonzero evaluator/signals/
simulated-trade counters are **meaningful actual historical research
progress**, but never upgrade `independentHistoricalE2E` or PAPER/LIVE.
Historical strategy certification remains **0/43** pending those independent
acceptance checks.

Progress checkpoints to report after actual host run are:
`sourceRows`, `sourceFuturesContracts`, `decisionWindows`,
`evaluatorWindows`, `candidateWindows`,
`simulatedRoundTrips`, `trainingTrades`, `outOfSampleTrades`,
`oosNetPaise`, `oosMaxDrawdownPaise`, and the fixed fail reason.
A passing offline fixture is NOT evidence those actual host counters exist.

## 2026-11-24 REAL host STR-176 first retrospective result and reason audit

The protected real owner host job [GitHub run #45363819619](
https://github.com/operationReact/AiTradingSystem/actions/runs/45363819619)
successfully read **3,929** real source-attributed canonical research rows,
authenticated **12** Upstox futures contract metadata responses, selected
**12** futures contracts with **171** daily futures rows, and attempted **75**
seven-session decision windows. **31** passed the structural evaluator input
gate, **44** were rejected at input eligibility, **0** met the unchanged
STR-176 momentum thresholds, and **0** next-open one-lot trades existed
to feed the train/OOS after-cost simulator (0 TRAIN, 0 OOS).
All source data remained private, no host writes/orders, and accepted
independent historical strategies remain **0/43**.

This is now an actual owner-private retrospective input test rather than
only unit-test coverage; **41.3% (31/75) is an input-eligible historical
window fraction, NOT a strategy certification percentage.**

Reason-aware version: `momentum(window,...,details=True)` returns its
identical original research decision *plus* one fixed non-sensitive diagnostic
reason. It categorizes missing sessions/OI/volume, source-session gaps,
future-expiry eligibility, stale source, price-range/gap outliers, absent
baseline, low participation, missing breakout, long/short breakout.
Every attempted window contributes **exactly one** diagnostic reason;
aggregate counts cannot leave the host unless they sum to the total input
window count. No thresholds, first-known assumptions, cached market values,
trade executions or rights records were changed. The next exact reviewed
read-only owner-host request will expose the **actual** reason histogram,
without instrument identifiers, prices or private broker values.

Do **not** optimize the 100-bps breakout/11,000-bps volume lift or 20-bps
minimum cost-adjusted edge solely to manufacture trading signals.

## Source-neutral diagnosis of the 26 no-signal participation failures

Actual protected owner-host [run #45364820027](
https://github.com/operationReact/AiTradingSystem/actions/runs/45364820027)
verified, from its exact 75-window retrospective input:
`EXPIRY_OUT_OF_SCOPE=44`, `VOLUME_OR_OI_PARTICIPATION_LOW=26`,
and `BREAKOUT_NOT_CONFIRMED=5`. The 44 near-expiry windows were
**correctly** rejected by the production 7-day-to-expiry gate;
nothing should loosen that to make a larger progress percentage.
The volume/OI branch's combined reason was insufficient to distinguish a
genuine low-volume regime from a data-unit normalization mismatch.

The follow-up keeps the same values, 11,000-bps recent-vs-baseline volume
ratio and 2,000-bps volume/OI threshold. It changes only the fixed diagnostic
label: `VOLUME_LIFT_LOW`, `VOLUME_TO_OI_LOW`, or
`BOTH_VOLUME_FILTERS_LOW`. Exact unit provenance must be cross-checked
against the exchange before any normalization or strategy threshold change.
No success/certification flags change.

## Independent source-class localization of STR-176 historical participation failures

The exact owner PAPER source-custody run
[#45366112030](https://github.com/operationReact/AiTradingSystem/actions/runs/45366112030)
measured 75 retrospective futures decision windows on 171 real canonical
NIFTY FUT bars across 12 actual contracts. Breakdown:
44 `EXPIRY_OUT_OF_SCOPE`,
17 `VOLUME_LIFT_LOW`,
2 `VOLUME_TO_OI_LOW`,
7 `BOTH_VOLUME_FILTERS_LOW`,
5 `BREAKOUT_NOT_CONFIRMED`, 0 qualified long/short signals.

The next read-only source diagnosis keeps every trading-signal threshold
and cost unchanged. `diagnosticReasonsBySource` partitions those same
**75** window outcomes by *existing* original-custody category:
`UPSTOX_PRIVATE` vs `NSE_PUBLIC_EOD`. Every window is counted once.
The aggregate provider-class reason counts must reconcile exactly to
`diagnosticReasons` and the total decision count before the private host
can emit a bounded receipt; mixed source classes inside a seven-session
contract fail closed. No futures keys, OHLC prices, OI values, source
timestamps, user credentials, or market payloads leave the host.

This establishes whether the 17 low volume-growth and 7 dual low-volume
results are concentrated in one upstream data class and warrant
a raw-source contract-volume/lot-size normalization audit. It does **not**
assert the NSE and Upstox volume/OI units are already equal, change
eligibility, bypass the 44 correctly rejected near-expiry windows, or
turn retrospective simulated opens into actual source-proven fills.
Independent historical certification stays at **0/43**.

## STR-176 expanded authenticated 90-day futures pilot (research tier)

The actual owner-private read-only run
[#45368422158](https://github.com/operationReact/AiTradingSystem/actions/runs/45368422158)
showed that 44 of 75 fixed 20-day-cohort test windows were correctly rejected
near contract expiry. The 31 eligible windows produced zero trades: 17 low
volume-lift, 2 low volume-to-OI, 7 both, 5 no breakout. By original source,
NSE-reconciled windows had 3 expiry and 3 lift failures, while Upstox-private
windows had 41 expiry, 14 lift, 2 OI, 7 both, 5 no breakout. **These
observations do not support loosening the 7-day-to-expiry guard.**

`str176_expanded_upstox_research.py` directly addresses the *coverage*
limitation rather than changing the strategy. It uses existing authenticated
single-owner research authorization and SSM token on the exact PAPER host,
with a fixed capped **49 read-only Upstox GETs**:
1 expired monthly expiry list, 12 verified NIFTY futures contract metadata
lookups (one per completed month), 36 historical DAY candle requests split into
three consecutive **30-day** windows per expiry covering the last **90
calendar days**. The provider's actual returned dates, OHLC, volume and OI
are parsed and checked; unknown/ambiguous futures keys, non-trading weekends,
duplicate candle days, abnormal prices, empty/missing contracts, unexpected
requests or provider errors fail or report incomplete evidence. The source
data is analyzed **in memory only** and never persisted/exported.

The numerical reference preserves original STR-176 momentum/expiry/volume/OI
thresholds: a distinct seven-completed-session decision window precedes
each modeled future open; one-lot next-open-to-following-open fills are
*simulated* with 20, 30 and 50 basis-point round-trip friction sensitivity,
chronologically splitting oldest eight futures expiry months from last four
out-of-sample. A same-contract overlapping entry is skipped; no arbitrary
price, trade, broker execution, exchange calendar or provider source
publication timestamp is invented. An exact-host protected OIDC/SSM workflow
may return only bounded aggregate futures rows, candidate/evaluator windows,
modeled trades and after-cost OOS values.

The first 3,929-row owner canonical cache remains **unmodified**. The new
90-day cohort can be broader than the 171 old futures rows, but its
actual provider responses, source value quality and ability to generate
STR-176 signals must be established by the **host-proof** receipt, not
from the synthetic unit tests. This does not establish point-in-time
availability at past decision dates, exact official NSE trading calendars,
Java evaluator parity on source rows, historical spread/bid-ask, broker
fills, or independently accepted cost-aware OOS market replay.
**Historical independent E2E remains 0/43** until all acceptance criteria
are independently met. No user-side licensing request, new AWS resource,
PAPER order or LIVE execution is included.
