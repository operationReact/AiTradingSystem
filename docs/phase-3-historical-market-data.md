# Phase 3 — Historical OHLCV market data

Phase 3 adds explicit historical market-data retrieval with the active instrument catalogue, Angel One SmartAPI, PostgreSQL, and the dashboard.

## Provider contract

The adapter calls `POST /rest/secure/angelbroking/historical/v1/getCandleData` with `exchange`, `symboltoken`, `interval`, `fromdate`, and `todate`. The parser expects the documented six-value tuple `[timestamp, open, high, low, close, volume]`, requires timestamp text with an explicit offset, and validates numeric fields and OHLC consistency before persistence.

Official contract and per-interval request limits: [Angel One SmartAPI Historical API](https://smartapi.angelone.in/docs).

Supported maximum inclusive calendar-day windows per provider request:
- `ONE_MINUTE`: 30 days
- `THREE_MINUTE`: 60 days
- `FIVE_MINUTE` and `TEN_MINUTE`: 100 days
- `FIFTEEN_MINUTE` and `THIRTY_MINUTE`: 200 days
- `ONE_HOUR`: 400 days
- `ONE_DAY`: 2,000 days

The application applies a stricter default total range limit of 366 days and processes at most five provider chunks per invocation. Both settings are configurable. Long requests return `PARTIAL` and resume from a saved date checkpoint. Request times are market-local (Asia/Kolkata, 09:15 through 15:30); timestamps are saved as PostgreSQL `TIMESTAMPTZ` instants.

## Storage and integrity

- Migration `V8__historical_market_data.sql` adds `market_candle`, `candle_backfill_run`, and `candle_backfill_checkpoint`.
- Unique key `(exchange, symbol_token, interval, candle_time)` makes repeated downloads idempotent and preserves continuity when a new daily instrument catalogue assigns a new database ID.
- OHLC and volume database constraints complement parser validation.
- Candle rows preserve exchange, symbol, token, and catalogue-version snapshots so history remains interpretable if older instrument catalogue rows are pruned.
- Successful chunk writes and cursor advancement share one transaction. Failed chunks leave the cursor in place and get a failure checkpoint. Interrupted runs are marked `FAILED` or `PARTIAL` after restart and can be resumed.
- Historical retrieval is read-only and does not change the Paper Only / LIVE safety boundary.

## API

All endpoints require `X-API-Key`.

- `POST /api/market-data/backfills` — `{"instrumentId":123,"interval":"FIVE_MINUTE","fromDate":"2026-10-01","toDate":"2026-10-26"}`
- `POST /api/market-data/backfills/{id}/resume` — continue a partial or failed run.
- `GET /api/market-data/backfills?limit=10` — recent history.
- `GET /api/market-data/backfills/{id}` — run status and cursor.
- `GET /api/market-data/candles?instrumentId=123&interval=FIVE_MINUTE&fromDate=2026-10-01&toDate=2026-10-26&page=0&size=100` — query stored OHLCV.

## Configuration and validation

- `app.market-data.max-range-days` defaults to `366` (allowed 1–3,660).
- `app.market-data.max-chunks-per-run` defaults to `5` (allowed 1–10).
- Historical requests use conservative process-local one-second pacing and retry only after an explicit provider session-expiry response.

CI uses deterministic mocked provider responses, not a live Angel One account. After deployment, apply Flyway through V8, import the latest instrument master, connect the operator key, open **Market data**, select an active instrument and a small date range, and select **Fetch & store candles** to validate provider connectivity.
