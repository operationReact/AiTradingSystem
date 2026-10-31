# P09-W07 — Flutter responsive matrix

Status: IN PROGRESS / strict Notion acceptance audit

## Canonical design evidence

Figma file `KjBWVCtUYona0bOGb5NDov`, P9 section `23:4422`, defines eight appearance/layout variants:

- Neon Futuristic Light / Desktop — 1440×900
- Neon Futuristic Light / Mobile — 390×844
- Neon Futuristic Dark / Desktop — 1440×900
- Neon Futuristic Dark / Mobile — 390×844
- Clean Modern Light / Desktop — 1440×900
- Clean Modern Light / Mobile — 390×844
- Clean Modern Dark / Desktop — 1440×900
- Clean Modern Dark / Mobile — 390×844

The Flutter handoff requires responsive reflow using `LayoutBuilder` / `MediaQuery`, SafeArea-aware mobile composition, and the shared spacing grid. Runtime classes remain:

- mobile: width < 600
- adaptive/tablet: 600–1023
- desktop: width >= 1024

## P09-W07 acceptance

1. Production Flutter layouts must behave deterministically across mobile, adaptive, and desktop classes; no native platform is allowed to depend on browser-only viewport assumptions.
2. Mandatory width evidence is required at **320, 375, 411, 768, 1024, and 1440 px**. Boundary-only coverage does not substitute for this matrix.
3. Overview hero, operator-access panel, workspace grid, global appearance controls, operations-health/data-state surfaces, and primary actions must not overflow or become unreachable at those mandatory widths.
4. Mobile uses one-column workspace content and compact appearance controls; adaptive uses two columns where space permits; desktop uses the full four-column matrix.
5. SafeArea remains authoritative for mobile/native insets.
6. Existing route/session/form preservation from P09-W06 must survive responsive rebuilds.
7. Data-state semantics from P09-W05 must remain unchanged across width classes.
8. Canonical Figma baselines 390×844 and 1440×900 remain visual references in addition to the mandatory width matrix.
9. Any screen-specific responsive obligations introduced by later real Flutter screens remain GAP until those screens are validated at their applicable mandatory widths.

This work remains presentation/layout only; it does not alter backend authority, broker behavior, authentication persistence, or LIVE activation. W07 must not be marked VERIFIED until exact-head CI and the mandatory viewport evidence are green.
