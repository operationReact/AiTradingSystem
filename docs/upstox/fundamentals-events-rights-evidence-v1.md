# Upstox fundamentals and corporate events: evidence gate (v1)

Owner: Upstox Analytics Worker 4 (FUNDAMENTALS_EVENTS). Scope: read-only source metadata, not Mind execution.

## Source acceptance is not research permission

An authenticated Analytics token, a successful HTTP response, or a nonzero key-ratio sample **does not** prove authorization for persistent storage, historical replay, training, redistribution, or publication. Until a documented provider entitlement explicitly permits each operation, treat its right as **UNVERIFIED** and fail closed. Do not populate canonical research tables or emit training-ready fundamentals/events from an unverified right.

## Evidence checklist per source/domain/use

| Domain | Endpoint existence | Authorized runtime read | Historical depth | Retain | Train | Publish |
|---|---|---|---|---|---|---|
| Current key ratios | Adapter described in #918; endpoint contract must be confirmed against provider docs | Not independently verified in this document | UNKNOWN | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Historical fundamentals / issuer filings | UNVERIFIED | UNVERIFIED | UNKNOWN | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Corporate actions / issuer events | UNVERIFIED | UNVERIFIED | UNKNOWN | UNVERIFIED | UNVERIFIED | UNVERIFIED |

These are *evidence states*, not claims that the provider lacks any particular API. Verification requires a provider-approved endpoint, an observed sanitized source receipt, explicit entitlement text or approved license reference, and effective/expiry dates. Keep credential values, account identifiers and proprietary payloads out of GitHub.

## Point-in-time producer contract for future permitted data

- `sourceProvider`, `sourceDataset`, `sourceRecordId`, `canonicalInstrumentId`, `listingEpisodeId`, `datasetVersion`, `sourceChecksum`.
- `fiscalPeriodEnd` (ratios only), `eventEffectiveAt` (events only), `publishedAt`, `availableAt`, `acquiredAt` as timezone-aware instants; never infer publication from period end or event date.
- `value` and `unit` only for observed, licensed fields; preserve revisions/restatements as separate versions. No synthetic zero for missing observations.
- `readEntitlement`, `retainEntitlement`, `trainEntitlement`, `publishEntitlement` each independently evaluated with source, proof reference, expiry and purpose; missing/expired evidence denies use.
- Record `NO_ENDPOINT_EVIDENCE`, `NOT_AUTHORIZED`, `RIGHTS_UNVERIFIED`, `NO_OBSERVATION`, `RATE_LIMITED`, `SOURCE_ERROR` as distinct safe diagnostics. A source probe is not a persistent ingestion run.

## Validation and handoff

Before any adapter persists or exposes a value, require (1) approved rights for the exact operation, (2) verified listing-episode identity, (3) non-future `availableAt` for as-of queries, (4) observed provenance and source revision. Hand off versioned fixtures and rights manifest to #924/#997 only after authorization. Workers 1/2 own generic candles/identity; Worker 3 owns coverage; Worker 5 owns progress UI; Claude owns all Mind consumers.

Related issues: #918, #924, #997, #1012. This document makes no provider calls and authorizes no new collection.
