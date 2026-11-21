# F&O 43 — runtime source-readiness observability (E2E-2, first increment)

This is a read-only deployment candidate, not full 43-strategy historical/market execution.
All 43 STR-170 through STR-212 stay `DATA_BLOCKED` pending independently verified
source rights and real source-original replay.

## New operator contract

`GET /api/admin/research/fno-43/readiness` — authenticated ADMIN / OPERATOR only;
`Cache-Control: no-store`; no credentials, positions or original market values emitted.

The response lists **exactly 43** sorted strategies with the real manifest
`requiredInputs` and evaluator source paths. Each gets an explicit integration stage:

- STR-170/171/172/173/174/175/176/177/178/179/180: `DIRECT_EVALUATOR_OBSERVED` if the existing read-only
  `FnoFuturesResearchService` returned an evaluator result, otherwise
  `DIRECT_ADAPTER_UNAVAILABLE`. Research may be blocked for missing rights,
  costs, contracts, quotes or historical sessions. These statuses are not
  exchange-data provenance or true strategy E2E evidence.
- STR-181/182: `SIGNAL_ROUTE_ONLY`. The existing `FnoLongOptionCrossoverV1`
  signal path is **not** a distinct `FnoLongCallV1` or `FnoLongPutV1`
  evaluator invocation.
- STR-180, STR-183–212: `NO_TYPED_SOURCE_ADAPTER`, even where
  separate product screens, old services or JUnit fixtures exist.

The four independent acceptance booleans per strategy are always false:
source-rights independently verified, historical E2E verified, PAPER E2E
verified and LIVE E2E verified. They cannot be inferred from app configuration
flags, fictional fixtures, a `RESEARCH_CANDIDATE` or a shared signal route.
If a research source throws, the dashboard reports `RUNTIME_EVALUATION_EVIDENCE_MISSING`;
it never reflects provider exception text. Duplicate direct evaluator evidence
also fails closed.

## What comes next

Wire typed source adapters to each missing distinct `evaluate(...)` contract,
using the shared canonical feed with true instrument/expiry/OI/Greeks timestamps,
source-first-known original market/broker provenance and actual input coverage.
For each evaluator, record a real read-only invocation, blocked reason, source
revision and rights-proven dataset digest, then add verified historical
walk-forward and independent PAPER trade-ledger acceptance. LIVE broker orders
remain a separate explicitly authorized process.

Tests in `Fno43StrategyReadinessServiceTest` verify exact 43 names/input
contracts, the four direct evaluator observations, the two separate signal
routes, missing/duplicate source fail-closed and rejection of automatic PAPER/LIVE
promotion. Tests are synthetic and provide no source-data coverage evidence.


**21 Nov E2E-2 update:** STR-173 has a read-only session-OI adapter with
research-rights and master freshness gates. The registry now has eleven directly
invoked research evaluator paths and 37 missing. An observed result may be
BLOCKED_RIGHTS or BLOCKED_DATA. All independent real-source E2E, PAPER and
LIVE validation counters remain zero.


**21 Nov E2E-2 follow-on:** STR-174 now has a distinct evaluator invocation
and an empty verified final close/OI source-receipt store. Without source
records that separately attest final close, finalized OI, calendar and original
as-of availability it stays BLOCKED_DATA; the default research-rights flag
keeps it BLOCKED_RIGHTS. Count **five direct research evaluator paths, 38
remaining**, still zero independently verified source-E2E, PAPER and LIVE.


**21 Nov E2E-2 STR-175 update:** A sixth distinct read-only research evaluator
call is connected through one provider/account-scoped, independently rights-gated,
point-in-time L1-depth + broker margin/capital and cost evidence store. The
new financial evidence table is empty by default and is purged after at most
seven days. No arbitrary instrument, assumed cash, synthetic book depth or
notional brokerage fees are substituted. The registry counts **6/43 direct
research paths**, **32 without direct adapters**, **2 signal-only routes**,
and still **0/43 independently validated real-source E2E, PAPER or LIVE**.


**21 Nov STR-176:** Seventh distinct completed-session evaluator path
connected, using V102 verified original-time OHLCV/OI history and a default-denied
research source flag. Registry summary: **7/43 direct evaluator callsites**,
**36/43 without direct adapters**, **2 signal-only**, **0/43 independently
verified historical real-source E2E**, **0/43 PAPER** and **0/43 LIVE**.


**21 Nov STR-177 follow-on:** Eighth distinct direct evaluator is now wired
for ten historical completed futures sessions using the shared V102 OHLCV/OI
source rights and actual first-known timestamp evidence boundary. Registry
shows **8/43 direct research invocation paths**, **35 missing distinct adapters**,
**2 signal-only**, and **0/43 independent historical real-source E2E, PAPER
or LIVE**. The source remains empty until real attested records are ingested.


**21 Nov STR-178:** Ninth distinct direct research evaluator now connects an
index-futures quote to independent broker-account portfolio beta, hedges,
margin, depth and room-limit evidence (empty V103 until verified sources arrive).
**9/43 direct callsites; 34 lack direct adapters; 2 signal-only; 0/43
independent real-data E2E, 0/43 PAPER, 0/43 LIVE.** No actual broker order
routing or risk-limit bypass is introduced.


**21 Nov STR-179:** Tenth distinct research-only futures liquidity gate now
uses three source-attested intraday L1/depth/OI/volume receipts (V104), independent
research rights and the existing live quote source. Futures-only current
adapter until option identity is separately proven. **10/43 direct evaluator
paths, 32 without direct adapters, 2 signal-only, and 0/43 independently
proven real-data E2E, PAPER or LIVE**. No liquidity screen result is an order.


**21 Nov STR-180:** Eleventh distinct research evaluator integrated with
original as-of spot/futures co-timed 15:30 indicative quotes, two exchange
calendars, later effective-dated F&O finalization, delivery and reviewed cost
provenance. V105 is empty by default and three source-use rights gates remain
disabled unless separately verified. **11/43 direct evaluator paths; 32 lack
direct adapters; 2 signal-only; 0/43 independently verified real data E2E,
0/43 PAPER, 0/43 LIVE.** No trades or AWS changes.


## 21 November 2026: STR-181/182 options E2E-2 integration

The two old EMA8/EMA21 **signal routes** now have *additional, separate*
direct invocations of their real evaluator classes in
`FnoOptionsResearchService`. Source-level evidence inspection includes
both the original futures service and the new options service; simply
finding a signal string no longer counts as distinct evaluator evidence.

V106 records independent original first-available 35+ completed NIFTY
daily closes and exact source-listed NIFTY CE/PE option quotes, depth/OI,
explicit Indian fees and source-reviewed budget. All tables start EMPTY
and source-use flags default DENIED. No data licensing, broker funds,
profitability, broker paper fills or live orders are inferred.

**Total direct research evaluator paths: 13/43; missing direct adapters:
30/43; signal-only without evaluator: 0/43; independently verified real
market history E2E: 0/43; PAPER: 0/43; LIVE: 0/43.** The earlier
11/43 statements above are historical snapshots, not the current tally.


## 21 November 2026: STR-183/184 source-backed debit spreads

Two distinct direct research evaluator calls in
`FnoDebitSpreadResearchService` validate, separately, the bull call
debit and bear put debit spreads using shared licensed NIFTY daily
history (V106) and full new as-of two-option source custody (V107).
The second leg's BID, ASK, independent depth and OI are never inferred
from a single-leg signal. Combined option fees, tax, risk allocation,
same-provider timestamps, lot and expiry require separate source proof
and research-use grants. Source tables start EMPTY; all flags default
DENIED; actual no-lookahead backtesting and broker execution are pending.

**Current registry evidence: 15/43 distinct direct research evaluator
paths, 28/43 with no typed direct adapter, 0 signal-route-only without
distinct evaluator, 0/43 independently verified historical real-data
E2E, 0/43 PAPER, 0/43 LIVE.** Earlier counts above are dated snapshots.


## 21 November 2026 — STR-185 and STR-186 credit-spread research

Separate, actual evaluator callsites for
`FnoBullPutCreditSpreadV1.evaluate` and
`FnoBearCallCreditSpreadV1.evaluate` are now wired to shared verified
V106 NIFTY daily history and new V108 account-scoped market-plus-margin
source proof. The broker-account source requires real settled positions,
six independent market/master/margin/cost research grants, and funded
initial/variation/settlement reserves; a broker login or source price
is not proof of funds or order authorization. V108 is empty by default.

**Current: 17/43 distinct direct research evaluator paths, 26/43
without direct adapters, 0 signal-only paths lacking direct evaluators,
0/43 independently verified real NSE source E2E, 0/43 PAPER, 0/43 LIVE.**
Earlier totals in this document are historical checkpoints.


## 21 November 2026 — STR-187/188 source-gated settled equity coverage

Two new independent direct read-only evaluators now require truly settled
and broker-reconciled stock shares, the genuine corresponding one-lot
physically deliverable PE or CE contract, 35+ completed equity closes
and original first-known quote/depth/OI timestamps. New V109 source
tables are EMPTY; ownership proof, tax/fee/budget and data use-rights
all default unavailable. Real broker holdings/paper/watchlists are not
silently converted into verified proof.

**Current 19/43 direct research evaluator paths; 24/43 without direct
adapters; 0 remaining signal-only without evaluator; independently
verified real source E2E 0/43, PAPER 0/43, LIVE 0/43.**
Earlier values in this document are dated audit snapshots.

## 21 November 2026: STR-189 verified collar direct research adapter

The twentieth distinct research path now separately evaluates a broker
settled one-lot stock collar with a lower-strike PE and higher-strike CE.
V109 source data is reused and V110 adds independently entitled **genuine
stock BID/ASK** plus nonduplicated combined fee/assignment/room risk
evidence. Both tables remain empty at default and rights are denied.

**20/43 direct evaluator paths; 23 without adapters; 0/43 independent
real-source history E2E, 0/43 PAPER, 0/43 LIVE.** All 43 catalog
entries stay `DATA_BLOCKED`, and no production order is submitted.


## 21 November 2026 — STR-190/191 true 1x2 ratio source research paths

The current audit observes **22/43 distinct read-only research evaluator
calls**, with **21 still missing direct adapters**. The new V111 empty
source table requires original exchange-timed L1 depth/OI quotes,
account-scoped authenticated short margin, independently verified
three-leg fees, two-short-lot liquidity and explicit tail-risk review
plus existing genuine daily close source records. Call upside
theoretical risk remains **unbounded**; put downside is severe,
finite at zero underlying, but not an execution guarantee.

Manifest remains `DATA_BLOCKED`; independently verified original
real-source E2E **0/43**, PAPER **0/43**, LIVE **0/43**.

## 21 November 2026 — STR-192 true original two-leg IV long straddle

The existing `FnoLongStraddleV1.evaluate` from PR #1541 is now called directly
by `FnoStraddleResearchService`, rather than being counted solely because
a deterministic strategy class/JUnit fixture exists. This uses the independent
V106 completed NIFTY close source and new **EMPTY** V112 source-attested original
NIFTY index+CE+PE bid/ask, independently captured **per-leg IV**, depth/OI,
identical verified strike/lot/expiry and original as-of timestamps.

Two separate IV digest/receipt records are mandatory. Model-computed,
calibrated or fabricated IV is not allowed to appear as captured market IV.
Six separate research rights receipts (index, option chain, instrument master,
call IV, put IV, all-in costs) and four rights configuration gates remain
default DENIED; both option keys default blank. No data is seeded.

**Current research integration: 23/43 distinct typed evaluator calls;
20/43 lack direct adapters. Independent true-market historical E2E 0/43,
PAPER 0/43, LIVE 0/43.** This is a research-only integration; all strategies
remain `DATA_BLOCKED`. Estimated one-sigma moves are not return forecasts.
The next planned adapter is STR-193 long strangle, which MUST NOT reuse
the same-strike straddle proof as a valid OTM strangle instrument pair.

## 21 November 2026 — STR-193 distinct OTM long strangle IV research

`FnoStrangleResearchService` directly invokes the existing distinct
`FnoLongStrangleV1.evaluate` from the earlier merged model PRs.
V113 stores EMPTY, separately attested actual higher-strike CE and
lower-strike PE with **strict** `PE strike < original index bid < CE strike`,
co-timed quotes, independently proven per-leg option IV, and genuine same
lot and expiry. One STR-192 same-strike straddle row can NEVER qualify.

STR-193 shares true V106 completed NIFTY closes but not the V112
same-strike option proof. Six source-model research-use receipts must be
verified as-of original quote time, including distinct per-leg IV digests
and receipts. All source flags remain default denied and exact contract
keys blank. No IV is calculated from the candidate's own quote and
passed off as an independent market observation.

**24/43 typed research evaluator callsites**, **19/43 without direct
adapters**, **0/43 independently verified real-market history E2E**,
**0/43 PAPER**, **0/43 LIVE**. All 43 entries stay `DATA_BLOCKED`.
The hypothetical IV one-sigma screening metric is NOT a profitability
estimate. Next direct research path: STR-194 Short Straddle; unlimited
call upside-loss and real broker margin/writing rights must be preserved.

## 21 November 2026 — STR-194 original BID-depth, account-scoped short straddle

The existing `FnoShortStraddleV1.evaluate` is invoked by a distinct
`FnoShortStraddleResearchService` read-only path. V114 adds a new
**EMPTY** account-scoped original NIFTY CE/PE bid/ask market source with
**independent BID-side depth and per-leg original IV**; selling at ask or
substituting derived IV is prohibited. Both genuine options must have
the **same verified strike, lot size and expiry**. The underlying source
must match true completed V106 NIFTY daily history.

Seven source receipts are checked as-of original time (underlying tick,
option chain, instrument master, independent CE IV, independent PE IV,
two-leg fees, broker-account short writing/margin). In addition to
separately verified CE/PE IV and instrument identities, V114 requires
a real reconciled broker margin reserve, evidenced short-writing
eligibility, nonduplicated all-in fees, genuine two-leg BID depth and
explicit review of the **UNBOUNDED short-call upside-loss tail**.
A current broker margin reserve or stress scenario is **not** a
maximum loss bound and does **not** grant order authority.

Research flags and exact option/account keys default denied/blank.
No provider token, market record, short-writing flag, margin estimate,
broker approval or order is generated by the migration, adapter or tests.

**Research integration 25/43 distinct direct evaluator callsites,
18/43 remaining adapters. Independently verified real market E2E 0/43,
PAPER 0/43, LIVE 0/43.** All strategies remain `DATA_BLOCKED`.
The deterministic Java fixtures and CI are NOT independently verified
NSE real-data or broker-order evidence. Next: STR-195 Short Strangle,
which must instead prove **separate OTM strikes** and account-level
two-short-leg margin/writing rights.

## 21 November 2026 — STR-195 out-of-money short strangle source research

The existing `FnoShortStrangleV1.evaluate` is now invoked by a distinct
`FnoShortStrangleResearchService` directly on **EMPTY** V115 source data,
with **PE strike < original NIFTY index bid < CE strike** for two genuinely
listed contracts of matching lot size and expiry. The V114 same-strike
short straddle evidence cannot qualify as an OTM strangle.

V115 requires original synchronized CE/PE real **BID depth** and distinct
provider-emitted true annualized IV with separately verified digests and
model-inference research-use receipts; independent account-scoped broker
short-writing eligibility, margin reserve, stress scenario and nonduplicated
combined fees with an explicit **UNBOUNDED CE upside loss** warning.
Margin, after-cost premium, and stressed finite price are **not** a maximum
loss cap, not evidence of profitable trades, and never authorize orders.
All rights default DENIED and account/instrument keys remain blank.

**26/43 distinct read-only research evaluators, 17 without direct adapters.
Independently verified original NSE real-data E2E 0/43, PAPER 0/43,
LIVE 0/43.** All strategies remain `DATA_BLOCKED`, with no
broker trading permission changes or production deployment.
Next distinct research path: STR-196 (per capability manifest), requiring
its own typed source proof, not inferred shared evaluator aliases.

## 21 November 2026 — STR-196 source-attested four-leg iron condor

New read-only `FnoIronCondorResearchService` calls the existing
`FnoIronCondorV1.evaluate` on exact V116 `FnoVerifiedNiftyIronCondorSourceStore`
evidence. Four independent listed NIFTY options require
long low PE < short high PE < original NIFTY spot < short low CE <
long high CE, with same expiry and lot. Five synchronized original-time
exchange quotes are required (underlying and each option).

Both sold legs use actual BID and BID depth; each protective long uses
actual ASK and ASK depth. Every leg requires its independently attested
provider-emitted annualized IV, independent quote/source hash and
source-as-of `MODEL_INFERENCE` rights; all original 35+ V106 completed
NIFTY candles and four-leg fees must be verified. No synthesized quotes,
estimated IV, filled broker orders or proof rows are created.

Theoretical max expiry loss uses **the wider of potentially unequal
put/call wing widths**, minus net combined after-cost credit. This
*defined expiry payoff* is not proof of atomic execution, margin,
protection against partial fills, liquidation, or LIVE/PAPER readiness.
All rights default false and exact contract keys remain blank.

**27/43 distinct source-level research evaluator callsites, 16 pending.
Independently verified real historical market-data E2E 0/43,
PAPER 0/43, LIVE 0/43.** The manifest remains `DATA_BLOCKED`.
Next: STR-197 Iron Butterfly (four-leg centered CE/PE and original IV).
