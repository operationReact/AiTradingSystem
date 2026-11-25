# F&O real data download — bounded in-memory pilot

This workflow starts a **real Upstox historical candle download**, but **does not create or fill the permanent MintTick F&O historical database**. It addresses immediate proof of real futures + options source data while Upstox bulk archival rights are not yet confirmed.

- Existing exact PAPER EC2: `i-09c01b470fb39b85a`, ap-south-1.
- Upstox Analytics Token: read in memory from existing SSM `/minttick/upstox/analytics-token`; never logged or saved.
- Existing approved standard Upstox expired instrument API, custom verified HTTP User-Agent.
- Choose **at most 3 distinct completed monthly NIFTY expiries** from provider listing, and per monthly expiry select one futures contract plus one matched CE + PE strike if present. Fetch up to 9 one-week **daily** OHLCV/OI historical responses.
- At most **16 upstream GETs per job** (1 expiry listing + 3*(1 futures discovery + 1 options discovery + 3 daily candle GET)). 1.6 seconds between calls, bounded errors/timeouts, no HTTP redirects. Only validated counts leave the private host.
- Raw prices and Open Interest exist only in process memory and are discarded upon process exit. No SQLite, S3, Postgres, file writes, cron, systemd start, broker orders, or trading-rights changes.
- The source PR runs synthetic unit tests only. To invoke this once, a **separate single-file JSON owner-approved request PR** must merge to dev. Protected AWS GitHub OIDC/SSM job runs the sample with a sanitized fixed receipt.

**Why not run the durable bulk backfill immediately?** Upstox publicly documents this endpoint for research and backtesting, but the documentation does not establish unrestricted permanent bulk market-data archiving/redistribution rights. The already installed durable pipeline requires an independently verified retention scope; an in-memory download can proceed without granting or pretending those rights.

To activate durable download, establish written or otherwise authoritative rights for this private one-user use case, then configure reviewed provider-token getter, operational grant attestation, the expiry/date universe and authenticated checkpoint snapshot. Following two persisted real-provider checkpoints, enable one rate-limited background worker on the existing EC2. All rights-gated source files are already staged on host.

This pilot **is not a continuous scheduled downloader** and is not a substitute for the eventual 20-year (where available) F&O historical archive.
