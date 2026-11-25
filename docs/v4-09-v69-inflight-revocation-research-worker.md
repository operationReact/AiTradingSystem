# V4-09 / V69 — Genuine in-flight ADMIN PAUSE must reject late research scores

## Controlled synthetic integration

Prior merged V67 verified complete positive Spring/PostgreSQL-to-Python
CatBoost/LightGBM model scoring and V68 verified a real Python HTTP 503
remains terminal UNKNOWN/no-retry with zero matched decisions.

V69 tests the **concurrent operator safety race** that those checks did not:

1. Start the same allowlisted real synthetic Python FastAPI worker on
   `127.0.0.1:18721`; launch a **CI-only** local forwarding pause gate on
   `127.0.0.1:18722`. Both are private to one GitHub runner.
2. Enroll an original two-model PAPER champion/challenger roster, freeze
   synthetic causal V59/V60 inputs and submit the real Spring ADMIN V64
   roster POST asynchronously. The proxy holds BOTH actual model HTTP
   requests *after* their independent V61 PostgreSQL reservations commit.
3. Invoke the genuine ADMIN V58 `PAUSE` control, confirming the session
   state transitions while Python requests remain blocked.
4. Release the proxy gate, forwarding both requests to real trained Python
   CatBoost and LightGBM models. Their valid late inference outputs are
   **not accepted**: Java V61 terminal receipts must both be
   `REJECTED / SESSION_REVOKED`, with zero `SUCCEEDED` receipts.
5. The original V64 response is `INCOMPLETE_NO_RETRY`; verify zero V56
   matched cases/decisions, zero V62 custody links, zero correlated PAPER
   orders and false capital/promotion authority. Repeat the exact roster
   POST and assert HTTP 409 and no extra worker HTTP requests or reservations.

The test asserts the proxy has held BOTH requests and that exactly two
durable V61 reservations exist **before** the pause; it cannot silently
test a pause-before-start scenario instead. On any test failure the
finally block releases held requests; CI cleanup stops both loopback
servers and deletes all temporary synthetic artifacts and secrets.

The CI proxy is separate from shipped Java/Python application code and
has hard-coded loopback addresses, bounded request sizes and no logging
of secrets, prompts, model bodies, broker credentials or predictions.

## Non-acceptance

This remains a synthetic one-runner integration, not a qualified
multi-minute production soak, an authenticated broker environment,
independently licensed Indian exchange/news feed, cloud worker SLA or
a real capital authorization. No LIVE execution configuration is changed.
V4-09 and overall application production readiness remain IN PROGRESS.
