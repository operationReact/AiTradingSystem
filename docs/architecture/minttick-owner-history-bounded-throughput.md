# Owner-private historical research — bounded faster batches (D20-08)

## Status and activation
This proposed change only expands the source-level safe upper bound from **5 equities/20 ONE_DAY requests** to **20 equities/80 requests** per private run, retaining 10 seconds minimum spacing, a 900-second provider-work budget and exact per-run validation. It is not an automatic AWS execution. Keep the existing audit-only PR #743 separate; **first verify physical SHA256 archive totals** from the interrupted second batch before activating larger downloads.

The download request schema is changed to exact `max_equities: 20` and `max_provider_calls: 80`; the existing committed `owner-personal-history-download.json` remains a **legacy 5/20 trigger** and must not be re-run after this code change. A later separately reviewed, one-file `dev` request must explicitly change those two fields to 20/80, after successful audit and a trial batch. The audit-only request is unchanged at zero provider calls.

## Safety limits
- One existing root-private EC2, PAPER only; single exclusive archive lock and strict SHA256/SQLite preflight.
- Maximum 20 currently active NSE cash equities per invocation, with 4 windows of at most 2,000 days each: at most 80 historical ONE_DAY calls. No intraday/derivatives/customer data.
- At least 10 seconds between consecutive historical calls; single account-wide persistent 60-minute cooldown for recognized 403/429 throttling (merged PR #747 already protects higher-throughput operation).
- Runtime guard stops issuing requests after approximately 855 seconds; a provider request times out within 35 seconds, leaving room below the existing 20-minute GitHub job timeout. Already-committed pages remain on private encrypted EBS; next invocation resumes missing windows without overwriting verified pages.
- Stop launching new downloads at/after 21:20 IST to preserve existing 22:00 IST EC2 stop. Never start, prolong, resize or reconfigure AWS resources solely for backfill.
- Keep actual rows private; GitHub Actions prints only bounded aggregate page/row counts and categorical status. No order endpoints or data redistribution.

## Performance and limitations
At 20 equities per invocation, a fully successful single batch can advance up to four times as many symbols as the five-stock pilot, with no increase in instantaneous API rate. It is **not** proof that Angel One permits 2006 history for every current token; listing episodes, retired stocks, corporate actions, original source publication time and historical sessions remain unverified. Provider errors and cooldowns can substantially reduce throughput. Do not claim a research-ready 20-year universe or 18 Mintick Mind models from these private files.
