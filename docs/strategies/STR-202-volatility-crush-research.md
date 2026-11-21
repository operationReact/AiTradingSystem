# STR-202 — Volatility Crush Research (Strategy 33/43)

**Catalog state: DATA_BLOCKED.** This is a retrospective, event-anchored **research-only** classifier of actually observed NIFTY 30-day ATM implied-volatility changes. It **cannot** propose or place a trade and must not imply that post-event information was knowable before the event.

## Exact question addressed

For a separately authenticated official announcement with a documented **schedule that was already public before the pre-event quote**, did a genuinely sourced NIFTY **30-calendar-day constant-tenor at-the-money (ATM) IV** measurement materially decline after the verified event ended?

- Only known and independently authenticated event categories are accepted for this first research version: `RBI_POLICY`, `MACRO_RELEASE`, `OFFICIAL_INDEX_EVENT`. Expected calendar sources are official RBI, NSE or government release calendars, and the upstream provider must independently verify event time, schedule, classification and source rights. These string labels alone **are not authentication**.
- Event `scheduleFirstKnownAt < preQuote.firstKnownAt <= eventStart < eventEnd < postQuote.exchangeAt <= postQuote.firstKnownAt <= asOf`. Pre quote and post quote are genuine market-time observations during an ordinary NSE weekday cash session. The event ends before the research decision. Original event schedule, exchange observation and provider-first-known timestamps must be retained. Neither first-known timestamp may be from the future.
- Both NIFTY spot and *independent source-reported* annualized ATM IV **percentages** are required for pre and post quotes. Normalize to the same **30-calendar-day tenor**, with the identical authenticated underlying, provider, methodology `LICENSED_ATM_30D_INTERPOLATION`, licensed rights and credible original point-in-time provenance. **Never compare a near-expiry raw option IV with another, differently tenored option IV**, or use VIX, generic realized volatility, LTP or fabricated IV as a substitute.
- At least 35 separately authenticated, chronologically ordered, completed NIFTY daily OHLC candles. Each must have a genuine 15:30 IST close and source-first-known timestamp. Crucially, **every** historical record must have been available **before the event began**. Official holidays, special sessions and event-calendar validity must be externally verified by the data connector; these fixed-time guards are not a comprehensive exchange calendar.
- Pre-quote age relative to announcement start is limited (configurable 1–72 hours; tests 24 hours), post quote must still be fresh when classified (configured max 180 seconds), and pre/post IV and spot must come from the **same authenticated source**.

## Explicit observed result, not an ex-ante signal

Let `IVpre` and `IVpost` be source annualized percent values (e.g. 36 = 36%, not 0.36) and `Spotpre`, `Spotpost` the genuine synchronized underlying source index observations:

`observedDropInVolPoints = IVpre − IVpost`

`relativeDropBps = 10000 × observedDropInVolPoints / IVpre`

`observedAbsoluteIndexMoveBps = 10000 × abs(Spotpost − Spotpre) / Spotpre`

The `RESEARCH_CANDIDATE` observation requires a positive **actual post-event** IV drop that exceeds both a configured absolute **volatility-point** decline and a relative threshold; and excludes an excessively large spot move by a *descriptive filter*. A smaller, nonexistent or opposite-sign IV change returns `NO_SIGNAL`. The post-event spot movement is an **observed outcome**, not a pre-event prediction.

Example only from fictional deterministic tests: pre normalized ATM IV 36%, post IV 22%, so the **observed** drop is 14 percentage points, ~3,888.89 relative basis points; NIFTY spot 100 → 102 is a 200 basis-point change. These are **not live NSE quotes, a verified real event or historical research results**. The test event is expressly fictional.

## What this does NOT establish

- A post-event IV decline is **not** evidence that the decline was forecastable ahead of the event or that a trade at the earlier IV could have been entered or exited at a profit.
- IV changes alone do **not** establish options P&L, a short-straddle/strangle execution opportunity, or suitable broker short-writing rights. Realized underlying movement, theta, smile and term structure, spreads, slippage, bid depth, actual taxes, intraday gap moves, residual option marks and margin requirements all matter.
- A quote from one tenor normalization method and another provider cannot be freely combined. The common source-method identifier is only a **contract gate**; real licensing and quality evidence must exist upstream.
- The current evaluator deliberately makes **no entry price, target, exit price, max loss, backtest win rate or trade authorization claim**. It describes a verified post-event observation at a point in time, not a forward recommendation.

All decisions, including `RESEARCH_CANDIDATE`, `NO_SIGNAL`, `BLOCKED_DATA` and `BLOCKED_RIGHTS`, have `retrospectiveOnly=true`, `executable=false`, `orderPlaced=false` and `liveTradeAuthorized=false`.

## Tests and unresolved dependencies

15 deterministic JUnit cases cover correctly calculated actual IV drop, both absolute and relative drop bps, event schedule and announcement ordering, all five independent data/event/tenor rights, source-confirmed event category/time/source, original first-known historical closes before event, provider and OHLC integrity, valid pre/post quote times and source IV normalization, wrong method/tenor/stale/future-known quotes, no-drop and IV expansion, small drop rejection, large underlying-move filter, sample sufficiency, bad assumptions and no-order invariants. Test fixtures are *not* actual source data.

**Outstanding for genuine F&O use:** independently licensed NIFTY 30D ATM IV before/after real official events with actual option-chain interpolation provenance; original as-of live event calendar and event release timestamps; trusted NSE session calendar; durable source-first-known historical data; realistic ex-ante **event studies and OOS walk-forward tests** that separate known-before from known-after information; actual tradable option mapping, fills, after-tax/fee margins and risk; separately approved PAPER ledger/UI and LIVE broker entitlements/kill switches. Catalog remains **DATA_BLOCKED**.

Related: [STR-201 IV Rank Mean Reversion](STR-201-iv-rank-mean-reversion-research.md).
