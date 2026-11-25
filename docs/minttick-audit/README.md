# MintTick Audit — Index

Status: audit and recommendations only. No application code was changed. Requires review before implementation.

| Report | Purpose |
|---|---|
| [architecture-discovery.md](architecture-discovery.md) | Frontend and backend architecture and integration map |
| [feature-inventory.md](feature-inventory.md) | Screen and functionality inventory with status |
| [ux-audit.md](ux-audit.md) | Issues with ID, screen, file, impact, severity, fix, validation |
| [screen-by-screen-review.md](screen-by-screen-review.md) | Eight screenshots mapped to code, scored out of 10 |
| [design-system-proposal.md](design-system-proposal.md) | Tokens, components, interaction and motion rules |
| [redesign-roadmap.md](redesign-roadmap.md) | Phased plan, dependencies, estimates, acceptance criteria |
| [architecture-improvements.md](architecture-improvements.md) | Backend and client architecture changes |

## Decisions required before implementation

1. Design direction: both `neonFuturisticAi` and `cleanModern` (AGENTS.md), or one design (`docs/design-system/minttick-single-design-ux-audit-20261106.md`).
2. Customer LIVE framing: hide, or show disabled with a reason.
3. Logo source and licensing.

## Evidence gaps

- Notion and Figma not accessed.
- Flutter analyze, tests, and goldens not run.
- No device or runtime evidence; screenshots are the only visual evidence.
- Operator screens not captured or scored.
- Backend endpoints for batched quotes, insight status, and P&L not verified as present.
