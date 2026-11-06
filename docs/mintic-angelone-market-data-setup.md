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

## Secure runtime injection — current EC2 dev pilot

For the existing EC2 PAPER backend, `infra/ec2-paper/paper-compose.yml` now forwards
the four environment variables to **the backend container only**. The existing
`infra/ec2-paper/run_paper.sh` reads host-local
`/opt/aitradingsystem-paper/runtime.env` (permission 0600) and builds a restricted
runtime Compose env file. The web container receives none of the credentials.

Use AWS Systems Manager **Session Manager**, not a public SSH listener, to access the
already-authorized instance and update that root-owned file in a restricted shell.
Do not enable a verbose shell, copy secrets into command arguments, log them, or use
unrestricted CI job variables. The next EC2 release must contain the updated Compose
file; simply changing the repository does **not** change the deployed container.

Recommended durable secret storage for future automated provisioning: AWS Systems
Manager Parameter Store, `SecureString` (KMS), in **ap-south-1** under four paths:
`/mintic/dev/angelone/api-key`, `/mintic/dev/angelone/client-code`,
`/mintic/dev/angelone/login-pin`, `/mintic/dev/angelone/totp-secret`.
A role-scoped, non-logging deployment retrieval step is still required before these
parameters automatically populate `runtime.env`. **Creating the parameters alone
does not wire them to the app.** Restrict `ssm:GetParameters` and `kms:Decrypt`
to the EC2 instance role and relevant parameter ARNs/key; audit access and rotate
on compromise. GitHub repository secrets are not a runtime secret store for EC2.

## Validation plan

After a reviewed deployment and runtime injection:

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
