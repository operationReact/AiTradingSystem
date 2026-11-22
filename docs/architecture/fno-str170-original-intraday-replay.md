# STR-170: original intraday cash/futures historical research replay

## Why the existing data is not accepted

STR-170 (FnoFuturesBasisCarryV1) needs simultaneously tradable
exchange-timed NSE_EQ spot BID/ASK and exactly matching deliverable
single-stock NSE_FO futures BID/ASK. Every quote must be no more
than 15 seconds old at its exact decision as-of, with at most
10 seconds exchange-time skew.

V105 finalized exchange-session closing quote evidence only becomes
known after closing-price finalization. It cannot be substituted into
an intraday 15-second decision retroactively. Neither midnight-labelled
private SQLite daily bars nor generic LTP are eligible.

## What is implemented

V125 creates an **empty**, append-only, one-day-retention original
cash/future paired source. An authorized upstream capture process must
write individually documented genuine event timestamps, research-use
rights, provider identities, immutable spot/future/listing/cost SHA
digests, instrument expiry/lot, and fully reviewed financing, dividend
and roundtrip costs. No original source rows or rights are seeded.

Fno170VerifiedIntradayPairStore checks two independently attested
NSE calendars and four **separate** VERIFIED MODEL_INFERENCE rights
domains: cash intraday BID/ASK, futures intraday BID/ASK, contract
listing, and cash/futures basis assumptions. Original exchange,
first observed and captured timestamps must be at or before the
historical decision and still within retention. Only the exact
source-listed stock/future pair and lot qualifies, with price
events inside the last 15 seconds; max one row is returned.

Fno170VerifiedHistoricalReplayService independently checks server-side
research, intraday source, and contract-discovery flags (all default
FALSE); actual current clock, cash and derivatives session status,
date/expiry, original hashes and at-or-before-decision timing. Then
it calls the actual unchanged FnoFuturesBasisCarryV1.evaluate.
The stock-borrow right is **always false**, so a theoretical reverse
cash-carry cannot be falsely approved from cash/futures quotes alone.

An operator/admin-only GET endpoint, historical-carry-replay, returns
fixed states and aggregate counters without private contract symbols,
prices, broker positions, provider hashes or credentials. It never
submits orders or enables source rights.

## Acceptance distinction

- A green CI run means fictional fixtures exercised the original
  evaluator and rejection gates. It proves implementation mechanics.
- V125 source was empty at creation. No source availability, vendor
  licensing, first-known original provider provenance, authorized
  historical market reproduction, realized execution or after-cost
  out-of-sample performance is proved by creating the reader.
- A single quote-pair decision is NOT a genuine historical backtest.
  To advance independent market E2E, first independently attest and
  retain source custody, collect bounded chronological decisions,
  calculate tradable bid/ask fill assumptions, slippage and statutory
  taxes, then audit out-of-sample results with private immutable
  evidence receipts. PAPER ledger and LIVE broker fills require
  separate independent acceptance.
- As of this work, historical strategy E2E is **0/43**.

No broker/API calls, extra AWS resources, data-export, rights
relaxation or orders are part of this implementation.

Tracker: https://github.com/operationReact/AiTradingSystem/issues/1658
