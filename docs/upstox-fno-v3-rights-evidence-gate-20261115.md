# Upstox NSE_FO V3 source-use evidence gate (operator-only)

Scope: issue #1074. This is an evidence checklist, **not** an entitlement grant, market-data receipt, quote cache, or activation request. Upstox Analytics Token access to REST/WebSocket does not establish permission to display to customers, retain, replay, train models, redistribute, or use data in PAPER simulation.

## Independent permissions to obtain from Upstox and applicable exchange/licensor

For each intended use, record a provider/licensor-issued written authorization reference, contract/version, scope, subject (account/app), valid-from/to, permitted instrument/exchange, retention limits and restrictions. Never paste private agreement text, account identifiers, access tokens, signed WebSocket URLs, or quotes in issues/logs.

| Permission | Default | Evidence required |
| --- | --- | --- |
| Private operator ephemeral read-only V3 test | NOT_VERIFIED | Provider API capability and approved private workflow; do not infer other rights |
| Customer-facing option display | DENY | Explicit customer display/use authorization and exchange licensing where applicable |
| PAPER price-driven simulation | DENY | Explicit simulated trading/data-use authorization independent of API read |
| Durable quote/candle retention and replay | DENY | Explicit retention, duration and derivative-data license |
| ML training and derived features | DENY | Explicit training/derivative-use license |
| Redistribution to third parties | DENY | Explicit redistribution permission |
| LIVE order placement | DENY | Separate broker/customer trading authorization; outside this worker's scope |

## Monday private V3 acceptance (non-persisting, bounded)

1. Confirm exact deployed SHA via protected host identity gate, approved operator workflow and existing SSM token; source plugin stays OFF by default outside the authorized ephemeral test.
2. Discover listed active NIFTY expiry from current option-contract API. Validate both CE and PE instrument keys and lot/expiry; select a bounded near-ATM subset. Never substitute an expired-instruments paid endpoint or fabricated contract.
3. During a verified NSE regular session (including holiday calendar), authorize the existing V3 WebSocket, subscribe to the bounded listed keys, and verify actual exchange LTT and bid/ask. Handshake alone is insufficient. Require fresh exchange LTT and frame observation (<=15 seconds) and source instrument match. Report only counts, status enums, timestamps and exact deployed SHA, not raw prices or signed URLs.
4. Verify bounded retry/backoff, 401/403/429 classification, disconnect/reconnect with resubscription, expiry and market-close stop, and a finite operator time budget. Never silently extend to 24-hour streaming.
5. Post a sanitized protected receipt reference to #1074 with observed-at, expiry, CE/PE subscribed, fresh/stale/unknown frame counts, exchange-LTT availability, reconnect outcome and stop reason. A receipt is source evidence, not PAPER readiness or retention authorization.
6. Keep customer display, PAPER-use rights and LIVE-order flags disabled until separate rights evidence is independently reviewed. Do not persist quotes, tokens or signed WSS URLs.

## Current evidence separation (as reported November 14, 2026)

Protected REST source workflow #43603258735 reported 284 NIFTY Nov 17 contracts (142 CE, 142 PE), 142 strikes and 142 Greek-complete strikes on EC2 SHA 4084238ffac0206a8acfbdaa24f9ad06d0a67554. This does **not** prove fresh V3 ticks or contractual use rights. Golden source registration #43608288161 reported zero rights metadata rows and zero jobs; this does **not** prove retained data. Independently verified active-session V3 exchange-LTT receipt: UNKNOWN. Rights: NOT_VERIFIED. Backend acceptance delta: +0.0pp.

Ownership: Worker 1 source/SSM/protected receipts; Worker 2 canonical contract; Worker 3 coverage; Worker 5 progress; Claude Mind/PAPER/UI. Do not edit those owned files from this workstream.
