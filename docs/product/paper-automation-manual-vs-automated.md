# Manual vs Automated PAPER semantics

Both modes use the same authoritative PAPER risk/execution path. The difference is who initiates the eligible intent.

## Manual

The customer reviews an opportunity or symbol, enters/confirms order parameters, and explicitly submits a PAPER intent. Server risk decides whether it proceeds.

## Automated

The account explicitly opts into Automated mode. An eligible server-side AI/research opportunity may initiate a PAPER intent without a per-trade confirmation, but the same server risk boundary still decides whether it proceeds. Emergency stop always overrides automation.

Automated mode does not mean unrestricted trading, LIVE trading, broker submission, or bypassing risk. It is only an alternate PAPER intent source in the current release.
