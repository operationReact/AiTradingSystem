# PAPER automation mobile state model

The Flutter app should render a small customer-facing state model derived from backend authority.

## Mode

- PAPER
- Live trading — Coming later

## Execution

- Manual
- Automated

## Safety

- Risk protected
- Trading paused

## Automation lifecycle

- Watching for opportunities
- AI opportunity found
- Checking risk
- PAPER order accepted
- Filled
- Position open
- Closed

## Outcome

- Unrealized P&L
- Realized P&L
- Profit
- Loss

Do not expose implementation states such as queue names, provider codes, reconciliation states, database status, raw authorization failures or internal exception text. The client must reread backend state after reconnect/restart.
