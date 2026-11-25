# V4-09/V94 — fail-closed read-only ECS worker task-runtime and IAM role identity

## Reason for this gate

The V75 worker image inspection and V93 container-privilege checks can verify an exact ECR image digest and a non-root container definition while still accepting a materially different *task* configuration. A task moved onto an EC2/host/bridge runtime, given extra volumes, a shared PID/IPC namespace, an unexpected App Mesh proxy, extra accelerators, an elevated execution-role identity or a modified compute budget must not be considered the expected research-worker posture.

## Offline-only implementation

The V75 sanitized read-only evaluator now requires the task definition to declare:

- Exactly `requiresCompatibilities = ["FARGATE"]` and `networkMode = "awsvpc"`.
- Exactly `cpu = "512"` and `memory = "1024"` (ECS API string values, reflecting the dormant Terraform scaffold).
- Distinct and exact account-scoped task and execution role names, consistent with `infra/terraform/research-worker-scaffold.tf`.
- No task-defined volumes, PID/IPC namespace sharing, proxy configuration, inference accelerators or ECS fault injection.
- A running ECS service with the FARGATE launch type and no alternate capacity-provider strategy.

A missing or contradictory declaration yields the sanitized blocker `WORKER_TASK_RUNTIME_UNVERIFIED` (or `WORKER_SERVICE_UNVERIFIED` for service-level failures), never a raw role ARN, image digest, secret reference or task definition. The output's additive `bounded_fargate_runtime_declared` boolean describes *only* the verified metadata.

Synthetic, no-AWS V75 tests cover each rejected task/service override and confirm the existing Terraform scaffold is consistent. There is no new task resource or change to Terraform, no update-service and no deployment.

## Limits

Matching an IAM role **ARN** is not proof its permissions remain limited; the reviewer still needs independently managed policy/trust inspection. This metadata check does not attest running image bytes, network reachability, real model/data rights, token values, backend TLS, original V56/V72 enrollment, PAPER/SHADOW outcomes, broker reconciliation, or capital/LIVE authority. The Terraform worker service is still default disabled with `desired_count = 0`.

Do not claim deployment or operational release from a green V75 synthetic contract. Only independently authorized read-only AWS runtime checks can speak to live configuration.
