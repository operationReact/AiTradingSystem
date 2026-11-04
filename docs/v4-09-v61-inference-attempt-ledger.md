# V4-09 / V61 — Immutable trained-model inference attempt ledger

## This bounded implementation slice

The previous merged #406 Java client can make one authenticated Python CatBoost or
LightGBM request against a V60 database-frozen causal feature projection. Without
durable pre-dispatch intent, a process crash or network timeout could otherwise
lead to unsafe retries or invented "missing" model decisions.

V61 introduces **two immutable PostgreSQL tables**:

- `v4_research_inference_reservation`: a single pre-dispatch reservation for
  an exact enrolled (session ID, case key, model version ID). It binds the V56
  roster, V59 original source identity and V60 frozen features, registered model
  version/artifact, causal as-of time, reservation time and 60-second expiry.
  Uniqueness prevents any implicit replay for failed/unknown/expired outcomes.
- `v4_research_inference_receipt`: one terminal event
  `SUCCEEDED`, `REJECTED`, `TIMEOUT`, or `UNKNOWN`. Validated success
  includes canonical request and response hashes, trained model family, action,
  score and evaluated time. Unsuccessful events record only enumerated safe
  diagnostics, never a worker body, token or remote error message.

A database trigger rejects UPDATE/DELETE of either table; exact-key uniqueness
and foreign keys defend immutable provenance. Missing receipts after interruption
are **UNKNOWN**, never permission to re-dispatch. No retry queue is created.

`ResearchInferenceAttemptLedger` is internal Java code; it does not expose
a REST controller or execute inference. `reserve` checks the active V58 session,
the PAPER execution mode, model emergency controls, frozen original source and
enrollment identity before persisting intent in its own transaction. Future
orchestration must **commit this reservation before network dispatch** and must
not hold database row locks while making a remote call. `succeed` validates the
already-validated response against the reservation, exact frozen symbol, model
artifact, original source hash, expiry and current safety state. `fail` records
safe terminal outcomes after pause/quarantine for forensic completeness.

Integration tests cover:
1. durable reservation without a terminal receipt and forbidden replay;
2. terminal success with no capital authority, duplicate prevention and
   database-enforced UPDATE/DELETE rejection;
3. forged model identity and uncertain transport outcomes;
4. pause blocking success while still allowing a rejection receipt.

## Important limitations / next PR

1. No ADMIN-only orchestration/dispatch endpoint, feature-roster worker scheduler,
   remote Python deployment, or automatic V56 same-case matched-decision append.
   The ledger is not yet invoked by a runnable end-to-end scoring flow.
2. The test fixtures are synthetic database evidence, **not** proof of licensed
   market/news custody, actual Indian-market inference accuracy, operational
   latency, broker execution, PAPER/SHADOW soak, or economic acceptance.
3. A later PR must use distinct transaction boundaries for pre-dispatch
   reservation, bounded no-retry worker call and terminal receipt. It must mark
   any abandoned reservation UNKNOWN and require fresh cases, never resend the
   same case/model. It must atomically verify that all required roster receipts
   succeed and are fresh before publishing V56 observation evidence.
4. Both Python and Java worker configuration remain opt-in and disabled by
   default. No model promotion, LIVE capital, broker trade or paid data
   activation is enabled in V61.

Evidence: exact-head CI on the V61 PR is required before merging. V4-09 remains
IN PROGRESS even after these database/unit/integration tests are green.
