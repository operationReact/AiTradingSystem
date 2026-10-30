# P09-W07 — Flutter responsive matrix

Status: PREPARED / implementation follows on stacked branch

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

The P9 handoff explicitly calls out an 8pt grid. Repository architecture additionally defines three runtime classes used by Flutter layout code:

- mobile: width < 600
- adaptive/tablet: 600–1023
- desktop: width >= 1024

## P09-W07 acceptance

1. Production Flutter layouts must behave deterministically at the mobile, adaptive, and desktop boundaries; no native platform is allowed to depend on a browser-only viewport assumption.
2. Overview hero, operator-access panel, workspace grid, global appearance controls, and operations-health screen must not overflow or obscure primary controls at representative widths.
3. Mobile uses one-column workspace content and compact appearance controls; adaptive uses two-column workspace content where space permits; desktop uses the full four-column workspace matrix.
4. SafeArea remains authoritative for mobile/native insets.
5. Existing route/session/form preservation from P09-W06 must survive responsive rebuilds.
6. Data-state semantics from P09-W05 must remain unchanged across width classes.
7. Tests must cover boundary widths around 600 and 1024 plus canonical mobile 390 and desktop 1440 widths.

This work remains presentation/layout only; it does not alter backend authority, broker behavior, authentication persistence, or LIVE activation.