# MintTick F&O: Independent December 2025 EOD reconciliation

**Status: software prerequisite only. Independent rights and source data are NOT obtained or verified.**

The owner-private Upstox NIFTY pilot has 321 duplicate contract/date observations in December 2025, of which 316 have differing OHLCV/OI. A fresh Upstox replay confirmed both timestamp families (00:00 and 09:15 IST). December remains quarantined, the other 11 months are structurally isolated for further research, and none of this authorizes paper/live trading or model backtests.

## Implemented

The standalone Python utility at infra/ec2-paper/fno_licensed_eod_reconcile.py performs an aggregate, read-only comparison against a locally supplied **separately licensed** NSE F&O UDiFF EOD file. No network traffic, market-data redistribution, SQL writes, cloud provisioning, new storage, or trading orders. It never marks historical backtests approved even when all observations match. If rights attestation is not separately reviewed and pinned by SHA-256, it fails closed.

## Required operator inputs

1. An actually licensed, permitted NSE F&O EOD CSV with fields BizDt, TckrSymb, FinInstrmTp, XpryDt, StrkPric, OptnTp, OpnPric, HghPric, LwPric, ClsPric, TtlTradgVol, OpnIntrst.
2. A private 23-row broker-to-exchange map CSV with fields contract_key,underlying,expiry,kind,strike,lot_size, built from authenticated, original-date Upstox metadata and independently checked against the NSE contract master. Lot size must be a positive whole number and must match the contract's actual December 2025 specifications; current NIFTY lot sizes must not be silently substituted for historical ones. Contract types: FUT, CE, PE. No guessed key parsing.
3. A separately approved rights attestation JSON with fields licence_reference, source, permitted_private_reconciliation, may_read_eod_on_host, may_retain_reference_data, may_redistribute, expires_on. Source name: NSE_DATA_AND_ANALYTICS_LICENSED_FO_EOD; private reconciliation and local read must be explicitly permitted, retention and redistribution set false. The digest of this approved JSON must be supplied via MINTTICK_REVIEWED_EOD_RIGHTS_SHA256. This file is not a substitute for an actual licence.

The authorized operator must ensure the files remain confidential on the existing private host and the current seven-day pilot TTL is enforced. Do not upload market-data files, instrument keys or licence documents to GitHub.

Example invocation (only after approval and licensed files exist):

    PYTHONDONTWRITEBYTECODE=1 python3 infra/ec2-paper/fno_licensed_eod_reconcile.py --cache /opt/aitradingsystem-paper/fno-history/private-research-cache.sqlite --catalogue /secure/contract-map.csv --reference /secure/licensed-nse-eod.csv --rights-attestation /secure/rights-approval.json

The utility emits aggregate counts of reference observations, missing reference data, exact all-six-field OHLCV/OI comparisons against midnight and 09:15 bars, and unmatched observations. The official NSE UDiFF F&O `TtlTradgVol` counts **contracts**; the private Upstox historical volume counts **underlying quantity** in the verified 2025-12-15 sample. Before comparing, the utility now multiplies NSE contract volume by the individually authenticated historical `lot_size`. Both original values remain unchanged and the report exposes separate raw/lot-normalized volume matches. A source row lacking verifiable contract lot size fails closed. It does not emit prices, keys or trade dates. It never deletes one source in favor of the other. A licence verification and a **separate reviewed release gate** remain mandatory.

## External blockers

- Obtain an executed NSE Data & Analytics F&O historical EOD subscription / approved source and contract identity reference, including private non-display commercial research rights, file access, data versioning/corrections, retention and any customer display/reuse rights.
- Independently adjudicate the 316 price discrepancies, or keep December 2025 excluded.
- **Complete for the 276-contract pilot's missing weekday candidates only:** the 207 published NSE holiday slots were exchange-calendar classified in run #45040413969, and the three May/October isolated gaps were independently matched to explicit **zero NSE F&O traded-volume** rows in protected official UDiFF EOD run #45043188940. No synthetic candles or fills should be created. Independent OHLC/price integrity and a complete official session calendar remain unverified.
- Establish a separately licensed durable store; do not extend the seven-day private cache or 28 Nov 2026 owner permit.

## Verified operational evidence

- Source replay: https://github.com/operationReact/AiTradingSystem/actions/runs/44895912141
- Quarantine: https://github.com/operationReact/AiTradingSystem/actions/runs/44896239171
- Selected contract coverage: https://github.com/operationReact/AiTradingSystem/actions/runs/44898197023
- Three isolated source gaps: https://github.com/operationReact/AiTradingSystem/actions/runs/44898828068
- Tracking: https://github.com/operationReact/AiTradingSystem/issues/1584

## May/October 2026: bounded official NSE three-gap exchange evidence

The December 2025 adjudication tool above continues to require separately
reviewed licensed data and is **not** enabled by the following independent
read-only *presence/volume* probe.

`infra/ec2-paper/fno_private_three_gap_nse_eod.py` isolates the existing
three private 2026 gaps (May one, October two), independently obtains an
authenticated **four-GET maximum** Upstox catalogue crosswalk for the two
affected expiry months and requests up to **three exact official NSE FO UDiFF
daily archives** from `nsearchives.nseindia.com` in memory. It validates
NIFTY underlying, exact expiry/type/strike and the exchange's reported
`TtlTradgVol`. It neither creates candles nor presumes the broker's token
is the NSE instrument identifier.

A successfully downloaded official report supports three distinct
outcomes per missing contract/day:

- `exchangeTraded`: official matching instrument/day explicitly reports
  a strictly positive traded volume. A real Upstox candle gap needs licensed
  historical repair, not generated OHLC.
- `exchangeZeroVolume`: the matching official row explicitly reports zero
  volume. This is evidence for **no trade**, not an approval to substitute
  a settlement quote or close price for a transaction.
- `exchangeRowAbsent`: the NSE archive is available but contains no exact
  matching contract row. **Cannot prove no trade**; remain unresolved.
- `archiveUnavailable`: any NSE HTTP/access/transport/format issue.
  Remains **blocked**, not auto-classified as zero trading.

The exact `infra/ec2-paper/requests/fno-private-three-gap-nse-eod.json`
one-file trigger authorizes only the narrowly bounded owner-private
SSM check before 2026-11-28, using owner-permitted research data. All
contracts/dates/prices/strikes and raw files stay out of GitHub Action logs.
Downloaded archives are held only in memory and are not retained. No source
rights claim or `backtestingApproved` flag is changed, and no trade occurs.

Offline synthetic tests validate official ZIP fields, contract crosswalk,
positive/zero/absent volume distinctions, source access rejection, scope
limits and fail-closed malformed or duplicate rows. The synthetic fixtures
are **not** market verification. Only an actual protected host run can
demonstrate access and source evidence; its receipts must be reported
separately and transparently.

## December 15, 2025 — one-day original duplicate-candidate spotcheck

A narrowly scoped, read-only independent pilot at
`infra/ec2-paper/fno_dec_2025_nse_spotcheck.py` now compares both existing
Upstox daily timestamp families (00:00 and 09:15 IST) against **one official
NSE FO UDiFF EOD archive for 2025-12-15**, but **only** for the 23 NIFTY
contracts in the existing private source pilot. It uses exactly **two
authenticated Upstox metadata catalogue requests** to map contract keys to
actual expiry, CE/PE/FUT type and strike. It does **not** guess mapping from
contract keys, fetch a full month, retain or redistribute the archive, or
write to the private cache.

The aggregate-only receipt separately counts full six-field exact matches
(OHLC/volume/OI) for midnight and 09:15 provider candidates, exact close
price matches, missing NSE identities, identical and conflicting duplicate
pairs, and cases where neither source candidate matches. A missing NSE
archive or identity is **not** a price-validation success. A matched price
for one day is **not** evidence of original-first-known timestamps, licensed
historical usage or reliability for other dates.

The exact one-file deployment trigger
`infra/ec2-paper/requests/fno-dec-nse-one-day-spotcheck.json` is bounded
to one archive, one date, two broker GETs, 23 private contracts and the
current owner research scope expiring on **2026-11-28**. The GitHub Action
prints only aggregate results; no raw exchange rows, strikes, prices,
timestamps, keys or credentials. The original **316 December conflicts
remain quarantined** until independently authorized, full-cohort source
reconciliation and provenance verification. The full licensed reconciliation
tool above continues to require externally reviewed NSE source rights;
a one-day research probe is not a commercial rights attestation.

## December 15 detailed independent field evidence and volume-unit hypothesis

The successful exact-host private NSE sample receipts
[#45049747561](https://github.com/operationReact/AiTradingSystem/actions/runs/45049747561)
and [#45050188470](https://github.com/operationReact/AiTradingSystem/actions/runs/45050188470)
validated one NSE F&O UDiFF official trading day against the existing
23-contract December 2025 NIFTY selection, without storing EOD files or
exporting market data. There were **46 saved candle candidates** (23
timestamp duplicate pairs; **22 conflicting six-field pairs**) and 23/23
authenticated NSE instrument identities. For **00:00 IST** Upstox candidates,
all **23/23** matched NSE **O/H/L/C and open interest** exactly, but **0/23**
matched NSE raw `TtlTradgVol`. The **09:15 IST** family matched independently
on open 21, high 21, low 23, close 2, raw volume 0, OI 4.

The next bounded probe compares these *same* one-day saved volume readings
against **NSE reported traded contracts × authenticated historical Upstox
`lot_size`** for each of the 23 contracts, and also counts unscaled and
inverse-lot hypotheses. This is **not** permission to re-scale the cache,
substitute OHLC, or accept the entire month as repaired: data units and
point-in-time lot-size provenance need verification across all dates,
including historical lot-size changes. An original-time rights-approved
full-cohort reconciliation and 316 December conflicts remain blocked
until independent evidence is complete.

## Full December licensed matcher — volume unit correction (22 November 2026)

The only real-host independent NSE comparison so far was **December 15**.
The successful protected original-host receipt
[#45052676114](https://github.com/operationReact/AiTradingSystem/actions/runs/45052676114)
independently matched 23/23 original daily Upstox midnight candles against
official NSE O/H/L/C/open interest; the remaining volume field reconciled
for **all 23/23** only after multiplying official NSE contract count by the
**per-contract authenticated historical lot size**. This is not evidence
for the entire month.

The existing **offline licensed full-month matcher** now requires the
catalogue's `lot_size` field, rejects zero, fractional or invalid values,
and normalizes contract volume **in memory** before comparing all six
fields. It emits aggregate-only results and never chooses a preferred
source candidate or edits stored candles. It **continues to fail closed**
without a separately reviewed and externally pinned original rights
attestation and an independently supplied authorized NSE EOD reference.

The remaining original December 2025 **316 conflicting OHLCV/OI candle
pairs** are still quarantined. Full-month validation is **not performed**
by synthetic tests or one-day source receipts. Source-first-known evidence,
data entitlement, historical IV/Greeks/quotes and historical/PAPER/LIVE
E2E **0/43** remain unchanged. NSE's research-data policies distinguish
publicly accessible downloads from licensing for trading or profit use;
personal research use must not be mistaken for commercial redistribution
rights.
