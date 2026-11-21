# MintTick Mind F&O — 43-strategy end-to-end integration / evidence plan

## Current verified boundary (2026-11-21)

Scope is **exactly STR-170 through STR-212** (43 deterministic research evaluators). The capability manifest currently contains all 43 distinct source/test/documentation entries, but **every one remains `DATA_BLOCKED`**. These are **code implementations**, not proof of genuine market data, historical P&L, broker order readiness or profit.

The existing backend evidence shows:
- **STR-170, STR-171, STR-172** have direct Java research call sites in `FnoFuturesResearchService`. These may return blocked results, because access/quote/instrument rights and actual historical snapshots must be independently established. A call site is not proof of execution against real data.
- The existing `CustomerMindFnoResearchController` uses `FnoLongOptionCrossoverV1` for signals identifying **STR-181 / STR-182**; `CustomerMindFnoLiveProposalService` recognizes those identifiers, but that does not show that the separate `FnoLongCallV1`/`FnoLongPutV1` evaluators were invoked. Do not count these as the same integration.
- Other cataloged strategy evaluators have **not had independent adapter/runtime invocation evidence established by this audit**. That must not be read as proof the adapters cannot exist elsewhere.
- Existing customer PAPER automation and broker LIVE proposal services **do not confer any order permission on the 43 research-only evaluators**.
- Earlier source QA, private source coverage and historical quality workflows run on separate explicit audited request scopes. Their mere existence does not prove clean historical data, complete Greeks, authentic broker positions or complete 43-strategy datasets. This PR does not trigger AWS workflows or change resources.

## Phase E2E-1 — evaluator evidence (this PR)

Commit an independent, reproducible read-only **43-strategy integration-evidence gate**: `scripts/fno_strategy_e2e_audit.py`, `scripts/tests/test_fno_strategy_e2e_audit.py`, `.github/workflows/minttick-mind-fno-43-integration-audit.yml`.

CI checks the authoritative manifest:
1. Exactly the unique contiguous `strategy.STR-170`–`strategy.STR-212` IDs; each explicitly `DATA_BLOCKED`, `JAVA_DETERMINISTIC`, with a unique SOURCE, TEST and DOC evidence file under bounded repository paths.
2. Every genuine Java source contains the matching strategy ID, evaluator entrypoint, explicit `executable()==false` and `orderPlaced()==false`. Optional PAPER/LIVE authorization methods must never return true.
3. Every referenced JUnit test source contains the actual strategy ID and at least one `@Test` case.
4. Execute **exactly those 43 manifest-named JUnit classes** on Java 21. Parse matching class-named Maven Surefire XML and refuse to pass if any class is missing, skipped, failed, has an error, or differs from test source declaration count.
5. Publish `fno43-evaluator-validation.json` with per-strategy source paths, actual executed test counts and source-level call-site observations. No secret or market-price export. The report **hard-codes zero real-data E2E validation, zero authorized PAPER, zero authorized LIVE** because the offline runner supplies no broker or NSE source evidence. Green Java/JUnit **never overrides** these zeros.

This automated gate is a **unit-to-manifest/test integration validation**, not full real-data business E2E. It is designed to prevent "43 implemented" being silently misreported as "43 production trading-ready".

## Phase E2E-2 — actual canonical market provenance (pending)

For each strategy create a named typed source adapter with:
- Genuine NSE `NSE_EQ`, `NSE_FO`, `NSE_INDEX` identities, official instrument master/expiry/lot/tick, provider rights and instrument lifecycle;
- Source-original exchange timestamp and *first-known* timestamp for every candle, book/option leg, OI, bid/ask, IV, delta/gamma/vega/theta (unit contract), event and funding assumption;
- Complete daily/intraday real candle, OI and side-specific order book lineage, missing bars/expiry crosswalk checks, duplicate-policy and timestamp integrity. No synthetic quotes or interpolated Greeks substituted for real data;
- Source-level coverage matrix per exact instrument, expiry, timeframe, provider and count; fail-closed, clear reason codes for absent data.

Run the independent owner-private history **read-only** audits on actual authorized broker/data snapshots under their own protected workflow. Attach provider dataset version, legal access/retention scope, audit digest and observed timestamp. A CI fixture alone must never satisfy this requirement.

## Phase E2E-3 — adapter execution and historical validation (pending)

Wire each distinct evaluator's actual Java `evaluate(...)` contract to its own typed real-data adapter, **not a generic one-size-fits-all quote DTO**. For every STR-170–212 report:
- `ADAPTER_AVAILABLE`, `SOURCE_RIGHTS_VERIFIED`, `INPUTS_COMPLETE`, `EVALUATED_AT`, `RESULT_STATUS` and exact original-known dataset revision;
- source completeness by instrument/expiry; robust 200+ day IV samples where required; actual matched pair/historical-book data for multileg structures; reconciled signed broker holdings where required;
- rejection paths `BLOCKED_RIGHTS`, `BLOCKED_DATA`, stale/closed session, insufficient depth and unavailable source. **Blocked is a valid honest result, not a candidate**;
- original-first-known chronological out-of-sample walk-forward, after-spread/slippage/statutory-cost scenario returns, untouched holdout, expiry and tail loss, settlement/margin and partial fill/multileg legging risks. No mixing current Greeks into historical replay.

No strategy passes data E2E until a real authenticated provider-based invocation is recorded and reproduces from retained data lineage (or an auditable rights-permitted digest) on the chosen exact backend revision.

## Phase E2E-4 — PAPER, UI, then separately authorized LIVE (pending)

For each contract/room type, independent acceptance of PAPER ledger (cash, margin, multi-leg atomicity/legging, partial fills, duplicate-orders, tax/fees, settlement, reconciliation, stop and kill switch), strategy-to-execution eligibility and Flutter diagnostics. **No automatic promotion from research signal to broker order**. LIVE broker F&O rights, per-order owner authorization, real funds/collateral, exchange/broker consistency and kill switches are a separately audited gate.

## Completion definitions (do not conflate)

| Gate | Minimum acceptance |
|---|---|
| Research evaluator integration | 43 valid manifest source/test/docs, 43 class-level Surefire passes, no order authority |
| Real-data source coverage | 43 source adapters with genuine timestamped provider instruments/chain/Greeks/positions as required; individual status and missing list |
| Historical E2E | 43 actual gated evaluator invocations with source version, 0 unexpected failures, replayable walk-forward and cost evidence |
| PAPER E2E | Independently configured and reconciled paper-only testing; explicit activation rights, room/lot/position matching, no broker live order |
| LIVE E2E | Separately approved access/risk, money paths, manual owner confirmation and observed broker reconciliation |

**Next implementation task:** per-strategy source adapter registry and read-only dataset coverage report for STR-170–212, starting with the 3 direct futures call sites and 2 index signal routes. Build durable `DATA_BLOCKED` reasons before connecting research evaluators to any order-capable component.
