# D20-06A: Production readiness audit before licensed bulk historical acquisition

Owner has authorized MintTick engineering to proceed with obtaining 2006–2026 market data for all eligible NSE/BSE equities and model dependencies.

**Important separation:** owner's permission to operate software is not itself a contractual source grant to retain, redistribute, use for commercial model training/inference or display data obtained from a third-party market-data service. The current Angel One API contract must be confirmed independently, including commercial rights and broad download quotas, before allowing an automated all-stock archiver. NSE officially offers licensed EOD/historical files and special display/usage terms.

### What this diagnostic does

The exact-instance GitHub Actions SSM workflow launches ONE read-only prerequisite inspection on the existing PAPER EC2 host in ap-south-1. It does not authenticate to Angel One, call getCandleData, inspect user data or price rows, create S3 resources or use broker orders.

The probe prints only bounded aggregate counts and categorical states from the live PostgreSQL schema:
- V77 `golden_listing_identity` verified historical listing episodes (not today's instrument master)
- V78 `golden_data_provider` and `golden_source_rights_receipt`, including presently unrevoked RAW_RETENTION and combined MODEL_TRAINING use
- V81 `golden_historical_exchange_session_evidence` independent calendar evidence count
- V82 `golden_vendor_page_plan` future source pages, `STAGED` page count
- D20-04A manifests and D20-03B validated 31-day durable chunks
- Schema absence returns `UNAVAILABLE`, never a false zero, and no legal contract or S3 physical controls are treated as verified by this probe.

The workflow checks **exact sole-file push** authorization, verified fixed-role AWS SSM identity and one already-running EC2, never starts EC2 or provisions resources. The job filters output to allowlisted count/status lines. PR creation/merge does NOT run EC2 inspection.

### Go/no-go for first real persistent licensed pilot

Confirm provider-specific written scope and rights (RAW_RETENTION, MODEL_TRAINING and later inference/display as required), vendor-wide rate policy, historical delisted listing aliases (V77), official session evidence (V81), timestamp/provenance limitations, and privately owned KMS encrypted versioned S3 bucket/role IAM with physical readback. Code from D20-05C/D20-05D/D20-05E prepares planning, partitioning and leases only; a separately reviewed source worker and archival checkpoint linking are still needed for real download. None of the first four 2006–2026 RELIANCE-EQ probe bodies were persisted.

No vendor commercial terms or V78 receipts may be fabricated to bypass these gates. The strict pilot remains scoped to real licensed sources and budgeted, approved AWS infrastructure.
