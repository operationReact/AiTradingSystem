# Worker 4 — fundamentals / corporate events source authorization and as-of evidence gate

Status: **RIGHTS_BLOCKED** for production retention, research reuse, training and redistribution until source-specific written permission is recorded. This is a data-producer checklist, **not** evidence that Upstox provides the endpoints below. Owner: FUNDAMENTALS_EVENTS (#997). Last reviewed: 2026-11-15.

## Verified repository context and unresolved source assertions

- #918 describes a current key-ratios read-only adapter and a bounded private acceptance probe. It does **not** prove a complete financial-statement, issuer-filing, historical-ratio, or corporate-action API, nor persistent ingestion.
- #924 requires provenance, listing-episode identity, point-in-time availability and per-use rights. #1198 merged a historical corporate-event observed-at cutoff regression, **not** an authorization grant or production event receipt.
- Analytics-token authentication and an HTTP 200 are insufficient to establish persistence, historical replay, feature training or publication rights. Do not infer coverage from smoke tests.
- Upstox documentation starting points: https://upstox.com/developer/api-documentation/analytics-token/ and https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/ . The historical candle API is **not** a fundamentals/corporate-events permission statement.

## Source authorization matrix (UNKNOWN means fail closed)

| Data family | Endpoint/contract evidence | Historical availability | Retention rights | Research/training rights | Production gate |
| --- | --- | --- | --- | --- | --- |
| Current valuation/key ratios | Adapter described in #918; endpoint and permitted field list require direct verification | UNKNOWN | UNKNOWN | UNKNOWN | BLOCKED |
| Point-in-time financial statements | No verified Upstox endpoint in reviewed owner issues | UNKNOWN | UNKNOWN | UNKNOWN | BLOCKED |
| Issuer filings and publication timestamps | No verified Upstox endpoint in reviewed owner issues | UNKNOWN | UNKNOWN | UNKNOWN | BLOCKED |
| Corporate actions and historical revisions | As-of normalization regression merged in #1198; not a source entitlement | UNKNOWN | UNKNOWN | UNKNOWN | BLOCKED |

Before enabling any producer, attach a source-specific evidence record identifying: provider, exact documented endpoint and version, account entitlement and permitted market, permitted fields, historical date range, query quotas, acquisition method, terms/document version and reviewed date, permitted **transient display**, **durable storage**, **derived research**, **training**, **redistribution** and **expiry/deletion** independently, plus approval reference. Keep private agreements/credentials out of GitHub. Absent evidence for a use => DENY that use.

## Point-in-time normalization and rejection contract

A candidate fundamental ratio/event must carry sourceId, sourceRecordId, canonical instrument/listing episode, observedAt (first known by MintTick), publishedAt (issuer/publication time when verified), acquiredAt, availableAt (earliest justified usable time), source revision/version, source rights reference, quality status, and payload checksum/reference where legally permitted. No numerical field may be populated from an inferred value.

- Reject unknown instrument episode, unknown/contradictory timestamp provenance, unavailable rights, or data first observed after the as-of cutoff.
- For historical research, enforce **publishedAt <= cutoff**, **availableAt <= cutoff**, **observedAt <= cutoff** when each is required by the applicable source contract. Missing required timestamp => abstain; never backdate availability from an effective corporate-action date.
- Revisions must remain distinct from earlier publications; a later correction cannot rewrite an earlier as-of result.
- Store only rights-permitted metadata/checksums when payload retention is forbidden; do not persist raw financial values to manufacture coverage.
- A query reporting no authorized source is **UNAVAILABLE/RIGHTS_BLOCKED**, not zero financial ratios/events.

## Runtime acceptance receipt (schema requirements; no receipt claimed)

A real production receipt must identify a stable source authorization reference, source dataset/version, canonical listing episode, partition (instrument / event-or-ratio-family / time window), attempted/verified/persisted **unique** units, source freshness and as-of cutoff, rejection counts by rights/timestamp/identity, checkpoint or transaction reference, runtime environment and observed time. A receipt without verified persistence and permission is not an acceptance increment. Do not copy secrets or proprietary values into issue #1012.

### Next concrete actions

1. Audit actual authorized Upstox Analytics ratio endpoint/response fields against official docs and private entitlement **without logging data**; record whether point-in-time publication history exists.
2. Obtain explicit permitted-use/retention evidence for each proposed source; otherwise retain only safe operational metadata.
3. Wire a source-specific manifest query to persisted receipts **only after** the schema and rights are confirmed; coordinate with Workers 1/2/3/5 and Claude via #924/#997. Do not modify Mind consumers, generic candles, or Flutter.
