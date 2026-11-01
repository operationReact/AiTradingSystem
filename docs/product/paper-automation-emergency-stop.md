# PAPER automation emergency-stop semantics

Emergency stop is server-authoritative and has higher priority than Manual/Automated preferences.

Required integrated behavior:

- blocks new Manual PAPER intents;
- blocks new Automated PAPER intents;
- prevents new simulated fills that have not crossed the authoritative accepted/filled boundary;
- existing open PAPER positions follow the repository's authoritative emergency-stop exit/hold policy;
- customer UI shows Trading paused and rereads backend state;
- clearing the UI toggle alone cannot resume trading;
- concurrent/retried processing cannot bypass the stop or duplicate exits.

Preserve the existing Phase 8/9 emergency-stop semantics if they are stricter than this minimum contract.
