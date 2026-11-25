# Worker 4 — fundamentals / corporate events source authorization and as-of evidence gate

Status: **RIGHTS_BLOCKED** for production retention, research reuse, training and redistribution until source-specific written permission is recorded. This is a data-producer checklist, **not** evidence that Upstox provides the endpoints below. Owner: FUNDAMENTALS_EVENTS (#997). Last reviewed: 2026-11-16.

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

## Operational diagnostic: admissibility vs coverage

Until a source entitlement and persistent receipt are independently verified, return `RIGHTS_BLOCKED` for production fundamentals/events availability, `UNKNOWN` for target and persisted counts, and no acceptance delta. A green CI check, sample key-ratio response, or this document is not a production data unit. Record source approval as a separately reviewed reference, never as a boolean inferred from successful authentication. Keep quote freshness and PAPER readiness outside this workstream.

### Next concrete actions

1. Audit actual authorized Upstox Analytics ratio endpoint/response fields against official docs and private entitlement **without logging data**; record whether point-in-time publication history exists.
2. Obtain explicit permitted-use/retention evidence for each proposed source; otherwise retain only safe operational metadata.
3. Wire a source-specific manifest query to persisted receipts **only after** the schema and rights are confirmed; coordinate with Workers 1/2/3/5 and Claude via #924/#997. Do not modify Mind consumers, generic candles, or Flutter.

## Rights-safe manifest query contract (2026-11-15)

This is a **diagnostic specification**, not a deployed query or observed receipt. Workers 1/2/3/5 may use it when an authorized production manifest is available. Do not read financial payload columns or secrets.

- Input filters: exact sourceId, authorizationReference, dataFamily (RATIO or CORPORATE_EVENT), canonicalListingEpisode, partitionStartInclusive, partitionEndExclusive, asOfCutoff and producerVersion. Reject missing or unapproved authorizationReference before touching payload storage.
- Read only permitted operational columns: sourceId, rightsDecision, datasetVersion, partitionKey, checkpointId, receiptId, recordedAt, publishedAt, availableAt, observedAt, verifiedUniqueUnits, persistedUniqueUnits, rejectedRightsUnits, rejectedTimeUnits and rejectedIdentityUnits. If any required column is absent, return SCHEMA_UNKNOWN, not zero.
- Partition receipts must be deduplicated by stable receiptId and unique source record/revision identity; retry attempts and repeated worker reports must not increase verified units. Never sum cumulative checkpoints as if they were independent increments.
- A receipt is eligible only when rightsDecision explicitly permits the requested durable use, the receipt is committed in the intended runtime, its source/partition/episode is valid, and all required as-of timestamps are <= asOfCutoff. Missing publishedAt/availableAt/observedAt where required => TIMESTAMP_UNKNOWN and ineligible.
- Return a structured diagnostic with sourceFamily, rightsStatus, manifestStatus, lastCommittedReceiptAt, distinctReceiptCount, verifiedUniqueUnits, persistedUniqueUnits, rejectedUnitsByReason, earliest/latest availableAt and evidenceRef. Unknown targets and unobserved receipts remain UNKNOWN; no fallback to API sample counts or GitHub PR counts.
- Never expose provider response bodies, ratio values, issuer filings, account tokens, authorization documents or proprietary payload checksums through public GitHub comments. Only sanitized receipt IDs/partitions if their disclosure is approved.

Acceptance remains **+0.0pp** until a permitted, independently verified persisted receipt qualifies under #1057. This contract does not authorize ingestion, training or redistribution.

## Worker 4 operational handoff: no entitlement, no inferred completeness (2026-11-16)

The next producer integration must distinguish three independent gates: `ENDPOINT_VERIFIED` (documented API shape), `USE_AUTHORIZED` (written permission for the particular use), and `PERSISTENCE_VERIFIED` (committed, deduplicated backend receipt). None may be inferred from the other. Until all three are independently evidenced for a data family, expose `RIGHTS_BLOCKED` or `UNKNOWN` with nullable counts, never `0` or `READY`. A receipt's canonical identity must include source record ID **and revision**, listing episode, data family, partition, and available-at boundary; duplicate retries do not qualify as additional unique records. Share only sanitized receipt references with #1012. This is a contract diagnostic, not a claim that any endpoint, entitlement, persisted table, or receipt exists.

## Temporal evidence ordering
Receipt eligibility additionally rejects `availableAt < publishedAt`, `observedAt < availableAt`, or `committedAt < observedAt` as `TEMPORAL_ORDER_INVALID`. These are offline diagnostic invariants, not authorization or persisted runtime proof.
