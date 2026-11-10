# MintTick personal history: independent read-only archive audit (D20-06F)

**Scope:** owner's personal Angel One historical NSE ONE_DAY research files only. No broker calls, no AWS resources, no order execution, no customer data redistribution, no model promotion.

The first verified private five-equity pilot completed successfully on 2026-11-10 with **24,772 actually retained OHLCV daily candles in 20 pages**. The second batch attempt (job [129964417109](https://github.com/operationReact/AiTradingSystem/actions/runs/42854673729/job/129964417109)) **failed with a timeout** following the 22:00 IST host stop. Some already committed pages may survive; actual additional rows **must not** be counted until the root-private manifest and archive files are re-inspected.

PR #733 added per-window categorical quarantine and an account-wide 429 cooldown. PR #734 added a bounded full-file SHA256 manifest integrity audit **before any new fetch**, but the normal download mode could still log into Angel One after passing that check. D20-06F adds a **separate audit-only mode** so a reviewer can obtain only validated archive counts without an Angel One HTTP call.

## Fixed reviewed CLI

On the existing running **owner-controlled** EC2 host, after the reviewed source files have been securely installed, the two fixed modes are:

- `/opt/aitradingsystem-paper/run_owner_personal_history.sh audit-only` — perform **only** private SQLite+SHA256 source-archive verification and print bounded aggregate counts. No source tokens, raw candles or credentials in output.
- `/opt/aitradingsystem-paper/run_owner_personal_history.sh` — previously reviewed bounded five-equity collector, now also calling archive-integrity verification before API requests.

The wrapper permits only the literal `audit-only` or default `download`; arbitrary script arguments, user-supplied paths and commands are rejected. The code is **not automatically executed upon PR merge**, and this PR deliberately does not trigger EC2/S3/Angel One use. To execute the read-only audit, a separately reviewed SSM request or a permitted authenticated owner-host terminal session is required while EC2 is already running and the existing 22:00 IST stop remains enabled.

## Fail-closed controls

- Linux and uid 0 required in production; fixed `/var/lib/minttick/owner-personal-angelone-history` location, private root-owned directories and `0600` SQLite manifest/lock files, no symbolic links.
- Acquires a **nonblocking shared lock** against the collector's exclusive lock, returning `ACTIVE_COLLECTOR` when a download is in progress. No remote or database writes.
- Opens existing SQLite in `mode=ro` and sets `PRAGMA query_only=ON`; no table creation, archive migration, collector authentication, account egress check or market-data API call.
- Reuses PR #734's bounded streaming gzip SHA256 audit and manifest structure/date/row-count checks to reject missing, corrupt, modified or too-open saved files. Returns only `PERSONAL_ARCHIVE_VERIFIED_PAGES`, `PERSONAL_ARCHIVE_VERIFIED_ROWS`, `PERSONAL_ARCHIVE_API_CALLS: 0`, and `PERSONAL_ARCHIVE_AUDIT_STATUS: VERIFIED`.
- The manifest files are still owner **personal research only**. Integrity of bytes is **not** proof of authentic 2006 broker aliases, corporate action treatment, verified exchange trading sessions, historical first-availability, licensed redistribution or completeness for 18 Mind models.

## Acceptance and next run

Synthetic regression tests prove zero broker/API/catalogue calls, zero manifest writes, exact counts, tamper rejection, safe owner permissions, and rejection of concurrent writers. The PR is purely source/tests/documentation; no cloud infrastructure added. Once private EC2 is naturally available, run audit-only first and record the **actual cumulative stored pages/rows** before any further five-equity batch. If it cannot be verified, do not count an unknown amount from the timed-out second run.
