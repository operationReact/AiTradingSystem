# V4-11 — Admin model lineage and authorization revocation (Flutter)

This change builds on merged PR #384's connected Flutter `/admin/models` screen. It **does not** replace the Model Control Center or duplicate the backend matched-decision ledger.

## Evidence and authorization

- The Java model registry's immutable `model-version.v1` response includes artifact, feature-schema, training-dataset, training-config, code SHA and optional research-registry and training-cutoff provenance. Flutter now carries and displays those fields under the selected model version rather than showing only artifact and version IDs.
- On any authentication-required or HTTP 401/403 from inventory, versions, matched session or case API calls, the Admin screen clears **all** previously displayed model inventory, versions, selected model, session and case evidence. It also clears the session UUID entry and displays ADMIN authorization denial on either tab.
- An authorization generation token invalidates older in-flight asynchronous responses. After access is revoked, a previously started request cannot restore sensitive model/session evidence. Non-auth transport errors retain explicit stale/error states.
- Explicit refresh after credentials are restored is possible, but there is **no** local role elevation, offline mutation replay or client authority to enable LIVE.
- Existing backend `@PreAuthorize(hasRole('ADMIN'))`, Java model-emergency controls and Java execution authority remain the security/trading source of truth.

## Regression and limits

Flutter widget tests exercise revocation after model/versions were already loaded, revocation after PAPER/SHADOW session evidence was displayed, and full registered model lineage. Connected repository mock tests assert Java API field mappings. CI Flutter analyze/tests, visual, mobile and APK build checks apply, but Android/iOS device/runtime acceptance remains separate.

The first V4-11 inventory + read-only PAPER/SHADOW slice remains **PARTIAL**. Remaining work includes editable bounded model policy, experiment launcher, backtests, champion/challenger promotion review/rollback, retraining, drift, TCA dashboards, and end-to-end three-platform acceptance. No production deployment or broker order is performed.

The user reports static IP configured and requested subscription/credential onboarding after implementation. That is not a license waiver or model-performance claim.
