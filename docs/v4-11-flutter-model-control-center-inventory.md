# V4-11 — Flutter Model Control Center: connected inventory (first delivery slice)

This implementation introduces the first **real connected Flutter Admin model-management screen** rather than another offline research metric.

## Routes and data

- `/admin/models` is reachable from the Flutter desktop navigation rail and the mobile More sheet.
- `GET /api/admin/model-governance/models` supplies immutable registered model definitions with provider, role, family, license status and exact identity fingerprint.
- Selecting a model calls `GET /api/admin/model-governance/models/{modelKey}/versions` for exact registered version, artifact, feature, dataset and code lineage. Results are backend-authoritative, not synthetic placeholders.
- `GET /api/admin/model-governance/emergency-controls` shows active version/family disables and global static fallback. This page does **not** mutate any control.
- All requests use the existing authenticated Flutter API client, including standard auth headers and backend correlation handling. Java endpoints are ADMIN-only. Server 401/403 results clear cached model evidence and show a permission-denied state; other failures retain last-known data with an explicit stale/error presentation.

## Acceptance

- Repository/contract tests verify exact Java endpoint usage, parsing, invalid response rejection, protected auth headers, and 403 propagation.
- Widget tests cover connected inventory, version detail, emergency controls, permission denial, and empty states.
- The page uses the existing responsive Material theme, mobile-first list layout, deterministic test keys, and appropriate loading/error/stale/empty states.
- It states explicitly that LIVE remains unavailable. No Flutter control can enable LIVE or authorize capital, and no backend governance or PAPER order logic changes.

## Remaining V4-11 work

Read-only inventory alone does not complete the V4-11 Admin Model Control Center. Implement independently authorized policy editors with reason/confirmation and rollback, experiment launcher, backtest comparisons, PAPER/SHADOW enrollment and disagreement dashboard, champion/challenger promotion review, drift/calibration, retraining requests, per-model costs, audit views and platform-specific Flutter runtime acceptance.

The user's static IP is reported configured. Subscriptions, data licenses and provider credentials are deferred to operational onboarding, not required to compile/test this UI but still required for the appropriate production uses.
