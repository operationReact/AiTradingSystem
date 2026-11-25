# V4-09/V87 — Join trusted GitHub read-only PAPER + dormant TLS inspection evidence

V73 prepares a separate operator-only read-only inspection of the actual Java PAPER backend, requiring one healthy backend ECS task/image that matches the exact Git commit. V86 prepares a separate, metadata-only read-only inspection of the internal Python HTTPS edge with a deliberately **dormant worker service**. These inspections test complementary properties, but a report from one run does not guarantee that the other report belongs to the same deployed source or was captured recently.

V87 adds a **manual GitHub-only** correlation gate. It reads exactly two previously generated sanitized artifacts and retrieves their GitHub Actions run records using the built-in `GITHUB_TOKEN`. It never acquires AWS IAM credentials, contacts market-data providers, probes the worker, accesses private model artifacts, reads secret values, changes ECS settings or enables trading.

## Operator flow (remote GitHub Actions; no local setup)

Before V87, an authorized operator must independently complete the V73 and V86 read-only IAM OIDC prerequisites. The V86 ALB/Route53/ACM setup is cost-bearing and default-disabled, and the V73 backend baseline expects the current `dev` SHA running in PAPER. Do not provision or modify infrastructure merely to satisfy a status check without independent cost/security review.

1. On one stable commit of `dev`, run **Inspect V4 PAPER runtime posture (read-only, not release acceptance)** (V73) and **Inspect V86 private research worker HTTPS edge (read-only, dormant only)** (V86). Both must finish successfully and publish their separate sanitized artifacts.
2. Note the GitHub Actions run ID from each successful manual run, not a PR check ID or a local JSON filename. The two runs must target the **same current `dev` commit**.
3. In GitHub Actions, open **Correlate V87 PAPER backend and dormant HTTPS inspection evidence**, select branch **`dev`**, enter the two run IDs and run it. No AWS credential is required for V87, only GitHub `actions:read` and `contents:read` access.
4. V87 fetches original run metadata from the repo's GitHub Actions API and downloads only the fixed `v4-paper-runtime-posture-readonly` and `v86-private-worker-tls-readonly` sanitized artifacts. It verifies both run origins are genuine successful `workflow_dispatch` on `dev` for the exact current commit, each report's schema/allowed blocked authority state, timestamps embedded within their respective run windows, age within 90 minutes, and report pair timestamps within 25 minutes. Other runs, substituted paths, PR-origin events, missing artifacts or mismatched/old reports cause a **BLOCKED** result.
5. The **only** published output is `v87-paper-dormant-tls-correlation.json` with bounded non-sensitive booleans and blocker codes (14-day retention). Raw run metadata and downloaded sanitized inputs are stored temporarily and deleted; no source archives, tokens or AWS data are included in the V87 artifact.

## What success means

A fully green report means **two recent GitHub read-only inspection reports** consistently described a PAPER Java backend and private worker HTTPS topology with the Python worker **still dormant**. The verdict is `PAPER_BACKEND_DORMANT_PRIVATE_TLS_ONLY`, *not* permission to enable worker dispatch, scale ECS, approve a V85 model signature, publish to ECR or trade.

V87 does **not** independently cryptographically sign the two artifacts, verify certificate chains from a live Java task, attest actual Python worker model weights or private token contents, verify market-data licensing, conduct real PAPER/SHADOW market runs, reconcile broker outcomes or authorize LIVE/capital. A GitHub Actions artifact can be forged by sufficiently privileged repo administrators; reviewers still need to verify the authorized OIDC roles, run sources, IAM permissions, GitHub audit trail and AWS account and should not mistake synthetic CI for real operational acceptance.

Until V73 and V86 operator-run jobs can succeed, the expected V87 result is `BLOCKED`. This is accurate; no AWS run, image publication, model artifact delivery or trading execution is performed by this feature.

## Verification

- `python3 -m unittest discover -v -s scripts/tests -p test_v4_operator_evidence_correlation.py`: synthetic happy path and adversarial run origin/branch/sha, stale timestamps, mismatched evidence, unjustified capital claims and early worker activation must be rejected.
- `python3 -m unittest discover -v -s scripts/tests -p test_v4_operator_evidence_correlation_policy.py`: no-AWS GitHub Actions permissions, exact sanitized artifact names and no publish/market operations.
- Normal Java/PostgreSQL, Python, Terraform, Docker and Flutter CI remain separate unchanged baselines.
