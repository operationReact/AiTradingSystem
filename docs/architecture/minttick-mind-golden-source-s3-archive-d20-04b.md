# Mintick Mind — D20-04B secure S3 archive adapter (code only)

**Status:** AWS SDK code and mocked tests only. No bucket, IAM role or key has been provisioned, no AWS calls ran in tests, no historical provider adapter or collector is enabled, and the 20-year population has **not** begun.

## Additive implementation

- `GoldenS3ArchiveAdapter` implements D20-03B's `GoldenChunkArchive` and D20-04A's `GoldenObjectInspector` with **one pre-existing, pinned private bucket** in `ap-south-1`.
- For an explicit opt-in only, the adapter validates the S3 bucket region, 12-digit expected bucket owner, enabled object versioning, all four Public Access Block settings, and a pinned same-account ap-south-1 SSE-KMS default key. Failed validation blocks startup of the optional adapter, without silently falling back to an unencrypted or unversioned bucket.
- Raw source bytes are bounded to the existing 32-MiB coordinator chunk maximum and written under deterministic `golden-source/bronze/v1/<job>/<chunk>/<sha256>.bin` keys with SSE-KMS and a SHA-256 request checksum. Existing objects are reused only after independent pinned-version inspection.
- A `s3://...?...versionId=...` locator always identifies a specific non-empty S3 object version; unpinned/foreign-bucket/out-of-prefix paths are refused. For readback, `HeadObject` checks object version, size and KMS key; `GetObject` fetches the pinned version and computes **SHA-256 from actual returned bytes**, never treating an ETag or provider response as a checksum. Storage receipt is issued only after matching physical readback.
- `GoldenS3ArchiveConfig` loads the client with AWS's **default credentials chain (role-based deployment)** and hardpins `ap-south-1`; no broker credential, access key, frontend token or KMS secret is stored. The bean is disabled by default via `MINTTICK_GOLDEN_S3_ENABLED=false`. Missing expected owner, bucket or KMS ARN fails configuration checks when enabled.
- No changes to V77–V80 migrations, market/order paths, existing operational `market_candle`, live feeds, model readiness or user-facing Flutter screens.

## Deployment gate: DO NOT ENABLE until separately approved

1. Confirm an **actual licensed provider** permits RAW_RETENTION for every target source. Confirm legal review for MODEL_TRAINING, MODEL_INFERENCE, END_USER_DISPLAY and REDISTRIBUTION independently. Existing empty V78 registry means no real provider calls.
2. Independently provision/review a versioned private bucket in the intended AWS account `098859530205` / region `ap-south-1`, with SSE-KMS customer-managed key, complete S3 Public Access Block, TLS-only policy, scoped S3/KMS IAM role, audit logs, lifecycle/cost estimates, and retention/legal-delete plan. The adapter **never creates or pays for resources**.
3. Provide configuration via trusted private deployment environment only: `MINTTICK_GOLDEN_S3_ENABLED=true`, `MINTTICK_GOLDEN_S3_BUCKET`, `MINTTICK_GOLDEN_S3_EXPECTED_OWNER`, and `MINTTICK_GOLDEN_S3_KMS_KEY_ARN`. Do not enable on the small EC2 PAPER node until workload authorization, cost and permissions are approved.
4. Independently verify real `GetObject`/version-ID/KMS grants, tamper detection, data-cost policies, source revisions, permanent alias history and revocation/purge workflows. A revoked grant cannot be made harmless by merely denying subsequent reads when bytes already exist; legal deletion must be handled separately.
5. Integrate a licensed historical provider plus session/calendar-aware validator and manually approved worker. Do not infer a 20-year history from Angel One or from a green CI check. Pilot one eligible listing before scaling to the complete historical universe.

## Test scope

`GoldenS3ArchiveAdapterTest` uses Mockito `S3Client` only. It covers invalid owner/KMS/prefix, wrong region, suspended versioning, incomplete public access block, wrong KMS key, bounded source hash, version-pinned upload/readback, corrupted readback, and untrusted URIs. This establishes behavior of the application contract but **does not** test live AWS permissions, production bucket policies, data licensing, real historical datasets or S3 durability.

**Next:** D20-04C reviewed Terraform/IAM plan and dry-run policy tests (no deployment), then D20-05 a source-authorized single-listing pilot. No bulk download or LIVE order activation is authorized by this PR.
