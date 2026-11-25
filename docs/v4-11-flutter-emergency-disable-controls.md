# V4-11 — Flutter Admin emergency model safety controls (de-risk only)

## Contract

This is a distinct V4-11 follow-up to [PR #384](https://github.com/operationReact/AiTradingSystem/pull/384) and the backend ADMIN-only [PR #340](https://github.com/operationReact/AiTradingSystem/pull/340) model governance service. PR #384 establishes the Flutter read-only registry/versions and PAPER/SHADOW evidence screen. This increment adds a separate `/admin/model-emergency` view on desktop rail/mobile More navigation. Its controls are **reductions only**:

- `PUT /api/admin/model-governance/emergency-controls/static-fallback` with `enabled=true`.
- `PUT /api/admin/model-governance/emergency-controls/families/{family}` with `disabled=true`.
- `PUT /api/admin/model-governance/emergency-controls/models/{versionId}` with `disabled=true`.

The Java backend remains authoritative for ADMIN role and model-version/family validity. The Flutter repository validates an exact version UUID, a bounded uppercase family and an audit reason of 10–512 characters. Before any request, the page requires a separate confirmation dialog naming the target and reason. Cancel makes **zero** mutation calls. The response is read from the exact backend contract and emergency controls are fetched again after a mutation. Server 401/403 clears cached evidence and hides the form. Other failures show explicit error/stale UI and do not claim success.

The UI **does not** provide model re-enable/unquarantine, generic policy changes, broker order placement or LIVE mode switches. Only a separate risk-reviewed governance path can remove a safety override; an Admin Flutter button cannot bypass the Java deterministic risk engine or the permanently blocked LIVE authority.

## Tests and acceptance

Repository tests assert exact URI/body, existing authenticated `X-API-Key` handling, non-optional `reason`, no `enabled=false`/`disabled=false` mutations, malformed scope rejection and authorization failure. Widget tests assert not even a mock mutation is sent before a reason and explicit confirmation, cancel behavior, denied-state hiding, and correctly surfaced safety state.

Mandatory CI: Flutter analyze/tests/web build, visual golden, mobile quality, Android APK, Phase 0 Java/PostgreSQL, Phase 4, and Flutter delivery-policy checks. CI success does not assert physical device acceptance or deployed runtime.

## Remaining scope

This does not complete V4-11. Model eligibility per environment, control audit history and versioned rollback, bounded ensemble editors, experiment and backtest views, PAPER/SHADOW enrollment and quarantine, retraining and drift monitoring, champion/challenger reviews, and full Flutter web/Android/iOS end-to-end flows remain open. Real operational config and licensed Indian market/news procurement are deferred until implementation completion as requested. The user-reported static IP setup is not a software blocker; LIVE trading remains disabled until independent release acceptance.
