# Dual Design System — approved product requirement

## Approved variants
- **Design A — Pro Terminal:** Dark data-dense bento trading terminal, compact market watchlists, charts, strategy/risk dashboards.
- **Design B — Clear Invest:** Light accessible clean investing interface, spacious cards, guided paper-order journey.
- **Explicitly excluded:** Neumorphism / neumorphic styling. Do not reintroduce it.

## Objective
Deliver two genuinely distinct, polished UI experiences across supported Android, iOS, mobile web and desktop web. These are product design variants, **not merely dark/light color themes**. Preserve the current product branding, approved assets and business rules. The generated mockups are visual direction, not pixel-perfect source assets or proof of implemented screens.

## Required user experience
1. Add **Appearance → Experience** with preview cards, labels, and instant selection between Pro Terminal and Clear Invest. Define a sensible existing-user default and do not surprise users by automatically switching their experience.
2. Persist the selected design locally for anonymous users and in authenticated user preferences when available; reconcile on sign-in and across devices without flicker or breaking offline startup.
3. Keep URL/deep link, navigation stack, form state, cart/order state, authentication, data, permissions and ongoing actions stable when switching. Never change API semantics or server-side authorization based on design.
4. Design **mobile-first** with Android/iOS safe areas, touch targets, keyboard, contrast, screen reader labels, reduced-motion support and responsive desktop layouts. Web and mobile may use platform-appropriate navigation but must share the same feature set where supported.
5. Implement semantic design tokens (colors, typography, spacing, radius, elevation, motion), variant-specific layout composition and reusable components. Avoid duplicating business logic, networking, state management or backend services.

## Feature coverage matrix
Both variants must support: market watch, instrument detail, chart, portfolio, strategies, backtesting, strategy replay, paper-order review and risk rejection, history, alerts, settings.

## Domain-specific non-regression rules
Keep paper trading only; risk controls and audit trails immutable across visual variants.

## Copilot execution plan (incremental PRs)
- **Phase 0: Audit.** Inspect actual repo architecture, routes/screens, design assets, platforms, CI and tests. Create `docs/design-system/implementation-inventory.md` with screen/role/platform matrix, gaps, current UI screenshot baseline and precise implementation order. No invented Flutter or iOS modules.
- **Phase 1: Foundation.** Add typed design-variant preference, migration/default behavior, tokens, theme provider/design manager, accessible switcher, tests. Preserve existing layout as safe fallback.
- **Phase 2: Vertical slice.** Build both variants for the primary dashboard/home, navigation and one core end-to-end journey, with Android/iOS/web/desktop responsive behavior as supported. Make them visually distinct in composition, not only colors.
- **Phase 3: Full coverage.** Extend both variants to remaining user flows, loading/empty/error states, dark/light accessibility where applicable, admin and partner surfaces where relevant.
- **Phase 4: Quality gates.** Run unit, integration, E2E, visual snapshot/golden and accessibility tests for **both** designs, including design switching mid-flow; CI matrix and release checklists. Add before/after screenshots for 320/375/411/768/1024/1440px where applicable.
- **Phase 5: Controlled rollout.** Feature flag, opt-in preview, metrics for design choice and regression monitoring, fallback/rollback plan. No production deployment or paid resource provisioning without explicit approval.

## Definition of done
- Every existing relevant screen appears in both designs; no dead controls, inaccessible UI or lost functionality.
- Preference persists and syncs correctly; switching never drops an in-progress transaction or authentication state.
- Platform builds, tests and visual comparisons pass; accessibility verified; performance within baseline budgets.
- Docs, design decision record, screenshots, test evidence and PR checklist updated.

## Working instructions
Implement in small, reviewable feature branches/PRs; resolve ordinary CI and merge conflicts independently; never bypass safety checks, conceal failing tests, auto-merge unreviewed changes or assert completion based on documentation alone. Post evidence and blockers to the PR.