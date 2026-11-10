# Mintick Mind D20-02 — Stable historical listing identity (schema foundation)

**Implementation status:** forward-only Flyway V77 and read-only temporal resolver.
**Safety:** research-only. No automatic import, no API or broker order routing, no new AWS resources, no change to `market_candle` primary key.

## What changed

- `golden_security_identity`: internal company/security identity, optional independently verified ISIN, source and SHA-256 evidence fingerprint.
- `golden_listing_identity`: immutable per-listing UUID, security link, exchange, series and time-bounded listing episode. Multiple distinct listing episodes remain possible.
- `golden_provider_symbol_alias`: bounded provider token and symbol history with source-published and first-available timestamps, ingestion time, revisions and evidence digest. Ticker or broker token is **not** a durable identifier.
- `golden_corporate_action_evidence`: append-only revisions for splits, bonuses, dividends, rights, mergers, demergers, renames, delistings and corrections, each independently time-gated.
- `GoldenListingIdentityRepository.resolveAsOf`: a read-only join that returns a listing only when the alias was valid and known at the requested decision instant, and the listing episode includes the exchange session. Zero matches means unavailable; competing distinct listings raise ambiguity rather than arbitrarily picking one.

## Important constraints

1. **Never guess identities from today's instrument catalogue.** V77 intentionally has no `INSERT FROM instrument_master` and does not backfill `instrument_cash_catalogue_history`. The current 2,778 active cash equities are **not** newly mapped.
2. **Never rewrite old provider token history.** When a token is reused, the original listing keeps its own internal UUID and historical valid-time aliases. Research traverses an explicit verified mapping.
3. **Never use announcements retroactively.** Preserve both event/effective date and first time information became publicly available. A historical decision on Tuesday must not use a corporate-action correction first published on Wednesday.
4. **History is not adjusted yet.** Raw bars remain untouched. Split-adjusted / total-return history and source-rights checks are separate reviewed phases, not implicit calculations.
5. **No licensed evidence is asserted by test fixtures.** Synthetic rows in `GoldenListingIdentityIT` check schema and temporal logic, not real NSE/BSE source accuracy.
6. **Fail closed:** Unknown alias, ambiguous alias, out-of-window listing, future-published alias and invalid chronology do not produce a confidently mapped security.
7. **Do not couple app/API token resolution to this table yet.** It is a new research custody layer; the live Angel One feed, market cache, paper/live broker routing and authentication remain unchanged.

## Remaining requirements before real 20-year history

- A signed/provenanced corporate-action, historical mapping and delisted-listing source for Indian exchanges, plus permitted retention and model training/use rights.
- A reconciliation job that proves provider symbols/tokens and company listing episodes against independent sources; manual review of ambiguous cases.
- Explicit linkage from catalogue/history rows to verified listing UUIDs, with versioned dataset manifests and no overwrite of historical research evidence.
- Golden Source daily-bar ingestion and S3 Parquet normalization, coverage by historical listing sessions, and adjusted-versus-raw lineage.
- The existing D20-01B model/source readiness gate must validate dataset digests and rights through a trusted upstream registry.

## Tests

`backend/src/test/java/in/aitradingsystem/instrument/GoldenListingIdentityIT.java` exercises real PostgreSQL schema constraints for token reuse, delisting, availability delay, ambiguous mappings, valid-time errors and immutable action revisions.

The migration is intentionally independent from operational tables to avoid any unreviewed production-data rewrite on deployment. It is still a forward-only Flyway migration and must pass PostgreSQL CI before merge.
