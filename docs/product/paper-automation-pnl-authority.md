# PAPER P&L and capital authority

The branch foundation demonstrates deterministic long/short P&L and capital reservation/release math. Production values must come from the existing persisted portfolio/PAPER ledger and its fee/slippage/valuation rules.

Integration must preserve:

- authoritative available/committed capital;
- existing simulated fees/slippage;
- position quantity/average price semantics;
- unrealized P&L valuation source and freshness rules;
- realized P&L on one authoritative close;
- portfolio/history consistency after retry/restart;
- account isolation.

Do not replace mature portfolio calculations with the simplified foundation math if the existing implementation is richer.
