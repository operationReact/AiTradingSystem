# P09-W08 — Flutter accessibility

Status: IN PROGRESS

## Canonical acceptance

The Unified Low-Level Implementation Plan requires:
- visible focus;
- semantic headings;
- label/error associations;
- no color-only P&L/risk meaning;
- chart text summary/table fallback;
- dialog focus trap/restore;
- platform reduced-motion preferences respected, including web reduced-motion;
- automated contrast checks where possible.

## Implementation in this slice

- Overview hero, application identity, operator-access section, workspace section, workspace page titles, and empty-state titles expose heading semantics.
- Workspace cards expose explicit button semantics with title + purpose rather than relying on icon/color alone.
- Dashboard metrics expose label/value semantics.
- Access-key authentication errors are associated directly with the input decoration while the live data-state error remains visible.
- Trading health/status states keep textual state labels; color is supplemental only.
- Status colors are brightness-aware so text use meets a minimum 4.5:1 contrast ratio against both Neon and Clean panels; automated tests cover all five trading-state colors in light and dark modes.
- Flutter Material controls retain platform keyboard focus behavior, with an explicit theme focus color.
- This production slice introduces no charts or dialogs. Any future chart must provide a text/table fallback and any future dialog must prove focus trap/restore before acceptance.
- No custom motion is introduced by Phase 9. Flutter receives platform accessibility media settings (including web reduced-motion); future custom animation must branch on `MediaQuery.disableAnimationsOf(context)` rather than overriding that preference.

## Safety

Accessibility changes are presentation-only. They do not alter backend authority, authentication persistence, trading decisions, broker actions, or LIVE activation.
