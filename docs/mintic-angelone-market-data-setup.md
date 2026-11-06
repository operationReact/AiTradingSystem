# Mintic — Angel One market-data setup (private development pilot)

## What this change implements

The Spring Boot market-data API now calls a broker-neutral `MarketDataProvider` for quote reads
and historical backfills. The initial `ANGEL_ONE` implementation reuses the existing, tested
SmartAPI REST auth, RFC 6238 TOTP generation, quote pacing, session recovery, instrument
master and candle parser. A future Upstox/FYERS implementation must provide the same contract.

- Authenticated `GET /api/market-data/quotes?instrumentId=<ACTIVE_ID>`: normalized
  `instrumentId`, `exchange`, `tradingSymbol`, `ltp`, `fetchedAt`, `source`.
- `GET /api/market-data/candles`: existing normalized historical query.
- `POST /api/market-data/backfills`: existing persistent/backfillable history, now
  routed through the neutral provider boundary.
- `POST /api/providers/angel-one/test-connection`: existing admin/operator-only test.
- `GET /api/market-data/instruments`: search the current imported instrument catalogue.
- `POST /api/market-data/instruments/sync`: existing admin/operator-only catalogue importer.

The quote `fetchedAt` is the backend retrieval time, **not** an exchange tick timestamp.
Do not label it as an exchange-real-time timestamp. Streaming remains a separate milestone.

## Get the four values

1. Sign in to the official SmartAPI portal at https://smartapi.angelone.in/ and create
   a Trading API app. Copy its **API key**: `ANGELONE_API_KEY`.
2. Use your Angel One trading account's **client code**: `ANGELONE_CLIENT_CODE`.
3. Use the account's **login PIN**: `ANGELONE_PASSWORD`. The name "PASSWORD" exists
   for backwards compatibility with the current login implementation.
4. During authenticator/TOTP setup, securely copy the **base32 shared seed**:
   `ANGELONE_TOTP_SECRET`. **Do not use a current six-digit code**; it expires every ~30 s.
   If the authenticator seed is unavailable, follow Angel One's official reset/re-enrollment
   procedure; do not attempt to recover it from six-digit codes.

Never commit actual values, paste them into chat, put them in Flutter/dart-defines, or
put them into public GitHub Actions output. The backend generates each short-lived TOTP.

## Secure runtime configuration — no local setup

For an initial private development pilot, create four AWS Systems Manager Parameter Store
**SecureString** parameters in **ap-south-1** (AWS Console → Systems Manager →
Application Management → Parameter Store → Create parameter):

| Name | Purpose |
|---|---|
| `/mintic/dev/angelone/api-key` | SmartAPI application's API key |
| `/mintic/dev/angelone/client-code` | Trading client code |
| `/mintic/dev/angelone/login-pin` | Trading account login PIN |
| `/mintic/dev/angelone/totp-secret` | Base32 TOTP enrollment secret |

Set **Tier = Standard** (subject to account limits) and **Type = SecureString**.
Prefer a scoped customer-managed KMS key if fine-grained access control is needed.
Never paste values into a chat, repository, Flutter app, Terraform state, or GitHub
Actions build logs. Restrict future runtime role permissions to these four parameter
ARNs; restrict `kms:Decrypt` to the selected key and approved SSM context. Do not
grant write permissions to ordinary application processes.

**Critical deployment boundary:** the existing `infra/ec2-paper/paper-compose.yml`
and its cost/safety policy explicitly forbid `ANGELONE_API_KEY` and other broker
credentials. This PR intentionally **does not** change that stack. It remains
PAPER-only and localhost-bound; it is not a broker-connected runtime.

The Spring Boot application already accepts the four environment names, but
Parameter Store is **not automatically wired to this app**. A later, separately
approved read-only market-data runtime must fetch the four SecureStrings at
startup, inject them as server-only environment variables without logging,
and use a role restricted to those specific SSM parameters. That runtime must
not have order execution permission or user-facing public credential exposure.
Only after this injection should `POST /api/providers/angel-one/test-connection`
be used to validate the authorized SmartAPI session.

For local experiments only, ignored process environment variables are supported
via `application.yml`; do not add actual secrets to `.env.example` or commit them.
The current PR is code integration, not a live broker login or AWS deployment.

## Validation plan

After a reviewed deployment to a *separate read-only market-data runtime* and verified secret injection:

1. `GET /api/providers/angel-one/status` — confirms configuration state without
   returning secret values.
2. `POST /api/providers/angel-one/test-connection` with an authorized admin/operator
   identity — login confirmation, no live orders.
3. `POST /api/market-data/instruments/sync`, then
   `GET /api/market-data/instruments?query=RELIANCE&exchange=NSE` to obtain an active ID.
4. `GET /api/market-data/quotes?instrumentId=<ID>` — verify actual data and source.
5. Run a bounded historical backfill, then retrieve `GET /api/market-data/candles`.
6. Verify no JWT, PIN, TOTP seed, API key or instrument broker token occurs in
   frontend responses, CI logs or request logs. Verify lack of trading/order authority.

This initial pilot is for the authorized brokerage account. A free SmartAPI key does
**not** grant a commercial market-data redistribution license. Before showing live
quotes to unrelated users, obtain explicit permitted-use terms from the broker/exchange.
Do not expose the authenticated API or token as a public streaming relay.

Official API documentation: https://smartapi.angelone.in/docs

Static-IP rules apply to order execution; this implementation adds no order endpoint.
