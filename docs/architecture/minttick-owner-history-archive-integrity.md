# MintTick D20-06E — private Angel One archive integrity before continuation

Status: **offline JavaScript-free Python logic and synthetic tests only**. The owner-private historical download is real, but this PR itself does not make any broker request or assume EC2 is running after its 22:00 IST stop.

## Evidence and purpose

- [First successful personal-history batch](https://github.com/operationReact/AiTradingSystem/actions/runs/42851887972/job/129954571949): **20 private retained pages and 24,772 genuine daily OHLCV candles**, across RELIANCE-EQ, TCS-EQ, INFY-EQ, HDFCBANK-EQ, ICICIBANK-EQ, dated from 2006 to 2026.
- [Second five-stock batch](https://github.com/operationReact/AiTradingSystem/actions/runs/42854673729/job/129964417109) **timed out** after the scheduled 22:00 IST stop (final action failure exit code 2 at 2026-11-10 16:45 UTC). It did **not** provide a completed sanitized count. Actual pages saved before interruption are **unknown**, and cannot be inferred from requested API reads.
- An interrupted process may have committed a raw gzip file before committing its SQLite manifest, or left an incomplete temporary file. An interrupted job's GitHub failure does not necessarily mean zero pages were written. The next run should verify **all existing manifest entries**, not only the five symbols it intends to download.

## Added protection

`verify_archive_integrity(conn, root)` runs immediately after opening the root-private SQLite manifest, **before any Angel One login or source call**. It fails closed when a manifest contains unsupported venue/symbol/token/period, corrupted JSON, wrong source format/scope, more than 2,000 rows per 2,000-day window, row-count mismatch, forged listing/session completeness claims, tampered SHA256 filename/payload, missing gzip pages, symlinks, wrong file owner, unprivate file modes, oversized compressed file, decompression exceeding 768KiB, or inconsistent first/last candle dates.

The audit returns only count of **hash-verified, manifest-referenced pages and candles**, never any price values or account credentials. Unreferenced `.pending` writes are not counted as durable source data; the existing exclusive file lock and safe retry logic handle bounded interruption recovery. The separate `page_failure` and provider cooldown logic from [PR #733](https://github.com/operationReact/AiTradingSystem/pull/733) still protects against vendor rejection and repeated 429s.

This is an integrity check of the **private copied provider data**, not an independent NSE/BSE official historical-session completeness audit, corporate action adjustment or issuer identity verification. The 18 Mind models and commercial Golden Source remain unready until their separate rights, reference data, as-of timestamps and other source domains are available.

## Deployment and next checkpoint

A PR merge neither runs EC2 nor fetches source candles. The existing owner-private workflow's single-file dev request must be issued when host is already running and SSM Online, with no overlapping collector and well before nightly stop. On a completed run, report `PERSONAL_PAGES_STORED_TOTAL` and `PERSONAL_ROWS_STORED_TOTAL` **only after the full manifest audit succeeds**, and distinguish newly fetched from pre-existing pages. Keep the existing 5-stock/20-call, 10-second spacing and no-redistribution caps. No automatic EC2 starts or increased AWS capacity.
