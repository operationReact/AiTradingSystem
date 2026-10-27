# Angel One SmartAPI integration: endpoint inventory

Status legend:
- **VERIFIED-SDK**: named in the official `angel-one/smartapi-java` README (v2.2.0). Parameters and response shapes are NOT verified.
- **UNVERIFIED**: not confirmed against official documentation. Do not implement against it without checking.
- **NOT IMPLEMENTED**: no code exists in this repository.

## Verification limits

- The official docs at https://smartapi.angelone.in/docs and https://smartapi.angelone.in/exchange-regulations are client-rendered. Automated retrieval returned only the page title, so endpoint paths, request and response schemas, and rate limits could not be confirmed from them.
- The SDK README (https://github.com/angel-one/smartapi-java) documents the Java method names only. It describes the session flow with `generateSession(clientId, password, totp)`, `renewAccessToken(accessToken, refreshToken)`, and `setSessionExpiryHook`.
- The SDK targets the legacy `com.angelbroking.smartapi` package. Compatibility with Java 21 and Spring Boot 3.5 is unverified.
- Rate limits, static-IP rules, and the instrument-master schema are unverified. Configured limits must stay conservative until checked.

## A. Authentication and session

| Capability | Endpoint / SDK method | Status | Notes |
|---|---|---|---|
| Login with client code, password, TOTP | `POST /rest/auth/angelbroking/user/v1/loginByPassword` (SDK `generateSession`) | PARTIALLY_VERIFIED, IMPLEMENTED | Path and body verified from SDK source. Response fields `jwtToken`, `refreshToken`, `feedToken` verified from SDK. Tokens are held in memory only and redacted from `toString()`. |
| Renew access token | SDK `renewAccessToken` | NOT IMPLEMENTED | The session manager re-logs in with TOTP instead. Refresh endpoint unverified for this project. |
| Session expiry | Envelope codes AG8001, AG8003, AB8050, AB8051, AB1010 | PARTIALLY_VERIFIED, IMPLEMENTED | Codes taken from SDK `SmartAPIResponseHandler`. On expiry the session is invalidated and one re-login is attempted. |

Session lifetime is unverified. A configurable ceiling (`session-max-age-seconds`, default 21600) forces re-login. Credentials are read from `ANGELONE_API_KEY`, `ANGELONE_CLIENT_CODE`, `ANGELONE_PASSWORD`, `ANGELONE_TOTP_SECRET`, and `ANGELONE_BASE_URL` (see `.env.example`). TOTP is RFC 6238 (HMAC-SHA1, 30 s, 6 digits), which is the SDK's behavior and is verified by unit tests against RFC vectors.

Header uncertainty: the SDK also sends `X-ClientLocalIP`, `X-ClientPublicIP`, and `X-MACAddress`. This project omits them deliberately (privacy and fragility). Whether Angel One requires them is UNVERIFIED. If login fails with a header-related error, this is the first thing to revisit.

## B. Instrument master

| Capability | Status | Notes |
|---|---|---|
| Download the instrument master | UNVERIFIED | URL and schema unconfirmed. Do not guess token mappings. |
| Symbol and token resolution | NOT IMPLEMENTED | Depends on the master file. |

## C. Historical candles

| Capability | Status | Notes |
|---|---|---|
| Historical OHLCV by interval | UNVERIFIED | Endpoint path, interval names, and max date ranges per interval are unconfirmed. |

## D. Quotes and streaming

| Capability | SDK method | Status | Notes |
|---|---|---|---|
| Last traded price | `POST /rest/secure/angelbroking/order/v1/getLtpData` (SDK `getLTP`) | PARTIALLY_VERIFIED, IMPLEMENTED | Request `{exchange, tradingsymbol, symboltoken}` verified from SDK. Response field `ltp` is only partially verified. Exposed at `GET /api/providers/angel-one/ltp`. |
| OHLC / full quote | UNVERIFIED | NOT IMPLEMENTED | |
| WebSocket streaming | UNVERIFIED | NOT IMPLEMENTED | Frame format, heartbeat, and exchange-type codes are unconfirmed. |

## E. Market intelligence

Gainers and losers, put-call ratio, Greeks, open-interest data, intraday-eligible lists, and cautionary scrips are all **UNVERIFIED** and **NOT IMPLEMENTED**. Surface them as explicitly unavailable until confirmed.

## F. Portfolio and account (read-only)

| Capability | SDK method | Status |
|---|---|---|
| Holdings | `getHolding` | VERIFIED-SDK, NOT IMPLEMENTED |
| All holdings | `getAllHolding` | VERIFIED-SDK, NOT IMPLEMENTED |
| Positions | `getPosition` | VERIFIED-SDK, NOT IMPLEMENTED |
| RMS / available margin | `getRMS` | VERIFIED-SDK, NOT IMPLEMENTED |
| Trade book | `getTrades` | VERIFIED-SDK, NOT IMPLEMENTED |
| Order history | `getOrderHistory` | VERIFIED-SDK, NOT IMPLEMENTED |
| Brokerage and charge estimate | UNVERIFIED | NOT IMPLEMENTED |

## G. Order execution (disabled)

Place, modify, cancel, order-detail, and GTT methods are named in the SDK (`placeOrder`, `modifyOrder`, `cancelOrder`). **All are UNVERIFIED for this project and must stay disabled.** Live execution is blocked server-side. No code path may submit a real order. Static-IP and account-level prerequisites are unverified and must be confirmed in the Angel One account before any future enablement.

## Decision

The SDK is NOT added as a dependency. It targets Java 1.8 and legacy libraries, and the project targets Java 21 and Spring Boot 3.5. The integration uses Spring `RestClient` (`provider/angelone/AngelOneApiClient`) against the endpoints above. The adapter stays server-side behind `AngelOneQuoteService` and `AngelOneSessionManager`.

## Implemented in this phase

- Configuration: `config/AngelOneProperties`, `config/AngelOneClientConfig`.
- Session: `AngelOneSessionManager` (in-memory JWT, TOTP login, max-age reuse, single re-login on expiry).
- Health: `AngelOneHealthService` with states NOT_CONFIGURED, CONFIGURED_UNVERIFIED, AUTHENTICATED, SESSION_EXPIRED, DEGRADED, UNAVAILABLE. Status is reported without network calls. Test connection is explicit.
- Quotes: LTP only, with a local rate limiter (`AngelOneRateLimiter`, fail-fast).
- API: `GET /api/providers/angel-one/status`, `POST /api/providers/angel-one/test-connection`, `GET /api/providers/angel-one/ltp`. All require the operator API key.

## Deferred (not implemented)

Instrument master, historical candles, WebSocket streaming, market analytics, portfolio and margin reads, brokerage estimates, and every order endpoint. Each needs its contract verified first. Live order execution remains disabled and fails closed.

## Rate limits

Local intervals are configured conservatively (`login-min-interval-ms`, `quote-min-interval-ms`). Official limits are UNVERIFIED and must be checked before raising throughput.
