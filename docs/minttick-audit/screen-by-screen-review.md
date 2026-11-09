# MintTick — Screen-by-Screen Review

Screenshots: `frontend/docs/minttick/` (eight Flutter Material captures, 390×844 class). Assumption: these are the intended set; confirm if more exist.
Scores are out of 10, with evidence. Issue IDs refer to `ux-audit.md`.

Summary: average about 5/10. The app's strongest asset is honesty (no simulated prices, explicit "Unavailable", "not actual balances"). Its biggest weaknesses are the LIVE framing, blocked first screens, symbol-only market rows, and the absent Mind surface.

---

## S-01 — Account (`…19.32.50.jpeg`)

- **Intended function:** identity, verification, sign out.
- **Code:** `apps/trading_client/lib/src/presentation/customer_account_page.dart` (userId at ~136, `SelectableText`).
- **Hierarchy:** clear title, verified-email row, sign-out action. Good.
- **Issues:**
  - Full permanent user UUID displayed and selectable. Unnecessary exposure in screenshots and support requests (Low–Medium). Mask to last 4 characters with a copy action.
  - Sign-out is not visually separated from identity data (Low).
- **Ideal:** avatar, name, masked ID with copy, verified-state chip (icon + text), destructive action isolated at the bottom.
- **Dependencies:** identity card reusable on Settings and More.
- **Score: 7/10.** Calm and clear; ID exposure and weak separation of sign-out.

## S-02 — More sheet over Portfolio (`…19.32.50 (1).jpeg`)

- **Intended function:** access secondary destinations.
- **Code:** `app.dart` `_MoreProductSheet` (~600–660); typo at 539, 602.
- **Issues:**
  - "Mantic Mind activity": naming error in a trust-sensitive product name (UX-14, High).
  - Mind hidden behind More (UX-14, High). The AI is the differentiator but sits as a secondary item.
  - Sheet mixes destinations (Account, Settings, Notifications, Brokers) without grouping (Medium).
- **Ideal:** grouped sections (Trading, Intelligence, Account); correct name; Mind promoted to primary nav.
- **Dependencies:** bottom nav in `app.dart` (~440–460).
- **Score: 6/10.** Organized enough; typo and buried AI.

## S-03 — Portfolio, Angel One selected, unverified (`…19.32.51.jpeg`)

- **Intended function:** show the broker portfolio read-only, or explain why not.
- **Code:** `minttick_customer_portfolio.dart` (Live branch ~277).
- **Issues:**
  - Error copy "Portfolio data could not be verified" with no reason (UX-08, Medium).
  - Two buttons, "Retry portfolio" and "Refresh verified balances", do the same job (UX-08, Medium).
  - Large blank area under the error (Medium).
  - Paper/Live segmented control: selected text is low contrast (UX-25, High).
  - No last-verified timestamp (UX-07, High).
- **Good:** no fabricated balances; honest error.
- **Ideal:** reason-specific card (session expired → Reconnect; network → Retry), last-verified time, read-only badge, holdings preview when available.
- **Score: 4/10.** Honest, but a dead end with contrast and redundancy problems.

## S-04 — Trading Rooms, LIVE mode (`…19.32.51 (1).jpeg`)

- **Intended function:** list and plan rooms.
- **Code:** `minttick_rooms_pages.dart` (~335–338 mode control); `trading_workspace_mode.dart`.
- **Issues:**
  - "No Live Rooms yet" with a primary "Plan a Live Room" CTA. In a product where live execution is not enabled, this makes LIVE planning the main path (UX-01, Critical).
  - "Live" as a workspace identity without an execution-state status (UX-01, UX-02).
  - "Broker-linked" subtitle shown regardless of connection (UX-02, High).
- **Good:** empty state is explicit.
- **Ideal:** empty state with "Plan a room (paper)" as primary, and a visible execution-status line; LIVE planning behind readiness.
- **Dependencies:** `CustomerExecutionReadinessController` (backend), shared mode control.
- **Score: 4/10.** Clear empty state, but the LIVE CTA is a safety-relevant framing problem.

## S-05 — Home, lower section (`…19.32.52.jpeg`)

- **Intended function:** planned allocations, Mind status, room creation.
- **Code:** `minttick_customer_home.dart` (~225, ~297–304).
- **Issues:**
  - "Planned room allocations · not actual balances ₹0": honest, but a ₹0 card on Home reads as an empty account (Medium).
  - "MintTick Mind · Status" is static copy, not backend status (UX-09, High).
  - "Create Live room" CTA repeats UX-01 (Critical).
  - Stacked cards at similar weight; weak hierarchy (UX-21, Medium).
- **Ideal:** one primary action; backend-backed Mind status (or hidden); allocation summary moved to Rooms.
- **Score: 5/10.** Good disclosure copy, weak hierarchy, misleading status card.

## S-06 — Home, top section (`…19.32.52 (1).jpeg`)

- **Intended function:** welcome, workspace state, market overview.
- **Code:** `minttick_customer_home.dart`; `minttick_market_preview.dart` (~96, 130–216).
- **Issues:**
  - Three "Unavailable" tiles (RELIANCE, TCS, INFY) fill the first viewport (UX-03, High).
  - "LIVE · YOUR WORKSPACE" badge on a PAPER-default product (UX-01, Critical).
  - INFY logo clipped (UX-05, Medium).
  - Fixed three-symbol list, not personalized (Medium).
- **Good:** "MintTick market data is unavailable. No simulated values are shown." is the right trust posture.
- **Ideal:** connection-required card with one action; watchlist when connected; market-session chip.
- **Score: 4/10.** Honest, but the first screen is mostly blocked tiles and a misleading LIVE badge.

## S-07 — Markets list (`…19.32.52 (2).jpeg`)

- **Intended function:** browse instruments.
- **Code:** `minttick_customer_market_page.dart` (~399–414).
- **Issues:**
  - Symbol-only titles (ABDL, ABFRL) with monogram "AB" (UX-06, Medium).
  - No price, change, or freshness in rows (UX-04, High).
  - "Load more equities" pagination without sort or price filter (Medium).
  - Search and exchange filter present (positive).
- **Ideal:** name + symbol, price, change (sign + arrow + text), status chip, sort by change or volume.
- **Dependencies:** batched quote endpoint (backend gap), `minttick_equity_avatar.dart`.
- **Score: 4/10.** Visually clean and fast to scan, but missing the core trading data.

## S-08 — Cash equities, 20 of 2756 (`…19.32.53.jpeg`)

- **Intended function:** equity list with logos.
- **Code:** `minttick_equity_avatar.dart` (`_officialDomains`, Google s2 favicon).
- **Issues:**
  - Logo quality inconsistent: some clipped, some blurry, some monograms (UX-05, Medium).
  - Third-party favicon requests disclose viewed symbols (UX-05, privacy).
  - Same list issues as S-07 (UX-04, High).
- **Good:** logos add recognition for the 14 curated names.
- **Ideal:** instrument-master logos or consistent monogram; no third-party calls.
- **Score: 5/10.** Recognition helps; execution is inconsistent and privacy-sensitive.

---

## Screens not captured (code review only; no screenshots provided)

| Screen | Score | Basis |
|---|---|---|
| Activity (`/activity`) | 2/10 | Placeholder; copy only (UX-16). |
| Notifications (`/notifications`) | 2/10 | Placeholder (UX-16). |
| Operator Overview, Research, Risk, Model control | Not scored | No screenshots; evidence gap. |

## Cross-screen dependencies

- Mode control (Paper/Live) appears on Home, Rooms, and Portfolio → one shared component (UX-02).
- Status/price chip appears on Home tiles, Markets rows, and Portfolio → one shared component (UX-04, UX-07).
- Identity card appears on Account and More → one shared component (S-01).
- Avatar used on Home and Markets → one shared logo component (UX-05).
