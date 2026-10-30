# AiTradingSystem implementation rules

- The user-confirmed target is Flutter for web, Android and iOS across Notion P0–P21 (22 workstreams). Read `docs/FLUTTER_CORRECTION_AUDIT.md` and `docs/architecture/flutter-delivery.md` before implementation.
- Canonical product requirements: https://app.notion.com/p/40736a828cb6811ba305d86e406e7e12. Canonical tracker: https://app.notion.com/p/40736a828cb68100a2d8e5d6a7be4d2e. Figma file `KjBWVCtUYona0bOGb5NDov`, Flutter handoff `30:2`. These supersede conflicting 16-phase, React-only, native-optional and PWA-only target statements in historical documents.
- Preserve Java/Spring Boot, PostgreSQL/Flyway and appropriate Python services. Flutter is the product client, not a requirement to rewrite backend risk/accounting in Dart.
- `frontend/` is a legacy React behavior reference. Do not add React product UI features. New client work belongs in `apps/trading_client/` using Flutter. Do not delete historical implementation or tests to conceal missing Flutter parity.
- Both `neonFuturisticAi` and `cleanModern` use the same domain/controllers/authorization and light/dark/system themes. Follow exact canonical Figma nodes and preserve layout, semantics, focus, reduced motion and safe areas.
- A backend-only PR may proceed after its client impact is assessed. It does not complete a Flutter work item. React builds, browser fixtures and screenshots do not count as Flutter evidence.
- Each UI requirement needs actual Flutter code and separate web, Android and iOS build/runtime evidence. Keep GAP/UNVERIFIED/BLOCKED distinct. Never mark a phase fully VERIFIED from a green policy check or unsigned build alone.
- The first read-only Flutter vertical slice must pass all three platform journeys before broad migration or legacy replacement. Record unavailable checks honestly; never skip a required platform and call it passed.
- Deterministic server risk, authorization, source identity, expiry and idempotency remain authoritative. No offline mutation queue/replay; no automatic submission/retry after UNKNOWN outcomes. Appearance changes cannot change execution mode or trading behavior.
- Production LIVE activation, paid cloud provisioning and production cutover remain separately gated. This correction does not authorize them.
- Update the linked Notion plan and tracker with exact PR/head/merge/CI evidence after merging. Preserve historical records and state which technology/scope each validates.
