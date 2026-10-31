# AiTradingSystem Flutter client

Production UI authority for AiTradingSystem across Web, Android and iOS.

## Architecture

- `lib/src/domain`: typed backend evidence; unknown or incomplete authority fails closed.
- `lib/src/data`: authenticated repositories for health, instruments, market data, research, scenarios, backtests, PAPER trading, risk/approvals and operational evidence.
- `lib/src/presentation`: production workspaces for Overview, Instruments, Market Data, Research, Scenario/Regime, Backtests, Portfolio, Orders, Risk/Approvals, News, Notifications, Audit, Settings and System Health.
- `lib/src/theme`: `neonFuturisticAi` and `cleanModern`, with System/Light/Dark support.
- operator credentials remain memory-only in the Phase 9 client; execution authority remains server-side.

The application is PAPER-only. It does not authorize LIVE broker execution, and UI state never replaces backend risk/readiness evidence.

## Local bootstrap

Use Flutter 3.47.x stable. Start the Spring backend on port 8080, then from this directory run:

```bash
flutter pub get
flutter analyze
flutter test
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080
```

For an Android emulator use the host mapping expected by the emulator environment; for iOS Simulator/local Web use an address reachable from that runtime.

## Validation model

Development pushes use the inexpensive Flutter fast gate: analyze, tests and release Web build. Full connected acceptance runs against a real Spring Boot + PostgreSQL backend on Web, Android emulator and iOS Simulator only at native-sensitive or phase/release boundaries.

Phase 9 acceptance additionally covers responsive behavior, accessibility, state-preserving appearance switching, research evaluation, PAPER order/risk evidence, emergency stop, backtest history and degraded-data behavior. Visual goldens are regression snapshots; they must not be described as design-fidelity proof without separate Figma evidence.

Platform wrappers may be reconciled by CI from the pinned Flutter stable line. Product Dart sources, routing, authority boundaries and tests remain repository-owned.
