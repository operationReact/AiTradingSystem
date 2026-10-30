# P08-PR05 — Audit search and CSV export

`AuditQueryService` filters exact event type, entity type/ID, correlation ID, authenticated/system actor and a half-open timestamp window. The default is seven days; a request may span at most 31 days. Pages contain 1–100 records, with bounded offsets and stable `(created_at DESC, id DESC)` ordering. Filters are bound SQL parameters.

New `AuditService` records capture actor identity from Spring's authenticated security context, never from a request's `details.actor` claim. Background work is SYSTEM. Existing records remain `LEGACY_UNKNOWN` with no invented identity. A caller-provided decision/acknowledgement name can remain payload evidence while the authoritative actor/source is separately recorded. The current API-key principal is `operator`; distinct user identities remain part of Phase 10 authentication.

Query and CSV output recursively redact values under credential-shaped keys, including passwords, secrets, access/refresh/JWT/feed tokens, API keys, authorization, cookies and credentials. Public instrument `symbolToken` values remain available. This key-based defense is not a claim that arbitrary free text has been comprehensively secret-scanned; producers must continue to keep secrets out of audit details. Persisted audit evidence is not rewritten by reads.

CSV export uses the same filter/redaction rules, at most 1000 records/5 MB, and rejects an oversized request rather than silently truncating it. Every cell is quoted with embedded quotes doubled. Dangerous `=`, `+`, `-`, `@` prefixes, leading tab/CR/LF and formula prefixes after whitespace/BOM are neutralized with an apostrophe.

PostgreSQL evidence proves authenticated actor and correlation filtering, distrust of payload actor claims, parameterized injection-like inputs, legacy identity preservation, nested redaction without source mutation, pagination and export bounds. CSV tests cover formula prefixes, whitespace/BOM, quotes and commas. HTTP audit/search/export routes follow in P08-PR06.
