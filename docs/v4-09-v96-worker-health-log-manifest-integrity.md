# V4-09 / V96 — verify ECS worker health probe, log routing and manifest path

## Why V96 exists

A V75 healthy ECS container and matching ECR digest did not prove that the task definition used the reviewed localhost health probe. A substitute `exit 0` check can hide an unhealthy worker. Likewise, a different log driver, region or destination can redirect model-worker logs. The manifest reference was only required to be absolute and the Secrets Manager ARN was checked by prefix.

## Offline implementation

- The read-only V75 worker task-definition evaluator requires the **exact** Terraform-defined health check: `CMD-SHELL` Python GET to `http://127.0.0.1:8090/health` with a 3-second call timeout, ECS interval 30s, timeout 5s, retries 3, and startPeriod 30s. Substitute commands, omitted/extra fields and altered timing fail closed with `WORKER_HEALTHCHECK_UNVERIFIED`.
- It requires ECS `awslogs` to exactly `/ecs/ai-trading-system/research-worker` in `us-east-1` with `ecs` stream prefix, and no extra log options or injected log secret destinations. Divergence gives `WORKER_LOG_ROUTING_UNVERIFIED`.
- Model-manifest env must be exactly `/opt/research/manifest.json`; the worker-token secret reference must be a resource-only Secrets Manager ARN in the configured AWS account and region, not a colon-suffixed JSON-key/version selector or an empty prefix. Existing V75 status code `WORKER_SECRET_OR_MANIFEST_DECLARATION_UNVERIFIED` is reused.
- Tests exercise legitimate Terraform-shaped fixture and wrong health commands, timings, logging and manifest/secret reference. The sanitized report includes only two additive booleans and blocker codes, not a raw log path, IAM ARN, secret reference, model bytes or full command.
- This change does not modify AWS, IAM, Terraform resources, log retention, model image build, or deployment. Default worker service desiredCount remains zero.

## Limits and operator gate

Task-definition metadata can never certify that health checks are functional in the deployed network, that logs have non-sensitive content, IAM roles remain least-privileged, market data/models are licensed and signed, or that actual Java→Python HTTPS and paper trading meet safety criteria.

A green synthetic V75 contract is **source-only**, not independently attested AWS runtime evidence. No ECR push, worker start, secrets retrieval, broker order, PAPER/SHADOW model promotion, LIVE or capital approval is authorized.
