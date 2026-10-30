# Flutter delivery architecture decision — accepted product target

The user confirmed Flutter web, Android and iOS as a hard requirement on 2026-10-30. This is a correction of delivery scope, not a request to convert Java business logic into Dart. The canonical [Notion plan](https://app.notion.com/p/40736a828cb6811ba305d86e406e7e12), its `FLUTTER-HARD-GATE`, P0–P21 amendments and [Figma Flutter handoff](https://www.figma.com/design/KjBWVCtUYona0bOGb5NDov?node-id=30-2) control conflicting historical frontend statements.

The product has 22 logical workstreams, not 16 sequential releases. Existing P0–P15 work-item IDs stay stable; P16–P21 add datasets, prediction, attribution, governance, workload lifecycle and interactive controls. LIVE remains the eventual product capability with controlled activation; PAPER/SHADOW are validation modes. Neither migration nor model output authorizes real-money trading.

## Ownership and client structure

- `backend/`: retain Java 21/Spring Boot, deterministic risk, authorization, persistence and execution services. PostgreSQL/Flyway remain canonical. Python ML/feature jobs remain appropriate where specified.
- `apps/trading_client/`: required Flutter web/Android/iOS client; currently GAP. Use feature/domain boundaries, a typed API/repository layer and controllers above the presentation variants. Select/pin the SDK and dependencies using current official sources in the foundation PR; no unverified SDK version is prescribed here.
- `frontend/`: historical React/Vite implementation and fixture tests only. Preserve it for behavioral comparison and rollback reference. No new React product features or production cutover are part of this correction.
- Semantic themes: `DesignStyle.neonFuturisticAi` / `DesignStyle.cleanModern`, `ThemeMode.light` / `dark` / `system`, ColorScheme/ThemeExtension and shared widgets. Figma Auto Layout maps to Row/Column/Wrap/ListView/Expanded/Flexible; only decorative effects belong in Stack/Positioned.

## Required safeguards

Typed DTOs must preserve candidate/run/config/catalogue identity, source timestamps, freshness/expiry, partial materialization and UNKNOWN states. Money/price/fee/P&L parsing and display require an explicit decimal-fidelity contract; no client binary-floating-point risk authorization. Exact calculations and trading decisions remain on the server. Request correlation and immutable error/reason codes must survive presentation changes.

Development operator credentials stay memory-only. P10 must explicitly design first-party web cookie/CSRF behavior and native session/secure-storage/refresh/logout behavior; do not blindly transplant React localStorage or embed shared production keys in a native binary. Logout invalidates authenticated caches. Account/role authorization is server-side.

Offline/resume/reconnect must disable mutations until fresh authoritative state is available. Never queue/replay order, approval, stop or mode changes. Network timeout after submission can mean UNKNOWN; query the original intent before retry. A pending review retains its identity/version/expiry across style changes. Resume must recheck expiry, credentials, broker state, data age and emergency stop. UI backgrounding does not run the trading engine on the phone.

## Correction sequence and hard gates

1. **FC-01 governance:** reconcile authority, inventory every planned/historical PR, maintain the per-phase correction matrix, and enforce legacy-reference boundaries. A green policy check certifies policy consistency only.
2. **FC-02 foundation:** scaffold the real Flutter app with web/Android/iOS platform directories, pin SDK/dependencies, typed transport/error/auth boundaries, routing/deep-link aliases, themes, shared components and mandatory Flutter CI. No counter-demo app counts as product parity.
3. **FC-03 first vertical slice:** implement read-only operations health against the real Java API with controlled provider doubles and PostgreSQL test data. Prove auth/loading/empty/error/stale/degraded/UNKNOWN/retry, correlation and reconnect in both styles/light-dark-system. Build and run on Flutter web, Android emulator/device and iOS Simulator/device. This gate blocks broad migration until all three have actual evidence.
4. **FC-04 domain migration:** catalogue/market data → scanner/candidate/scenario → backtesting → risk → PAPER orders/fills/positions/ledger → approvals → analytics/notifications/audit → readiness/news/settings. Preserve source contracts and dangerous-action confirmations. Each slice records PR-level real-backend E2E, not only an HTTP mock.
5. **FC-05 full acceptance:** complete the 19 P09-W04 surfaces plus P21 Control Center, Trade Intelligence, Learning and Cost Centers as their backend dependencies become available. Validate 320/375/411/768/1024/1440 logical widths, keyboard/focus/text scaling, TalkBack/VoiceOver/web semantics and reduced motion. Native permissions, system dialogs and lifecycle interactions need appropriate native test coverage.
6. **FC-06 release:** gate P11/P14 on three-platform builds and runtime journeys; gate signing/store/device checks independently from compilation. An unsigned iOS build is not App Store readiness. Reconcile web hosting, deep links, environments, TLS and API auth before any cutover. Preserve P15 LIVE and P20 safe-stop controls.

## Evidence contract

Every affected requirement records its Notion ID, actual PR and reviewed SHA, Figma node, backend/API test source/run, Flutter paths, widget/unit/golden results, web/Android/iOS build and runtime artifacts, authentication/lifecycle/negative-path coverage, and remaining gaps. Backend-only scope may mark Flutter code not applicable only with an explicit client-impact assessment. A required but unavailable platform check is BLOCKED/UNVERIFIED, never waived by a successful React test.

Official references checked for this audit: [platform integration](https://docs.flutter.dev/platform-integration), [testing overview](https://docs.flutter.dev/testing/overview), [integration tests](https://docs.flutter.dev/testing/integration-tests), [iOS setup](https://docs.flutter.dev/platform-integration/ios/setup), [accessibility testing](https://docs.flutter.dev/ui/accessibility/accessibility-testing). Flutter supports a common codebase but needs platform-specific tooling and runtime validation. Native permission/system UI is outside ordinary `integration_test` coverage and needs an appropriate additional test approach. iOS tooling requires macOS/Xcode; this Linux workspace cannot execute it locally. CI/toolchain setup can proceed independently of signing/store credentials.
