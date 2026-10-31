# P09-W09 final Flutter acceptance

Status: **IN PROGRESS until exact-head CI and the final connected Web/Android/iOS phase-boundary gate are green.**

This document maps the Phase 9 final acceptance requirements to repository-owned evidence. It does not waive runtime evidence and does not authorize LIVE trading.

## Functional acceptance matrix

| Requirement | Repository evidence |
| --- | --- |
| Operator unlock / connected health authority | `test/widget_test.dart`, `integration_test/connected_health_app_test.dart` |
| Experience switch preserves route/session | `test/widget_test.dart` |
| Experience switch preserves unsent form state | `test/widget_test.dart` |
| Research scan → candidate → evaluation | `test/research_page_test.dart` |
| PAPER order validation, idempotency and backend risk result | `test/paper_trading_pages_test.dart`, `test/paper_trading_repository_test.dart` |
| Risk/approval server authority and row-version safety | `test/risk_approvals_page_test.dart`, `test/risk_approval_repository_test.dart` |
| Emergency stop confirmation + authoritative reread | `test/w09_acceptance_completion_test.dart` |
| PAPER backtest run → history | `test/w09_acceptance_completion_test.dart`, `test/backtest_repository_test.dart` |
| Degraded market-data fail-closed UI | `test/w09_acceptance_completion_test.dart`, `test/market_data_repository_test.dart` |
| Corrected-screen responsive matrix | `test/corrected_screen_responsive_matrix_test.dart`, `test/responsive_matrix_widget_test.dart`, `test/responsive_layout_test.dart` |
| Accessibility semantics / non-color-only evidence | `test/accessibility_semantics_test.dart`, `test/accessibility_contrast_test.dart` |
| Workspace inventory/navigation | `test/w04_inventory_pages_test.dart`, `test/workspace_navigation_test.dart` |
| Overview visual regression | `test/visual_golden_test.dart` + committed goldens |

## Final phase-boundary rule

The normal PR gates are intentionally inexpensive. After the final candidate head is stable and fast exact-head CI is green, run `.github/workflows/flutter-connected-runtime.yml` **once** with:

- `validation_scope=phase-completion`
- `phase=P09`

That workflow must prove the same exact candidate head through:

1. real Spring Boot + PostgreSQL connected repository/Web runtime;
2. connected Android emulator journey; and
3. connected iOS Simulator journey.

A failure must be diagnosed and fixed at root cause. Old-head or partially successful native evidence cannot verify a newer commit.

## Visual-fidelity boundary

Committed golden snapshots detect unintended rendering changes. They are not, by themselves, Figma fidelity evidence. Any remaining Figma comparison gap must stay explicit rather than being inferred from a green golden test.

## Safety boundary

Phase 9 remains PAPER-only. No test, appearance mode, approval UI or final acceptance action may provision cloud resources, deploy production, mutate DNS/secrets/signing/store configuration, submit broker orders, or enable LIVE trading.
