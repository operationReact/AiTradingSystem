# PAPER automation implementation checklist

Status: IN PROGRESS

- [x] Define account-scoped PAPER/LIVE and MANUAL/AUTOMATED product semantics.
- [x] Keep LIVE broker submission explicitly disabled/fail-closed.
- [x] Define one mandatory server-authoritative risk gate for manual and automated trade intents.
- [x] Define automated PAPER lifecycle and acceptance evidence.
- [ ] Persist account-scoped trading mode and execution mode in the existing authoritative preference store.
- [x] Implement safe account-scoped PAPER + MANUAL defaults and Automated opt-in boundary.
- [x] Implement shared risk-decision service for manual and automated PAPER intent boundaries.
- [x] Implement automated opportunity eligibility and idempotent simulated order creation foundation.
- [x] Implement simulated fill and position foundation.
- [x] Implement deterministic PAPER capital accounting foundation.
- [x] Implement mark-to-market unrealized P&L foundation.
- [x] Implement stop-loss, target and emergency-stop exit decisions.
- [x] Implement realized P&L and account-scoped closed-history foundation.
- [x] Add focused and full-journey backend tests covering accepted/rejected/manual/automated/emergency/idempotent/capital/history behavior.
- [x] Add customer-safe lifecycle snapshot/event/copy contracts.
- [ ] Integrate these boundaries with the existing persisted production PAPER order/portfolio/risk/history services. Do not ship a parallel in-memory ledger.
- [ ] Connect Flutter global controls to authenticated account-scoped backend state.
- [ ] Render customer-facing automated lifecycle states in actual Flutter screens.
- [ ] Add phone-size Neon/Clean visual acceptance evidence against canonical Figma.
- [ ] Pass exact-head Flutter analyze/tests and backend PAPER E2E gates.
- [ ] Package Android APK with LIVE submission still disabled.

Next implementation cursor: integration with the repository's existing persisted account preferences, risk, PAPER orders/fills/positions/portfolio/history and authenticated account context. Then wire Flutter PAPER + Manual/Automated controls and lifecycle UI. LIVE broker submission remains disabled.
