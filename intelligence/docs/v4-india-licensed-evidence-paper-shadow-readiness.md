# V4-05 / V4-09 / V4-14 — Licensed Indian source evidence and PAPER/SHADOW preflight

## Meaning of owner approval

On November 4, 2026, the product owner instructed continued licensed-data implementation and expressed approval to pursue LIVE trading. Record this as intent to proceed with **readiness review**, not approval from the exchange, news publisher, broker, regulator or risk reviewers. It does not authorize an order or infrastructure deployment. Keep LIVE disabled until independently verified requirements are met and a separate reviewed activation is performed.

## License procurement / external evidence

These are **reference sources**, not agreements already signed by the application:

| Category | Official source | Exact agreement requirements to confirm |
| --- | --- | --- |
| NSE cash-equity historical/live market data | [NSE Data Sharing & Usage Policy](https://www.nseindia.com/static/market-data/nse-data-policy) and [NSE Data & Analytics Subscriptions](https://www.nseindia.com/static/nse-data-and-analytics/data-information-vending) | NSE/BSE five-minute history and intraday feed, permitted commercial research, derivatives excluded, permitted retention and derived-feature use, display and redistribution restrictions, fees and expiry |
| BSE commercial market and corporate data | [BSE Self Data Feed](https://marketdata.bseindia.com/) and [BSE Information Products](https://www.bseindia.com/downloads1/Information_Products_Pricing_Sheet.pdf) | Exact real-time/historical feed, BSE data terms, non-distribution and model feature generation, payment and contract duration |
| Indian financial news text and announcements | Publisher / authorized news vendor with rights chain | Article headline/body copyright, capture, NLP prompt and derivative-sentiment use, internal research versus commercial redistribution, deletion and retention; exchange price-data rights do not grant publisher text rights |
| Angel One broker access / algo framework | [Angel One SmartAPI regulations](https://smartapi.angelbroking.com/exchange-regulations) and [NSE retail algo procedures](https://www.nseindia.com/static/trade/platform-services-non-neat-decision-support-tools-algorithm-trading) | Angel One requires registered static IP for API order execution from April 1, 2026; confirm account, strategy, tagging, broker and exchange requirements that apply to the commercial provider and the operator |
| Market costs / risk | Broker agreements, exchange/tax circulars, fee book, contract notes | Actual STT, stamp duty, brokerage, exchange charges, GST, slippage, impact, market hours/corporate adjustments and timestamp/custody |

Do not commit signed contracts, copyrighted news or paid exchange feed data, broker keys, order tokens, account identifiers, secrets or private documents into GitHub. Store secured originals privately and keep only SHA-256 digests and non-secret HTTPS references in the intake. Public URLs and self-declared rights are not legal verification.

## Delivered software scope

The module at intelligence/src/aitrading_intelligence/india_live_readiness_evidence.py provides a research-only, network-free evidence intake and report.

1. LicenceEvidence records declared provider, agreement digest, valid date range and explicit permission categories for Indian news or market data. Even a complete declaration cannot set external independent verification true.
2. CustodyReceipt requires reference/digest evidence for news and market captures, Indian fees, broker registered static IP, applicable exchange algo compliance, kill-switch rehearsal and reconciliation.
3. PaperShadowSession records mode, synthetic-versus-runtime origin, source fingerprints, shared cohort/window, decision count and observed outcomes, simulated PAPER fills, and error/UNKNOWN/risk/freshness counts. SHADOW cannot place a simulated fill; neither session can contain real broker orders.
4. LiveIntent records only a dated owner request for readiness review; it has no order-authorizing or environment-changing effect.
5. IndiaEvidencePreflightReport reports missing documents and reasons for rejection. A complete self-asserted dossier can reach only EXTERNAL_REVIEW_REQUIRED, never independent license acceptance, PAPER/SHADOW acceptance or LIVE/capital authorization.

This is intentionally complementary to the existing modules india_market_provenance.py, paper_learning_soak.py and shadow_governance.py and to the Java risk and execution boundary. An operator writing RUNTIME_EXPORT into JSON does not prove an actual runtime evaluation.

## Offline usage

Provide a private JSON file conforming to IndiaEvidenceIntake; the default entry point evaluates only, does not connect to Angel One or Gemini and does not spend money. For example:

    cd intelligence
    python -m aitrading_intelligence.india_live_readiness_evidence /private/indian-evidence-dossier.json /private/new-readiness-report.json

The report is created only when absent using mode 0600 and prints only the fingerprint. A preview report generated from CI fixtures **is not real source or trading performance evidence**.

Regression:

    cd intelligence
    python -m pytest -q tests/test_india_live_readiness_evidence.py
    ruff check src tests

## Requirements before LIVE can be separately enabled

- Have counsel or a qualified reviewer verify executed commercial rights, relevant licensors, coverage of text/market use, retention, derived signals and expiry.
- Independently witness/preserve actual timely licensed market/news captures and prospective holdout registration and blinded labels. Use frozen PRs #373/#374/#376/#377 contracts; no future-label leakage or cherry-picked events.
- Run actual approved Gemini requests through Java; preserve request IDs, model, prompt/response fingerprints and timing without exposing provider credentials. Build real holdout performance comparisons with sufficient data and multiple-testing guards.
- Run real PAPER/SHADOW simultaneous soak on the same legally available, correctly costed decision cohort; verify actual simulation fills, source lineage, TCA, reconciliation, unresolved UNKNOWN handling, no stale data, kill switch/fallback and model pause/rollback. Require independent acceptance, not just a CI fixture.
- Confirm current Angel One static IP, registration/tagging, account/strategy requirements and exchange framework for the exact planned use; do not infer broker approval from the user's approval.
- Perform explicit new production risk release review with bounded maximum capital/day/orders, independent monitor, deterministic Java safety gates and manually approved enable step. This code has no enable operation.

## Known limits

All contract and runtime files are user supplied and **not externally authenticated** in this software. No authentic licensed India news/market data, actual Gemini request, real PAPER/SHADOW run, complete broker clearance or LIVE approval are claimed in this PR. The owner has approved pursuing LIVE, not waived compliance.

Flutter impact: intelligence-only, no new UI. Actual V4-09 and V4-11 Admin PAPER/SHADOW dashboards and Flutter web/Android/iOS runtime acceptance remain open. Passing Flutter policy CI is not runtime acceptance.
