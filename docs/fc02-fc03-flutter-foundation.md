# FC-02 / FC-03 Flutter foundation and operations-health slice

Status: implementation submitted; platform runtime acceptance remains UNVERIFIED until CI and connected runtime evidence are reviewed.

## Traceability

- Notion: `FLUTTER-HARD-GATE`, FC-02 foundation and FC-03 first vertical slice.
- Figma: Flutter handoff `30:2`; responsive rules `30:6`, theme rules `30:22`, component mapping `30:38`, testing/semantics `30:46`.
- Backend: `GET /api/operations/health`, `OperationsController`, `OperationalHealthService.HealthSnapshot`.

## Implemented

- Flutter root at `apps/trading_client` with web/Android/iOS platform roots and Flutter 3.47 stable CI.
- Route aliases `/`, `/operations`, `/operations/health`.
- Typed health snapshot/component/state parsing. Unknown future states fail closed to UNKNOWN.
- Memory-only development operator credential; no local persistence.
- Explicit connect/refresh and explicit retry only; no background mutation/replay.
- Both `neonFuturisticAi` and `cleanModern` plus light/dark/system.
- Responsive `SafeArea`, `LayoutBuilder`, `Wrap`/`ListView`; no device-name branching.
- Loading, empty, transport/auth error, degraded and UNKNOWN evidence presentation.
- Correlation ID display when returned by the backend.
- Widget/unit coverage proving no request before explicit connect, no duplicate request on style switch, UNKNOWN presentation and explicit retry.
- Separate Linux web/Android and macOS iOS build jobs.

## Still required before FC-03 can be VERIFIED

CI build/test results must pass. Then run the client against the real Java/PostgreSQL API using controlled provider evidence on Flutter web, Android emulator/device and iOS Simulator/device. Record runtime artifacts, reconnect/background-resume behavior and authentication negative paths per platform. A compile pass alone does not satisfy the gate.
