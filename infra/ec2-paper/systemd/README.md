# MintTick owner-private history scheduler (not yet activated)

This is **an EC2 host-only systemd timer**, not a new customer endpoint or an independent SmartAPI implementation. The existing root-owned `run_owner_personal_history.sh` remains the only writer of the private Angel One `ONE_DAY` EBS archive.

## Why systemd instead of Spring `@Scheduled`

The current Java backend runs in a memory-capped Docker container and cannot access the root-only archive or broker credentials. Mixing privileged history jobs with customer HTTP traffic, or running a second Spring Boot worker, would increase operational complexity and risk. The OS already supports bounded runtimes and non-overlapping oneshot units.

## Policy

- Market **off-hours**: weekdays every two minutes from 16:00 through 20:58 IST; weekends every two minutes from 09:00 through 20:58 IST. These are **scheduler checks**: when the oneshot collector is already running, systemd never launches an overlapping invocation. The next eligible tick starts another bounded batch.
- No missed-job catch-up after EC2 restarts; `Persistent=false`.
- Independent 22:00 IST EC2 stop remains in place; scheduler refuses starts at or after 21:00 IST.
- Existing collector's exclusive `flock`, private SQLite checkpoints, archive integrity checks, 403/429 global provider cooldown, five-equity/20-call caps and ten-second spacing remain authoritative. A new root-only 30-second inter-batch checkpoint prevents restarting at an even-minute timer boundary immediately after a prior batch finishes.
- After **two batches that attempted work but produced zero candles**, pause another 24 hours rather than repeatedly requesting unproductive recovery windows. Count empty recovery windows even when no page was saved. After a pause, allow one batch; positive progress clears the streak.
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

## SSM-connected owner activation (reviewed release path)

The `ec2-paper-ssm-app-deploy.yml` approved one-file request now carries the
owner-history installer and source-verified metadata exporter inside the same
SHA256-verified ARM64 release archive. The root installer refuses missing
root-only Angel One scripts/private archive, validates EC2 IMDSv2 identity,
atomically prepares a first year-by-year metadata snapshot, and checks its
0644 permission **before** image deployment. After Java/PostgreSQL/Flutter
health succeeds, it enables the oneshot systemd timer and checks it is active.
This is **opt-in only**: a code merge alone does not trigger the SSM release;
only the exact reviewed `infra/ec2-paper/requests/ssm-release.json` one-file
push to `dev` can authorize this deployment. Manual Android release also
requires its separate reviewed single-file request.

The 22:00 IST nightly EC2 stop remains enabled; the timer runs automatic bounded batches every two minutes while EC2 is up and within the allowed window, so this does not promise 24x7
host uptime. The timer resumes scheduled slots only when EC2 is running,
and may publish PAUSED after repeated zero-candle batches. The dashboard
reports these outcomes rather than claiming continuous candle growth.

## Adaptive collection: no hourly backlog idle time

The two-minute calendar fires only during eligible hours, and a running
`Type=oneshot` service cannot overlap itself. Each invocation runs up to one
existing 20-call bounded Angel One batch. On success it commits archive and
scheduler checkpoints; the next eligible two-minute tick continues from the
next window. No rate-limit increases are granted. Provider 403/429 uses the
collector's account-wide persisted 60-minute cooldown, honored before next
provider request. After two attempted zero-candle batches, persist a 24-hour
pause and report PAUSED in the owner UI. No-work checks do not pretend to
retrieve candles. A separate persisted 30-second minimum gap protects the
boundary between short successive batches. The `ONE_DAY` candle interval
is unchanged and independent of the polling cadence.

**Do not modify EC2 night stop for this change.** If EC2 is stopped, no
scheduled collection occurs until it starts again.
