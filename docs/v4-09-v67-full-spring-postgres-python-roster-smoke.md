# V4-09 / V67 — Synthetic full Spring/PostgreSQL-to-Python PAPER/SHADOW roster smoke

## Why

V66 proved Java 21 actually exchanges HTTP request/response bytes with real
running Uvicorn/FastAPI and trained synthetic CatBoost + LightGBM artifacts,
including the HTTP/1.1 h2c-Upgrade fix. It did not exercise the original Java
ADMIN route, model governance registry, V58 safety, real PostgreSQL V59/V60
source/feature rows, V61 immutable reservation/receipt, V62/V56 finalization,
or V64 full-roster orchestration together.

## Test and CI

`ResearchRealWorkerFullRosterIT` is enabled only in the dedicated runner-local
V4 CI job with `ATS_V4_REAL_HTTP_SMOKE=true`; it is skipped by ordinary
Java/production builds. An isolated PostgreSQL test database and temporary
private FastAPI worker run on the *same CI machine* with freshly trained
synthetic models/manifest, ephemeral worker token and no brokerage credentials.

The test builds two Java model registry entries whose version IDs exactly
match the Python manifest's fixed synthetic training artifacts; registers
one champion/one challenger, prospectively inserts causal synthetic
V59/V60 time-ordered source/features, then invokes:

`POST /api/admin/model-governance/paper-shadow/sessions/{id}/cases/{caseKey}/dispatch-research-roster`

It validates the actual ADMIN/Spring security and V58 ACTIVE state, V63
original enrolled-roster preflight, committed V61 per-model intent before
real Java HTTP/1.1/Python trained-model inference, two immutable terminal
SUCCEEDED receipts, one original V56/V62 matched case and two model
decisions, false capital authority and zero correlated PAPER orders.
Replay of the exact case is refused and never resends the remote request.

The V4 workflow now includes this integration in addition to V66's
independent real HTTP smoke and previous Python/Java model tests.
A failed interprocess model response, expired reservation, omitted roster
member, Spring authorization mismatch or immutable custody failure must
fail CI; no fake decision or fallback is permitted.

## Explicit limitations

- Synthetic market/news inputs were created by a test harness and are NOT
  evidence of independent exchange/news provider rights, point-in-time
  source authenticity, or user broker permissions.
- This test executes within one runner; it is not a deployed Java/Python
  service, latency/throughput acceptance or a production health soak.
- No automatic real-market order, broker reconciliation, promotion, training
  improvement, discretionary risk authority or LIVE trading is enabled.
- V4-09 is still IN PROGRESS until independent licensed Indian market data,
  deployed trained-model worker attestation, PAPER/SHADOW soak, net-of-cost
  returns and independent broker/deployment validation are accepted.

Both Java worker/roster feature toggles remain disabled by default outside
this explicitly gated synthetic CI test.
