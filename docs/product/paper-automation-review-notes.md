# PAPER automation architecture review notes

## Accepted direction

- One customer model: Trading mode (PAPER/LIVE) × Execution mode (Manual/Automated).
- PAPER + Manual is the safe default.
- Automated requires explicit account opt-in.
- Every intent uses one server-authoritative risk boundary.
- LIVE is modeled for UX continuity but unavailable in this release.
- Simulated PAPER code has no broker-adapter dependency.

## Critical review finding

The foundation classes on this branch intentionally use in-process state to make safety/lifecycle behavior executable and testable quickly. They are not acceptable as a second production ledger because prior phases already implemented persisted PAPER/account/risk infrastructure. Before merge, integrate/refactor against those existing authoritative services and remove or convert temporary in-process adapters where they duplicate production concerns.

## Product review finding

Documentation/copy contracts are not UI implementation. Actual Flutter Home/AI/Portfolio/More screens still need the global controls and lifecycle state, followed by canonical Figma phone-size review.

PR must remain Draft until these findings are closed and exact-head gates pass.
