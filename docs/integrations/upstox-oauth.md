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
