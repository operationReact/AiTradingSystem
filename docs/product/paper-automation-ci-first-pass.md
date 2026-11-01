# PAPER automation first CI pass

Open the Draft PR and use the resulting exact-head workflows as the first repository-wide compilation/test signal for this connected mutation set.

Expected handling:

- if Java compilation fails, fix the concrete source/API mismatch on a new head;
- if existing tests fail, determine whether the new foundation broke an invariant or the test exposes a real integration conflict;
- if Flutter contract tests fail, fix path/copy/contract defects rather than weakening gates;
- if security/dependency checks fail, remediate the actual finding;
- do not rerun unchanged failures;
- do not merge even if CI is green because persistence/UI/visual/device blockers remain.
