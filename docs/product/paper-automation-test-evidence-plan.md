# PAPER automation test evidence plan

Evidence should be layered:

1. Unit tests — mode guards, risk rules, idempotency, P&L/capital math, customer state mapping.
2. Service integration tests — authenticated account preference + authoritative risk + persisted PAPER order/fill/position/history.
3. End-to-end backend tests — Manual and Automated journeys, emergency stop, retry/restart/idempotency.
4. Flutter widget tests — controls, states, customer copy, responsive behavior and error handling.
5. Connected runtime — Flutter against real Spring/PostgreSQL PAPER backend.
6. Visual acceptance — phone-size canonical Figma comparison for Neon and relevant Clean secondary surfaces.
7. Android packaging/device — release APK against phone-reachable HTTPS PAPER backend and physical-device flow.

Do not skip lower layers because a higher layer passes, and do not infer device/UI acceptance from backend tests.
