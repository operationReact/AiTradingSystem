# V4-09/V75 — Deployed worker ECS image inspection, read-only

**Scope:** operator-triggered, AWS metadata-only research worker image posture. This does not deploy or run a worker, call Java/Python endpoints, read any credential, load model weights, authorize any trading, or verify economic value.

## Inherited context and current prerequisites

- AiTradingSystem AWS target account: `816344830716`; region: `us-east-1`; cluster: `ai-trading-system`.
- The **proposed** ECS service/container, task family and ECR repository are `ai-trading-system-research-worker` / `research-worker`. Existing Terraform currently contains a backend/frontend ECS service and **does not provision this worker service**. Do not infer its existence from code.
- Worker `intelligence/Dockerfile` contains the Python application but not an independently deployed, license-cleared, production-pinned trained model artifact. Installing an image alone is not market-model readiness.
- A worker image must be built, published, deployed and verified under a separate reviewed plan; this read-only workflow cannot do so.
- In the worker ECS task definition, require a single essential `research-worker` container, full `dev` SHA ECR image tag, `TRADING_MODE=PAPER`, an absolute local `AI_RESEARCH_MODEL_MANIFEST` path, and `AI_RESEARCH_WORKER_TOKEN` injected via a same-account/region Secrets Manager **reference** (not plaintext). Manifest existence, token value and worker IAM authorization cannot be verified by metadata-only inspection. Keep execution and Java research opt-ins default OFF.
- Task and ECS service must have `enableExecuteCommand=false`; ECS container health must be `HEALTHY`; expect exactly one completed/running task. These are intended acceptance criteria, **not claims of current worker state**.

## IAM/operator setup

1. In AWS IAM account `816344830716`, create a **separate** role (e.g. `ai-trading-worker-image-readonly-dev`) with the exact GitHub OIDC trust policy in [V73 inspection trust file](../infra/aws/readonly-v73-oidc-trust.json). It allows only the repository `operationReact/AiTradingSystem`, `dev` branch and `sts.amazonaws.com` audience.
2. Attach [V75 limited worker inspection permissions](../infra/aws/readonly-v75-worker-inspection-policy.json). These allow only STS identity, cluster/service/tasks/task-definition Describe/List and ECR DescribeImages. No IAM, Secrets Manager value access, ECS task execution/update, Docker push, deployments or broker operations. `ecs:DescribeTaskDefinition` requires `Resource: "*"` by AWS service-authorization design; the other resources are constrained where supported.
3. In GitHub **Settings → Secrets and variables → Actions → Variables**, set repository variable `AWS_WORKER_READONLY_ROLE_ARN` to this new role's ARN. Do not reuse `AWS_ROLE_ARN` (deployment) or `AWS_READONLY_ROLE_ARN` (backend posture).
4. Open **Actions → Inspect V75 worker ECS image (read-only; NOT model readiness)**, choose `dev`, and run. Review the sanitized 14-day evidence artifact. Failures remain `BLOCKED` and require independent operational investigation. No workflow changes AWS state.

## Required evidence boundary

V75 reads STS caller identity, ACTIVE cluster, completed ECS worker deployment, exactly one running healthy worker task, exact registered task definition, current full GitHub `dev` SHA image tag, ECR declared digest and actual running container `imageDigest`. It checks token **reference presence** and no known sensitive plaintext environment names; it does **not** read token values or worker filesystem.

If all metadata checks pass, the status remains `PAPER_WORKER_IMAGE_POSTURE_ONLY_MODEL_UNVERIFIED`. The report explicitly sets false for:

- worker trained model artifacts/manifest bytes validated;
- secret value/permissions and private network proved;
- actual Java V72 ADMIN authenticated original-enrollment roster match;
- licensed NSE/BSE/news source custody;
- representative PAPER/SHADOW market soak and broker reconciliation;
- LIVE/capital authority.

**Next independent gate** is authenticated read-only V72 worker readiness from a legitimately provisioned Java ADMIN/PAPER session, with model file hashes and original V56 roster; only then consider paper/shadow operational tests. Never automatically turn on model dispatch or LIVE.
