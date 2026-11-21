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
- Independently validate exchange holidays/special sessions and the three isolated weekday contract-day gaps; the Upstox recheck did not return those 3 bars.
- Establish a separately licensed durable store; do not extend the seven-day private cache or 28 Nov 2026 owner permit.

## Verified operational evidence

- Source replay: https://github.com/operationReact/AiTradingSystem/actions/runs/44895912141
- Quarantine: https://github.com/operationReact/AiTradingSystem/actions/runs/44896239171
- Selected contract coverage: https://github.com/operationReact/AiTradingSystem/actions/runs/44898197023
- Three isolated source gaps: https://github.com/operationReact/AiTradingSystem/actions/runs/44898828068
- Tracking: https://github.com/operationReact/AiTradingSystem/issues/1584
