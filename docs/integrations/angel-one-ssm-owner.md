# MintTick owner-only Angel One SSM connection (read-only)

## Scope

This change makes the existing server-side Angel One credential configuration
available to **one explicitly authorized MintTick customer account**. It does
not turn a platform operator's private SmartAPI account into a shared broker
connection for other customers, does not change PAPER/LIVE semantics, and
does not enable placing orders.

### Configuration at a dedicated, authenticated account API backend

Configure the existing secret properties through AWS SSM Parameter Store (or
another approved server-side secrets mechanism) on the **appropriate customer
account API runtime**, not in Flutter or the website:

- `ANGELONE_API_KEY`
- `ANGELONE_CLIENT_CODE`
- `ANGELONE_PASSWORD` — the Angel One login PIN/password required by the provider
- `ANGELONE_TOTP_SECRET` — server-side authenticator seed
- `MINTTICK_ANGEL_ONE_OWNER_SUB` — the exact `sub` of the intended customer
  in the MintTick Cognito user pool. This is an authorization binding, not
  a broker credential. Obtain it from verified Cognito identity records.

The deployed runtime must have narrowly scoped IAM access to precisely the
approved SSM parameters. No values should appear in Actions logs, Flutter
Dart defines, public URLs, CI artifacts, GitHub source, or chat.

**Fail-closed:** when the owner subject is missing, does not match the
authenticated Cognito JWT `sub`, or the backend lacks complete configured
Angel One credentials, `GET /api/customer/brokers/angel-one/configured-login`
returns `{"available":false}`. Only the owner sees the one-tap UI.

For the owner, `POST /api/customer/brokers/angel-one/connect-configured`
generates a short-lived TOTP on the server, authenticates against SmartAPI,
verifies the returned broker profile, and binds a read-only session to the
authenticated customer UUID. The connection expires and can be disconnected.

All other customers must use their **own** supported broker connection.
No server-stored broker credentials may be shared across customer identities.

The existing isolated PAPER EC2 runtime deliberately excludes broker secrets
and has only local listeners. **Do not silently forward secrets to that host,
open an inbound port, or imply Android customer sign-in is publicly reachable.**

## Acceptance

1. Spring/JUnit owner allowlist and JWT tests pass.
2. Flutter dark and light widget tests at 320dp and 390dp pass.
3. Account-edge method allowlist includes only the new owner capability and
   secure POST connection route; no execution route is added.
4. On a real HTTPS customer backend, authenticate as the allowlisted user,
   confirm configured-login availability, and connect. Confirm the masked
   Angel One account matches the intended owner; test funds and holdings.
5. Authenticate as a second user: configured-login is false and POST returns
   403. No account data from the owner is accessible.
6. Build the Android release with the actual HTTPS account API and the
   public Cognito client after the live API preflight succeeds.
7. Verify physical-device session restore, disconnection, network failure,
   login failure, expiry, and Upstox hosted OAuth callback.

The Upstox developer-app client ID/secret and exact HTTPS callback must be
configured separately per `docs/integrations/upstox-oauth.md` before it can
connect to a real Upstox account.

## Customer API gateway route contract

The public Lambda edge in `infra/minttick-domain/account_proxy.py` now permits
three additional **read-only-account connection management** routes:
`GET configured-login`, `POST connect-configured`, and `POST disconnect`
(all under `/api/customer/brokers/angel-one/`). These routes forward the
Cognito access token to the existing Spring Boot account API. The backend
independently checks `ROLE_CUSTOMER`, the active account UUID and, for
`connect-configured`, the exact server-side Cognito owner subject. The
configured broker login accepts an empty body; no PIN, TOTP seed, API key,
or other broker credential is submitted from the Android device.

**Not deployed by merging:** this code does not create the `minttick-account-api`
stack, publish a public API, set the owner subject, install SSM secrets,
or build a connected Android APK. The existing private PAPER EC2 intentionally
has no broker secrets. The one-tap connection will report unavailable until
server-side secrets and owner authorization are independently provisioned.
Live order placement remains disabled.


## Updated owner-only connection architecture (November 2026)

The current MintTick user is correct that the four Angel One credentials may already
exist as **SecureString** values in SSM. That does **not** make them available to the
PAPER EC2 application: the existing runtime deliberately excludes those values.

The read-only private Lambda `minttick-dev-angelone-feed` is the authorized bridge:
it fetches the four SecureStrings, generates each **six-digit current TOTP** from
the long base32 **enrollment seed**, verifies the broker profile against the configured
client code, and serves read-only account information. Its broker JWT stays in Lambda
memory. The Spring backend holds only an owner-bound, masked, expiring connection
status; it does not store the API key, PIN, TOTP seed or broker token.

To activate the one-tap **Secure connect** button:
1. Confirm the exact four SSM SecureString parameter names in Mumbai and verify
   that the long TOTP value is the base32 enrollment seed, not a current OTP.
2. Provision the dedicated feed IAM bootstrap and Terraform Lambda from
   `docs/minttick-market-feed-ssm-lambda-pilot.md` using the existing reviewed
   workflow. SSM parameters alone are not deployment.
3. Enable the backend's two nonsecret settings
   `MARKET_DATA_PROVIDER=ANGEL_ONE_LAMBDA` and
   `MARKET_DATA_LAMBDA_FUNCTION_NAME=minttick-dev-angelone-feed` using the
   existing protected EC2 SSM configuration mechanism. Only the private
   Lambda is allowed to read the four SecureStrings.
4. Bind `MINTTICK_ANGEL_ONE_OWNER_SUB` to the **exact Cognito access-token
   subject** of the intended owner (not the MintTick internal user UUID).
   Never assume the first registered user owns the private broker credentials.
5. Verify `GET /api/customer/brokers/angel-one/configured-login` returns
   `{"available":true}` for this one owner and false for another account.
   Test read-only funds, holdings, positions and order book. Keep paper and
   real order execution separately disabled.

The public API Gateway explicitly denies manual `POST /connect` and every
place/modify/cancel-order route. The Android owner flow does not ask the user
to re-enter four secrets already held in Parameter Store. Do not send any
secret or authenticator seed to ChatGPT, GitHub or a Flutter build.

### Angel One-side setup checklist

Confirm a valid SmartAPI application/API key and enabled TOTP on the intended
Angel One client code at the official SmartAPI developer portal. Check whether
the chosen API app has permitted market data and read-only account access.
If the long enrollment key is lost, reset/enroll TOTP using Angel One's official
account flow. A six-digit authenticator code cannot recover the long seed.
Static-IP brokerage/execution rules are separate and no live order API is
activated by this read-only rollout.
