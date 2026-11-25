# V4-09 / V70 — Bounded ten-member synthetic research-roster throughput

## Target gap

V69 proved fail-closed operator PAUSE during two concurrent real Python
model calls. V70 adds a **bounded load/concurrency observation** at the
actual V64 maximum **10 enrolled models** and **4 in-flight requests per case**.

The test worker still uses real serialized CatBoost/LightGBM trained model
weights, authenticated loopback HTTP/1.1, Java Spring/ADMIN security,
runner-local PostgreSQL V56/V59/V60/V61/V62/V63/V64 and no order APIs.
For throughput purposes only, the original **two actual synthetic trained
artifacts** are reused under **ten distinct trusted CI-only model IDs**.
These are NOT ten independently researched or validated trading models.

## Deterministic load procedure

1. Run all original V66–V69 Java/Python full-stack tests with exactly
   two original registered models/immutable private manifest.
2. Append eight deterministic extra version IDs to that private manifest.
   They reference only the original two SHA-256 checked local synthetic
   artifact files. The real Python allowlist validator still checks every
   version ID, file hash, model family and artifact fingerprint.
3. Register all ten exact Python-manifest models into Java and enroll
   one PAPER champion with nine SHADOW challengers, on a new synthetic
   causal source snapshot and frozen Java feature row.
4. Arm the CI-only loopback gate. Start the real ADMIN V64 roster POST.
   Confirm **exactly four** real worker HTTP requests are held after
   **exactly four V61 reservations** have already committed. Additional
   calls may not bypass V64's four-thread per-case bound. Release the gate.
5. Require the actual roster to finish within the existing **45-second**
   V64 budget and within V61's **60-second** reservation TTL. Confirm
   10 terminal SUCCEEDED receipts (identity-bound and research-only),
   10 V56 matched decisions, one V56 case and one V62 custody link.
   Require zero PAPER orders and no capital authority.
6. Repeat the same case and assert no additional Python HTTP requests.
7. Emit `v4-synthetic-roster-throughput.json` as a 14-day CI artifact,
   containing only a coarse wall-clock runtime, observed in-flight
   concurrency, roster size, number of trained weight families, successful
   receipts and research-only flags. Do not include tokens, features,
   user IDs, copyrighted market content or model predictions.

V70 uses only a **single bounded synthetic run** in one CI host. The
45-second check is an existing functional timeout, NOT a certified
production latency SLA, throughput guarantee or probabilistic percentile.
For meaningful p50/p95/p99 latency and broker-economic comparisons, a
separate repeated, representative and independently sourced PAPER/SHADOW
soak is required.

## Deployment and licensing still blocked

No production Java/Python worker is deployed or attested by this test.
The external Indian equity market/news rights, point-in-time source
custody, historical survivorship/adjustments, actual broker reconciliations,
transaction costs, calibration/drift, mobile production readiness and
separate LIVE risk authorization remain UNVERIFIED. Do not turn on
automatic orders or infer trading profitability from these CI artifacts.
