## Summary

Starts the next customer-release slice after #206: PAPER automation and shared risk controls.

This Draft introduces the product/safety foundation for PAPER/LIVE × Manual/Automated, while keeping LIVE and all broker submission unavailable. Manual and AI-automated PAPER intents share one risk-decision model. Automated execution requires explicit account opt-in plus eligible/high-confidence AI opportunity. Foundation tests cover idempotent simulated fills, position/P&L/capital/history lifecycle, target/stop/emergency exits, account isolation and customer-safe states.

## Important review boundary

This is intentionally Draft. The new in-process foundation is not a second production ledger. Before merge it must be reconciled with existing persisted Phase 10 account/session/preferences and Phase 7/8/9 risk/PAPER order/fill/position/portfolio/history infrastructure. Actual Flutter Home/AI/Portfolio/More controls/lifecycle UI, canonical Figma phone evidence, exact-head CI and Android APK packaging remain merge blockers.

## Safety

- PAPER only.
- LIVE unavailable.
- Broker submission unavailable.
- Angel One remains read-only.
- No production/cloud deployment.
