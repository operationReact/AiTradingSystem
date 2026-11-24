# Protected F&O historical source-to-evaluator readiness API

The operator-only GET endpoint is:

/api/admin/research/fno-43/historical-replay?strategyId=strategy.STR-174&contractKey=NSE_FO%7CEXACT_KEY&asOf=2026-11-20T11%3A00%3A00Z

It is only for roles ADMIN and OPERATOR, never a customer/public route.
Requires exact listed instrument key and historical asOf no later than server
time. It sends no broker orders and has no POST, write, export or
source-entitlement activation operation. It returns Cache-Control: no-store.

The endpoint intentionally supports only three **genuine-source-backed**
Java research evaluator adapters:
- STR-174: original authenticated V100 final futures CLOSE and finalized OI
  rights/calendar-joined source, five completed sessions;
- STR-176: original V102 completed OHLCV + OI with separate first-known
  timestamps and immutable source digests, seven sessions;
- STR-177: original V102 completed OHLCV + OI and source rights, ten sessions.

All 43 strategies also have distinct typed Java structural replay dispatch
paths. The other 40 currently cannot be queried through this endpoint for a
genuine source replay because their required underlying, quote, option IV/
Greeks, depth, broker positions and calendar sources have not yet been
historically bound to an independent rights/custody source.

The response only contains strategy ID, as-of date, evaluator/rights
status, fixed reason code, original row counts and session window size,
and FALSE independentHistoricalE2E/PAPER/LIVE/broker order flags. It never
returns contract keys, user holdings, instruments, prices, premiums, Greeks,
source data hashes, data provider tokens, raw SQL or private file contents.

A source-rights flag can be disabled, original source data can be missing
in the deployed database or historical as-of rights may not be qualified.
In those cases the service correctly reports BLOCKED; it must not silently
fabricate an as-of history from a later daily download.

Even when a local historical Java research evaluator produces a candidate,
independent real-market E2E acceptance still requires independently
audited, permitted source custody, reproducible point-in-time replay,
after-fees/slippage/taxes and non-leaking out-of-sample/walk-forward
performance and an immutable private acceptance receipt. This API does not
claim those proofs, nor paper ledger or live broker execution. It does not
enable new research/download rights or bypass permissions.

Tracker: https://github.com/operationReact/AiTradingSystem/issues/1658

## STR-176 actual V102 source-eligibility inventory (read-only)

The protected `GET /api/admin/research/fno-43/str176-original-source`
endpoint inspects the **actual** V102 PostgreSQL historical evidence table,
not the 3,929-row owner-private SQLite research cache and not fictional JUnit
fixtures. It reports only total source rows, rights/time/calendar-joined rows,
and the number of contract/provider groups with at least seven distinct
historical sessions within the bounded 26-calendar-day decision window.
The endpoint returns fixed reason codes and never returns instrument keys,
account identifiers, source digests, market prices, entitlements or broker
credentials. Missing database migrations or backend errors return
`SOURCE_STORE_UNAVAILABLE_OR_UNVERIFIED` with unknown (-1) counts, not
misleading zero counts.

Every potentially eligible session must be joined to independent verified
`MODEL_INFERENCE` research-use receipts for OHLCV and final OI, the
verified NSE historical trading calendar, exchange close, source capture
cutoffs and original as-of availability. Contracts must not be too near or
too far from expiry for STR-176. A seven-session group still does **not**
prove consecutiveness across verified trading holidays, actual source
publication truth, executed evaluator decisions, historical fills, transaction
costs, walk-forward OOS results, or independent acceptance. All acceptance
and order flags stay false.

**Crucial distinction:** the existing `readiness` source-rights metric was
initialized to literal zero and the existing `source-evidence` registry
checks only original envelope families STR-203..212. Neither counter can
establish whether V102 source rows for STR-176 exist. This dedicated query
makes that first prerequisite measurable on a deployed backend.

Deploying this diagnostic after code CI is separate from calling it.
Until authenticated exact-host output and independent historical after-cost
proof exist, certified historical STR-176 remains **0/1**, and full
STR-170..212 certification remains **0/43**.

## Protected original V102 source audit without backend deployment

`infra/ec2-paper/str176_v102_source_probe.py` and
`.github/workflows/minttick-str176-v102-host-readonly.yml` perform a bounded,
read-only original PostgreSQL V102 prerequisite audit on the **existing exact**
owner PAPER EC2 instance, without restarting the backend. The workflow is
triggered on dev solely by an additional reviewed, one-file JSON request:
`infra/ec2-paper/requests/str176-v102-readonly.json`.

It runs a fixed `BEGIN READ ONLY` SQL query inside the existing PostgreSQL
container. SQL joins original source OHLCV/OI receipts, separate independently
verified research-use rights, original first-known/capture timestamps, the
NSE historical trading-calendar evidence and retention/expiry requirements.
The only result leaving the host is three aggregate counts: stored V102 rows,
rights-and-as-of-eligible rows, and provider/contract candidate groups with
seven distinct eligible session dates. If any query, schema, permission,
host-security or output validation fails, all counts are **unknown**, not zero.

This audit does not identify which source rows are usable by the real
strategy after full calendar/price/lot validation, and it does not replay a
strategy, estimate bid/ask fills, account for transaction costs, perform
walk-forward OOS evaluation or independently certify anything. The output
has `independentHistoricalCertified=0/43` and never accesses broker orders.
It must not be used to override evidence gates or reinterpret synthetic tests
as genuine historical observations.
