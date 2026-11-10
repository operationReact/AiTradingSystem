# MintTick / Mintick Mind — Angel One first 2,000-day history depth probe

**Scope:** One explicitly authorized, read-only, no-storage query to Angel One SmartAPI, on existing registered-static-IP PAPER EC2, checking RELIANCE-EQ with current NSE token 2885 for **10 Nov 2006–1 May 2012** (2,000 inclusive calendar dates). This **does not** grant source retention, training, inference, display or redistribution rights. It does not trigger Golden Source backfill or modify user-facing candles.

## Why

The vendor documents `ONE_DAY` max request range of 2,000 days; it does **not** promise that an individual symbol has 2006 data. For a real 20-year dataset, the data's first/last available dates, year coverage, missing sessions and source rights matter more than an accepted request range. Current token 2885 is **not** proof of a historical listing episode; therefore this query is an availability sample only and cannot publish a V81 calendar record or a D20 immutable BRONZE manifest.

## Safe execution

1. Code-only PR adds `infra/ec2-paper/probe_angelone_2006_depth.py` and path-scoped workflow/tests; no request file is included in that PR. The existing authentication helper `probe_owner_angelone_auth.py` supplies the already reviewed SSM parameter retrieval, registered outbound IP/interface validation, TOTP, Angel One login and owner profile verification.
2. PR must pass its exact-head offline test gate and other required repository checks, be mergeable against current latest `dev`, and be merged **only into dev**. This addition itself makes zero AWS or vendor requests.
3. To actually authorize **one** real provider call, separately commit exactly `infra/ec2-paper/requests/angelone-2006-depth.json` on `dev` with exact allowed fields. A push trigger with that one-file SHA is the only production AWS path. It never deploys the backend.
4. The GitHub job assumes the existing narrow EC2 SSM deployment role and validates existing running instance, account, region, identity and tags. SSM streams the source script via base64 into Python; all credentials and provider OHLCV stay **in EC2 process memory**. It uses exactly one REST `getCandleData` call at `ONE_DAY` plus one login and profile request, no retries/other instruments/order endpoints. 
5. A bounded 256-KiB response is validated in memory for date range, timestamp timezone, duplicates, OHLC and volume. GitHub sees only predefined categorical results, count, earliest/latest date and per-year counts; no price, volume, account code, broker token, JWT, TOTP, provider raw response or credentials.
6. Any empty, malformed, partial, throttled, blocked, unauthorized, future/out-of-window or inconsistent source response is shown as **not verified** and must not be transformed into complete history. A `NONEMPTY` result proves **some bars exist**, not that every trading session from 2006–2012 exists. Actual calendar and corporate action quality still require D20-05 rights-backed independent evidence.

## Future windows (not automatically scheduled)

- Phase 1: 2006-11-10–2012-05-01 inclusive (2000 calendar dates)
- Phase 2: 2012-05-02–2017-10-22
- Phase 3: 2017-10-23–2023-04-14
- Phase 4: 2023-04-15–2026-11-10 (partial 2000-day window)

For each next step, **first examine actual earliest/latest year coverage and any provider limits**. Do not trigger another window automatically, and do not store/redistribute data before permissions are verified. A provider historical API success is not a data-reuse license, nor does a current ticker guarantee delisted/past identities.

**No new AWS resources, IAM roles, database writes, synthetic candles, model promotion or broker orders** are introduced.


## Actual first-window evidence (2026-11-10, real owner app SmartAPI)

- Exactly one live read-only SSM provider request was triggered by the one-file `dev` commit `62256a310f3322b3faf03ef659b220641bb31608`, using PR #717's guarded implementation, with the running exact EC2 host `i-09c01b470fb39b85a`.
- GitHub Actions SSM result job **129870285287** completed **successfully**; sanitized machine response: `DEPTH_STATUS: NONEMPTY`, `DEPTH_VALIDATED_ROWS: 1356`, `DEPTH_FIRST_CANDLE: 2006-11-10`, `DEPTH_LAST_CANDLE: 2012-04-30`.
- Returned daily candle counts by calendar year, **partial boundary years**: 2006=35, 2007=249, 2008=246, 2009=243, 2010=252, 2011=247, 2012=84. These counts reflect Angel One's response only; **no independent historical exchange-session calendar completeness or corporate-action reconciliation was performed**.
- Angel One served some daily RELIANCE-EQ candles from 2006, which was the original technical uncertainty; neither current symbol token continuity nor contractual retention/training/display rights are established.
- **No actual price data was persisted**. The probe returned only status, bounded count and dates. The operational Golden Source V78 rights registry and V80 object archive were not populated by this test.

## Sequential bounded depth checks: no automatic bulk download

The continuation PR allows only explicitly committed phases 2, 3 and 4. An exact one-file push to the existing request path triggers ONE provider request per phase on the existing EC2 role, validating date ranges, rights-independent read-only status, and sanitized counts. No provider archive/storage activation or hidden repeat polling. Phase 4 is a shorter, 1,306-day window through 2026-11-10. Each stage must complete before authorization of the next; an absent early result is not backfilled with fabricated bars.
