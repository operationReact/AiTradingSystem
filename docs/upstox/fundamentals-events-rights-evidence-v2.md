# Upstox fundamentals and corporate events — rights and as-of acceptance v2

Owner: worker 4 FUNDAMENTALS_EVENTS. Broker-neutral producer evidence only; no Mind consumer, ingestion or trading changes.

## Observed versus unknown

| Domain | API existence | Authorized read | Historical depth | Retain | Train | Publish |
|---|---|---|---|---|---|---|
| Current key ratios | Existing adapter mentioned in #918; endpoint not independently verified here | UNKNOWN | UNKNOWN | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Historical issuer fundamentals/filings | UNKNOWN | UNKNOWN | UNKNOWN | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Corporate actions and issuer events | UNKNOWN | UNKNOWN | UNKNOWN | UNVERIFIED | UNVERIFIED | UNVERIFIED |

A successful Analytics token, HTTP 200 or sample ratio is not a license to persist, replay, train or redistribute. Default all unproven permissions to DENY. Do not insert provider values into canonical research storage without an explicit, purpose-specific entitlement whose effective dates and scope cover the operation.

## Versioned producer receipt contract

A future authorized adapter should emit a receipt with `schemaVersion`, `sourceProvider`, `sourceDataset`, `sourceRecordId`, `canonicalInstrumentId`, `listingEpisodeId`, `sourceChecksum`, `sourceRevision`, `datasetVersion`, `acquiredAt`, `publishedAt`, `availableAt`, `observedAt`, `entitlementProofRef`, `entitlementValidFrom`, `entitlementValidUntil`, `allowedPurposes`, and `failureReason`. Ratio observations additionally require `fiscalPeriodEnd`, `metricCode`, `unit` and a licensed observed value; corporate events require `eventType` and `eventEffectiveAt`. All timestamps must be timezone-aware. No value is invented when missing.

The public diagnostic receipt must omit proprietary values, tokens, financial account data, raw payloads and unapproved license text. A hash/reference is not proof of a permitted use by itself. Persisting even a raw source payload requires separately verified retention rights.

## Deterministic fail-closed acceptance matrix (future implementation tests)

1. No entitlement evidence → deny retain/train/publish, report RIGHTS_UNVERIFIED.
2. Read-only entitlement → allow only the documented transient read, deny retain/train/publish.
3. Retention allowed but training absent → retain only within licensed scope/TTL; training denied.
4. Expired or future-dated entitlement → deny operation even if historical API read succeeds.
5. Missing publishedAt or availableAt → do not promote observation to as-of-safe research.
6. availableAt after requested as-of instant → exclude observation; do not backdate availability from fiscalPeriodEnd or eventEffectiveAt.
7. Restatement/revision → preserve prior source revision and independent availability timestamps, never overwrite historical as-of view.
8. Listing episode unresolved → quarantine mapping, no canonical publish.
9. HTTP 429 or source failure → record RATE_LIMITED/SOURCE_ERROR, not a synthetic zero metric.
10. No observed source response → report NO_OBSERVATION and UNKNOWN counts, not zero completed partitions.

This document is a specification/test matrix, **not** executable tests, endpoint verification, licensed ingestion, runtime receipt evidence or production acceptance. The implementation gate remains blocked until provider-approved endpoint and operation-specific usage rights are verified.

Handoff: #924 canonical producer contract, #997 Claude consumer boundary, #1012 worker ledger. Workers 1/2 own candle/identity core, Worker 3 coverage, Worker 5 operations UI.
