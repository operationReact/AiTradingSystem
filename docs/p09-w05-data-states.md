# P09-W05 — Flutter authoritative data states

Status: IN PROGRESS

## Traceability

- Canonical Figma file: `KjBWVCtUYona0bOGb5NDov`
- Phase 9 section: `23:4422` — `SECTION / P9 / Appearance & Interaction System`
- Applies across Neon Futuristic / Clean Modern, light / dark, desktop / mobile variants.
- Canonical visual rule: **Server state remains authoritative**.

## State contract

The Flutter client does not invent trading readiness or fabricate data while evidence is unavailable.

| UI state | Source condition | Behavior |
| --- | --- | --- |
| Empty | No authoritative summary loaded | Prompt for explicit connection; no network request until user action. |
| Loading | Initial request is in flight | Show loading state; do not infer or substitute trading values. |
| Ready | Backend `systemStatus=OK` | Render authoritative dashboard values. |
| Stale | Backend returns `STALE`, or a refresh fails after previously successful evidence | Keep last-known evidence visible but explicitly label it stale/last-known. |
| Degraded | Backend returns `HALTED`, `RECONCILIATION_FAILED`, or `DEGRADED` | Surface degraded state and do not present execution readiness as healthy. |
| UNKNOWN | Backend returns an unrecognized status | Fail closed; never treat an unrecognized value as healthy. |
| Error | Request fails before any authoritative evidence is available | Show explicit unavailable/error state. |

No client-side freshness duration is invented in P09-W05 because the current dashboard API does not publish a freshness SLA or stale threshold. When the backend adds an authoritative stale marker, the Flutter state model already recognizes it.

## Stable automation semantics

- `dashboard-state-empty`
- `dashboard-state-loading`
- `dashboard-state-ready`
- `dashboard-state-stale`
- `dashboard-state-degraded`
- `dashboard-state-unknown`
- `dashboard-state-error`
- `dashboard-access-key`
- `dashboard-connect`
- `dashboard-refresh`
- `dashboard-disconnect`
- `workspace-state-empty`

## Validation scope

Focused Flutter widget coverage verifies:
- no automatic request in the empty state;
- loading while the first authoritative request is pending;
- `OK` ready state;
- explicit backend stale state;
- halted/degraded presentation;
- unknown-status fail-closed behavior;
- first-load transport error;
- last-known evidence retained but marked stale after a refresh failure.

## Safety boundary

This slice is presentation/read-only state handling. It performs no broker action, LIVE activation, cloud provisioning, order submission, hidden retry, or offline action replay.
