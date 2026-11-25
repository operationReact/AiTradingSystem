# Phase 2 — Market-data foundation

This phase adds a protected, read-only instrument discovery path. It does not place broker orders and does not enable live trading.

## Instrument master

- `POST /api/market-data/instruments/sync` triggers the existing backend instrument catalogue import. The established importer performs bounded download, validation, deduplication, import auditing, and atomic catalogue publication.
- The download does not follow redirects and rejects non-200, empty, oversized, malformed, or excessive-row payloads.
- Instrument parsing and supported exchange/type filtering are centralized in the existing catalogue parser; the active catalogue is retained if a refresh is incomplete or invalid.
- Imports are versioned and audited. The active catalogue is only switched after the import passes validation.
- `GET /api/market-data/instruments?query=RELIANCE&exchange=NSE&page=0&size=20` searches the existing versioned catalogue; `GET /api/market-data/instruments/status` returns the current catalogue summary.
- Both endpoints are under `/api/**` and therefore require the existing operator API-key authentication.

## Persistence

The application already has a versioned `instrument_master` catalogue and historical `market_candle` schema (migration V8). This phase reuses those persistence paths instead of introducing duplicate instrument or candle tables. Authenticated SmartAPI historical-candle ingestion is already implemented through the existing resumable backfill flow (`POST /api/market-data/backfills`); this phase adds instrument catalogue access under the market-data API namespace.

## Safety

- The adapter is read-only and uses a fixed provider host to avoid arbitrary URL fetching.
- All catalogue imports use the existing download and parser guardrails. API endpoints remain operator-only.
- No API credentials belong in frontend environment variables or source control.
- The application remains PAPER-only. Imported prices are market-data inputs, not trade instructions or investment advice.
