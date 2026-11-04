# V4-09 / V66 — Actual Java HTTP to real Python trained artifact, synthetic CI

## Implementation

Earlier V65 verified Python and Java contracts in independent processes,
but never sent Java request bytes over HTTP to a running Python worker.
V66 adds a **runner-local true HTTP** integration (not a mock transport):

- Build ephemeral, genuine CatBoost and LightGBM research artifacts from
  previously available synthetic training fixtures. Store raw JSON weights
  and sha256-pinned two-model manifest in an exclusive local temp directory.
- Create an ephemeral CI-only 32+ character worker token; bind Uvicorn to
  `127.0.0.1:18721` only, never public interfaces. Worker remains
  disabled by default in regular builds.
- Execute the real Java `ResearchArtifactInferenceClient` with its production
  Java 21 `HttpClient` against the real Python FastAPI
  `/v1/research/artifact-inference` route; score both registered families.
  Verify exact model UUID/family/artifact, original case/symbol/source,
  request/response canonical fingerprints, bounded probability and
  explicit false capital authority.
- Test a real HTTP 401 for invalid token and fail-closed Java handling for
  a model absent from the Python manifest. No retry or broker action.
- Clean up the loopback server, token scope, temporary model weights and
  manifest on job exit (including failure).

The existing `.github/workflows/v4-research-artifact-contract.yml`
gets a third **Java HttpClient to running Python** job, so all synthetic
Python tests, Java contract tests and actual loopback transport run on
the same exact PR commit SHA whenever relevant code changes.

## Scope boundaries

The Java test assembles an explicitly synthetic V60-shaped `ProjectionView`
and invokes the internal client directly, not a production Spring
database session. This is genuine in-process trained weights and
interprocess HTTP, but it **does not** test a real NSE/BSE data provider,
user-owned brokerage account, production deployment, licensed market/news
capture, complete Spring/PostgreSQL V59/V60/V61/V62 roster flow, economic
edge, fees, drawdown or PAPER soak. No LIVE path is enabled. Those require
distinct gated work and independently verifiable evidence.

V4-09 remains IN PROGRESS after passing V66.

## Real transport defect fixed on this branch

The initial real HTTP test correctly failed: Java 21's default HttpClient
attempts cleartext HTTP/2 Upgrade (h2c) against Uvicorn's h11 transport.
Uvicorn observed `Content-Length > 0` but received **zero body bytes** and
FastAPI rejected the request as HTTP 422 (Pydantic `body:missing`).
This was reproduced with a separate minimal Java/Python HTTP client-server
pair and resolved by setting Java's worker client to HTTP/1.1 explicitly.
The security policy remains HTTP only for loopback and HTTPS otherwise,
with redirects and uncertain-outcome retries still disabled.

The real CI integration test guards against this regression; the existing
mocked Java Transport tests alone could not observe it.
