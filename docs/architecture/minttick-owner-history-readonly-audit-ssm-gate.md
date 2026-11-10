# MintTick owner-private archive audit — separate SSM execution gate (D20-06G)

This is **not a download** and is **not automatically run by merging the workflow**. It prepares the repository to request one read-only SHA-256/SQLite archive audit for the owner's personal Angel One research files on the **already-running** EC2 `i-09c01b470fb39b85a` in `ap-south-1`.

## Preconditions

- Continue from latest remote **`dev`**, not `main`, preserving concurrent Mind/Markets/Broker merges.
- Run only 09:00–21:20 Asia/Kolkata; existing host must already be running, have SSM Online and retain the exact enabled nightly 22:00 stop. The gate **never starts, prolongs, resizes, or replaces EC2**.
- The independent archive audit from merged PR #736 is the only permitted data action. The guard rejects if another collector owns the exclusive lock, private files are missing or corrupted, or root-only permissions are invalid. It uses zero Angel One APIs.
- Only the **new dedicated** audit request path can invoke this workflow. The ordinary owner-personal-history download trigger remains unchanged and separate. Shared GitHub Actions concurrency serializes the requests.

## Activation AFTER the code-only PR is reviewed and merged

A separately approved **single-file dev commit** must create the file `infra/ec2-paper/requests/owner-personal-history-audit.json` containing exactly:

```json
{
  "application": "MintTick",
  "operation": "owner-personal-history-audit-only",
  "account": "098859530205",
  "region": "ap-south-1",
  "instance_id": "i-09c01b470fb39b85a",
  "max_provider_calls": 0,
  "read_only_archive": true,
  "private_ec2_only": true,
  "no_redistribution": true,
  "enable_trading": false
}
```

Changing this file in a multi-file commit, on another branch, with any altered fields, or outside the gated hours must fail before AWS API use. This request is not yet present. Do not generate or merge it while EC2 is stopped.

The SSM gate validates AWS account, pinned host, SSM availability, nightly schedule, immutable SHA/digest and exact release-package members, then installs only three reviewed, nonsecret scripts into their existing private code location. It invokes **only** `/opt/aitradingsystem-paper/run_owner_personal_history.sh audit-only`. Actual retained OHLCV remains on private EBS; transient S3 contains code only. No public raw candles, API keys or account tokens.

## Evidence required before resuming downloads

A successful GitHub audit job must show exactly `PERSONAL_ARCHIVE_VERIFIED_PAGES`, `PERSONAL_ARCHIVE_VERIFIED_ROWS`, `PERSONAL_ARCHIVE_API_CALLS: 0`, and `PERSONAL_ARCHIVE_AUDIT_STATUS: VERIFIED`. It prints no SSM raw stdout/stderr, actual candles or credentials. Failed/time-out audits must **not** revise confirmed totals. The latest verified baseline remains **20 pages / 24,772 daily candles across five stocks**, based on the original successful 2026-11-10 pilot. The timed-out second batch is still unconfirmed.

A verified physical checksum confirms saved-byte integrity **only**. It does not prove historical listings, corporate actions, point-in-time availability, complete NSE instrument coverage, redistribution rights, or training readiness for Mintick Mind models.

Once the archive total is independently verified and the existing instance remains running, a separately approved and rate-limited five-equity download batch can proceed through the original request workflow. Preserve the original min-10-second spacing, 429 quarantine, per-window handling, active-job lock, and 21:20 cutoff; do not start new downloads for the sake of auditing.
