# Mintick Mind — D20-05A daily history strict validation contract

**Status:** implemented **offline Java validation only**, against synthetic data. No vendor feed, licensed 20-year history, historical exchange calendar, AWS deployment, new PostgreSQL migration, source token mapping or scheduled batch is introduced.

## Safety intent

A response of 200 OK, a calendar weekday, or a plausible stock price are **not** sufficient to establish 20-year history. The D20-03B coordinator correctly routes invalid records to quarantine, partial valid windows to INCOMPLETE and fully verified windows to VERIFIED. D20-05A adds a strict **daily source envelope** validator that can be explicitly supplied to that coordinator only after an independently reviewed provider and evidence source are present. There is no automatic dependency injection or production source connection here.

**Pilot source envelope (not general vendor-native CSV)**

UTF-8, LF only, exactly one row per historical session date sorted ascending, max 31 calendar days in a chunk. Exact header:

```csv
listing_id,exchange,session_date,open,high,low,close,volume,source_published_at,first_available_at,source_revision,price_basis
```

All 12 columns are mandatory. Exchange must be the matching historical **NSE** or **BSE** listing episode, not today's broker token. Prices are strictly positive finite decimals with bounded scale, OHLC must be internally consistent, and traded volume is a positive whole number. `price_basis` must be `RAW`: adjusted or total-return histories need separate evidence and different downstream publications. Corporate-action adjustments are never fabricated or silently retrofitted. `source_revision` must be a positive source version. Publication and first-availability timestamps are explicit UTC instants: `session_close <= source_published_at <= first_available_at <= inspection_now`.

This envelope is an **already normalized, adapter-supplied view**, not the original vendor file. Before production, a licensed source connector must preserve vendor-native bytes and map with separately audited lineage, including reference hashes and the source's actual notice/publication timestamps. Simply converting today's market bars into this format is not admissible 20-year historical evidence.

## Independent evidence requirements

1. `GoldenHistoricalListingEvidence` supplies historical `listing_id`, venue, listing interval and immutable identity-evidence digest. Request dates must lie inside exactly that episode. The adapter must reconcile source identifiers against V77's stable identity records, not silently alias an old token to a current issuer.
2. `GoldenHistoricalSessionEvidence` supplies the actual *verified* exchange/session date, TRADING versus CLOSED status, actual session close, source digest, and evidence verification timestamp. `UNKNOWN`, missing digest, future verification, or a close on the wrong India-local date quarantines the chunk; there is no "weekdays by default" fallback. Historical holiday, Muhurat, exchange suspension and special session proofs must be obtained from approved data. The synthetic tests include a Friday session, a closed Saturday and an explicitly attested Sunday special session.
3. Each TRADING session needs exactly one RAW daily bar to claim full coverage. A missing bar produces a precise list of missing session dates and an `INCOMPLETE` count. A closed day with a reported bar, duplicate, out-of-order row, wrong venue/listing, invalid OHLCV, or impossible as-of timestamp rejects/quarantines the entire chunk.
4. If a historically eligible security did not trade on an open exchange day, a future source-specific audited no-trade/suspension handling policy must distinguish zero-volume evidence from data gaps. **D20-05A does not assert empty missing bars are legitimate, and does not silently mark a no-trade session complete.**
5. The current code's absolute maximum is 31 calendar days and at most the upstream bounded byte payload; 20-year history must be broken into durable resumable segments. The existing V78 rights registry retains separate RAW_RETENTION, MODEL_TRAINING, MODEL_INFERENCE, END_USER_DISPLAY and REDISTRIBUTION grants. Neither this validator nor a synthetic fixture creates entitlements.

## Release acceptance before first real single-listing pilot

- Licensed historical provider and permissible rights are independently verified; actual depth/coverage, delisted listings and redistribution/training limits are documented.
- Historical session evidence is sourced and checked for every calendar date in the pilot chunk; no simulated/fake calendar allowed.
- V77 identity resolved to one reviewed listing episode; correct source-published, first-available timestamps and corporate-action revisions retained.
- Source-native raw bytes plus derived daily envelope are independently archived/versioned and linked via immutable content hashes. Approved ap-south-1 S3 storage and role access must be actively verified; D20-04B/C do not themselves provision AWS.
- Only then run a **manual one-listing one-chunk** licensed test and compare expected sessions, validated bars, gaps, raw-vs-adjusted lineage, archive hashes, rights, error/restart behavior and actual cost.
- This work is **not** evidence of 20 years populated, trained models ready, production model quality, live market data status or client APK changes. All market/trading paths remain untouched.

## Test evidence

`GoldenDailyBarValidatorTest` uses synthetic listing IDs, calendar digests and manually constructed prices; tests cover complete/missing/empty windows, special trading session, invalid/unverified historical calendars, wrong listing episodes, adjusted prices, future/preclose as-of leakage, duplicates and malformed/invalid candle fields. Tests do not verify a real NSE/BSE calendar, source licensing, S3 object durability or broker market data.

**Next:** D20-05B: independently attested persistent historical calendar/identity reference adapter and repeatable single-listing preflight/coverage report; after external licence and approved storage, a production source-native loader and manual pilot.
