# Upstox fundamentals and corporate events — source/rights verification manifest

Owner: Worker 4 (FUNDAMENTALS_EVENTS). Base: `dev`. Scope: source authorization and point-in-time normalization metadata only; not Mintick Mind, execution, generic candles, or progress UI.

## Provider endpoint discovery (documentation, NOT runtime authorization)

Official Upstox SDK example catalog lists read-only fundamentals examples for company profile, balance sheet, cash flow, income statement, key ratios, shareholdings, corporate actions and competitors: https://github.com/upstox/upstox-nodejs/tree/master/examples/fundamentals

The Upstox Python SDK release notes also describe rollout of Fundamentals APIs: https://github.com/upstox/upstox-python/releases

**Do not interpret documentation or an Analytics token as entitlement or permission to retain, train on, or redistribute returned values.** Specific paths, response shapes, historical depth, availability dates and account entitlements require direct official documentation and authorized runtime verification before activation.

## Acceptance matrix (default DENY)

| Source family | Endpoint discovery | Authorized GET receipt | Retention grant | Training grant | Publication grant | Persisted runtime units |
|---|---|---|---|---|---|---|
| Key ratios | Documented family; exact route needs verification | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |
| Financial statements | Documented family; exact route needs verification | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |
| Shareholding | Documented family; exact route needs verification | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |
| Corporate actions | Documented family; exact route needs verification | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |

No source is approved for persisted ingestion, training, publication or corporate-action price adjustments based on this manifest.

## Required adapter receipt, once separately authorized

Record source identifier, exchange/listing identity + ISIN, provider endpoint family, source revision/checksum, observedAt UTC, source publishedAt UTC, source availableAt UTC, period end and reporting period, currency/units, rights grant reference and expiration, permitted operation, response status, request correlation ID (non-secret), persisted partition, verified unique unit count and target denominator. Reject missing identity, missing publication/availability timestamps, expired or ambiguous rights, and unknown values; do not backdate publication to reporting period end.

An HTTP 200 or a 5-candle/7-ratio smoke sample is **not** a persisted runtime receipt. A PR/CI success changes backend acceptance by **+0.0 percentage points** absent verified persisted receipts.

## Immediate operational next step

1. Obtain authoritative account-specific Upstox endpoint entitlements and written/contractual read, retention and derived-use permissions, without exposing SSM secrets.
2. On a separate explicitly authorized one-shot read, collect sanitized receipt metadata only; distinguish missing source coverage from permission failures.
3. Implement a gated adapter only for verified permitted operations, with point-in-time regression tests and zero fabricated values.
4. Coordinate with #924, #997, #1012 and Worker 5 for receipt display; leave Claude-owned Mintick Mind untouched.

This is a diagnostic rights manifest, not a live data ingest or permission grant.
