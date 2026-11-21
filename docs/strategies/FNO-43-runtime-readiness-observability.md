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
