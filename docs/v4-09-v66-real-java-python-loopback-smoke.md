# V4-09 / V66 — Real loopback Java → Python trained-artifact HTTP smoke

## Why this is necessary

PR #413 placed Python's real trained-artifact tests and Java's independent mock-transport contract tests on the same commit, but the tests never exchanged an actual HTTP request. Consequently, time formatting, JSON number serialization, authenticated headers, trained-weight loading, Python response canonicalization or the real Java HttpClient behavior could diverge without CI noticing.

## Implementation

This PR adds a **separate exact-head GitHub Actions job** to `.github/workflows/v4-research-artifact-contract.yml`. In a disposable Ubuntu runner, it:

1. Installs the actual pinned Python CatBoost, LightGBM and FastAPI dependencies and Java 21.
2. Trains two small **synthetic historical** challenger artifacts using existing model test fixtures; serializes their actual weights and constructs a SHA-256 verified, UUID allowlisted manifest in `$RUNNER_TEMP`.
3. Generates an ephemeral 64-character worker token in-memory; runs real Uvicorn bound **only** to `127.0.0.1:18771` with no external connections or trading credentials.
4. Runs `ResearchArtifactWorkerLiveHttpTest` through the **production** Java `ResearchArtifactInferenceClient` constructor, which uses the default production `HttpClient` implementation (not a mocked transport). It scores both CatBoost and LightGBM, validates real response and request canonical fingerprints, expected artifact IDs, fresh causal times, finite scores and research-only capital flags.
5. Sends incorrect-token and nonallowlisted-model requests through the same Java HTTP adapter. Verifies fail-closed handling with no token exposure. Verifies that a disabled Java client cannot use the worker.
6. Kills Uvicorn and removes all ephemeral generated weights, manifest and logs after the test, even on failure.

The JUnit test is protected with `@EnabledIfEnvironmentVariable(V4_WORKER_HTTP_SMOKE=true)` and is **skipped in ordinary Java unit/DB CI**, preventing reliance on ports or worker secrets. The workflow supplies all required ephemeral environment variables. No pinned credentials or saved weights are committed.

## Explicit limits

This is a **genuine interprocess network integration test** using the production Java HTTP client and a running Python inference worker, but its projection is an intentionally synthetic, in-memory record. It does **not** prove the Java V59/V60 PostgreSQL source-capture pipeline, the V61 attempt ledger, V62 finalization or V64 orchestration communicate with a remote deployed worker in one live session. Those parts still have independent real PostgreSQL and unit tests. It does not use licensed exchange/news feeds, Angel One credentials, PAPER orders, economic backtests, live broker connections or real money. It does not certify external infrastructure, model accuracy, full-roster latency, iOS connected-device behavior or production readiness.

**Acceptance:** V4 real HTTP worker job, Python and Java contract jobs, Java 21/PostgreSQL baseline and applicable security/research workflows must all succeed for the exact PR head. Any hash/schema mismatch must fail CI rather than patching production safeguards to accept untrusted data.
