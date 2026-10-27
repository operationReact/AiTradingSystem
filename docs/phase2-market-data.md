# Phase 2 — Market-data foundation

This phase adds a protected, read-only instrument discovery path. It does not place broker orders and does not enable live trading.

## Instrument master

- `POST /api/market-data/instruments/sync` downloads the public Angel One instrument master from the fixed approved HTTPS host.
- The download does not follow redirects and rejects non-200, empty, oversized, malformed, or excessive-row payloads.
- Only NSE/BSE cash-equity rows are persisted; derivative instruments are excluded.
- Rows are upserted by exchange + symbol token, making repeated syncs idempotent.
- `GET /api/market-data/instruments?query=RELIANCE&exchange=NSE&limit=20` searches the local catalog.
- Both endpoints are under `/api/**` and therefore require the existing operator API-key authentication.

## Persistence

Flyway migration `V3__market_data_foundation.sql` creates `market_instrument` and `market_candle`. Candle rows have OHLC consistency checks and a unique key on instrument, interval, and candle timestamp. Historical-candle fetching from authenticated SmartAPI is intentionally a separate follow-up; this phase does not assume a broker session or create one.

## Safety

- The adapter is read-only and uses a fixed provider host to avoid arbitrary URL fetching.
- Payload limits are enforced after the HTTP response is read; keep this endpoint operator-only and review memory limits before running on a small instance.
- No API credentials belong in frontend environment variables or source control.
- The application remains PAPER-only. Imported prices are market-data inputs, not trade instructions or investment advice.
