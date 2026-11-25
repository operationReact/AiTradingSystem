# V4-09/V82 — Isolated Java backend worker credential and HTTPS origin preparation

## Scope and default-off behavior

V81 introduced an **optional internal** ACM HTTPS ALB for `https://research-worker.<domain_name>`, with private Route 53 only, and removed direct Java-to-worker HTTP access. V82 prepares the Java backend ECS task to receive the exact **same pre-existing** worker token secret and its private HTTPS DNS origin without enabling any client request.

`research_worker_java_secret_prep_enabled=false` by default. A normal Terraform plan creates **zero V82 resources** and adds no extra token or URL to the backend. A new opt-in must satisfy V76 worker scaffold, V77 cost-bearing private AWS endpoints, V81 internal TLS and existing `enable_https=true` in the AiTradingSystem `us-east-1` account.

## IAM privilege boundary

The existing `aws_iam_role.ecs_execution` is shared by the public frontend and Java backend. Granting that shared role the Python worker secret would widen frontend execution permissions. V82 therefore creates a **separate backend-only ECS execution role** *only when operator-enabled*:

- ECS task trust is restricted to `ecs-tasks.amazonaws.com`, using the standard ECS ECR/log execution policy.
- A narrowly scoped `secretsmanager:GetSecretValue` policy permits the existing backend DB master-user secret, backend application API key, and **only the same V76 `research_worker_token_secret_arn`** that is referenced by the Python ECS worker task. There are no broad wildcard secret actions and no additional broker, IAM, EC2 or order permissions.
- The Java backend task definition selects that role only when the explicit V82 opt-in is true. The frontend task and shared execution role **never** get the new research token permission. Existing task roles are unchanged.
- If the secret uses a customer-managed KMS key, any necessary narrowly scoped `kms:Decrypt` is **not** added by default. That is an independently reviewed operator/security prerequisite. Do not work around missing IAM with AdministratorAccess.

## Java backend task definition

An operator-enabled V82 plan adds to the backend task, **and only its backend container**:

```text
AI_RESEARCH_WORKER_URL=https://research-worker.<domain_name>
AI_RESEARCH_WORKER_CLIENT_ENABLED=false
AI_RESEARCH_WORKER_READINESS_ENABLED=false
AI_RESEARCH_WORKER_TOKEN=(ECS Secrets Manager valueFrom reference; no plaintext)
```

The two `false` flags are deliberate. V72 ADMIN worker-roster reconciliation requires **both** enabled flags, and actual inference routing is not allowed until separate operator-approved deployment attestation. Merely having a trusted HTTPS URL and a token is insufficient. No command sets either flag true, injects a secret value into Terraform state, writes credentials to a log, invokes HTTP or starts the Python worker. Existing PAPER mode is unchanged.

The Java `ResearchArtifactInferenceClient` and `ResearchWorkerReadinessService` already enforce HTTPS for non-loopback worker URLs, reject URL user-info/query/fragments/redirects, apply strict timeouts and protect against retries on UNKNOWN outcomes. Those checks remain intact; V82 does **not** change the Java production code or grant independent authenticity of the worker beyond normal TLS certificate validation.

## Outstanding deployment acceptance gates

1. Verify actual DNS delegation, public ACM validation and private VPC resolution to internal ALB. V81 is default-disabled and **unapplied**.
2. Independently provision a strong 32+ character worker token at the exact declared same-account Secrets Manager ARN, validate the backend and Python separate ECS execution roles and restrict access. V82 never creates or rotates the token.
3. Independently approve and package source-rights-cleared V80 model artifacts into an immutable, tracked ECR image; V76 worker remains `desired_count=0`, with all model execution disabled.
4. Run operator-authorized AWS read-only V73/V75 inspections, then an independently authorized worker smoke/deployed readiness check from an actual ADMIN/PAPER V72 original enrolled Java session (never caller-supplied worker URL). This must include TLS hostname trust/negative network tests and exact Java↔Python shared secret proof.
5. Independently confirm market/news source licenses, prospectively frozen India holdout, live-market PAPER/SHADOW soak, broker reconciliation and separate LIVE/capital authority. V82 cannot certify any of these.

**No AWS apply, ECS rollout, secret retrieval, broker operation, model inference or LIVE trade is performed by the V82 pull request.**
