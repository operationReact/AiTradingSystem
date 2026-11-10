# Mintick Mind Golden Source D20-05D — 2000-day acquisition to immutable 31-day quality partitions

**Status:** strict offline Java partition/validation code and synthetic tests only. This change does not authorize market-data retention, provision AWS storage, activate a provider or scheduler, download historical candles, run models or alter broker orders.

## Why this is necessary

- Verified Angel One owner-account probes in PRs #717–#718 returned **4,955 ONE_DAY RELIANCE-EQ candles** across four bounded requests, dated 2006-11-10 through 2026-11-09. These four read-only calls never persisted price/volume payloads, only count/date evidence. They do **not** prove a complete 20-year stock universe, rights to persist, or true 2006 identity continuity.
- D20-05C PR #720 mapped the 24 data domains, 18 Mind model catalogue entries, 43 routes and 232 strategy templates and separated up to **2,000-calendar-day provider windows** from D20-03B/D20-05A's **31-calendar-day quality/archive windows**.
- Without an explicit bridge, a naive implementation would call the provider again for **each** 31-day source chunk, massively multiplying request counts, retry/429 risk and provider load. The proper path is *one licensed vendor page* → *bounded 31-day canonical segments* → *independent listing/calendar/temporal validation* → *separately authorized immutable source-native and canonical archive*.

## Added D20-05D API

- `GoldenDailyAcquisitionPartitioner` is **not a Spring bean** and has no broker SDK, HTTP client, persistence calls, S3/IAM, endpoint, scheduler or command for source acquisition.
- Caller presents one bounded `Page` with **actual provider-native bytes and SHA-256**, independently established source-evidence digest, permanent V77 listing UUID, NSE/BSE, exact inclusive provider-window dates (<=2000 calendar dates) and already typed, ascending source rows. It must not map today's Angel One token onto a 20-year history without attested historical listing-alias proof.
- Each normalized `Row` includes a local exchange session date, raw unadjusted OHLCV, genuine source revision, **independently evidenced** `source_published_at` and `first_available_at`. Angel One's historical REST candles do **not** inherently supply these historical publication/first-availability timestamps. A production adapter cannot fill them from current ingestion time or session-close guesses, nor claim causally correct backtests until an independent point-in-time source is verified.
- Strict page checksum and list ordering/uniqueness, 32-MiB provider payload bound, null/bad exchange/out-of-window rejection, exactly ONE_DAY, RAW-only price mode and max 2,000 calendar dates. Violations reject the whole page rather than silently omitting records.
- Each <=31-day canonical CSV fragment reuses the previously merged `GoldenDailyBarValidator`, which requires V77 listing-episode evidence plus V81 verified historical session calendar for **every calendar date** (including CLOSED). Real exchange holidays, Muhurat sessions and suspension evidence cannot be inferred from weekdays.
- The output has stable deterministic chunk IDs scoped to source page SHA, job UUID, listing UUID, dates; canonical SHA-256 and linked original source SHA for custody, source-evidence digest, per-31-day missing session dates, plus aggregate valid/expected/missing counts. A missing candle remains **INCOMPLETE**. No page is marked complete merely because one vendor API call succeeded.
- A PagePlan contains **in-memory bytes** so a future approved pipeline can pass them to separately reviewed archival interfaces; it performs **zero uploads**. It does not itself establish that the vendor-native raw bytes have been legally archived or that the canonical chunks are rights-authorized to persist.

## Production integration sequence — deliberately NOT implemented here

1. Obtain Angel One/approved NSE/BSE source-specific written authorizations for broad historical downloads, RAW_RETENTION, MODEL_TRAINING, MODEL_INFERENCE, END_USER_DISPLAY, REDISTRIBUTION (where required). V78 empty/revoked => no provider download/storage/feature publication. A working login and 2000-day request-size limit do not grant rights.
2. Establish permanent historical listing episodes and time-bounded vendor aliases from authoritative evidence, including delisted securities; populate V77 only after reconciliation.
3. Obtain actual historical exchange-specific calendar and session close data, verified publication/availability timestamps, correct cash/adjusted price basis and historical corporate actions (V77/V81). If original published-at dates cannot be substantiated, label source with temporal limitations, and do **not** route it to point-in-time training.
4. Obtain approved production ap-south-1 versioned, KMS-encrypted object store, source-native immutable custody and verified 31-day canonical readbacks; existing D20-04B/C code has **not** provisioned a real bucket. Link raw SHA to all normalized partitions with source evidence.
5. Add reviewed, rights-gated and per-provider paced/staged 2000-day supplier adapter, durable spool, exactly-once checkpoint, lease fencing, provider-wide cooldown and bounded 429 retry. The existing D20-03B coordinator expects 31-day source fetches; **D20-05D does not wire it to the new page bridge** or claim to solve exactly-once provider acquisition. That requires a separately reviewed staging transaction.
6. Start with one authorized listing and independent actual-session completeness report, then expand 50→500→all eligible historic episodes under verified provider request quota and actual budget. Model training remains blocked until each mandatory input domain from the 24-domain matrix is independently authorized and available as-of.

## Tests and nonclaims

The new `GoldenDailyAcquisitionPartitionerTest` uses only synthetic prices, session calendars and source-native strings. It tests 2,000→65 chunks, source SHA custody, 31-day boundary allocation, deterministic chunk IDs, listing isolation, missing verified sessions, duplicate/out-of-order/out-of-range pages, invalid price modes, temporal leakage and immutable input copy. **No real provider, no historical 20-year data stored, no source licensing, no model readiness or APK changes.**
