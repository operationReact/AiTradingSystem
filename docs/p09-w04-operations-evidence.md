# P09-W04 correction — News, Notifications, Audit and Settings

Status: IN PROGRESS / stacked corrective Flutter slice

## Canonical evidence

- Figma file `KjBWVCtUYona0bOGb5NDov`
- Phase 8 analytics/operations section `23:3931`
- Phase 9 appearance/interaction section `23:4422`
- Phase 15 LIVE activation section `24:1967`
- Flutter handoff `30:2`

## Backend authority

News:
- `GET /api/news?limit=20&offset=0`

Notifications:
- `GET /api/notifications?activeOnly=true&page=0&size=20`
- `POST /api/notifications/{id}/acknowledge`

Audit:
- `GET /api/audit?page=0&size=20`

Execution settings:
- `GET /api/system/execution-mode`

The Flutter client does not recreate provenance, incident state, audit details or LIVE readiness. It displays backend evidence as returned.

## Flutter implementation

- `data/operations_evidence_repository.dart`
  - typed news provenance, notification, audit and execution-mode evidence
  - authenticated API reads and notification acknowledgement
- `presentation/operations_evidence_page.dart`
  - `/news`: source/publisher/fetch/summary provenance, linked symbols and timestamps
  - `/notifications`: explicit severity/message/entity/acknowledgement state
  - `/audit`: server-redacted event/correlation/actor/details evidence
  - `/settings`: active execution mode plus backend LIVE blockers
- `app.dart`
  - routes `/news`, `/notifications`, `/audit`, `/settings`

## LIVE safety

When `liveAvailable=false`, the Settings screen deliberately renders no LIVE activation control. It shows the backend blockers instead. This preserves the separate Phase 15 activation gate and prevents a Phase 9 presentation surface from implying LIVE readiness.

## Tests

- exact endpoint/query/header contracts for all four evidence sources
- notification acknowledgement POST
- news source + summary provenance rendering
- acknowledgement refresh behavior
- audit evidence rendering without client enrichment
- Settings proves backend blockers are visible and no `Switch to LIVE` action is rendered

## Remaining Phase 9 gaps

This slice does not complete P09-W04 or Phase 9. Explicit Scenario/Regime production Flutter coverage and any remaining inventory/navigation gaps must be closed before W09 final verification. No LIVE activation, broker submission or credential persistence is introduced.
