# PAPER automation merge policy

Keep the PR Draft while code/persistence/UI changes are ongoing.

Only mark Ready and merge when the same exact head has:

- authoritative persistence integration with no duplicate ledger;
- required backend PAPER/risk/security tests green;
- Flutter analyze/tests green;
- actual customer controls/lifecycle UI implemented;
- visual golden regression green;
- phone-size canonical Figma evidence accepted;
- Android APK packaging green;
- required connected backend/device evidence for touched flows;
- LIVE/broker submission proven disabled.

If the head moves after evidence is collected, reevaluate the affected gates on the new exact head. Do not merge based on stale CI from an earlier SHA.
