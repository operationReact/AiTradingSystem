# Secure Sign In boundary for PAPER automation

PAPER automation requires real account-scoped authentication. The development-only session bootstrap is not acceptable for release acceptance.

Required integration:

- Secure Sign In creates/resumes an authenticated server session for a persisted user/account;
- trading/execution preferences resolve from that authenticated account;
- portfolio/P&L/history and broker read-only data are account-scoped;
- preference/risk mutations use existing authorization + CSRF protections;
- logout/session expiry immediately prevents new Manual/Automated trade intents;
- reconnect rereads account state from backend authority.

Do not embed a shared API key or development session bootstrap in the production Flutter app.
