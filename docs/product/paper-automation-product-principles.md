# PAPER automation product principles

- Safety before automation: automation never bypasses risk.
- One ledger: persisted backend state is authoritative.
- One risk path: Manual and Automated differ only in intent source.
- Explicit opt-in: Automated is never enabled implicitly.
- Customer language: explain outcomes, not backend plumbing.
- Fail closed: uncertain authority creates no new trade.
- Recoverable: retries/restarts do not duplicate financial state.
- Observable: customers can understand what automation is doing.
- Broker separation: PAPER simulation is independent of Angel One order submission.
- Release honesty: CI green, mockups and contracts do not equal physical-device/product acceptance.
