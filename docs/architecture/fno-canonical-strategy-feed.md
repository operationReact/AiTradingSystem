# MintTick F&O — one canonical market-data pipeline for all 43 strategies

**Decision:** Implement F&O instrument discovery and licensed market-data ingestion once per authorized provider/account and fan out bounded, normalized **read-only** snapshots to every registered strategy. Do not give 43 strategies independent Upstox/Angel One WebSocket connections, tokens, REST pollers or subscription lifecycles.

This ADR is an integration target, **not evidence of a deployed source adapter**. Broker F&O trading-segment activation and rights to ingest, research, display, train on, or retain quotes are independent gates.

## Boundaries and ownership

1. **Provider adapters:** Upstox V3 and Angel One own connection/authentication, source quotas, reconnect, instruments, expiry listing and tick provenance. Current UpstoxFnoV3LiveFeedService is a manually operator-started pilot with MAX_SUBSCRIPTIONS=64; it is not a 43-strategy universal feed.
2. **Canonical contract registry:** NSE_FO key, exchange, underlying, asset type, expiry, lot size, tick size, option strike and side when relevant, contract provenance and settlement/delivery characteristics. Never guess expiries or synthesize contract identities.
3. **Immutable market snapshots:** each verified field retains instrument key, source, exchange event timestamp, receive timestamp, bid/ask/size, LTP, OI and IV/Greeks only where genuinely present. Evaluate cross-leg timestamp skew and quote freshness; no stitched stale rows.
4. **Independent rights:** contract discovery, internal research use, display, retention, model training and orders are separate. Unknown/denied => BLOCKED, not an opt-in that silently enables rights.
5. **Shared subscription planner:** compute union of instrument keys required by active strategy research and customer views; deduplicate, cap to provider quota, backpressure and emit over-budget counters. Do not stream every strike/universe by default.
6. **Research fan-out:** strategies consume typed canonical snapshots (never broker token/WebSocket lifecycle). Per-strategy registration declares prerequisites, supported instruments, freshness, expiry, synchronization and cost model. Missing source fields => explicit BLOCKED_DATA rather than zero, mock Greeks, fake quotes or silent skip.
7. **Backtesting/PAPER:** replay only permitted as-of historical observations, fees, liquidity and multi-leg non-atomic execution uncertainty; separate cost/risk gates and owner PAPER authorization.
8. **LIVE orders:** use an entirely separate, account-specific risk-checked and consented broker execution adapter after segment approval and audited verification. Green CI is not order authorization.

## Shared dependencies

| Family | Count | Data needed |
| --- | ---: | --- |
| Futures (STR-170–180) | 11 | Listed futures, expiring contracts, spot when needed, bid/ask/OI and cost assumptions |
| Options directional (STR-181–191) | 11 | Underlying direction, CE/PE contracts, quote freshness/liquidity |
| Options volatility (STR-192–203) | 12 | Genuine option-chain strikes, verified IV/Greeks/OI and past observations |
| Options hedging (STR-204–212) | 9 | Option chain, actual portfolio Greeks/exposure, hedge limits and margin |

STR-170 needs deliverable single-stock spot plus matching futures. STR-171 needs two separately verified futures contracts with the same underlying, differing expiry and synchronized two-leg bid/ask. Its positive research result is NOT risk-free arbitrage or executable trading.

## Practical rollout

- **Now:** build deterministic evaluator + reproducible JUnit fixture tests for each catalog strategy; retain DATA_BLOCKED status pending real source evidence.
- **Shared integration:** build one authorized canonical snapshot/contract registry with server-side permission checks, including source release and expiry provenance. Prove actual paired-contract live ticks before claiming ready.
- **Batch activation:** fan verified snapshots out to strategies requiring those fields and track source age, subscription budget, number of eligible vs blocked strategies, reason and last evaluation time. Missing data may block only some of the 43.
- **Validation:** no-lookahead historical replay and PAPER simulations per strategy, separate from account-level LIVE activation.

Acceptance dashboard fields: provider_state, subscriptions_requested/active/rejected, fresh_exchange_timed_quotes, stale_quotes, source_rights_verified, strategy_input_ready_count, strategy_data_blocked_count, per_strategy_last_evaluation_at.

Official reference: https://upstox.com/developer/api-documentation/v3/get-market-data-feed/ and https://upstox.com/developer/api-documentation/instruments/ .

## Private pilot daily-candle as-of boundary (22 November 2026)

The short-lived owner-private 3,929-row F&O daily pilot retains source bar
timestamps such as `00:00+05:30` as **session labels, not observation or
publication time**. A completed daily high/low/close or final OI must not
enter a strategy decision from the same calendar session, regardless of how
the provider encoded `candle_ts`. The owner-private
`read_canonical(..., as_of=aware_datetime)` call now rejects missing/naive/
future decision timestamps and selects only `session_day < local Indian
decision date`. This is a necessary conservative anti-look-ahead bound,
**not proof of exact source publication or original first-known time**.
The existing source/retention rights, expiration date, lack of complete
exchange-calendar and provenance, and `backtestingApproved=false` stay
unchanged. No data is promoted to the 43-strategy verified market E2E metric,
PAPER, LIVE, or any customer-facing API by this change.
