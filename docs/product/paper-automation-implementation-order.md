# PAPER automation remaining implementation order

1. Authoritative backend integration: account/session/preferences -> risk/emergency -> PAPER order/fill/position/portfolio/history.
2. Persisted automated opportunity processing with idempotent retry/restart behavior.
3. Secure Sign In/account-scoped client session integration where still missing.
4. Flutter account mode controls in More and compact state on Home.
5. Flutter AI/Research automated lifecycle and Manual View & Trade behavior.
6. Flutter Portfolio open/closed P&L/history states.
7. Angel One connect/read-only funds/holdings/positions/order book.
8. Exact-head backend/Flutter/security/golden/APK gates.
9. Canonical Figma phone review and backend-connected Android physical-device acceptance.
10. Merge, update both Notion pages, then continue to next unfinished release gate.

At every step preserve PAPER-only broker-disabled safety.
