# V4-09 — Durable session pause, resume and quarantine (Java control-plane slice)

This slice adds independent, ADMIN-only safety state to the canonical V56 matched PAPER/SHADOW cohort. It is **not** a live inference publisher, an independently authenticated exchange/news source, a broker authorization or a statement of successful soak.

## Data and behavior

Flyway `V58__v4_matched_session_safety_state.sql` adds a one-row-per-session safety-state record and append-only transition history. Existing V56 sessions are backfilled ACTIVE at revision zero using the existing enrollment fingerprint; this is an **administrative** continuity backfill, not invented runtime evidence. Each new enrollment creates its own state row and initial transition in the same transaction as participant enrollment.

ADMIN-only Java endpoint family:

| Action | Path |
| --- | --- |
| Fetch current state | `GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/control` |
| PAUSE/RESUME/QUARANTINE | `PUT /api/admin/model-governance/paper-shadow/sessions/{sessionId}/control` |
| Fetch immutable transition history | `GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/control/history?limit=50` |

PUT body: `{"action":"PAUSE","reason":"source provenance degraded"}`. Reason 10–512 characters; response includes server-controlled revision, state, SHA-256 fingerprint, actor and timestamp. Changes emit transactionally audited `V4_MATCHED_SESSION_SAFETY_CHANGED` events; no model/market data or capital orders are created.

Allowed transitions: ACTIVE -> PAUSED, PAUSED -> ACTIVE (requires PAPER authority and all enrolled models currently allowed), ACTIVE or PAUSED -> QUARANTINED. Quarantine is **terminal** and requires fresh model enrollment to recover; no QUARANTINED -> ACTIVE shortcut. Duplicate/illegal transitions conflict rather than rewrite state.

### Concurrency and fail-closed requirement

`MatchedPaperShadowIntakeService.append` holds a PostgreSQL `SELECT ... FOR SHARE` lock on the safety row through the complete evidence transaction, rejecting a missing, PAUSED or QUARANTINED row. Session transitions lock the same row with `FOR UPDATE`. This prevents a matched event from committing after a completed pause/quarantine without first observing the transition. Replayed existing evidence is still readable over GET; no new POST append is accepted while inactive. The original V56 immutable case fingerprint, roster, source timing and model-disable checks continue unchanged.

Model/family/global emergency-disable controls are independent; resume checks all enrolled models against current effective governance and cannot override a global fallback. Neither the state machine nor ADMIN can bypass deterministic Java risk, paper execution mode or LIVE release gate. All API responses explicitly mark `canAuthorizeCapital=false`.

## Tests

`MatchedPaperShadowControllerIT`: ACTIVE enrollment and initial transition, PAUSE blocks event creation, governed RESUME admits new event, history and audit evidence, terminal quarantine cannot be resumed or reused, ADMIN-only controls, bounded history, unknown target fail-closed, and disabled challenger blocks resume while quarantine remains possible. Run all Java 21/PostgreSQL/Flyway, Docker, Python, Flutter policy, dependency CI gates before merging.

## Remaining V4-09

Actual authenticated inference publisher; same-source simultaneous champion/challenger model execution; model timeout/abstention; custody-verifiable India news and market feed; real PAPER execution/TCA and fill reconciliation; bounded scenario soak; Flutter Admin pause/quarantine controls. This is a control-plane increment only. No production deployment performed.
