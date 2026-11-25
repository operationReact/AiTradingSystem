# V4-11 — Connected Flutter PAPER/SHADOW session evidence report

This is a read-only Flutter Admin integration of the actual V4-09 Java
`v4-matched-paper-shadow-session-evidence.v1` endpoint added by merged PR #397.

The existing Model Control Center `/admin/models` PAPER/SHADOW view retrieves,
in the same authenticated session load:

- an immutable registered model roster / enrollment fingerprint;
- the bounded recent cases and candidate actions from V56;
- a fresh, server-computed repeatable-read session report with the V58
  ACTIVE/PAUSED/QUARANTINED state/revision, real V57 automated PAPER order links,
  recorded fill quantity/fees/slippage, missing links, mismatch counts and
  champion/challenger disagreements.

The report is accepted by Flutter only if its contract, safety state, identity
and immutable enrollment fingerprint agree with the session record. A report
that claims source authentication, broker/soak/economic approval or LIVE capital
authority is rejected by the typed client. Missing/malformed counts or required
reason codes also fail closed.

All information remains a research/observational dashboard. It does **not**
measure broker P&L, guarantee licensed source rights, prove model production
inference, or accept an independent PAPER/SHADOW soak. No order, model promotion
or LIVE control is added. 401/403 invalidates every cached Admin view, including
the new aggregate; inability to load a fresh report does not display a stale
completed acceptance score.

Relevant regression tests:
`model_control_center_repository_test.dart` (authenticated real endpoint,
fail-closed response shape) and `model_control_center_page_test.dart` (PAUSED
state, missing PAPER links and explicit unverified state).

V4-11 remains IN PROGRESS: enrolled model controls, genuine producer runtime,
complete TCA, qualified PAPER/SHADOW soak, model comparison, monitoring,
retraining and production platform acceptance are still pending.

External source/subscription activation remains deferred at the owner's
instruction; no API keys or paid market/news requests are used here.


Rebased onto merged #398 safety controls and #407 V61 ledger foundation. The Flutter read-only session aggregate is invalidated when an ADMIN V58 pause/resume/quarantine succeeds or when a separately read state revision disagrees. No inference ledger or capital controls are presented as LIVE acceptance.
