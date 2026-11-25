# MintTick — senior UI/UX audit and single-design decision (2026-11-06)

**Source of truth:** Actual current Flutter dark/light screenshots captured at 390×844dp on commit `29e3c28`, the approved MintTick visual board, and Flutter source under `apps/trading_client`. These are engineering/audit findings, not user research or claims of production usability. No invented prices, trades or performance.

## Decisions — locked
- **One product design system**. Delete the competing "Neon Futuristic AI" / "Clean Modern" design styles and profile chooser.
- Offer **Dark and Light appearances only**. Same layouts, widgets, features, navigation and API behavior.
- Keep brand colors: mint `#00E676`, teal `#00BFA5`, navy `#0B0F1A`, ice `#F8FAFC`. Use a darker green `#007A52` for legible light-theme foreground actions.
- Use neutral surfaces and restrained accent: avoid magenta/violet, ornamental glow, nested saturated green cards.
- Approved MintTick icon/wordmark geometry is immutable. Current Flutter painter is an approximation; exact source-asset fidelity is **not verified**. Fix with approved source artwork, not a redrawn AI variation.
- Do not modify PAPER-only safety semantics, authorization, broker credentials or API contracts as a consequence of a visual refactor.

## Findings from current screenshots and code

| Severity | Area | Evidence / failure | Acceptance |
|---|---|---|---|
| P0 | Dual identity | Settings shows two unrelated profiles; theme builds two style branches | One shared theme; appearance switches only dark/light |
| P0 | Authentication | Sign in disabled / identity handoff not connected | Working login, session restore, failure states; do not simulate security |
| P0 | Market integrity | Empty market displays a huge bordered panel; backend supplies catalogue/candles but no live LTP | Contextual empty/error states and timestamped, source-labeled prices where supported |
| P0 | Brand fidelity | Flutter vector mark recreated from concept rather than production master | Exact approved logo across launcher/web/Home/sign-in |
| P1 | Home hierarchy | Generic hero repeats PAPER explanation; actionable account status and market exploration compete | Action-first account state and clear primary CTA |
| P1 | Navigation | Five mobile destinations are cramped; 'More' hides many admin features | Label clarity, stable touch targets, logical consumer IA; advanced/admin separate |
| P1 | AI Research | Exposes scanner form before useful state/context | Guided start, research evidence, uncertainty and history; no unsupported AI performance |
| P1 | Portfolio | Unauthenticated state has large blank area | Clear sign-in action, relevant educational state; never fake PAPER holdings |
| P1 | Card system | Repeated borders, green gradients and nested cards reduce hierarchy | Shared neutral surfaces, consistent spacing, quiet borders |
| P1 | Typography | Inter was specified but not bundled; smaller text exists beyond shared type ramp | Bundle and validate licensed Inter; scale & overflow tests at Android text scaling |
| P1 | Responsive | CI catches standard widths but doesn't prove keyboard/screenreader/device usability | 320/360/390/411/600dp + large font, TalkBack, landscape and keyboard test |
| P2 | Store delivery | Screenshots aren't signed APK/iOS live-device acceptance | Validate actual APK and iOS on supported devices |

## Phase plan

1. **Single theme foundation (this PR)** — one `buildTheme(Brightness)`, remove style state and profile UI, preserve both appearances, neutralize card surface palette, protect statuses with contrast regression tests.
2. **Brand asset fidelity** — obtain the standalone approved master mark (or exact source export), replace reconstructed painter/raster assets with the exact geometry, establish safe-area and scale checks.
3. **Mobile customer IA** — session-aware onboarding and Home; production auth; restructure the signed-out path. Preserve PAPER enforcement.
4. **Markets and research information design** — compact rows + pagination already merged (#531); improve data-state UX, real quote integration, scanner transparency, instrument detail and actionable risk information.
5. **Portfolio and account** — correct empty/loading/offline/expired states and backend-connected PAPER holdings.
6. **Device acceptance** — real Android/iOS screenshots, screen readers, contrast, text scaling and non-fictional data fixtures.

## Release gates

- No horizontal or vertical render overflows at 320/360/390/411/600 logical pixel widths and 1.0/1.3/1.5 text scales.
- Text contrast at least WCAG AA 4.5:1, non-text distinguishability tested; do not use color alone for P&L/risk.
- All tap targets at least 48dp.
- No speculative trading price/performance in production; all prices show source and freshness.
- All themes use the same component tree and accessible semantics.
- All required Flutter analysis/tests, Android APK and build workflows pass; changes merge to dev before deploying.
