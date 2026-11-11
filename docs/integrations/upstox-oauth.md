# MintTick Upstox OAuth connection — initial read-only integration

## Customer login

1. Sign in to MintTick as a Cognito-linked `CUSTOMER`.
2. Navigate to **More → Connected brokers → Upstox**.
3. Tap **Connect with Upstox**. The authenticated backend generates a
   256-bit, five-minute, single-use state and returns an authorization URL
   for `https://api.upstox.com/v2/login/authorization/dialog`.
4. The customer authenticates **at Upstox**, not in MintTick. Upstox sends
   `code` and `state` to the registered HTTPS callback.
5. The backend consumes the state, exchanges the authorization code
   server-to-server using MintTick's Upstox app client ID and secret, and
   verifies the Upstox `/v2/user/profile` response.
6. Return to MintTick and tap **Refresh connection**. Funds, holdings, positions,
   order book and registered IP status can now be read using the user's
   own short-lived Upstox session.

No customer API key, PIN, password, TOTP, token or authorization code needs
to be entered into MintTick. The platform's **client secret is server-only**.

## Required owner setup (dev account)

Create an application at https://account.upstox.com/developer/apps and register
the exact callback URL on the dedicated MintTick customer HTTPS API deployment:

`https://<public-api-domain>/api/customer/brokers/upstox/callback`

Install these as server-side environment values sourced from AWS Secrets
Manager/SSM via Terraform / GitHub Actions, never into Flutter, repository or chat:

- `UPSTOX_CLIENT_ID` — application API key (not the user's client code).
- `UPSTOX_CLIENT_SECRET` — Upstox application secret.
- `UPSTOX_REDIRECT_URI` — the exact, registered **HTTPS** callback URL.

The backend rejects missing config on `POST /api/customer/brokers/upstox/start`,
but continues to serve a healthy status endpoint so the UI can explain setup.

`/api/customer/brokers/upstox/callback` is the only unauthenticated route.
It is guarded by a random, 256-bit, one-use, five-minute state, generated
only after verifying the initiating customer JWT. Tokens are never included
in the redirect HTML. All other Upstox endpoints require `ROLE_CUSTOMER`,
and the account-only API filter explicitly rejects write/order methods.

## Supported endpoint matrix

| Action | Method | Path |
| --- | --- | --- |
| Initiate browser OAuth | POST | `/api/customer/brokers/upstox/start` |
| Receive broker callback | GET | `/api/customer/brokers/upstox/callback` |
| Connection status | GET | `/api/customer/brokers/upstox/status` |
| Disconnect | POST | `/api/customer/brokers/upstox/disconnect` |
| Funds | GET | `/api/customer/brokers/upstox/funds` |
| Holdings | GET | `/api/customer/brokers/upstox/holdings` |
| Positions | GET | `/api/customer/brokers/upstox/positions` |
| Order book | GET | `/api/customer/brokers/upstox/orders` |
| Registered static IP | GET | `/api/customer/brokers/upstox/registered-ips` |

No place, modify, cancel or auto-execute route is exposed for Upstox.

## Production rollout gate

The initial authorization-state store and bearer sessions are in server
memory for a **single-instance integration test**. They expire at the daily
Upstox 03:30 IST cutoff or on service restart; multiple replicas/load
balancers **must not** be deployed with this implementation. Before production
multi-user use, replace them with transactionally consumed OAuth state in a
shared datastore and broker tokens encrypted with KMS envelope keys.

For actual Upstox API orders, both a broker-approved execution arrangement
and a registered per-customer static IP are necessary. See the official
announcements:
- https://upstox.com/developer/api-documentation/authentication/
- https://upstox.com/developer/api-documentation/get-token/
- https://upstox.com/developer/api-documentation/announcements/algo-trading-circular/
- https://upstox.com/developer/api-documentation/announcements/static-ip-apis/

**This PR is a read-only broker authorization integration.** It must not be
mistaken for production Live order readiness; existing Paper/Live room safety
gates remain unchanged.

## Public HTTPS gateway support

The account API Gateway's exact `GET /api/customer/brokers/upstox/callback`
route is anonymous **only for the OAuth redirect**. The dedicated Lambda
accepts one `state` plus one `code` or `error` parameter, rejects duplicate
or unexpected query parameters and forwards the callback to Spring Boot.
Spring Boot consumes the short-lived, one-time, customer-bound state and
exchanges the code server-to-server. The Lambda returns fixed, no-store HTML
with no broker code/state/error reflection and a no-referrer policy.
The other Upstox endpoints (start, status, disconnect, funds, holdings,
positions, orders, registered IPs) require a valid Cognito CUSTOMER JWT.
No order-placement methods are opened by the edge.

**Rollout:** This changes code and the CloudFormation template, not the AWS
stack. Before using it, deploy or update `minttick-account-api` in Mumbai,
register the exact HTTPS callback at Upstox, store developer app credentials
only on the protected backend, and verify on a physical device. The private
PAPER runtime deliberately remains unconfigured until this separate setup.


## Required Upstox developer configuration for MintTick

Unlike Angel One's SSM-owner flow, Upstox uses broker-hosted OAuth. Create
or select a valid Upstox developer application and register this **exact**
callback (not an arbitrary website URL):

`https://api.minttick.com/api/customer/brokers/upstox/callback`

The backend needs these configuration values:
- `UPSTOX_CLIENT_ID` — app's public API key
- `UPSTOX_CLIENT_SECRET` — server-only app secret
- `UPSTOX_REDIRECT_URI` — exact HTTPS callback above

Store the secret securely on the **appropriate protected customer API
runtime**, not in the Android client, GitHub variables, plaintext repo
files or chat. Unlike the four Angel One SecureStrings, these Upstox
application settings are **not automatically installed** simply because
Angel One parameters exist. The existing PAPER EC2 Compose environment
must be explicitly configured (or the Upstox integration moved to a
separate dedicated credential store/runtime) before `status.available`
can become true.

Once configured, the Android customer taps **Connect with Upstox**, logs
in on the official Upstox authorization page, and consents. MintTick's
server consumes the one-use state and exchanges the code privately.
The callback route has no Cognito token only because it is the broker
browser redirect; the initiating start and all account reads require
the verified customer JWT. Do not claim the Upstox account is connected
until the authenticated status endpoint confirms it.

The protected gateway already admits the exact Upstox OAuth and account
routes. No live order placement is enabled.


## 2026 developer-app setup checklist (no secrets in GitHub or Android)

1. In `https://account.upstox.com/developer/apps`, create or select the
   **trading/Algo** application for MintTick. Copy the API Key and API Secret
   to the secure AWS console only. Do not use the separate Analytics/read-only
   extended token as an order authorization.
2. Register exactly
   `https://api.minttick.com/api/customer/brokers/upstox/callback` as the
   application's redirect URI. First check that this hostname's customer
   callback is actually deployed: matching a string in the source code is
   insufficient.
3. In **AWS Console → Systems Manager → Parameter Store**, select region
   **ap-south-1** and create:

   | Parameter | Type | Value |
   | --- | --- | --- |
   | `/minttick/upstox/client-id` | `SecureString` | Developer API Key |
   | `/minttick/upstox/client-secret` | `SecureString` | Developer API Secret |
   | `/minttick/upstox/redirect-uri` | `String` | Exact HTTPS callback above |

   The paths are **new proposed convention names**, not automatically
   provisioned by existing code or an assertion that these parameters exist.
   Use the designated KMS key and least-privilege instance/task role for
   `ssm:GetParameter` with decryption. Do not use public app config,
   Terraform state with literal secrets, or shell command arguments to
   publish secret values.
4. In the **protected runtime that actually serves the customer API**, use
   its existing secret-loading mechanism to inject the three values as
   `UPSTOX_CLIENT_ID`, `UPSTOX_CLIENT_SECRET`,
   `UPSTOX_REDIRECT_URI` before Spring Boot starts. See
   `backend/src/main/resources/application.yml`. **Simply creating SSM
   parameters does not connect Upstox:** the source checkout has no automatic
   mapping from these proposed paths into environment variables. The
   deployment/IAM mapping must be reviewed for the exact runtime before
   enabling it. Do not configure the owner-private Analytics probe as a
   shortcut for a customer OAuth session.
5. Verify as the authenticated MintTick customer:
   `GET /api/customer/brokers/upstox/status` reports
   `available=true`. Tap **Connect with Upstox** in the Android app,
   complete browser-hosted login, return and tap **Refresh connection**.
   Only claim success when `connected=true`, then check funds, holdings,
   positions, orders and registered IP status on the same owner account.

### Separate prerequisites before LIVE order execution

- Upstox access tokens typically expire at **03:30 IST**, requiring a new
  authorized session for a subsequent trading day.
- Register the **actual fixed outbound egress IP** of MintTick's order
  execution runtime in Upstox **My Apps → Static IPs**. An EC2 public address
  is not necessarily the egress IP when NAT/proxy/networking is involved.
  The IP is registered per Upstox user; changing it invalidates existing
  access tokens. Read-only historical/Analytics access does not establish
  order-placement IP compliance.
- Place-order V3 uses
  `POST https://api-hft.upstox.com/v3/order/place` with a short-lived
  customer access token and a broker-defined instrument key. A market-data
  token, analytics token, API key or API secret alone must never be sent as
  an order authorization.
- Order dispatch needs a separately reviewed customer-bound execution
  runtime with explicit consent, broker permissions, accepted risk
  controls, daily limits, unique idempotent order intents, reconciliation
  after timeouts, and disable/kill-switch behavior. Do not open the write
  route on the read-only account API or turn on LIVE by uploading secrets.
- Upstox lists combined retail **regular algo** write limits of
  **10 requests/second**, **500/minute**, and **2,000/30 minutes**.
  Actual rules depend on the approved account/app; enforce limits lower
  than or equal to current broker policy.

References:
- https://upstox.com/developer/api-documentation/v3/place-order/
- https://upstox.com/developer/api-documentation/get-token/
- https://upstox.com/developer/api-documentation/rate-limiting/
- https://upstox.com/developer/api-documentation/appendix/my-apps-support-for-algo-trading-circular/


## Secure direct SSM configuration (development runtime)

A dedicated, **disabled-by-default** backend flag allows MintTick to load the
platform's Upstox OAuth developer credentials directly from AWS Systems Manager
Parameter Store on **EC2 startup**, without copying the secret into
`/opt/aitradingsystem-paper/runtime.env` or a Docker Compose file.

**Credential paths consumed by this code** (must match the three user-created
parameters exactly):

- `/minttick/upstox/client-id` — `SecureString`
- `/minttick/upstox/client-secret` — `SecureString`
- `/minttick/upstox/redirect-uri` — `String` or `SecureString`, value
  `https://api.minttick.com/api/customer/brokers/upstox/callback`

**Operator-owned, separately reviewed activation checklist:**

1. Confirm all three parameters exist in account `098859530205`, region
   `ap-south-1`, with the expected types and the exact redirect URI.
   No command or screenshot should print or display the secret value.
2. Attach the least-privilege example policy
   `infra/ec2-paper/upstox-oauth-instance-role-policy.json` to the
   **existing EC2 instance role** actually used by the PAPER backend.
   This policy is source documentation, not an automatically applied IAM
   change. A customer-managed KMS key also needs a separately scoped
   `kms:Decrypt` grant for the actual key.
3. On the protected PAPER EC2 host, configure only
   `MINTTICK_UPSTOX_OAUTH_SSM_ENABLED=true` in root-owned `runtime.env`
   (mode 0600), without API keys, OAuth secrets or tokens. Do not change
   Angel One settings, read-only Analytics token setup, or PAPER trading mode.
4. Deploy reviewed application code via the existing SSM deployment procedure
   and validate that the backend, Web reverse proxy, and API Gateway are
   healthy. A new backend process reads the three parameters with
   `GetParameters(WithDecryption=true)` using the role's normal SDK
   credential chain. It never falls back to environment credentials when the
   SSM flag is enabled, and any read/type/format error leaves
   `available=false` without exposing values.
5. Authenticate as the linked Cognito customer. Confirm
   `GET /api/customer/brokers/upstox/status` returns `available=true`
   (only a configuration check). Complete a browser redirect from **Connect
   with Upstox**, return to MintTick and verify `connected=true` plus
   actual read-only holdings/funds/positions/orders. The OAuth state and
   bearer token remain process-local and expire on restart / session expiry.
6. **LIVE is not enabled** by steps 1–5. Customer-bound order routing, broker
   permission/static egress IP verification, consent, risk limits, order
   preflight and reconciliation are separate later changes.

Never treat a successful CI job or three SSM parameter names as proof the
live AWS role can decrypt them. The actual instance policy and host opt-in
must be inspected and tested before announcing a connected account.


## Optional personal-owner PIN + TOTP automatic session recovery

Official Upstox docs provide an alternative individual-app token endpoint:
`POST https://api.upstox.com/v2/totp-login/authorization/token`, using the
app's `x-api-key` header and account's six-digit PIN plus current six-digit
TOTP in JSON. Unlike regular OAuth, no browser redirect is required. **Upstox
explicitly restricts this endpoint to individual apps**; it may reject Algo
Trading / third-party app configurations. Its successful use revokes the
app's previous token, so MintTick reuses unexpired sessions rather than
repeatedly regenerating tokens. Access tokens still expire at 03:30 IST.
Official docs:
- https://upstox.com/developer/api-documentation/get-token/
- https://upstox.com/developer/api-documentation/authentication/

**One-time owner setup (not applied automatically):**
1. Confirm the current Upstox developer app is type **individual**, and the
   owner's trading account has TOTP enabled. For other app types, continue the
   existing secure OAuth redirect or implement the Upstox approval-notifier
   flow; do not impersonate a user.
2. Create two **SecureString** parameters in AWS SSM `ap-south-1`:
   `/minttick/upstox/login-pin` and
   `/minttick/upstox/totp-secret` (the owner's own TOTP *seed*, Base32,
   **never** the changing six-digit code). Do not send either to a chat,
   Flutter, the repository, logs, screenshots, or any public webhook.
3. After reviewing exact IAM resources, attach
   `infra/ec2-paper/upstox-owner-totp-instance-role-policy.json` to the
   existing EC2 instance role. If a customer KMS key was used, the key's
   explicit restricted `kms:Decrypt` grant is still needed.
4. Confirm existing protected settings identify the *same exact owner*
   Cognito subject as for Angel One:
   `MINTTICK_ANGEL_ONE_OWNER_SUB`. A missing or wrong subject makes
   owner login unavailable. Other signed-in MintTick users cannot read or
   invoke the owner's protected credential flow.
5. Only after role permissions and provider eligibility have been verified,
   turn on `MINTTICK_UPSTOX_OWNER_TOTP_ENABLED=true` in protected
   `runtime.env` and deploy the reviewed backend. The existing
   `MINTTICK_UPSTOX_OAUTH_SSM_ENABLED=true` must also be working.
   Both switches default to **false**. Do not make a public API or
   unsigned mobile-app flag for this.
6. Verify an authenticated owner-only
   `GET /api/customer/brokers/upstox/configured-login` returns available.
   First visit/later expired session calls owner-only
   `POST /api/customer/brokers/upstox/connect-configured` with **no
   PIN, seed or TOTP fields**. The backend checks existing token, reads the
   exact two SSM SecureStrings, generates TOTP, validates the provider's
   verified profile and binds the token only to that owner's UUID.
   At least 90 seconds between unsuccessful attempts; no automatic
   retries in a tight loop.
7. For individual-app restrictions, Upstox downtime, expired segments,
   missing permissions, or invalid PIN/TOTP, MintTick fails closed and
   still offers the original Upstox website login. No broker order endpoint
   is accessed by this feature.

**Important:** This only restores authenticated **read-only account access**.
Upstox access does *not* enable real-money order execution. A LIVE room still
requires its own explicit selected broker account, approved static egress IP
matching `GET /v2/user/ip`, server-side account-wide risk/capital limits,
quote freshness, reconciliation, order idempotency and a deliberate
permission gate. Upstox's developer token and year-long analytics token
must never be substituted for a customer's trading access token.
