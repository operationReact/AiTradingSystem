# V4-09/V89 — GitHub-only read-only inspection role variable preflight

## Purpose

V73 (PAPER backend metadata), V75 (research worker ECS image metadata) and V86 (private HTTPS ALB/ACM/Route53 metadata) each have **separate least-privilege AWS IAM GitHub OIDC role prerequisites**. Previously, to discover a missing or mixed-up GitHub Actions role variable, an operator had to dispatch the corresponding AWS inspection and receive a failure. V89 provides a **single manual, zero-AWS**, sanitized preflight of only the three GitHub repository variable strings.

It does not prove IAM roles exist or are securely configured. It does not assume OIDC, fetch AWS state, approve cloud cost, build/push any model, start ECS, dispatch research, contact a broker or authorize LIVE/capital.

## Remote Actions workflow (no local setup)

In GitHub repository operationReact/AiTradingSystem, go to Actions → **V89 GitHub-only inspection role variable preflight (no AWS access)** → Run workflow on **dev**. This workflow has only contents:read permissions, no OIDC token permission, and reads precisely these nonsecret GitHub Actions repository Variables:

- AWS_READONLY_ROLE_ARN — expected ARN in account 816344830716 for ai-trading-paper-inspection-readonly-dev (V73).
- AWS_WORKER_READONLY_ROLE_ARN — expected ARN in that account for ai-trading-worker-image-readonly-dev (V75; the documented example role is standardized for this preflight).
- AWS_WORKER_TLS_READONLY_ROLE_ARN — expected ARN in that account for ai-trading-worker-tls-metadata-readonly-dev (V86).

Set each in **Settings → Secrets and variables → Actions → Variables** only after an AWS operator explicitly creates and authorizes its corresponding read-only IAM role. Do not copy a deployment role, set AWS access keys, or reuse a role between inspections. The V73/V75/V86 IAM policies and dev-only trust constraints remain separately documented in their milestone files. V89 never changes the repository variables; its scope is checking whether the declarations match the documented names/account.

The workflow requires an exact operationReact/AiTradingSystem dev ref and 40-character commit SHA, checks the three expected complete ARN strings with no wildcards/paths/whitespace, rejects missing/swapped/wrong-account roles and reports only flags and bounded blocker codes. It retains only v89-readonly-role-variable-posture.json for seven days, never a raw ARN, secret, account credentials or AWS metadata snapshot.

## Interpretation

- GITHUB_INSPECTION_ROLE_VARIABLE_FORMAT_ONLY means **three expected GitHub Actions variable values are present in the expected format**. It is emphatically *not* a green AWS IAM/OIDC readiness check.
- BLOCKED means at least one variable is missing or unexpected, or the runner is not the exact intended repository/dev branch. The sanitized blocker codes identify the V73, V75 or V86 field without revealing the declared ARN.
- Real role existence, trusted OIDC assumptions, IAM permission scope, AWS account posture, deployed Java/worker instances and actual TLS reachability require separate operator-run V73/V75/V86 read-only workflows, independently authorized under the intended account. V87 still requires real successful recent V73/V86 evidence; V89 cannot satisfy V87.

All authority booleans for AWS operational verification, model artifact custody, LIVE and capital remain false, even when format checks pass.

## Tests

Run python3 -m unittest discover -v -s scripts/tests -p test_v4_inspection_role_variables_preflight.py for synthetic tests of missing/wrong-account/wrong-role/misbound repo/dev SHA, no-ARN output, and no-AWS manual workflow scope. Dedicated dev PR CI runs this suite without credentials.
