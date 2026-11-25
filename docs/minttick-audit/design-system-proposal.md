# MintTick — Design System Proposal

Status: **proposal for review**. No application code changed.

## 0. Open decision (blocks finalization)

Two documents conflict on the design direction:

- `AGENTS.md` (custom rules) and the Notion/Figma references require **both** `neonFuturisticAi` and `cleanModern` styles, each with light, dark, and system themes, sharing one domain layer and layouts.
- `docs/design-system/minttick-single-design-ux-audit-20261106.md` locks **one** design system (Dark and Light only) and says to delete the competing style profiles.

This proposal assumes **one shared component tree, with style variants applied only at token level**: tokens differ; layouts, widgets, and semantics do not. That satisfies both. Confirm before implementation.

## 1. Principles

1. Honesty first: no value without its source, timestamp, and status.
2. Execution state is always visible and never implied by color or copy.
3. Restraint: one accent for action; status colors only for status.
4. Numbers are the hero: tabular figures, aligned columns, sign + arrow + color.
5. Same components across platforms; differences only in layout density.

## 2. Color tokens (semantic)

| Token | Dark | Light | Use |
|---|---|---|---|
| `canvas` | `#0B0D10` | `#F8FAFC` | page background |
| `surface.1` | `#131821` | `#FFFFFF` | cards |
| `surface.2` | `#1B2430` | `#F1F5F9` | nested or selected |
| `border.subtle` | `#2A3442` | `#E2E8F0` | dividers |
| `text.primary` | `#E6EDF5` | `#0F172A` | body and numbers |
| `text.secondary` | `#A3B1C2` | `#475569` | metadata (≥ 4.5:1) |
| `brand.mint` | `#00E676` | `#00E676` | logo and brand moments only |
| `action.primary` | `#2DD4BF` | `#007A52` | primary buttons (AA on surface) |
| `status.positive` | `#4ADE80` | `#0A7D4B` | gains (always with ▲ and sign) |
| `status.negative` | `#FB7185` | `#B42318` | losses (always with ▼ and sign) |
| `status.warning` | `#FBBF24` | `#8A4B00` | stale, degraded |
| `status.info` | `#60A5FA` | `#1D4ED8` | informational |
| `status.neutral` | `#94A3B8` | `#64748B` | unavailable, closed |

Contrast targets: 4.5:1 for text, 3:1 for non-text indicators. All values are proposals and must be measured in both themes before adoption.

Rule: never use color alone for status. Pair with icon and label (e.g., "▲ +1.2%", "STALE · 14:02 IST").

## 3. Live-data status chips

| Status (`CustomerLiveQuote`) | Chip label | Icon | Color token |
|---|---|---|---|
| LIVE | Live · HH:mm:ss | dot | status.positive |
| CONNECTING / RECONNECTING | Connecting | spinner | status.info |
| STALE | Stale · last HH:mm | clock | status.warning |
| MARKET_CLOSED | Market closed | moon | status.neutral |
| UNAVAILABLE | Unavailable | slash | status.neutral |
| BROKER_CONNECTION_REQUIRED | Connect broker | link | action.primary |

Price cells show a number only for LIVE. Other statuses show the chip and last-verified time, never a fabricated price.

## 4. Execution-state banner (new, global)

One line, visible on Home, Rooms, Portfolio, and any trade surface:
- "Execution: not enabled" (default), with the reason from backend readiness.
- Never shows "Live trading active" unless backend readiness returns allowed for the account.
- The mode selector changes data scope only, per `trading_workspace_mode.dart`.

## 5. Typography

- Family: bundle a licensed Inter (asset and pubspec entry). Until bundled, do not claim Inter (`minttick_typography.dart:5`).
- Scale (sp / line height): Display 28/34, Title 20/26, Section 16/22, Body 14/20, Caption 12/16, Numeric-L 24/30 (tabular), Numeric-M 16/22 (tabular), Numeric-S 13/18 (tabular).
- Numbers: `FontFeature.tabularFigures()` for price, change, quantity, and P&L.
- Currency: ₹ with Indian grouping (`1,23,456.78`); times in IST with explicit zone.
- Text scaling: layouts must hold at 1.0, 1.3, and 1.5.

## 6. Spacing, grid, radii

- 4-pt base; use 4, 8, 12, 16, 20, 24, 32. Replace ad-hoc 15/18/19 (see `minttick_customer_home.dart`).
- Mobile: 16 dp side margin; 12 dp card gap.
- Tablet/desktop: 12-column grid; max content width 1280.
- Radii: 12 (cards), 8 (chips and inputs), 999 (pills). No nested radii.

## 7. Elevation and borders

- Flat surfaces with a 1 px subtle border. One shadow level, for modals only.
- Gradients: none on data surfaces. Brand gradient only on splash and logo.
- Glass/blur: not used on trading data; allowed only on modal scrims.

## 8. Icons

- Sizes: 16 (inline), 20 (buttons), 24 (navigation).
- Single outline set; filled only for the selected navigation item.
- Logos: see §10.

## 9. Interactive components

| Component | Spec |
|---|---|
| Primary button | action.primary fill, 48 dp min height, one per screen |
| Secondary | outline border, same height |
| Destructive | status.negative text; confirmation for irreversible actions |
| Segmented control (scope) | selected = surface.2 + check icon + bold text; ≥ 4.5:1 |
| Tabs | underline indicator, 48 dp targets |
| Inputs | 48 dp, visible label (not placeholder only), error text with icon |
| Bottom nav | 5 max, labels always shown, 48 dp targets |
| Chips | status chips per §3 |

Interaction states: default, hover (web), focus (2 px ring in action.primary, visible in both themes), pressed, disabled (text.secondary + reason), loading (skeleton).

## 10. Logos and instrument identity

- Source: instrument master from backend; licensed logo set stored and served by the backend, or bundled assets.
- No third-party favicon fetch from the client (remove `google.com/s2/favicons` in `minttick_equity_avatar.dart`).
- Fallback: monogram from symbol, fixed 40 dp circle, one color rule (hash of symbol to six neutral tones), always paired with the symbol text.
- Minimum logo size 24 dp; fixed container with `BoxFit.contain`.

## 11. Trading components

| Component | Required fields |
|---|---|
| Instrument row | logo, name, symbol·exchange, price (only if LIVE), change (sign + arrow), status chip |
| Quote card | price, change, updated time, source label ("Angel One · live"), session chip |
| Market overview | ≤ 3 items; if none are LIVE, a single connection card instead of tiles |
| Holdings / positions | qty, avg, LTP (with status), P&L (realized and unrealized labelled separately), source |
| Order (future) | state chip: Draft → Preflight → Awaiting approval → Submitted → Filled / Rejected / UNKNOWN; UNKNOWN shows "Check status", no auto-retry |
| Metric tile | label, value (tabular), period, source |
| Chart (future) | candlesticks, timeframe segmented control, source and timestamp footer, no fabricated data |

## 12. MintTick Mind components

- Insight card: title, plain-language summary, source list, timestamp, limitation text, "not a recommendation" label.
- Confidence: shown only if backend-calibrated; otherwise "Confidence not available".
- Control panel: pause automation (always visible when automation exists), current risk limits, capital allocated (planned vs actual, labelled).
- History: each decision with outcome, time, and reason. No performance claims without backed data.

## 13. Responsive patterns

- < 600 dp: single column, bottom nav, sheets for secondary actions.
- 600–899 dp: two columns for lists and cards; bottom nav kept.
- ≥ 900 dp: rail navigation (existing `_DesktopProductRail`), two-pane list/detail.
- Tables: on mobile, convert to cards with labelled fields; no horizontal scroll for primary data.

## 14. Motion

- Durations: 150 ms (state change), 250 ms (sheet), 300 ms max (page).
- Motion only for state change (chip change, skeleton to content). No ambient animation on data.
- Honor reduced motion (`MediaQuery.disableAnimations`).
- Price flashes off by default; if used, a text and icon cue accompanies them.

## 15. Accessibility rules

- Contrast: 4.5:1 text, 3:1 non-text, in both themes.
- Touch targets: 48 dp minimum.
- Semantics: prices read as "₹ 1,234.50, up 1.2 percent, updated 14:02, live".
- Focus visible on web; logical order.
- Text scaling to 1.5×; no clipped primary data.
- No color-only status.

## 16. Component mapping (Flutter, proposed)

| Component | Location (proposed) |
|---|---|
| Semantic tokens | `apps/trading_client/lib/src/design/tokens.dart` |
| Theme builder | `theme/app_theme.dart` (single `buildTheme(Brightness, StyleVariant)`) |
| Status chip | `design/widgets/status_chip.dart` |
| Price text | `design/widgets/price_text.dart` |
| Instrument row | `design/widgets/instrument_row.dart` |
| Execution banner | `design/widgets/execution_state_banner.dart` |
| Empty/error state | `design/widgets/state_view.dart` |
| Logo | `design/widgets/instrument_logo.dart` |

Note: the existing `TradingColors` extension and alias getters (`neonCyan`, `neonViolet`) should be renamed to semantic names during migration.

## 17. Adoption rules

- Forbid `Color(0x…)` and `fontSize:` literals outside `design/` (CI grep or lint).
- Every new screen uses shared state views and status chips.
- Visual goldens required for each component at 390×844, with 1.0 and 1.5 text scale, in both themes.
