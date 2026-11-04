# V4-09 / V71 — Bounded synthetic research worker fault-soak

## Safety goal

Extend the merged V67–V70 real process-to-process Spring Boot/PostgreSQL/
Java HTTP/1.1 ↔ Python FastAPI synthetic PAPER/SHADOW pipeline to independently
repeat **three separate original enrolled research cases** with distinct
failure types. The genuine trained CatBoost champion remains available;
the LightGBM challenger experiences one deliberately injected error at a
127.0.0.1-only CI proxy:

1. `http-503`: a non-200 response, with unknowable remote computation state.
2. `timeout`: proxy retains the request for 11.5 seconds, beyond the Java
   `HttpRequest` 10-second client deadline. Java must classify as UNKNOWN;
   the HTTP request may have been processed, so **never retry**.
3. `malformed`: HTTP 200 with structurally invalid JSON research response.
   Java must reject the evidence, not substitute a fabricated model prediction.

For each separate original V56 session/case, require:
- Exactly two committed V61 reservations before remote transport and one
  actual real HTTP request per original enrolled model.
- Exactly one authentic CatBoost SUCCESS receipt; the failed LightGBM must
  have an immutable `UNKNOWN/TRANSPORT_UNKNOWN` for 503 and timeout or
  `REJECTED/RESPONSE_REJECTED` for malformed JSON.
- V64 returns `INCOMPLETE_NO_RETRY`; **zero** V62 custody links, V56
  matched cases/decisions, PAPER orders or capital authority.
- Subsequent same-case dispatch must fail HTTP 409, with no new model HTTP
  request or reservation.
- Proxy fault-count must be exactly one per case. A final cleanup step always
  clears the local fault mode, even on assertion failure.

V71 does not alter production Python/Java inference semantics: uncertain
timeouts remain terminal UNKNOWN (non-retriable) by design. The V71 CI-only
fault controls do not appear in FastAPI routes and are bound to loopback,
with a fixed upstream target, safe bounded request lengths, and no logging
of tokens, features, model scores, remote bodies or broker credentials.

The V4 real worker workflow runs the new selected integration test *after*
the original V66–V69 acceptance checks and *before* the separate V70 ten-model
load test. Results are uploaded for 14 days under
`v4-synthetic-worker-fault-soak`. The JSON contains synthetic status names,
receipt/decision counts and per-case elapsed milliseconds; no model data or
user/session identifiers.

## Boundaries and further acceptance

This deliberately creates reproducible CI fault evidence, **not** a statistically
representative multi-day soak, operational p95/p99 throughput, independent
licensed NSE/BSE/news custody, a real deployed worker, permitted live broker
actions, net-of-cost performance or risk authorization. The timeout
observation is not proof that the worker did *not* process the model.
PAPER/SHADOW deployed acceptance and LIVE activation remain separately gated.
