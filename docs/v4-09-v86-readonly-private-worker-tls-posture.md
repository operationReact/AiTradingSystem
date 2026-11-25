# V4-09/V86 — Read-only AWS private worker HTTPS topology inspection

V81 introduced a cost-bearing, default-OFF internal HTTPS ALB, V82 added a dedicated Java-backend-only caller SG and two-path allowlist, and V83 requires an independently pinned exact worker TLS hostname. None of those Terraform files proves that AWS actually has the expected resources or the expected live networking. V86 adds a separate **manual, metadata-only** inspection of the real deployment topology.

## 1. What this proves and what it does not

The AWS CLI snapshots are kept **only inside a private temporary directory** on the GitHub runner. The Python evaluator emits a small, sanitized report with boolean checks and finite blocker codes. It checks:
- AWS account `816344830716`, `us-east-1` and the exact expected ALB `aitrading-rsrch-private` is **internal**, `application`, active and assigned to two subnets with `MapPublicIpOnLaunch=false`.
- Exactly one **HTTPS/443** ALB listener, recognized TLS policy, a default fixed HTTP **403**, and **only** the two paths `/v1/research/artifact-readiness` and `/v1/research/artifact-inference` routed to the HTTP/IP worker target group port **8090**.
- ACM describes an **ISSUED, unexpired**, FQDN-matching certificate in use by that ALB. The checker reads only the certificate **metadata**, not private keys.
- The VPC-associated **private** Route53 hosted zone `research-worker.maargglobal.com` contains a single private A alias to the exact ALB. This is an AWS metadata check, **not proof of publicly delegated parent DNS**, JVM certificate trust, a live TLS handshake or a reachable Python process.
- The three expected security groups all belong to the same VPC. The independent Java identity SG grants no ingress/egress privileges by itself; the ALB accepts TLS only from that SG and sends port 8090 only to the worker SG; the worker accepts port 8090 only from the ALB. The frontend ECS service must not have the Java identity group; the worker stays `desiredCount=0`, `runningCount=0`, `enableExecuteCommand=false` and without a public task IP.

Even a fully successful report has verdict `PRIVATE_TLS_TOPOLOGY_CONFIGURED_WORKER_DORMANT_ONLY`. It **does not** mean that the worker is deployed or model-ready, nor does it establish TLS route tables/NACLs, client SNI/CA validation at runtime, secret contents, NSE/BSE/news licenses, trained model custody, paper performance, broker reconciliation or trading authorization.

A missing ALB, unissued ACM cert, altered SG, public or unapproved path, missing role, wrong account or inactive worker is **BLOCKED**, not a reason to turn on research or LIVE. All truth fields for real TLS handshake, model bytes, broker and capital remain false.

## 2. Operator-only read-only AWS IAM prerequisite (no local setup)

The GitHub connector cannot provision AWS IAM or access this AWS account merely because GitHub and Notion are connected. An operator must create this role **in AWS account `816344830716`**:

1. Ensure the AWS IAM GitHub OIDC provider with audience `sts.amazonaws.com` already exists.
2. Create a new IAM role named `ai-trading-worker-tls-metadata-readonly-dev` with trust policy [`infra/aws/readonly-v86-worker-tls-oidc-trust.json`](../infra/aws/readonly-v86-worker-tls-oidc-trust.json). It accepts **only** `repo:operationReact/AiTradingSystem:ref:refs/heads/dev`, not pull requests, arbitrary repos or branches.
3. Attach **only** [`infra/aws/readonly-v86-worker-tls-policy.json`](../infra/aws/readonly-v86-worker-tls-policy.json), ideally behind a permissions boundary. It grants *only* ELB, EC2, ACM, ECS and Route53 read actions needed for the inspector. IAM requires `Resource="*"` for some discovery-only Describe and listing calls. It does **not** grant CloudFormation, Terraform, ECS updates/task execution, ECR publishing, Secrets Manager secret reads, or network modifications.
4. In GitHub `operationReact/AiTradingSystem` → Settings → Secrets and variables → Actions → **Variables**, configure `AWS_WORKER_TLS_READONLY_ROLE_ARN` with that IAM role's ARN (a non-secret identifier). Do not repurpose the deployment role `AWS_ROLE_ARN` or the V73/V75 separate inspection variables.
5. When the V81/V82 cost-bearing infrastructure has been **independently approved and intentionally provisioned**, run GitHub Actions → **Inspect V86 private research worker HTTPS edge (read-only, dormant only)** → **Run workflow** on branch **`dev`**. The workflow cannot run from a PR and has no periodic trigger.

Until those IAM and infrastructure prerequisites exist, the workflow will **not** report a real verified topology. It is correct and expected for its verdict to be `BLOCKED`.

## 3. Data handling and limitations

All raw ECS, Route53, security-group, ALB and ACM API JSON snapshots may contain sensitive operational metadata. They are never printed or uploaded. The workflow creates an `umask 077` private temporary folder and removes it on exit, then uploads **only** `v86-private-worker-tls-posture.json` (the sanitized code/boolean report), retained for 14 days.

A copied or forged JSON fixture can pass the *offline* Python tests; this test result is **not runtime evidence**. Real evidence must originate from the manually dispatched exact-dev OIDC job in the expected account and be independently cross-checked. The checker does not inspect network ACLs, effective route tables, direct TCP/TLS reachability from Java tasks or domain delegation. A future separate in-VPC diagnostic must not send the private worker token to an untrusted endpoint.

This workflow does not invoke Terraform, change SGs, start tasks, build images, read model artifacts, access broker credentials, enable research or authorize LIVE/capital. The independent source/model signing and private image release gates remain separate.

## 4. Tests and verifiable software acceptance

- `python3 -m unittest discover -v -s scripts/tests -p test_v4_worker_private_tls_posture.py` uses synthetic representative AWS Describe outputs to prove expected dormant metadata can pass and security regressions fail.
- `python3 -m unittest discover -v -s scripts/tests -p test_v4_worker_private_tls_inspector_policy.py` ensures exact OIDC trust, allowlisted read-only actions, closed dev-only manual workflow and no secret/full AWS payload uploads.
- Both run in a dedicated no-credentials GitHub Actions workflow on PR head. Existing Java, Python, Docker, Terraform, and Flutter policies are not replaced.
