# PAPER automation Android acceptance

Android packaging is required but does not by itself prove product acceptance.

Required evidence for the release slice:

- release APK builds on the exact reviewed head;
- embedded backend URL is a phone-reachable HTTPS PAPER backend for device acceptance, not a CI placeholder;
- Secure Sign In/account-scoped session works on device;
- PAPER + Manual/Automated preference state loads and mutates through backend authority;
- AI/Research -> PAPER lifecycle -> Portfolio/P&L can be observed against the backend;
- emergency stop/paused state is reflected on device;
- Angel One, when connected, remains read-only;
- no LIVE broker order submission is possible;
- primary phone screens pass canonical Figma visual review.

A CI APK using a non-routable placeholder endpoint is packaging evidence only.
