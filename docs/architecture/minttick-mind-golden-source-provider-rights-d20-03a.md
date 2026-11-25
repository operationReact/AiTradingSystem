# Mintick Mind D20-03A — Provider rights and dataset coverage registry

**Status:** Database and read-only, default-deny contract only. No purchased licenses, history downloads, cloud resources, account access or order authorizations are asserted.

## Schema (Flyway V78)

- `golden_data_provider`: registered code, legal name and explicitly external versus synthetic-test classification.
- `golden_source_rights_receipt`: one source domain, one specific permitted use, terms digest, receipt verification/capture timestamps, exclusive expiry, status and recorded revocations. No receipts are seeded.
- `golden_source_coverage_evidence`: immutable dataset digest and per-listing/market interval/date-range record counts, availability times, source fingerprints and quality. No coverage is asserted during migration.

The use enum deliberately separates `RAW_RETENTION`, `MODEL_TRAINING`, `MODEL_INFERENCE`, `END_USER_DISPLAY` and `REDISTRIBUTION`. Never infer one from another. One successful Angel One historical candle API test does not mean any of these rights are granted.

## Eligibility semantics

`GoldenSourceRightsRepository.isPermitted(provider, domain, use, usageAt)` returns true only for an external provider, matching source and specific use, a verified *independently reviewed* receipt known by the time of actual data access, an effective unexpired term and no recorded prior revocation. A retained, older verified receipt is overridden by a later recorded revocation for the same source/use. Regrant after revocation requires a separate reviewed policy version, not an implicit automatic allow.

Importantly, `usageAt` means actual **dataset access time**, not a simulated historical trade date. A legally licensed dataset obtained in 2026 can support a valid point-in-time backtest for 2010, while the market event's first-publication date independently governs which features could have been known by that 2010 decision. The two clocks must never be confused.

`permitsRetentionAndTraining` requires **both** authorizations. Nothing in this repository is exposed through a new public or operator API.

## Integrity and limitations

- A `VERIFIED` coverage row must contain at least one expected record and have valid = expected. Incomplete datasets are explicitly labelled incomplete or quarantined.
- Historical market coverage must eventually use exchange trading sessions, listing episode validity, delistings and suspension-aware gap causes rather than simply counting requested days.
- The evidence digest and `verified_by` field are *references to upstream attestation*. They are not independently verified signatures or a substitute for signed exchange/provider contract checks. A future private operator workflow must verify source documents and hash/permissions before inserting receipts.
- Synthetic integration tests deliberately insert mock provider and receipt rows into ephemeral PostgreSQL. Those rows cannot authorize real production data, and production source rights tables remain empty until a reviewed acquisition workflow exists.
- Source rights are orthogonal to Golden Source per-model readiness (D20-01B) and stable listing identity (D20-02). A future trusted integration must bind database receipts, source dataset manifests and the 18-model feature contracts before actual ingestion or inference.

## Next release gate

Before any 20-year daily bar ingestion:

1. Independently validate an EOD source's history span, delisted stocks, raw-vs-adjusted actions, retention, training, derivation and end-user display rights.
2. Document vendor/source account, price, rate limits, allowed dates and rights receipt; write an operator-only verification path with audit.
3. Add a licensed provider-independent chunk interface, bounded retries, queue/checkpoints, quality validation and an immutable S3 manifest.
4. Prove a single listing and a small batch by year, including no-data and corporate-action test cases. Scale only after genuine coverage and rights checks.

**No market candles are populated by V78. This is an enablement and protection prerequisite, not a historical-data completion claim.**
