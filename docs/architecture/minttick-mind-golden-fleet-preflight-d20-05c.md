# Mintick Mind D20-05C — all-stock / all-model Golden Source fleet preflight

**Status:** Implemented offline inventory and requirements planner only. Zero market-data calls, downloads, S3 writes, IAM activation, real V77 listings inserted, backtest/model-ready claims or customer execution authority.

## User objective and verified sample evidence

- Target: 20 years daily market history from 2006-11-10 to 2026-11-10, as available from each listing's actual start, including renamed and delisted securities.
- Existing separate Angel One one-stock probe [PR #717](https://github.com/operationReact/AiTradingSystem/pull/717), [PR #718](https://github.com/operationReact/AiTradingSystem/pull/718): RELIANCE-EQ returned **4,955 daily candles**, from **2006-11-10** through **2026-11-09**, from four audited, bounded 2000-day SmartAPI windows. Real network sample, no retention/redistribution rights established, no bars persisted.
- The cash-equity catalogue previously had **2,778 active** eligible instruments, not 2,778 verified historical listing episodes. This number is an earlier observation, not an eternal inventory or a historical survivorship-safe universe. **Do not seed V77 by joining today's broker token backwards 20 years.**
- A flat simplified calculation for 2,778 eligible, full-history listings would be 2,778 × 4 = **11,112 source requests** when using 2000-day windows. Actual total varies by listing dates/rights/availability, historical delistings and requested intervals. Invoking the 31-day custody chunk as a *provider request* would multiply calls unnecessarily. Instead request at most 2000 days under an approved connector, validate source-native files, and split the resulting data into immutable <=31-day archived quality chunks. An independent resumable spool/source adapter will be needed to align with the existing D20-03B 31-day coordinator.

## Code

- `scripts/golden_source_fleet_preflight.py` consumes the **existing canonical** 24-source, 18-model, 43-screen, 20-family/232-template requirements JSON. Nothing is claimed ready from UI fixtures, catalogue entries or completed probe counts.
- Optionally accepts a **manually reviewed exact V77 listing-episode inventory export** as a file to count eligible listing periods from each real listing date to delisting-exclusive. It does not verify who supplied that JSON, does not promote it to an independently attested source, does not query broker tokens, and never emits a runnable download job. Missing or malformed inventory fails closed.
- Outputs per-domain consumer dependencies (required model, screen and strategy families), historical field counts and rights statuses, each model's mandatory data families and missing-verified-source state, and aggregated 2000-day vendor read requests vs 31-day immutable archive segments. Defaults to ZERO supplied/verified historical listings, ZERO authorized bulk downloads, ZERO ready models, explicit unavailable statuses.
- The planned first/last per-listing dates are research **scope estimates**, never point-in-time mapping of current symbols, trading-calendar evidence, permitted data, or guaranteed vendor source response.

Usage (offline, no account credentials):

```bash
python3 scripts/golden_source_fleet_preflight.py > /tmp/minttick-golden-readiness.json
# Optional after independent custody review: input JSON lists V77 listing IDs/episodes,
# exchange, listed_from, delisted_on, identity_evidence_digest, plus
# top-level schema minttick.golden.listing-episode-preflight.v1 and reference_digest.
python3 scripts/golden_source_fleet_preflight.py --inventory /path/to/reviewed-listing-episodes.json
```

## Real all-equity ingestion gates (NOT met by this PR)

1. Confirm in writing whether Angel One SmartAPI permits automated broad historical calls, **persistent internal archival**, commercial model training and inference, downstream display/redistribution. Recent official moderator states the user's market feed **must not be redistributed**: https://smartapi.angelone.in/smartapi/forum/topic/5712/can-i-build-a-platform-for-users-and-show-data-charts-fetched-from-my-account/2 ; general documentation advertises ONE_DAY 2000-day max, not bulk authorization or historical listing identity.
2. Obtain independent full-universe historical security master, listing birth/death, symbol/token validity and delisted records; import reviewed V77 evidence, never automatically equate current stock tokens to an older issuer.
3. Acquire verified NSE/BSE 20-year session calendars, Muhurat/exception, suspended/no-trade evidence, and corporate actions as-as-known, not only daily OHLCV. V81 is default empty.
4. Enable versioned S3 ap-south-1 bronze storage with narrow SSM/IAM/KMS and reviewed, separately approved cost and lifecycle. Keep raw-source bytes + per-session proof, invalidation/revision and rights restrictions. V80 and adapter code alone do not activate physical storage.
5. Build a rights-gated 2000-day *source acquisition* staged adapter and a separate <=31-day *quality/archive splitter*, bounded provider-wide QPS, circuit breaker, retries, resumable offset and manifest references. Publish only verified chunks, not optimistic HTTP 200 responses.
6. Progress one licensed historical listing → 50 → 500 → all verified eligible NSE/BSE episodes and historical delistings; publish per-year actual/expected trading-day counts, source hash, no-lookahead availability and completeness, no synthetic gap filling.
7. **All 18 models need more than daily OHLCV.** The exact 24 domain sources and requirements are in `docs/architecture/mintick-mind-golden-source-requirements.v1.json`: dated fundamentals, shareholder/promoter disclosures, dividends/splits, earnings/news, macro, historic NSE membership, breadth/VIX, FII/DII, derivatives chain and margins, historical broker costs, intraday, book depth, account-private portfolio and risk state, experiment/feature/version provenance. Acquire each via appropriately licensed independent official sources; do not force 20 years of tick/order-book data where unavailable or appropriate. If any mandatory inputs missing, models **ABSTAIN**, not fabricate.

**Critical limitation:** Real 2006–2026 data were read in previous *one-symbol, no-storage* depth probes, but this preflight creates no lasting daily dataset. User authorization to build software is not itself a data-provider licence or AWS cost approval. No new live broker order capability is introduced.
