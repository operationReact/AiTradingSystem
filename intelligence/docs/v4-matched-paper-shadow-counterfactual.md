# V4-09 — Matched multi-model PAPER / SHADOW counterfactual ledger

## Purpose

The V4 plan calls for simultaneous champion / challenger decisions on the **same** market/news input, an immutable comparison of actions and hypothetical after-cost outcomes, and explicit no-automatic-promotion governance. PR #379 already provides a *documentary licensing and soak preflight*, not a model comparator. This increment implements the missing **offline cohort comparator**.

This PR is independent of paid data subscriptions, Angel One API keys, publisher licenses, registered static-IP setup and production deployment. The owner confirmed on 2026-11-04 that the static IP is already configured and wants **implementation first; remaining configuration later**. Do not represent that statement as independently audited broker or exchange compliance, and do not attempt an order or paid subscription.

## Contract

Module: `intelligence/src/aitrading_intelligence/matched_paper_shadow_counterfactual.py`

Contract version: `v4-matched-paper-shadow-counterfactual.v1`

1. A frozen `PaperShadowEnrollment` declares exactly one PAPER champion and at least one SHADOW challenger with registry fingerprints, before the first observed decision. It also fixes target specification, assumed round-trip costs, minimum model ENTER counts and matched sample size. Registration timestamp remains self-declared rather than externally attested.
2. Every `MatchedMarketObservation` carries source/news and market fingerprints; all candidate models must have predicted on the **identical predecision input fingerprint** at or after the market/news became available and no later than the decision timestamp.
3. The cohort must contain every declared model for each observation, without duplicates, changes to model registry identity, late predictions, or future outcomes being available at the time of the decision. This is internal consistency, *not* proof of authentic provider timestamps.
4. Observed forward market return is provided only to the **offline evaluator after label availability**. It is never a field of `MatchedModelDecision`. The evaluator does not query models or submit orders.
5. Research metrics: per-model action coverage, ENTER/WAIT/ABSTAIN totals, hypothetical *gross* forward return less the predeclared round-trip basis-point cost for hypothetical ENTERs, mean hypothetical net return and maximum drawdown of the cumulative hypothetical return sequence. These are simplified *counterfactual* numbers, **not** executable fill prices, broker P&L, market impact, or an estimate of economic alpha.
6. The report includes source hash identity, per-case disagreement counts and immutable report/row fingerprints. Insufficient sample/ENTER support is `INCONCLUSIVE`. `CI_SYNTHETIC` is always inconclusive, even for perfectly predictive fixtures.
7. The report **always** has `actual_paper_orders_submitted=false`, `actual_shadow_orders_submitted=false`, `actual_paper_fills_verified=false`, `original_market_data_custody_verified=false`, `genuine_live_runtime_soak_verified=false`, `model_promotion_allowed=false`, and `can_authorize_capital=false`.

## Run (offline; no provider or broker networking)

Freeze the model enrollment and source cohort as private JSON. The operator should collect all candidate signals from existing Python/Java research exports with real predecision timestamps **before** obtaining a future label. Once label availability and acquisition rights are established, evaluate:

```bash
cd intelligence
python -m aitrading_intelligence.matched_paper_shadow_counterfactual \
  /private/frozen-paper-shadow-enrollment.json \
  /private/matched-model-market-observations.json \
  /private/new-counterfactual-report.json \
  --evaluated-at 2026-11-04T16:00:00+05:30 \
  --capture-origin CI_SYNTHETIC
```

The CLI creates only a new output with private 0600 permissions and prints the content fingerprint. It does not perform data acquisition, subscription, inference, paper order submission, brokerage integration or LIVE toggling.

Regression:

```bash
cd intelligence
python -m pytest -q tests/test_matched_paper_shadow_counterfactual.py
ruff check src tests
```

## Gaps deliberately kept open

- **PAPER runtime:** Java PAPER order intentions, simulated fills, order IDs, TCA and reconciliation must be bound to an actual persisted runtime session. The static hypothetical comparison is not that runtime ledger.
- **SHADOW runtime:** continuously enroll/quarantine challengers, capture their decisions *at the same real market decision*, and persist disagreement and outcomes. A user-supplied `RUNTIME_EXPORT` string cannot attest continuous uptime or independent source custody.
- **Authentic source/licensing:** obtain permitted data and news content for commercial research, retain externally witnessed acquisitions and actual Indian fee book before production use.
- **Governance:** Java (not Python) remains sole broker/risk/capital authority. Need independently accepted PAPER/SHADOW soak, kill-switch, reconciliation, eligible model governance and explicit production release review before LIVE. User approval to finish development is not a waiver of these gates.
- **Flutter:** no user-interface change in this PR; V4-11 dashboard, real web/Android/iOS runtime acceptance remain separate. Green Flutter policy is not Flutter device acceptance.

The next code-only increment is a Java runtime PAPER/SHADOW capture adapter that generates real source-contract observations and binds simulated PAPER fills, then Flutter read-only evaluation dashboards. Configuration/license activation can follow after implementation while acceptance gates stay visibly pending.
