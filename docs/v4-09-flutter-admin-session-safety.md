# V4-09/V4-11 — Flutter Admin session safety controls

## Delivered slice

Extends existing Flutter `/admin/models` PAPER/SHADOW tab with the Java-authoritative V58 control state introduced by merged PR #393. No new mock UI route, no separate safety authority, no broker integration.

- Reads `GET /api/admin/model-governance/paper-shadow/sessions/{sessionId}/control` and `.../control/history?limit=20` via existing authenticated Java ADMIN API client.
- Displays ACTIVE/PAUSED/QUARANTINED, revision, backend fingerprint, last actor/reason and five most recent immutable transition records.
- Only ACTIVE sessions offer **Pause intake**; PAUSED sessions offer **Resume intake**; ACTIVE/PAUSED sessions offer **Quarantine permanently**. QUARANTINED is terminal and cannot resume through this UI.
- All mutations show an explicit confirmation dialog and require a 10–512-character audit reason. Cancel/blank reasons submit **zero** requests.
- After `PUT /control`, validates Java returned expected state and revision advancement and independently GETs both state and immutable history before displaying success. Mismatched/missing/forged evidence fails closed and disables further mutations until refresh.
- Any HTTP 401/403 from safety reads/writes purges the same Flutter Admin cached model/session/evidence snapshots, including in-flight prior generations (compatible with merged PR #391).
- Error, loading, denial, empty and last-known states do not fabricate session status. No model promotion, LIVE activation, PAPER order or BROKER action is present.

## Acceptance tests

Dart mocked HTTP verifies exact authenticated GET/PUT/history route and server-required body; no invalid action or short reason sends a PUT; forged `canAuthorizeCapital=true` or wrong state is rejected. Flutter widget tests verify pause with reason, display of backend PAUSED, cancellation, irreversible quarantine (no resume button) and the absence of LIVE actions. Existing model inventory, session-browser, lineage and permission-revocation tests are preserved.

**Crucial limitation:** these controls **only halt new matched Java evidence intake**, not the separate automatic PAPER order scheduler, actual inference workers, market feed consumers or broker orders. Global emergency controls and Java deterministic risk authority remain independent. Operator remediation must coordinate those components explicitly. This slice is neither verified simultaneous PAPER/SHADOW operation nor real licensing/soak acceptance.

## Remaining

Implement authenticated market/news/model runner, matching single-source freeze, worker leases/timeouts, actual PAPER trade linkage/reconciliation, source rights and continuous soak, and complete Flutter enrollment/environment controls. V4-09 and V4-11 remain IN PROGRESS. Do not infer platform device QA or deployment from CI build artifacts.
