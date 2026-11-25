# AiTradingSystem Flutter client

Production UI authority for AiTradingSystem across Web, Android and iOS.

## Architecture

The client is being migrated to a feature-first layout without regressing the newer product/UI work already on `dev`.

- `lib/src/core/session`: shared account/session state used across features.
- `lib/src/core/widgets`: shared presentation primitives such as responsive layout, appearance controls and data-state panels.
- `lib/src/features/operations_health`: health domain/data/presentation slice. Unknown or incomplete authority fails closed.
- `lib/src/features/overview`: feature entry point for the product overview; compatibility exports remain temporarily while older callsites are migrated.
- `lib/src/data` and `lib/src/presentation`: remaining authenticated repositories and workspaces are migrated feature-by-feature rather than by a destructive bulk rename.
- `lib/src/theme`: `neonFuturisticAi` and `cleanModern`, with System/Light/Dark support.

The application remains PAPER-only. It does not authorize LIVE broker execution, and UI state never replaces backend risk/readiness evidence.

## Local bootstrap

Use Flutter 3.47.x stable. Start the Spring backend on port 8080, then from this directory run:

```bash
flutter pub get
flutter analyze
flutter test
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080
```

For Android, use an API base address reachable from the device/emulator. For iOS Simulator/local Web, use an address reachable from that runtime.

## Validation model

Every structural migration must preserve the production behavior already present on `dev`. Exact-head validation requires Flutter analyze/tests and the repository delivery gates; Android APK packaging and visual regression run at release-sensitive boundaries.

Visual goldens are regression snapshots, not design-fidelity proof. Customer UI acceptance additionally requires phone-size review against the canonical Figma product direction.

Platform wrappers may be reconciled by CI from the pinned Flutter stable line. Product Dart sources, routing, authority boundaries and tests remain repository-owned.
