# P09-W04 correction — Scenario, Regime and Workspace Navigation

Status: IN PROGRESS / final known required-screen correction before W09 re-audit

## Canonical evidence

- Figma file `KjBWVCtUYona0bOGb5NDov`
- Phase 4 research/scenario section `23:1967`
- Phase 9 appearance/interaction section `23:4422`
- Flutter handoff `30:2`

## Backend authority

Persisted scenario evidence is read only from:

- `GET /api/research/simulations/{scenarioRunId}`

The controller also exposes a stateless expert simulation POST, but this Phase 9 screen deliberately does not call it. The UI only displays scenario evidence already persisted by candidate evaluation/orchestration.

The backend `ScenarioRunView` binds each run to scan/candidate/instrument identity and includes history window, data cutoff, methodology, regime, fallback tier, request fingerprint, simulation parameters, outcome counts/probabilities, R distribution and lifecycle status.

## Flutter implementation

- `data/scenario_evidence_repository.dart`
  - typed persisted scenario evidence
  - authenticated exact-ID lookup
  - fail-closed validation of required provenance fields
- `presentation/scenario_lookup_page.dart`
  - accepts only a scenario-run UUID and clearly states that no new simulation is started
- `presentation/scenario_evidence_page.dart`
  - candidate/scan/instrument binding
  - regime/fallback/methodology
  - history/cutoff/seed/cost/fingerprint provenance
  - persisted outcome frequencies/probabilities
  - expected/P05/P50/P95 R
  - lifecycle/error evidence
  - loading/error/stale states
- `app.dart`
  - `/scenarios` route
  - global workspace navigation sheet exposing all corrected production Flutter surfaces

## Tests

- exact persisted scenario GET path and `X-API-Key`
- required provenance fails closed when regime/cutoff/identity is missing
- Flutter scenario screen renders candidate binding, regime, fallback, methodology and R evidence
- global workspace navigation exposes Overview, Instruments, Market, Research, Scenario/Regime, Backtests, Portfolio, Orders, Risk/Approvals, News, Notifications, Audit, Settings and System Health

## Safety and remaining verification

No client-side simulation result is invented, normalized or re-run. Missing provenance does not render as valid scenario evidence. This slice does not enable LIVE, submit broker orders or infer trading readiness.

After this PR, the known P09-W04 required-screen gaps are closed at implementation level, but P09-W04 and Phase 9 remain UNVERIFIED until the stacked corrective chain is retargeted sequentially onto `dev`, exact-head Flutter CI passes, W07/W08 obligations are rechecked against the new screens, and W09 final frontend tests cover the resulting production surface.
