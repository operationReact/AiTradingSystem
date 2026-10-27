# Phase 2 — Angel One instrument master and search

## Scope

This phase adds the versioned instrument catalogue used to resolve the exact exchange segment, trading symbol, token, lot size, tick size, expiry and strike for later market-data phases. It does not add quotes, candles, WebSocket streaming or order execution. All endpoints are under the authenticated `/api` namespace; live order placement remains disabled.

## Operator workflow

Configure the backend environment only. The defaults are conservative and refer to Angel One's published instrument-master JSON endpoint:

- `ANGELONE_INSTRUMENT_MASTER_URL` — optional override, restricted to the official HTTPS master host/path.
- `app.providers.angel-one.instrument-master-max-bytes` — maximum bytes downloaded, default 50 MiB.
- `app.providers.angel-one.instrument-master-max-rows` — maximum JSON rows parsed, default 500,000.
- `app.providers.angel-one.instrument-search-max-page-size` — maximum search page size, default 100.
- `app.providers.angel-one.instrument-master-max-rejected-percent` — maximum invalid-row percentage, default 5%.
- `app.providers.angel-one.instrument-master-min-retained-percent` — minimum instrument count compared with the active catalogue, default 80%.

After deploying the backend and applying Flyway migrations V4–V6, authenticate as the operator and explicitly trigger the first import:

```bash
curl -X POST "$API_BASE/api/instruments/import" \
  -H "X-API-Key: $APP_API_KEY"
```

Check the import response for `status=SUCCEEDED`, then inspect the active catalogue:

```bash
curl "$API_BASE/api/instruments/status" \
  -H "X-API-Key: $APP_API_KEY"
```

Search for instruments by symbol prefix/name and optional exchange/type filters:

```bash
curl --get "$API_BASE/api/instruments/search" \
  -H "X-API-Key: $APP_API_KEY" \
  --data-urlencode "q=RELI" \
  --data-urlencode "exchange=NSE" \
  --data-urlencode "page=0" \
  --data-urlencode "size=20"
```

Import history is available from `GET /api/instruments/imports?limit=10`; a specific run is `GET /api/instruments/imports/{id}`. Do not put the operator API key in frontend environment variables, a URL, or shell history for a shared machine.

## Import safety properties

1. The import attempt is recorded before downloading, so failures are visible in history.
2. Download is HTTPS-only, restricted to the official host/path, redirects are disabled, and the body is bounded by configured bytes and timeout.
3. Parsing is bounded by row count and validates token, symbol, segment, expiry, strike, lot size and tick size. Invalid rows are counted. If the percentage exceeds the configured limit, the import fails.
4. Exact duplicate identities are de-duplicated; conflicting duplicate payloads fail the import.
5. The first valid catalogue can be published only when at least one valid instrument exists. Once a catalogue exists, an import below the configured retained-row threshold is rejected.
6. Catalogue inserts, success history, and the active-version pointer are committed in one PostgreSQL transaction while holding a lock on the catalogue-state row. A failed transaction is followed by a separate failed-run update. A failed or interrupted candidate leaves the previous active version intact.
7. Search always filters to the active version. Results are deterministic and paginated; page size and query inputs are bounded. Old catalogue rows are pruned to the active plus two previous successful versions, while import-run history is retained.
8. Token values are stored as text. Provider strike and tick-size values published in paise are normalized into rupees; equity's `-1` strike sentinel becomes null. Expiry is parsed case-insensitively.

## Database migrations

- `V4__instrument_master.sql`: import audit, instrument rows and active-catalogue state.
- `V5__instrument_master_indexes.sql`: uniqueness and search indexes.
- `V6__versioned_instrument_identity.sql`: ensures the unique identity index is version-scoped, so the same token can occur in consecutive daily catalogues.

V1–V3 remain unchanged. Do not modify applied migrations.

## Validation

Automated checks in GitHub Actions run backend unit and PostgreSQL/Testcontainers integration tests plus the frontend production build. The parser tests cover valid equity/derivative examples, paise normalization, invalid segments/tokens/lots/expiry/strike, malformed JSON and row bounds. Integration tests cover authentication, successful import/search/status, failed incomplete refresh preserving the prior active version, duplicate handling and query/pagination validation.

The first live download should be run deliberately by an operator and its counts reviewed. The JSON master is a daily instrument reference, not a real-time price source and not a guarantee that an instrument is currently tradable or eligible for every order type.
