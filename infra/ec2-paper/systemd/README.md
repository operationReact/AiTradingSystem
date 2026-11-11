# MintTick owner-private history scheduler (not yet activated)

This is **an EC2 host-only systemd timer**, not a new customer endpoint or an independent SmartAPI implementation. The existing root-owned `run_owner_personal_history.sh` remains the only writer of the private Angel One `ONE_DAY` EBS archive.

## Why systemd instead of Spring `@Scheduled`

The current Java backend runs in a memory-capped Docker container and cannot access the root-only archive or broker credentials. Mixing privileged history jobs with customer HTTP traffic, or running a second Spring Boot worker, would increase operational complexity and risk. The OS already supports bounded runtimes and non-overlapping oneshot units.

## Policy

- Market **off-hours**: weekdays at 16:15, 17:15, 18:15, 19:15, 20:15 IST; weekends hourly from 09:15 through 20:15 IST.
- No missed-job catch-up after EC2 restarts; `Persistent=false`.
- Independent 22:00 IST EC2 stop remains in place; scheduler refuses starts at or after 21:00 IST.
- Existing collector's exclusive `flock`, private SQLite checkpoints, archive integrity checks, 403/429 global provider cooldown, five-equity/20-call caps and ten-second spacing remain authoritative.
- After **two zero-candle batches**, pause another 24 hours rather than repeatedly requesting unproductive recovery windows. After a pause, allow one batch; positive progress clears the streak.
- Only sanitized counters go to the journal; no broker tokens, OHLCV, credentials, stderr or raw broker responses.
- No LIVE order routing, public endpoint, customer data publication, or new data provider.

## Deployment — explicitly opt-in after validation

**Merging this code does not install or enable the timer on EC2.** First verify the existing private archive, host identity, running SSM agent, credential permissions, Angel One historical rights, and 22:00 IST stop schedule.

Review and securely copy these files onto the **existing** PAPER EC2 only:

- Root-owned `infra/ec2-paper/owner_history_scheduler.py` to `/opt/aitradingsystem-paper/owner_history_scheduler.py` mode `0600`.
- Root-owned `infra/ec2-paper/owner_history_progress.py` to `/opt/aitradingsystem-paper/owner_history_progress.py` mode `0600` (required alongside the scheduler; sanitizes and verifies year counts before every monitored run).
- Create `/opt/aitradingsystem-paper/monitor` as root-owned, mode `0755`, before updating Compose; the exporter atomically writes only `progress.json`, mode `0644`.
- Deploy the reviewed updated PAPER backend container and its **read-only summary mount**. Do not mount `/var/lib/minttick/owner-personal-angelone-history` in the API container.
- `infra/ec2-paper/systemd/minttick-owner-history.service` and `.timer` to `/etc/systemd/system/`, owner root, mode `0644`.

After exact SHA verification and `systemd-analyze verify`, explicitly enable the timer:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minttick-owner-history.timer
sudo systemctl list-timers --all minttick-owner-history.timer
```

To pause safely: `sudo systemctl disable --now minttick-owner-history.timer`. The archive is unchanged; a running batch is not forcefully cancelled.

Do **not** enable the legacy backend `OWNER_HISTORY_BACKFILL_ENABLED` simultaneously. Avoid `Restart=always`, duplicate market-data collectors, public ports, and any relaxation of broker limits.

**Data quality is a separate blocker**: the two most recent Angel One short-window recovery runs both returned zero new rows. Scheduling does not fix invalid historical token mappings, unsupported date windows, or sparse history; examine the private exact token/date/outcome ledger before enabling automatic high-cadence retries.

## Temporary owner app dashboard

After separate backend deployment, sign in with the designated Cognito owner account and open **Settings → Historical Data Monitor**. Flutter retrieves only the sanitized summary from `GET /api/customer/owner-history/v1/progress` (no-store); an exact Cognito owner-subject match is required. Without the private monitor export, configured owner subject, or a fresh snapshot, the API fails closed instead of displaying test candles. The Flutter page never publishes exchange OHLCV or broker secrets. It refreshes while visible and supports manual retry; dashboard data is not proof of 20-year completeness. Remove the Settings route, API, read-only mount, and exporter after archive certification is finished.
