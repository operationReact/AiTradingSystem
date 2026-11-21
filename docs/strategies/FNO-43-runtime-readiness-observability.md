# F&O 43 — runtime source-readiness observability (E2E-2, first increment)

This is a read-only deployment candidate, not full 43-strategy historical/market execution.
All 43 STR-170 through STR-212 stay `DATA_BLOCKED` pending independently verified
source rights and real source-original replay.

## New operator contract

`GET /api/admin/research/fno-43/readiness` — authenticated ADMIN / OPERATOR only;
`Cache-Control: no-store`; no credentials, positions or original market values emitted.

The response lists **exactly 43** sorted strategies with the real manifest
`requiredInputs` and evaluator source paths. Each gets an explicit integration stage:

- STR-170/171/172/173: `DIRECT_EVALUATOR_OBSERVED` if the existing read-only
  `FnoFuturesResearchService` returned an evaluator result, otherwise
  `DIRECT_ADAPTER_UNAVAILABLE`. Research may be blocked for missing rights,
  costs, contracts, quotes or historical sessions. These statuses are not
  exchange-data provenance or true strategy E2E evidence.
- STR-181/182: `SIGNAL_ROUTE_ONLY`. The existing `FnoLongOptionCrossoverV1`
  signal path is **not** a distinct `FnoLongCallV1` or `FnoLongPutV1`
  evaluator invocation.
- STR-174–180, STR-183–212: `NO_TYPED_SOURCE_ADAPTER`, even where
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
research-rights and master freshness gates. The registry now has four directly
invoked research evaluator paths and 39 missing. An observed result may be
BLOCKED_RIGHTS or BLOCKED_DATA. All independent real-source E2E, PAPER and
LIVE validation counters remain zero.
