# Dual-design implementation inventory (Phase 0)

Source: issue #40 and design specification PR #39 (`feature/dual-design-system-spec`).

## Actual frontend

React 18 + Vite + React Router, JavaScript JSX, Tailwind, TanStack Query, ECharts. Current routes: Overview, Signals, Paper Trades, Risk, Instruments, Market Data, Backtesting. Shared `AppShell`, `AppContext`, `ui.jsx`, and API hooks. Operator API key is held in memory, not localStorage.

## Platforms

Desktop/mobile responsive web exists. A PWA manifest and mobile viewport metadata exist. Android Play Store and iOS App Store binaries do **not** exist in this repository; no Flutter module exists. Do not claim native builds until actual projects and device testing are added.

## Experience coverage and gaps

| Surface | Pro Terminal | Clear Invest | Next work |
| --- | --- | --- | --- |
| Shared shell/navigation | Existing compact UI | Selector + preference foundation only | Distinct mobile/desktop compositions |
| Overview | Existing panels | Not redesigned | Both dashboard variants |
| Signals and charts | Existing | Not redesigned | Both variants |
| Paper trades and risk rejection | Existing | Not redesigned | Guided vs dense order journey |
| Risk | Existing | Not redesigned | Both variants |
| Instruments and market data | Existing | Not redesigned | Both variants |
| Backtesting | Existing | Not redesigned | Both variants |
| Portfolio, alerts, settings, replay | Audit route/API availability | Audit route/API availability | Implement missing flows only where backend supports them |

## Phase 1 foundation delivered in this branch

Experience values `pro-terminal` and `clear-invest`; invalid preference falls back to Pro Terminal; preference stored locally; selector in shared shell changes preference without remounting route or provider. Experience tokens are separate from light/dark theme. No profile preference API exists, so cross-device synchronization is not yet implemented. This is **not** a completed dual-design implementation.

## Next order

1. Add visual preview cards and distinct Pro Terminal / Clear Invest dashboard compositions.
2. Implement distinct navigation and paper-order review vertical slice using shared API/business logic.
3. Migrate remaining routes and add keyboard/screen-reader, responsive 320/375/411/768/1024/1440 and visual regression coverage.
4. Add authenticated preference synchronization only after a verified backend user-profile contract exists.
5. Release gate: no LIVE order activation, AWS apply or production deployment without explicit approval.

Screenshots and physical Android/iOS validation: pending; do not label these as passed.
