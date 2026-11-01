# PAPER automation market-data boundary

Automated entry and position monitoring require trustworthy market prices. Production integration must reuse the repository's existing market-data freshness/degraded-data policy.

Rules:

- do not create a new automated PAPER intent from stale/unavailable market data when the authoritative policy says fail closed;
- do not invent a fill/exit price when no acceptable price is available;
- customer UI should say market data is temporarily unavailable rather than expose raw degradation codes;
- existing open positions follow authoritative degraded-data/emergency safety behavior;
- retries after data recovery must remain idempotent.

The new automation slice must not weaken the Phase 9 degraded-data acceptance behavior.
