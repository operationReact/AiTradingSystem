# MintTick expired F&O — disabled code staging

**Purpose:** place the already-reviewed, rights-gated F&O historical Python library on the existing fixed PAPER EC2, not enable it.

The exact host check on 2026-11-21 showed identity PASS, code NOT_INSTALLED, journal MISSING, snapshot MISSING, service STOPPED and retention rights UNVERIFIED ([AWS run](https://github.com/operationReact/AiTradingSystem/actions/runs/44887496905)).

## Package

The protected staging operation copies only these repository files:

- `infra/ec2-paper/fno_history_backfill.py` — restart-safe metadata queue and worker.
- `infra/ec2-paper/upstox_fno_backfill_adapters.py` — Upstox expired OHLCV/OI GET adapter and optional transactionally idempotent sink, **both default deny** pending independent retention-grant verification.
- `infra/ec2-paper/fno_history_progress_snapshot.py` — atomic metadata-only exporter.

Files are SHA256 verified before and after an atomic write and installed mode 0600 under `/opt/aitradingsystem-paper/infra/ec2-paper/` using the existing SSM role. Existing `.env` and `runtime.env` are inspected only for permissions; contents, credentials and market-data files are never read. Replaying the exact same package is safe and returns ALREADY_CURRENT.

**No new service is installed or started, no checkpoint database or progress file is created, and no downloaded or simulated candle is persisted on EC2. No code in this package authorizes trades.**

## Trigger

First merge the code PR after its offline tests. This does not call AWS. Then, in a separate PR with ONLY `infra/ec2-paper/requests/fno-history-code-stage.json`, use the exact `expected_request()` JSON object. Once merged to dev, the protected `MintTick F&O - stage disabled source on exact EC2` workflow verifies the exact AWS identity, role, fixed existing EC2 and SSM, ships the three reviewed files and prints only the allowlisted outcome (STAGED or ALREADY_CURRENT). If authorization/host checks fail, it does not stage code.

## Remaining gates

1. Obtain written Upstox permission for the intended **bulk archived expired NSE_FO** data scope, allowed storage duration, internal backtesting and any redistribution. API access with Upstox Plus is not itself proof of blanket archival rights.
2. Implement and review approved runtime rights attestation and scoped SSM Analytics Token getter. Do not substitute a hardcoded True verifier.
3. Select and audit actual futures/option contract discovery universe and date windows before planning.
4. Configure secure checkpoint and candle storage (if licensed) and exported progress snapshot; backend `FNO_HISTORY_PROGRESS_SNAPSHOT_PATH` must match the configured protected snapshot location.
5. Add operator-controlled service start/stop, shared rate-limiter and alerts. Verify two *real* consecutive committed checkpoints and restart recovery before scheduling bulk historical downloads.

Until those conditions are met, a successful stage workflow means **code installed, downloader inactive, dataset not populated**.
