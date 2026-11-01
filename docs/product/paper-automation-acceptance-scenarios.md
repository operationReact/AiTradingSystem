# PAPER automation customer acceptance scenarios

## Manual PAPER

A signed-in customer in PAPER + Manual can review an AI idea, choose View & Trade, enter/confirm quantity/stop/target, pass the same risk engine and receive a simulated order/fill/position. No broker order is sent.

## Automated PAPER

A signed-in customer explicitly enables Automated. Eligible high-confidence AI opportunities are evaluated server-side. A qualifying opportunity that passes risk can create one idempotent simulated order/fill and position without per-trade confirmation. The customer can observe state and P&L.

## Risk rejection

An opportunity exceeding buying power/risk limits or missing safe parameters creates no simulated fill/position and shows readable guidance.

## Emergency stop

Trading paused prevents new Manual and Automated intents. Existing PAPER positions follow authoritative safety exit behavior; the UI rereads the resulting state.

## Target/stop lifecycle

Price monitoring updates unrealized P&L. Reaching target/stop closes the PAPER position once, updates capital, realized P&L and history.

## Restart/reconnect

Customer state is reconstructed from persisted backend authority. Automation does not duplicate accepted orders/fills/exits after retry/restart.

## LIVE

LIVE is shown as unavailable/Coming later. No customer action in this release can submit a broker order.
