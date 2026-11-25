# Mintick Mind D20-05E — durable vendor-page request plan and global pacing (V82)

**Status:** dormant Java/PostgreSQL control plane and synthetic tests. Not a market-data connector, licensed archive, operating worker, live broker order, customer API, S3 deployment or scheduler. It does not actually fetch one candle.

## Why this exists

D20-05D safely normalizes a single already-obtained 2,000-day provider response into <=31-day independently verified quality windows. For 2,778 hypothetical full-20-year eligible current listings, four windows per listing suggest **11,112 source requests** at full history; the actual number must include historical, delisted, renamed listings and each actual listing date. A safe multi-day collector needs durable request planning, single-worker claims, provider-wide pacing, retry/cooldown and crash recovery before enabling source calls.

## Added contract

- V82 `golden_vendor_page_plan` records permanent historical listing UUID (V77), registered EXTERNAL provider (V78), `daily_bars`, `ONE_DAY`, inclusive period up to 2,000 dates, dataset revision and one immutable idempotent page-plan key. No `instrument_master`/today's broker-token backward mapping. The entire full-history period is allowed for a listing only if it lies within its registered listing episode.
- `golden_vendor_page_provider_gate` contains a single provider-wide `next_allowed_at` and configurable `min_spacing_seconds`. Default **10 seconds between leases**, deliberately conservative and **NOT** a claimed Angel One permitted quota. The future production worker needs explicit provider-contract/request-budget verification, and must enforce any stricter quotas beyond this minimal guard.
- `GoldenVendorPagePlanRepository.plan()` inserts only metadata and pacing row, creating no HTTP/API request, storage cost, AWS object, historical calendar or V78 rights. Repeated planning is idempotent.
- `claim()` returns at most **one lease** only if RAW_RETENTION rights are currently granted for the exact external provider and `daily_bars`. Empty/revoked rights make ZERO claims; a future worker must recheck immediately before the actual HTTP call and again before persistence/usage.
- Provider-wide gate row is locked in the same PostgreSQL transaction as the page claim. Per-page `FOR UPDATE SKIP LOCKED`/token lease fencing protects double-claims; max 5 attempts, 5-minute lease TTL; transient `retry()` activates provider-wide exponential cooldown, bounded at 30 minutes, and blocks stale lease-token updates. Both claim and retry acquire locks in **gate first → page second** order to avoid deadlocks.
- V82 `STAGED` is merely an intentionally future-ready metadata status requiring source-native SHA/object URI/version and source-evidence digest. Once staged, a database trigger forbids updates/deletes. **No method in this PR marks a page STAGED**, and a populated metadata row by itself is not proof of actual retained licensed bytes; independent physical readback and pinned S3 version are prerequisites.
- `progress()` reports only bounded count metadata. No UI or operational endpoint is exposed.

## Safety and correctness gates still open

1. **Licensing:** Angel One historically returned 4,955 daily RELIANCE-EQ candles from 2006 through 2026 using read-only probes, but persisted **zero real bars**. The owner must obtain written source rights for broad automated download, RAW_RETENTION, MODEL_TRAINING, MODEL_INFERENCE, END_USER_DISPLAY, redistribution where applicable. A successful personal broker login is NOT a valid commercial archive entitlement.
2. **Historical identities and calendars:** current cash catalogue is not a survivorship-safe twenty-year listed universe. Need V77 V81 independently reviewed listing episodes, corporate actions, NSE/BSE actual session closure and publication/first-availability evidence. Never invent historical availability timestamps from ingestion time.
3. **Storage:** ap-south-1 encrypted versioned immutable source-native archive and physical independent readback; D20-04B/C code has not provisioned an actual bucket. The new V82 ledger has no source body and never creates an archive.
4. **Staged conversion:** a future reviewed worker must use a genuinely permitted 2000-day provider connector, physically archive source bytes, then invoke D20-05D partitioner with verified data, independently check <=31-day fragments against V77/V81 and link the output to existing D20-03B/04A checkpoint and manifest IDs with exactly-once source-page ancestry. This PR does not wire source acquisition to that pipeline.
5. **All model inputs:** 18 Mind model catalogue entries depend on 24 data families, including fundamental filings, governance, corporate actions, historical index membership, derivatives, news/macro and point-in-time feature provenance. A daily candle archive alone leaves those models NOT READY.

## Evidence

The runner-local PostgreSQL `GoldenVendorPagePlanRepositoryIT` tests synthetic provider/rights/listing rows only, covering 2006–2026 pages, default-deny rights, idempotency, two-listing provider-wide pacing, crash lease recovery, stale fencing, provider cooldown, revocation, 2,000-day SQL bound, revision isolation, staged custody immutability and zero entitlement creation. No market-data I/O, new billable infrastructure, trading execution or Android app change is introduced.
