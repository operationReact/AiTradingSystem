# PAPER automation risk rules

The shared server risk boundary currently establishes these minimum release rules for both Manual and Automated PAPER intents:

1. PAPER mode only; LIVE is rejected.
2. Emergency stop rejects new intents.
3. Quantity and entry price must be positive.
4. Stop loss and target are mandatory and positive.
5. Available PAPER capital and risk budget must be positive.
6. Trade notional cannot exceed available PAPER buying power.
7. Stop loss must differ from entry.
8. Total entry-to-stop risk cannot exceed configured max risk amount.
9. Potential reward must be at least the entry-to-stop risk.
10. Accepted intent idempotency keys cannot create duplicate accepted work.
11. Automated intent additionally requires explicit account Automated opt-in, opportunity eligibility and confidence threshold.

Existing authoritative Phase 7/8 risk policy may be stricter. During integration, preserve stricter existing limits and make this boundary delegate to/compose with them rather than weakening them.
