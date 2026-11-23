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
As of the next security increment, **file-based execution is disabled**,
including when `--execute-sandbox` is specified. Standalone JSON remains
inspection-only; it is not authenticated evidence and must never submit.

Actual execution is available only in
`scripts/minttick_mind_sandbox_authenticated.py`, which performs a fresh,
authenticated HTTPS GET to the fixed
`https://api.minttick.com/api/customer/mind/v1/fno-evaluation` endpoint using
an owner-provided Cognito/customer bearer access token from the process
environment `MINTTICK_CUSTOMER_ACCESS_TOKEN`. The token is never printed or
persisted. Redirects and unexpected response types are rejected. It then
validates the same real-time candidate against freshly downloaded Upstox NSE
BOD. This does not independently prove source licensing.

The authenticated runner is read-only by default (`--evaluate`); a
simulated broker request requires all of
`--execute-sandbox --confirmation SANDBOX_ONLY --journal /persistent/path/mind-sandbox.sqlite`.
The journal path must be absolute and outside the Git checkout, and must be
on genuinely durable storage. One atomic SQLite claim is committed
**before** the first broker request, keyed to strategy ID plus latest
completed crossover candle. Duplicate or ambiguous outcomes remain blocked
even if the quote timestamp changes or the process crashes. Do not store
this journal on an ephemeral CI runner.

Only the dedicated AWS sandbox SSM-reader role can obtain the sandbox token.
No LIVE host, production order OAuth, or customer live-order route is
available. **This journal verifies local submission control, not broker fills,
positions, cash balances or reconciliation against Upstox order book.**
Upstox documents sandbox support primarily for place/modify/cancel; its
documentation is not a substitute for validating order-detail retrieval.
Never promote a sandbox ACK to a real-trade or ledger reconciliation status.

Example of validation only, on a local **genuine backend response**:

```bash
python3 scripts/minttick_mind_upstox_sandbox_bridge.py \
  --research-json /secure/path/to/current-mind-fno-evaluation.json
```

Do not commit the owner response or customer data to GitHub. Expired quotes
must fail. No sandbox execution should be scheduled without verified ongoing
authenticated backend origin, explicit owner approval, expiry checks,
idempotent intents, durable reconciliation, and a kill switch.


### Authenticated runtime example (no broker order)

Provide a current authenticated **customer** access token to the process
using your secure runtime/session mechanism; never paste it in CI inputs,
GitHub logs, source files or chat:

```bash
python3 scripts/minttick_mind_sandbox_authenticated.py --evaluate
```

If no token is available, this command fails closed. The market-hours
endpoint may return `NO_TRADE` outside NSE hours, which is a valid
non-executable signal, not an integration failure. This task does **not**
provision a customer JWT or claim the remote endpoint has been queried.

To perform one explicitly authorized sandbox-only test on a persistent
host after the above checks and only with a newly observed candidate:

```bash
python3 scripts/minttick_mind_sandbox_authenticated.py \
  --execute-sandbox --confirmation SANDBOX_ONLY \
  --journal /var/lib/minttick/private/mind-sandbox.sqlite
```

The directory must already be secure and persistent. Repeated signals,
unknown results and interrupted submissions are not retried. Run no
unattended automation from this command.

## What this PR proves / does not prove

- **Proves:** input-shape adaptation, authenticated fetch URL and header
  contract under test, deterministic fail-closed order eligibility,
  current BOD contract matching, persistent SQLite one-attempt claim and
  no-network synthetic tests for replay and failure cases.
- **Does not prove:** an actual authenticated production Mintick Mind
  candidate has been fetched during market hours, application deployment,
  a real Mind-triggered sandbox order, broker fill/position reconciliation,
  independent real historical strategy E2E, real PAPER ledger fills or LIVE readiness.

Current wider F&O-43 dashboard source-level evaluator callsites: **43/43**.
Independent real historical source replay, PAPER and LIVE E2E were **0/43**
at last source-recorded audit. Do not turn those metrics green because the
unrelated standalone sandbox lifecycle passed.
