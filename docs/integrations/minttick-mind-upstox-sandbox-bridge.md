# Mintick Mind research → Upstox sandbox: first non-funded signal bridge

## Verified starting point (23 November 2026)

Standalone Upstox sandbox place → modify → cancel returned matching order
acknowledgements for one current NIFTY CE contract from Upstox's NSE BOD.
Evidence: https://github.com/operationReact/AiTradingSystem/actions/runs/45204966160

That **does not** verify the Mind engine generated that order, nor establish
PAPER ledger correctness, after-cost profitability, real market data rights or
LIVE order authorization.

## Scope of this incremental integration

`scripts/minttick_mind_upstox_sandbox_bridge.py` is a **manual, sandbox-only**
adapter for the authentic, current JSON response of the backend read-only
`GET /api/customer/mind/v1/fno-evaluation` endpoint. It maps only:

- `strategy.STR-181` + `BUY_CALL_RESEARCH` to one long CE sandbox intent.
- `strategy.STR-182` + `BUY_PUT_RESEARCH` to one long PE sandbox intent.

The other **41 strategies are deliberately unsupported** by this order
bridge; their Java research evaluator paths are not a multi-strategy trading
engine. A signal file alone is **not proof of server authenticity**. The
owner must retrieve it from the actual protected backend as a customer, not
write one by hand or present a synthetic CI fixture as provider evidence.

The bridge requires `RESEARCH_CANDIDATE`, source-attributed NIFTY index
candles, at least 35 completed candles, exchange-timed quotes no older than
15 seconds, matching strategy and call/put side, unplaced/non-executable
research flags and a non-expired *future* expiry. It independently matches
the exact instrument key, option type, underlying, expiry, strike, lot and
5-paise tick against current **official Upstox NSE BOD** and applies a
₹25,000 one-lot simulated notional ceiling. It sends only a **non-funded**
sandbox BUY/modify/cancel via the existing Upstox sandbox client.

**Default invocation never fetches SSM credentials or places broker orders**;
it does read the *public* BOD file for contract identity validation.
Only an explicitly provided `--execute-sandbox --confirmation SANDBOX_ONLY`
enters the existing sandbox-only transport and SSM reader. That requires the
separately provisioned exact sandbox SSM-reader AWS identity. No LIVE host,
production token, customer OAuth session, or broker order route is available.

Example of validation only, on a local **genuine backend response**:

```bash
python3 scripts/minttick_mind_upstox_sandbox_bridge.py \
  --research-json /secure/path/to/current-mind-fno-evaluation.json
```

Do not commit the owner response or customer data to GitHub. Expired quotes
must fail. No sandbox execution should be scheduled without verified ongoing
authenticated backend origin, explicit owner approval, expiry checks,
idempotent intents, durable reconciliation, and a kill switch.

## What this PR proves / does not prove

- **Proves:** input-shape adaptation, deterministic fail-closed order
  eligibility checks, current BOD matching, no-network synthetic unit tests
  for safety and manual-only execution switches.
- **Does not prove:** that a genuine Mintick Mind research candidate was
  observed and passed these checks at market open, deployment, actual Mind
  to sandbox order, independent real historical strategy E2E, real PAPER
  ledger fills or LIVE readiness.

Current wider F&O-43 dashboard source-level evaluator callsites: **43/43**.
Independent real historical source replay, PAPER and LIVE E2E were **0/43**
at last source-recorded audit. Do not turn those metrics green because the
unrelated standalone sandbox lifecycle passed.
