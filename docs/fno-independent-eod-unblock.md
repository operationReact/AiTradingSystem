# MintTick F&O: Independent December 2025 EOD reconciliation

**Status: software prerequisite only. Independent rights and source data are NOT obtained or verified.**

The owner-private Upstox NIFTY pilot has 321 duplicate contract/date observations in December 2025, of which 316 have differing OHLCV/OI. A fresh Upstox replay confirmed both timestamp families (00:00 and 09:15 IST). December remains quarantined, the other 11 months are structurally isolated for further research, and none of this authorizes paper/live trading or model backtests.

## Implemented

The standalone Python utility at infra/ec2-paper/fno_licensed_eod_reconcile.py performs an aggregate, read-only comparison against a locally supplied **separately licensed** NSE F&O UDiFF EOD file. No network traffic, market-data redistribution, SQL writes, cloud provisioning, new storage, or trading orders. It never marks historical backtests approved even when all observations match. If rights attestation is not separately reviewed and pinned by SHA-256, it fails closed.

## Required operator inputs

1. An actually licensed, permitted NSE F&O EOD CSV with fields BizDt, TckrSymb, FinInstrmTp, XpryDt, StrkPric, OptnTp, OpnPric, HghPric, LwPric, ClsPric, TtlTradgVol, OpnIntrst.
2. A private 23-row broker-to-exchange map CSV with fields contract_key,underlying,expiry,kind,strike, built from authenticated Upstox metadata and independently checked against the NSE contract master. Contract types: FUT, CE, PE. No guessed key parsing.
3. A separately approved rights attestation JSON with fields licence_reference, source, permitted_private_reconciliation, may_read_eod_on_host, may_retain_reference_data, may_redistribute, expires_on. Source name: NSE_DATA_AND_ANALYTICS_LICENSED_FO_EOD; private reconciliation and local read must be explicitly permitted, retention and redistribution set false. The digest of this approved JSON must be supplied via MINTTICK_REVIEWED_EOD_RIGHTS_SHA256. This file is not a substitute for an actual licence.

The authorized operator must ensure the files remain confidential on the existing private host and the current seven-day pilot TTL is enforced. Do not upload market-data files, instrument keys or licence documents to GitHub.

Example invocation (only after approval and licensed files exist):

    PYTHONDONTWRITEBYTECODE=1 python3 infra/ec2-paper/fno_licensed_eod_reconcile.py --cache /opt/aitradingsystem-paper/fno-history/private-research-cache.sqlite --catalogue /secure/contract-map.csv --reference /secure/licensed-nse-eod.csv --rights-attestation /secure/rights-approval.json

The utility emits aggregate counts of reference observations, missing reference data, exact all-six-field OHLCV/OI comparisons against midnight and 09:15 bars, and unmatched observations. It does not emit prices, keys or trade dates. It never deletes one source in favor of the other. A licence verification and a **separate reviewed release gate** remain mandatory.

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
