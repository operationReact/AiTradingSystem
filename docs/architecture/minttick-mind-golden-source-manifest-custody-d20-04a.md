# Mintick Mind D20-04A — Immutable Golden Source manifests (code-only)

**State:** dormant metadata foundation. No AWS S3 buckets, IAM, KMS, Glacier, source adapter, object reader or external model access is enabled. This PR does not certify any 20-year historical data.

## Purpose

A 20-year history is not a mutable table of today's broker tokens. A research decision must be reproducible against **one licensed, version-pinned, quality-verified, immutable snapshot**. D20-03B provides rights-gated, idempotent, durable chunk jobs and verified/incomplete source coverage. D20-04A adds the next custody link:

- **Flyway V80:** append-only `golden_dataset_object_manifest`. Each record pins one verified source chunk, content hash, durable-object version identifier, size, schema version, SHA-256 deterministic manifest digest, source evidence digest and publication time.
- **BRONZE** is the only layer presently publishable; future SILVER and GOLD entries require an explicit parent manifest. No transformation, compaction, materialization or training is implemented.
- A database trigger blocks in-place updates and deletes. Corrections require a new historical job/revision and a new manifest, not hidden replacement of old model inputs. Legally required object/metadata purges need an audited separate workflow.
- **`GoldenObjectInspector` SPI:** the approved future storage adapter must reread the versioned object and independently compute its actual checksum/byte size. A provider response or archive receipt alone cannot certify the source.
- **`GoldenDatasetManifestRepository.publishBronze`:** requires a complete, nonempty, VERIFIED D20-03B chunk; valid source publication timestamps; current verified `RAW_RETENTION` rights; independently inspected stable version, nonzero byte count and matching SHA-256. Rechecks rights before publishing, records content-addressed provenance, and rejects conflicting re-publication.
- **`findAuthorizedBronze`:** internal metadata read checks retention **and** the particular requested use at actual access time. An earlier training grant does not permit display or redistribution, and a recorded revocation denies new reads of an old manifest. Consumers must still verify the pinned object version/digest when physically reading bytes.

## Security and future dependencies

1. Acquire independently reviewed source-specific rights for acquisition/retention, internal training, inference, display and redistribution as applicable. Empty V78 rights registry means no production access.
2. Define and deploy secure encrypted, versioned, access-scoped S3 storage in **ap-south-1** only after a separately reviewed cost/IAM/KMS/retention plan. The interfaces are not proof that S3 exists.
3. Implement real `GoldenHistoricalDatasetProvider`, calendar-aware `GoldenDatasetChunkValidator`, `GoldenChunkArchive`, and `GoldenObjectInspector` adapters with verified fail-closed error paths, source quotas, checksum readback and object-version pinning.
4. Add immutable SILVER canonical raw/adjusted histories, historical identity and corporate-action revisions, then GOLD point-in-time features for the actual 18 model entries, all 43 Mintick Mind UI routes and 232 strategy prototypes. Each source has independent coverage and rights; missing required inputs remain BLOCKED/ABSTAIN.
5. Build explicit source revocation/object erasure, correction lineage, per-model immutable snapshot manifest and batch-access testing before model data promotion. None of this introduces broker order/capital authority.

## Tests

`GoldenDatasetManifestIT` uses synthetic provider receipts, synthetic confirmed chunks, and a fake object inspector against PostgreSQL/Flyway. Checks deny-without-rights before storage access; complete versus incomplete chunks; checksum mismatch; idempotent version-pinned publication; conflicting version blocked; distinct TRAIN/DISPLAY/REDISTRIBUTION access; post-publication revocation; database mutation trigger. These synthetic fixtures are not actual vendor licenses or valid external datasets.

**D20-04A does not download data, modify existing `market_candle`/V8 backfill, or deploy an S3 storage system.**
