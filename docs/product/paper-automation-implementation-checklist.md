# PAPER automation implementation checklist

Status: IN PROGRESS

- [x] Define account-scoped PAPER/LIVE and MANUAL/AUTOMATED product semantics.
- [x] Keep LIVE broker submission explicitly disabled/fail-closed.
- [x] Define one mandatory server-authoritative risk gate for manual and automated trade intents.
- [x] Define automated PAPER lifecycle and acceptance evidence.
- [ ] Persist account-scoped trading mode and execution mode.
- [x] Implement shared risk-decision service for manual and automated PAPER intent boundaries.
- [x] Implement automated opportunity eligibility and idempotent simulated order creation foundation.
- [x] Implement simulated fill and position foundation.
- [x] Implement mark-to-market unrealized P&L foundation.
- [x] Implement stop-loss, target and emergency-stop exit decisions.
- [x] Implement realized P&L on position close.
- [x] Add focused backend tests covering accepted/rejected/manual/automated/emergency/idempotent lifecycle behavior.
- [ ] Integrate these boundaries with the existing persisted production PAPER order/portfolio services and history.
- [ ] Connect Flutter global controls to account-scoped backend state.
- [ ] Add customer-facing automated lifecycle states without raw backend vocabulary.
- [ ] Add phone-size Neon/Clean visual acceptance evidence.
- [ ] Pass exact-head Flutter analyze/tests and backend PAPER E2E gates.
- [ ] Package Android APK with LIVE submission still disabled.

Next implementation cursor: integrate the new shared safety/risk/automation boundary with the repository's existing persisted PAPER execution and account-preference infrastructure. Do not create a parallel production trading stack. Persist account-scoped trading/execution modes, then connect Flutter controls and run exact-head gates.
