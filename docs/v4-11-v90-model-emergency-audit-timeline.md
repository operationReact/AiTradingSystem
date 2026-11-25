# V4-11 / V90 — connected ADMIN emergency model control audit timeline

## Vertical slice

V4-11's Flutter Emergency model controls view previously showed the current override state from Java, but not the history of who changed the safety switches or why. V90 connects a read-only audit-history view to existing PostgreSQL audit_event rows, which emergency control mutations already write transactionally. No tables, invented events, model activation or broker operations.

**Java endpoint:** GET /api/admin/model-governance/emergency-controls/history?limit=25, with optional controlType and scopeKey filters. Requires ROLE_ADMIN via existing controller security. Only the three established emergency-control event types are queried, within a bounded rolling 31-day window, descending by timestamp/audit ID. It accepts 1–50 rows and validates type/scope, accepted status, authenticated actor, boolean flags and the persisted 64-hex state fingerprint. Invalid or unverified rows block the read, rather than being silently shown as trusted safety events. It does not expose arbitrary JSON audit details, correlation IDs, unrelated user activity, private tokens or credentials.

The JSON response exposes only id, eventType, controlType, scopeKey, previousActive, active, reason, actor, occurredAt, stateFingerprint and canAuthorizeCapital=false. The audit record proves that Java committed a control change; it is not independent model-runtime or market evidence.

**Flutter:** Existing /admin/model-emergency ADMIN screen displays server-reported recent changes: actor, scope, prior/new status, reason and timestamp. A refresh control rereads the real Java endpoint. After confirmed emergency reduction, the screen reloads current controls and audit history. Loading/empty/stale/invalid/403 states are distinct; a server 401/403 purges cached current overrides and their history so revoked ADMIN access cannot leave safety evidence visible. The screen retains de-risk-only controls and adds no re-enable, order-submission or LIVE operations.

## Acceptance tests

- backend/src/test/java/in/aitradingsystem/api/ModelEmergencyAuditHistoryIT.java: Spring Security + PostgreSQL mutation/audit/GET, actor/reason/fingerprint, 403 roles, bounded limit and filter rejection.
- apps/trading_client/test/model_emergency_control_repository_test.dart: exact real Java HTTP path, authentication, typed JSON validation, capital-denied response and 403.
- apps/trading_client/test/model_emergency_controls_page_test.dart: connected Flutter screen renders actual server audit history, refreshes/reloads after action and clears on access denial.

No AWS resources, deployment, external model custody, licensed market-data feed, live worker connection, genuine PAPER/SHADOW soak, broker reconciliation or capital authority is supplied here. Python worker remains dormant and Java research activation remains disabled.
