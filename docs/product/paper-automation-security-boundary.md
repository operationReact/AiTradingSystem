# PAPER automation security boundary

Automation is account-scoped behavior, not an authentication shortcut.

Required integration rules:

- Resolve account identity only from the existing authenticated server session/principal.
- Never accept an arbitrary account ID from the mobile client as authority.
- Preference mutations require the existing CSRF and authorization controls.
- Automated execution runs only for the authenticated/persisted account that opted into Automated mode.
- Emergency stop is server-authoritative and overrides Manual/Automated preferences.
- Client-side PAPER/LIVE or Manual/Automated controls are requests/preferences, not authority to bypass risk or release gates.
- LIVE and broker submission remain unavailable in the current release.
- Angel One credentials/tokens must never be exposed to this PAPER simulation domain.
