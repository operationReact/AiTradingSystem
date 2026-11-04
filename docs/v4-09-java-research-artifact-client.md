# V4-09 Java → Python trained-artifact inference client (research only)

## This PR's verified-code scope

PR #400 implements actual CatBoost/LightGBM scoring at Python `POST /v1/research/artifact-inference`.
PRs #402/#405 freeze six causal, source-bound Java features in PostgreSQL V60.
This slice adds the Java **internal client** that can request one enrolled model score
from a validated V60 projection and reject invalid or tampered research-only responses.
It does **not** yet launch a runtime session, read enrollment policy itself, write
per-model durable outcomes, append V56 matched decisions, make PAPER fills, call a
broker, promote a model or enable LIVE.

The client intentionally has no public REST endpoint and is disconnected from
trading execution. All three configuration values must be present for use:

- `AI_RESEARCH_WORKER_CLIENT_ENABLED=true` (false by default).
- `AI_RESEARCH_WORKER_URL=https://<private-approved-worker-origin>` (HTTPS
  required except loopback-only HTTP for local testing; no URL credentials,
  path, query, fragment or redirect).
- `AI_RESEARCH_WORKER_TOKEN=<private-random-secret-at-least-32-characters>`.
  Set this to match the Python worker's `AI_RESEARCH_WORKER_TOKEN`;
  Python also requires its own operator-managed `AI_RESEARCH_MODEL_MANIFEST`.
  Never put the secret or artifact paths in Git, Notion, logs or Flutter.

Calls are synchronous, bounded (3-second connect/10-second total), nonredirecting,
and **never retried** after an uncertain outcome. The request consists only of
server-frozen causal feature fields and registered model UUID. The response must
retain exactly the Python contract fields; original case/model/source identities
and canonical request SHA-256 must agree. The independently calculated
Python-style response hash, numerical bounds, timestamp causality, model family,
action and all three false authority/custody/promotion flags are checked.
Remote body/header/exception messages are not returned to application callers.

The Java tests cover a **Python-produced independent canonical JSON golden hash**,
bounded request construction, disabled default, token length, refusal of plaintext
remote origins and redirectable URLs, forged capital/promotion and response
fingerprints, unexpected model identity, rejected unfrozen source and no auto retry.
Test fixtures are synthetic and do not establish market-data rights, genuine
model skill or operational runtime acceptance.

## Next controlled integration PR (required)

1. Add an append-only PostgreSQL per-session/case/model attempt ledger with
   immutable request/response hashes, status `SUCCEEDED/REJECTED/TIMEOUT/UNKNOWN`,
   model artifact identity, expiry and safe diagnostics. Mark uncertain outcomes
   UNKNOWN; do not silently replay.
2. Build an ADMIN-only, PAPER-gated orchestrator which locks V58 session safety,
   verifies frozen V59/V60 source and registered model roster/fingerprints,
   dispatches bounded per-model worker requests **outside DB row-lock spans**, and
   persists individual results transactionally.
3. Only when all matched original roster responses are validated, fresh,
   consistent and durable, permit the existing V56 matched-decision service to
   append the same-case evidence. No order action is implied.
4. Add Java/PostgreSQL/FastAPI E2E tests for success, partial failure, timeout,
   quarantine and concurrent mutation. Then perform an authorized real-runtime
   acceptance exercise. Keep all operational licensing, broker eligibility,
   PAPER/SHADOW soak, production deployment and LIVE release separate and gated.
