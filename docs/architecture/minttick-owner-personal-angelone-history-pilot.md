# MintTick owner-only Angel One historical daily research archive

## Confirmed scope

The MintTick owner clarified on 2026-11-10 that the present use is **one person's private Angel One account, personal trading analysis and personal backtesting**, not multi-tenant commercial redistribution. Angel One's official FAQ states SmartAPI historical data is free and available for NSE/BSE equity and derivatives. The official docs permit up to **2,000 calendar days** in a ONE_DAY request. No rights are inferred for customer display, redistribution, third-party access, commercial SaaS or public GitHub publication.

The previous four personally authenticated, fixed-EIP probes returned **4,955 structurally valid RELIANCE-EQ daily candles** from 2006-11-10 to 2026-11-09, without persisting raw OHLCV. That proved one stock's available API depth, not the whole current or historical market.

## D20-06B code-only owner-private collector

- `infra/ec2-paper/owner_personal_angelone_history.py` is not a Spring service, public website route or subscription. It composes with the existing SSM/private-static-IP owner authentication helper on the specific EC2 host, and uses the already registered private catalogue for **up to five NSE EQ symbols per run, chosen by persistent archival progress from the active catalogue**. The first pilot selected RELIANCE, TCS, INFY, HDFCBANK and ICICIBANK; subsequent runs advance to unfinished active NSE equities.
- Each actual symbol token comes from the active database catalogue at run time; it is not a fabricated 2006 identity. No matches means blocked. Permanent Golden Source V77 historical security identity and V81 exchange sessions are not assigned on the basis of a 2026 token.
- Exactly four consecutive `ONE_DAY` date ranges cover **2006-11-10 through 2026-11-10**; each query spans <=2000 days. Maximum 20 API calls in the initial pilot, at least **10 seconds** between consecutive historical API requests; provider HTTP 429 immediately stops without request storms.
- The collector requires **Linux root, a dedicated private 0700 root-owned directory, 0600 archive files and free disk**, and stores only validated OHLCV arrays with public instrument IDs in `/var/lib/minttick/owner-personal-angelone-history/pages/`. A root-private SQLite manifest tracks symbol/token, range, SHA-256, row count, first/last date; a rerun validates existing file hashes and skips completed windows. Writes are atomic with file and directory fsync, and a host-side exclusive file lock prevents concurrent runs.
- Authenticated JWT, PIN, API key, client code, TOTP seed and private credentials never enter the stored JSON, public GitHub logs, Flutter, or user-facing API. The host command must verify **all EBS volumes are encrypted** before any real run; the collector code alone does not inspect AWS EBS encryption.
- **Not licensed Golden Source:** outputs explicitly mark historical listing-identity continuity, actual exchange session completeness and source original publication/first-available times as UNVERIFIED. Personal raw research files are not promoted to commercial training, 18 Mind models, V82 `STAGED`, feature store, stock market display, or algorithmic order execution.
- The PR includes an **offline validation** workflow and a separate owner-only **one-file approval** execution workflow. PR checks and merge do not trigger AWS or broker downloads. Only a standalone commit modifying `infra/ec2-paper/requests/owner-personal-history-download.json` on `dev` may initiate one controlled pilot.
- The execution workflow verifies exact AWS account, EC2 host, running SSM readiness and the EBS root-volume configuration. It first attempts to independently read actual volume encryption through AWS. The existing narrow OIDC role presently lacks `ec2:DescribeVolumes`; if denied, a personal-only fallback requires EXACTLY ONE attached EBS volume and reviewed Terraform `root_block_device.encrypted=true` / `delete_on_termination=false`. That fallback verifies infrastructure configuration but **does not independently prove the live volume is encrypted**. No broker credentials or customer PII are persisted. It places only three **nonsecret reviewed scripts** into the existing private three-day-release S3 artifact bucket under an exact commit SHA and SHA-256, then invokes AWS SSM to download the checksum-verified archive, validate its fixed allowed members, install the signed code as root-owned private files and run the fixed-path personal collector. It never uses a dynamically supplied GitHub script/command or exposes actual OHLCV in logs.
- S3 is used **only for the ephemeral code delivery artifact**, not source candles. The actual private market-data archive remains on the existing encrypted EBS with root-only access. This workflow has not run yet at PR creation; a successful job is required before claiming retained data.

## First real retained pilot completed

The explicit owner-only execution on 2026-11-10 (GitHub Action job 129954571949) succeeded: 5 NSE stocks, 20 provider windows, **24,772 verified-shape daily candle rows persisted** on the owner's private EC2 EBS. Records: RELIANCE 4,955, TCS 4,954, INFY 4,955, HDFCBANK 4,954, ICICIBANK 4,954. No original issuer/listing continuity, exchange calendar completeness or adjustment evidence has yet been attested; do not promote to the Golden Source/model features.

## Subsequent NSE batch selection (D20-06C)

Instead of repeating the five fixed stocks, the collector now obtains the currently active NSE_CM `-EQ` catalogue (max 5,999 entries), rejects conflicting token/symbol aliases, and selects up to five **unfinished** stocks per run. Selection prioritizes any incomplete original five before alphabetical remaining catalogue symbols. The private SQLite page manifest determines completion by exact NSE token and each inclusive 2,000-day window; existing archived bytes are checksum-checked before being skipped. All new rows remain owner-only. Existing exact one-file workflow request still caps each run at five equities and twenty API requests. Replaying the workflow with the same request content is not enough to create a new GitHub push; each run requires separately authorized repeat execution. The ordinary workflow output allows aggregate rows/pages; ticker-specific sanitized log lines currently target only the first five, so subsequent ticker names may not be printed in GitHub logs.

This batch selection still only covers **today's active NSE equity catalogue**; historical delisted/renamed or BSE-only records require separately verified historical security masters and explicit provider-source handling. It neither trains nor deploys the 18 Mind models.

## Future expansion

After the pilot is stored, audited and the operational route approved, expand to additional **current NSE/BSE active cash equities** in bounded batches while respecting Angel One account-level rate limits, lockouts, disk space, and session expiry. Collect historical delisted listing episodes and additional data families separately from appropriately evidenced sources; today's catalogue alone is survivorship-biased. Historical EOD OHLCV is only one of 24 model data domains. Financials, dividends/splits, index membership, derivatives, disclosures and point-in-time evidence remain separate data workstreams.

Official sources:
- https://smartapi.angelone.in/docs
- https://smartapi.angelone.in/faq
- https://smartapi.angelone.in/smartapi/forum/topic/5671/asking-for-static-ip-for-getting-api-for-historical-data

## Status

The four past Angel One depth probes returned 4,955 bars but retained zero. PR-only tests do not fetch candles. A separate dev-only one-file `owner-personal-history-download.json` approval and successful SSM execution are required to change the retained count; on success inspect the job's sanitized `PERSONAL_ROWS_STORED_TOTAL` and `PERSONAL_PAGES_STORED_TOTAL` metrics. No trading orders or public source dataset sharing are enabled.
