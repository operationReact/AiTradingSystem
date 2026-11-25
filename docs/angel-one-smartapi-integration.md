# Angel One SmartAPI integration: verified server-side contract

Last verification: **2026-11-02** against Angel One's current SmartAPI documentation and official SDK route inventory.

This integration intentionally uses Spring `RestClient` rather than adding the legacy SmartAPI Java SDK as a runtime dependency. Credentials and JWTs remain server-side and are never persisted by this adapter.

## Safety boundary

- The implemented broker surface is **read-only** for account data and market data.
- No SmartAPI place-order, modify-order, cancel-order, convert-position, or GTT mutation method exists in `AngelOneApiClient`.
- Real-money execution remains disabled and belongs to a later gated phase.
- API responses and provider exceptions never include credentials, JWTs, passwords, or TOTP secrets.
- Session JWTs are stored in memory only by `AngelOneSessionManager`.

## Authentication and session

| Capability | Provider route / behavior | Project status |
|---|---|---|
| Login | `POST /rest/auth/angelbroking/user/v1/loginByPassword` | Implemented |
| TOTP | RFC 6238, 6 digits / 30 s | Implemented |
| Session reuse | In-memory JWT with configurable max age | Implemented |
| Session expiry recovery | One explicit re-login after SmartAPI token/session expiry | Implemented |
| Credential persistence | None | Enforced |

Provider credentials are supplied through environment configuration (`ANGELONE_API_KEY`, `ANGELONE_CLIENT_CODE`, `ANGELONE_PASSWORD`, `ANGELONE_TOTP_SECRET`, `ANGELONE_BASE_URL`).

## Market data

| Capability | Provider route | Project status |
|---|---|---|
| LTP | `POST /rest/secure/angelbroking/order/v1/getLtpData` | Implemented |
| Historical candles | `POST /rest/secure/angelbroking/historical/v1/getCandleData` | Implemented |
| Instrument catalogue/token resolution | Server-side instrument master | Implemented |

`GET /api/providers/angel-one/ltp` accepts only an internal `instrumentId`; exchange, trading symbol, and provider token are resolved from the active catalogue rather than trusted from the caller.

## Read-only account data

The following SmartAPI routes are current official **GET** endpoints and are implemented by `AngelOneApiClient` + `AngelOneAccountReadService`:

| Capability | SmartAPI route | Application endpoint |
|---|---|---|
| Funds / RMS | `/rest/secure/angelbroking/user/v1/getRMS` | `GET /api/providers/angel-one/funds` |
| All holdings + provider summary | `/rest/secure/angelbroking/portfolio/v1/getAllHolding` | `GET /api/providers/angel-one/holdings` |
| Settled holdings | `/rest/secure/angelbroking/portfolio/v1/getHolding` | `GET /api/providers/angel-one/holdings/settled` |
| Positions | `/rest/secure/angelbroking/order/v1/getPosition` | `GET /api/providers/angel-one/positions` |
| Order book | `/rest/secure/angelbroking/order/v1/getOrderBook` | `GET /api/providers/angel-one/order-book` |

These application routes are protected by the existing Spring Security chain. Authenticated `VIEWER`, `TRADER`, `ADMIN`, or `OPERATOR` identities may perform `GET /api/**` reads; mutation endpoints remain separately restricted.

The application returns the provider `data` payload wrapped with `source=ANGEL_ONE_SMARTAPI` and a server-side `fetchedAt` timestamp. No provider session token is exposed to the client.

## Retry and session-recovery policy

`AngelOneAccountReadService` follows the same safety model as quote reads:

1. Obtain the current in-memory JWT.
2. Apply the endpoint-specific local rate limiter.
3. Use `AngelOneRetryPolicy` only for idempotent/read-only transient failures.
4. On an explicit provider session-expiry signal, reserve retry capacity first, re-authenticate once, and retry the read.
5. Never retry validation/provider rejection as if it were transient.

## Verified provider rate limits

Angel One's SmartAPI rate-limit documentation checked on 2026-11-02 publishes:

| API | Provider limit |
|---|---:|
| Login | 1 request/sec |
| RMS / funds | 2 requests/sec |
| Holdings | 1 request/sec |
| All holdings | 1 request/sec |
| Positions | 1 request/sec |
| Order book | 1 request/sec |
| LTP | 10/sec, 500/min, 5000/hour |
| Historical candles | 3/sec, 120/min, 5000/hour |

The project intentionally keeps its historical-candle limiter more conservative at 2/sec and 80/min. Account reads use dedicated local categories: 500 ms minimum spacing for RMS and 1000 ms minimum spacing for holdings/positions/order-book.

## Still deferred / disabled

- WebSocket streaming acceptance and production reconnect soak.
- Trade-book read model if/when the UI requires it.
- Brokerage/charge-estimate normalization.
- Order placement, modification, cancellation, position conversion, GTT mutations, and any other real-money write path.
- Live execution enablement until independent risk, broker reconciliation, runtime soak, and explicit capital-promotion gates are satisfied.

## Verification sources

- Angel One SmartAPI docs: `https://smartapi.angelone.in/docs`
- Angel One official SmartAPI SDK route inventory.

Do not infer undocumented provider behavior from this document. Re-verify provider routes, response shapes, static-IP rules, and rate limits before changing the broker execution boundary.
