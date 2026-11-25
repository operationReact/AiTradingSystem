# V4-11 — First connected Flutter Admin Model Control Center slice

## Delivered

A real Flutter route `/admin/models` is reachable from the desktop side rail and mobile More navigation. The page includes two read-only areas:

- **Model inventory:** reads actual ADMIN-protected model definitions and the exact immutable version list from the Java V4-01 registry. Displays model role, family, provider, licence status and version lineage. No sample or fake production data are substituted.
- **PAPER/SHADOW evidence:** operator supplies an existing V4-09 Java session UUID. The screen reads the immutable session plus up to 50 recent complete matched source cases from merged PR #381, showing champion/challenger actions, disagreement, timestamps, registered model versions and source validation limitations.

The Flutter repository uses the existing `AuthenticatedApiClient` and existing session header, so all actual calls go through backend ADMIN RBAC; 401/403 have explicit access-denied states. Empty, loading and stale/error states are visible. Mobile and desktop layouts use the existing Flutter theme. The page has no LIVE toggle, model promotion, simulated fill entry or broker action.

Tests use mocked HTTP for wire-contract checks and a typed fake repository for widget behavior. They verify four connected ADMIN routes, session query limit and authenticated headers, model details, disagreement, validation, empty, access denied and lack of LIVE controls.

## Session discovery follow-up

The next V4-11 increment adds an ADMIN-only `GET /api/admin/model-governance/paper-shadow/sessions?limit=20` backed by the immutable V56 session table (maximum 50 results, ordered by recorded session creation). The Flutter PAPER/SHADOW tab now has a recent-session dropdown which loads the existing Java roster and candidate actions without manual UUID lookup. Each entry remains explicitly read-only, and no session is automatically enrolled or traded. New Java, repository and widget tests cover role restrictions, result bounds, exact route and selecting an existing session.

## Not complete

This is **not** the full V4-11 Admin control center. It lacks model registration forms, experiment launcher, ensemble weights, confidence/latency/drift dashboards, PAPER/SHADOW enrollment/pause/quarantine, promotion and rollback, cost/retraining, full audit search and platform-specific production acceptance. The V4-09 Java ledger still needs authenticated inference publisher, actual PAPER fills/transaction cost/reconciliation and genuine SHADOW runtime.

Backend logic stays authoritative. Do not treat app installation, demo screens, CI synthetic fixtures or model-list presence as real trading acceptance.

Operational subscriptions/news and static-IP configuration remain separate from code development. No licensed feed or paid API call is performed by this UI.
