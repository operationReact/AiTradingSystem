# MintTick F&O — private historical bulk ingestion (first operational scope)

**First operational scope:** 12 recent completed NIFTY expiry months; one expired future plus up to 11 matched CE/PE strike pairs per expiry; `day` OHLCV+OI; about a 21-calendar-day window ending at contract expiry. This is an initial bounded cohort, **not every NSE_FO contract or a complete 20-year archive**.

## Owner authorization and data limits

The user approved immediate bulk downloading to their private MintTick AWS account on 21 November 2026. This authorizes operating their infrastructure and collecting data for **their internal single-user historical research/backtesting**. It **does not constitute Upstox or NSE granting a commercial redistribution, perpetual archive or multiuser non-display data license**. The private runtime records `provider_licence_verified=false`, no redistribution, no live orders, and automatically expires 28 November 2026.

The local SQLite history is a **time-bounded, private cache** with a daily seven-day expiry cleanup, file mode 0600 and directory mode 0700. No raw OHLCV/OI rows, bearer token, instrument keys or private prices are uploaded to GitHub, displayed in workflow receipts, copied to S3, or exposed via customer APIs. No order endpoints are present. Use of data outside this scope requires provider-specific permission review.

## Operations on the existing EC2

- Host `i-09c01b470fb39b85a` in ap-south-1, existing restricted OIDC/SSM route only. No new EC2/RDS/etc.
- A separately reviewed, exact single-file owner approval request triggers `MintTick F&O owner-private bulk ingestion - controlled start`.
- The workflow validates exact region/account/instance/tags, stages `infra/ec2-paper/fno_private_bulk_worker.py` with immutable SHA-256, writes a private owner-scope marker, plans three months and commits up to three real candle batches. It **verifies actual persisted row counts**, not just HTTP 200.
- Only when the smoke batch persisted rows, it starts one systemd service `minttick-fno-private-backfill.service` that extends the queue idempotently to 12 completed expiry months. It is restart-safe with SQLite checkpoint leases, idempotent receipts, slow request pacing (one provider GET at least every 2.5 seconds per session), 401/403 stop, 429/5xx retries, and a maximum of 600 GETs per worker run.
- A systemd timer `minttick-fno-private-purge.timer` runs a daily TTL cleanup. At owner-scope expiry, no additional fetch is authorized; all cached candles are cleared by the timer.
- Existing AWS EC2 stop-at-22:00-IST cost protection remains unchanged. Systemd is enabled and can resume on next permitted host startup. Neither paper nor live order rights are altered.

## Stop, inspect and resume

On the existing EC2 as an authorized operator:

```sh
sudo systemctl status minttick-fno-private-backfill.service
sudo systemctl stop minttick-fno-private-backfill.service
sudo systemctl disable minttick-fno-private-backfill.service
sudo systemctl status minttick-fno-private-purge.timer
sudo python3 /opt/aitradingsystem-paper/infra/ec2-paper/fno_private_bulk_worker.py status
```

The status command prints only total planned/complete jobs and private cached candle row count. The operational result is also available in the sanitized GitHub workflow run log. The existing customer Historical Data Monitor uses a separate preparatory snapshot schema and **does not automatically reflect this private active cache**. Do not claim Flutter production deployment or an exchange-wide completion percentage from this cohort.

## Next scope expansion and legal gate

After the first real persisted checkpoint proof, expand instrument coverage conservatively (other index contracts, stock F&O, all strikes, more intervals, older expiries), with accurate source-derived totals, verified expiry catalogs, disk limits and shared Upstox account quotas. Before permanent multiuser or public data access, obtain the relevant market-data retention/non-display/redistribution permission; keep the private temporary cache distinct from the platform's eventual licensed historical dataset.

Official docs: https://upstox.com/developer/api-documentation/get-expired-historical-candle-data/ and https://betanseapi.nseindia.com/static/market-data/nse-data-policy
