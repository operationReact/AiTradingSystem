# Cost-controlled CI policy (proposal)

## Objective
Minimize GitHub Actions runner charges without weakening paper-trading safety, security, or release gates. No automatic deployments. This policy is a proposal until reviewed and merged; it does not change existing workflow triggers.

## Execution policy
- Prefer local/agent development-environment validation for each change, recording commands, results, and commit SHA in the PR.
- Before merging any changes to trading execution, deterministic risk gates, cash/position ledger, auth, schema migrations, or order idempotency: require appropriate unit/integration/security tests on the exact candidate commit. A missing, skipped, cancelled, or failed check is not a pass.
- Run full regression at milestone boundaries and before releases, rather than on every routine commit. Retest affected components whenever code changes invalidate earlier evidence.
- Trigger paid CI deliberately using workflow_dispatch for validation workflows where compatible with branch protection. Never bypass required checks; configure branch protection and required-check policy together before changing triggers.
- Never auto-deploy on push, PR, or merge. Deployments require separate explicit authorization and manual execution.
- Preserve PAPER as default. Do not enable LIVE trading, submit broker orders, or provision AWS infrastructure.

## Optimization backlog (verify before implementation)
1. Inventory all workflow files and GitHub Actions runs; identify billed minutes, runner types, reruns, and most expensive jobs.
2. Identify duplicate push/PR builds; consider removing redundant triggers while retaining merge gates.
3. Add concurrency cancellation for superseded *validation* runs only; do not cancel deployment or migration jobs automatically.
4. Cache Maven/npm dependencies using lockfiles; measure cold/warm timings.
5. Use path filtering for documentation-only changes, without making required branch checks remain indefinitely pending.
6. Split fast focused tests from full milestone regression; retain mandatory security and trading-risk coverage.
7. Keep AWS OIDC verification and claim diagnostics manual-only; review IAM role permissions independently.
8. Compare Actions usage and cost before/after changes; revert optimizations that reduce safety or reliability.

## Rollout gates
- Baseline actual Actions billing and workflow inventory before claiming savings.
- Each workflow change in a focused PR, reviewed for trigger semantics, permissions, branch protection, and test coverage.
- No change to deployment permissions, LIVE activation, or trading-risk safeguards as part of CI optimization.
