# V4-09 / V76 — Dormant AWS research worker IaC (no deployment)

The repository did not previously have ECS Terraform resources for the Python research worker. V76 prepares a **dormant** service, not a running worker, using `infra/terraform/research-worker-scaffold.tf`.

## Safety / cost defaults

- `research_worker_scaffold_enabled=false` by default. This creates **zero worker resources** on normal Terraform apply.
- If an authorized operator explicitly enables the scaffold, Terraform can create the ECR repository, log group, dedicated minimal worker task role, narrowly scoped token-reading ECS **execution** role, security group, task definition and a private-subnet ECS service with **`desired_count = 0`**.
- The worker service has **no public IP, no public ALB, no ECS Execute Command**, and no broker/risk/market/data service IAM actions in its task role. Inbound TCP 8090 is permitted only from the existing Java-backend ECS security group.
- Because the existing private subnets have no NAT or VPC endpoints, **do not scale** the service until separate network/ECR layer pull/S3/Logs/Secrets Manager connectivity review. V76 deliberately does not add chargeable networking without operator cost approval.
- No trained weights or manifest are shipped. A declared `/opt/research/manifest.json` path is **not proof** that a manifest or license-cleared weights exist. Python V72 will refuse readiness when artifacts are missing.
- The task is labeled `TRADING_MODE=PAPER`, but Python cannot authorize capital regardless; existing Java LIVE/risk authority remains separate. No research dispatch flags are changed.

## Explicit operator prerequisites before enabling the dormant scaffold

- Use only AiTradingSystem AWS account `816344830716` in `us-east-1`. Terraform task definition has a fail-closed account/region precondition.
- Supply a full immutable worker image Git SHA via `research_worker_image_sha`. The repository must already contain that exact verified image when a release is later attempted.
- Supply a **pre-existing** Secrets Manager ARN via `research_worker_token_secret_arn`; never provide a token in Terraform variables or state. Validate the actual secret value length and ECS execution-role access separately.
- Run `terraform fmt -check`, `terraform validate`, a cost review and a human review of a Terraform plan; verify exact account and region before applying.
- Enabling the scaffold provisions resources even with zero running tasks, so inspect applicable ECR/CloudWatch/Secrets Manager charges. No AWS action is performed by this pull request.

## Acceptance boundaries

The dormant scaffold cannot pass the V75 running-worker digest inspection because `desired_count=0`. A later separately reviewed rollout must add private egress/VPC endpoints, real image/model packaging, secrets delivery, stable private Java-to-worker addressing, network health and operator-controlled, version-pinned task deployment. Then run V75 plus the authenticated V72 Java Admin original-roster check.

India market/news licenses, genuine OOS economic results, representative PAPER/SHADOW market soak, real Angel One reconciliation, LIVE/capital release gates and actual AWS operations remain unverified. Do not enable worker dispatch or claim any release readiness from Terraform CI.
