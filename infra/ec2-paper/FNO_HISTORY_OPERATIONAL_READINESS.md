# F&O history operational readiness — existing PAPER EC2

This is a **zero-provider-GET** readiness check for the existing MintTick AWS host. It cannot start or store an F&O dataset. Real expired futures source access is already confirmed by Issue #1504, but Upstox Plus API entitlement does not establish unrestricted market-data retention or redistribution rights.

## Current pipeline components

- PR #1550: resumable, rights-gated SQLite metadata checkpoint queue and backfill worker.
- PR #1552: real Upstox expired NSE_FO historical GET provider and optional transactionally idempotent SQLite candle sink, both requiring a separately verified grant.
- PR #1553: backend customer-authenticated F&O progress endpoint and Flutter Historical Data Monitor section; no fictitious percentage if no real snapshot.
- This readiness patch: read-only GitHub OIDC → SSM check of the pre-existing PAPER EC2, plus offline proof that two planned fixture checkpoints survive process restart.

## How to run the exact-host readiness check

1. Merge the readiness code PR after all checks pass. This **does not** invoke AWS.
2. In a separate PR changing only `infra/ec2-paper/requests/fno-history-readiness.json`, commit the **exact** object emitted by `fno_history_readiness.expected_request()`. It is not an order, download, grant or storage approval.
3. Merge that one-file request to `dev` after CI. The protected `MintTick F&O history - exact-host zero-GET readiness` workflow first checks the single-file approval, then assumes the existing narrowly scoped OIDC role, verifies the exact AWS host and reads host status using SSM.
4. Record the allowlisted status receipt: identity, source-code presence, checkpoint journal, progress snapshot freshness, systemd service state and rights=UNVERIFIED.

The host inspection never accesses bearer tokens, reads candle values, makes an Upstox request, writes files, invokes systemctl start/stop or changes trade rights. Its expected results may legitimately include CODE_NOT_INSTALLED, MISSING_CHECKPOINT and UNVERIFIED rights. A successful GitHub workflow means **inspection completed**, not downloading enabled.

## Fixed host paths examined

- Runtime root: `/opt/aitradingsystem-paper`
- Candidate source: `infra/ec2-paper/fno_history_backfill.py` under runtime root, or `fno_history_backfill.py` directly under root
- Metadata journal: `fno-history/checkpoints.sqlite` under runtime root
- Progress snapshot: `fno-history/progress.json` under runtime root
- Optional systemd unit: `minttick-fno-history-backfill.service`

These are **proposed integration paths** for the future operator-controlled collector, not claims that the host contains them. The Spring Boot `FNO_HISTORY_PROGRESS_SNAPSHOT_PATH` must point to the same protected snapshot file only after authorized operational wiring.

## Activation conditions — still required

1. Receive and verify the applicable Upstox/market-data permission covering bulk expired NSE_FO candle archival, allowed instrument scope, retention period, internal backtesting and any redistribution. An active Plus plan and working Analytics Token alone cannot prove all of this.
2. Review and deploy an approved, time-limited grant verifier and managed token getter in the existing EC2 environment. Never use a placeholder `lambda _: True` for the production verifier or inject the bearer into Flutter.
3. Configure one operator-controlled, account-rate-limited downloader instance, restricted to approved instruments and intervals, with a start/stop and error recovery plan. Separate expired-future and expired-option discovery coverage. Prevent unchecked all-symbol universe sweeps.
4. Test two sequential, real provider-to-durable-sink checkpoint commits, restart recovery without duplicates and per-batch counts. Verify the source-derived OHLCV/OI against persisted rows.
5. Once the pipeline is authorized and running, adapt the progress contract to **truthfully** reflect RUNNING/PAUSED and verified retention state rather than always publishing PREPARATION_ONLY. Deploy the backend/Flutter build, configure protected snapshot path, and verify the authenticated screen.

No production rollout, EC2 instance creation, scheduled bulk download, market-data archive or broker order is authorized by this readiness work.
