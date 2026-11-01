# PAPER automation PR scope

This PR starts from `dev` merge `700d026e9265c2be48d7ecafc1ef3afc0cb20801` after customer-mobile PR #206.

## Included

- PAPER/LIVE and Manual/Automated product semantics.
- Server fail-closed LIVE/broker-submission capability boundary.
- Shared PAPER risk decision model.
- AI automated opportunity eligibility.
- Idempotent simulated PAPER intent/fill foundation.
- Position, P&L, capital, stop/target/emergency-stop and history foundations.
- Account-isolated safe defaults and Automated opt-in.
- Customer-safe lifecycle/copy contracts.
- Full automated PAPER journey tests.

## Merge blockers

This PR remains Draft until all of the following are true:

- foundation is reconciled with existing persisted production PAPER/account/risk services rather than creating a parallel ledger;
- actual Flutter screens expose the global PAPER + Manual/Automated controls and customer lifecycle states;
- exact-head backend and Flutter gates are green;
- phone-size visual evidence matches canonical Figma sufficiently for product acceptance;
- Android APK packages successfully;
- LIVE broker submission remains disabled.

No production/cloud deployment is part of this PR.
