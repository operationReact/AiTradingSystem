# P09-W05 Flutter data states

Status: IN PROGRESS

This work item implements explicit Flutter presentation states without fabricated trading evidence.

Required states:
- LOADING: authoritative request is in progress.
- EMPTY: authoritative response contains no applicable records/evidence.
- ERROR: no trustworthy state can be shown because the request failed.
- STALE: last-known evidence remains visible only when clearly marked non-current after refresh failure.
- DEGRADED: authoritative service/control evidence reports degraded operation.
- UNKNOWN: required evidence is unavailable or indeterminate; safety-sensitive actions remain blocked/fail-closed.

Rules:
- Never convert UNKNOWN, stale, or degraded evidence into a healthy presentation.
- Never invent market, portfolio, signal, risk, or operational values to fill an empty state.
- Retry/refresh remains explicit; no hidden action or offline replay is introduced.
- State semantics must remain accessible and theme/style agnostic.

Current implementation:
- `DataStatePanel` provides reusable visual + accessibility semantics for all six states.
- Widget tests cover loading, stale and unknown fail-safe semantics.

Remaining in this PR:
- Integrate the shared states into dashboard/workspace surfaces.
- Add first-load error versus stale-refresh behavior.
- Map authoritative DEGRADED/UNKNOWN backend evidence to explicit state presentation.
- Extend widget coverage for empty/error/degraded and retained stale evidence.
- Run Flutter analyze/tests and platform build gates before merge.
