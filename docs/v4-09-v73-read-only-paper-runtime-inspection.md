# V4-09 / V73 — Read-only PAPER runtime posture evidence, never deployment approval

## Why this is not yet an AWS deployment

Merged V66–V72 proves a full Java/Spring/PostgreSQL/Python synthetic
CatBoost/LightGBM model pipeline and local artifact-inventory identity checks.
It does **not** prove that the latest commit is deployed to the existing
AWS PAPER backend or that any trained Python worker is separately deployed.
Existing `inspect-paper-backend.yml` prints ECS events and task summaries
but does not verify a pinned Git commit, TLS/ALB response, plaintext secret
posture, disable-by-default research toggles or a structured fail-closed
posture contract.

V73 introduces a separate **manual, read-only** GitHub Actions workflow:
`.github/workflows/v4-paper-runtime-inspect.yml`.

### Target and security

The target is inherited from existing AiTradingSystem deployment wiring,
**not** the separate Bro & Bro AWS infrastructure:

- AWS Account: `816344830716` (OIDC allows this account only).
- AWS Region: `us-east-1`.
- ECS cluster: `ai-trading-system`.
- Existing PAPER backend ECS service and task family:
  `ai-trading-system-backend`.
- Existing ALB target group: `aitrading-api`.
- Existing public PAPER API health: `https://maargglobal.com/api/health`.

These are **configured targets**; they are not claims of current availability,
ownership/verification of a deployment, or permission to publish an image.
Do not interpret an ECS task definition as an independently verified worker.

### What the manual workflow actually does

A maintainer must choose `dev` and dispatch **Inspect V4 PAPER runtime
posture (read-only, not release acceptance)** from GitHub Actions. The
existing repository `AWS_ROLE_ARN` variable must point to a permitted
OIDC role with read-only STS, ECS and ELB Describe permissions. Restrict
role permissions and GitHub OIDC subjects at the AWS IAM level; the workflow
uses only read APIs. It cannot push images, deploy ECS services, rotate
secrets, invoke a trading strategy or alter desired count.

The workflow requests short-lived AWS credentials, then collects, in a
private GitHub runner temporary directory: STS caller account, ECS cluster
and service descriptions, registered task definition, ALB target health,
an HTTPS `/api/health` response, and any named research-worker ECS service.
Temporary raw task JSON is never uploaded or printed (it may contain
sensitive environment values). It is removed when the verification ends.

`scripts/v4_paper_runtime_posture.py` fails closed unless ALL are true:

1. Caller account equals `816344830716`.
2. ECS cluster ACTIVE, existing backend service has exactly 1 desired,
   1 running, 0 pending, one PRIMARY completed rollout.
3. Registered backend task is ACTIVE and matches the service's exact
   task-definition ARN in the allowed account and region.
4. Backend image is the exact account/region ECR repository and the
   selected **full GitHub `dev` commit SHA** (not `latest` or an older tag).
5. The task explicitly sets `TRADING_MODE=PAPER`, has no duplicate
   environment names and no recognized credentials/tokens/passwords in
   plaintext ECS environment values.
6. Research client, roster dispatcher and worker-readiness opt-ins are
   explicitly `false` or absent (all application defaults are false).
   Java/Spring relaxed-binding variable aliases and secret-reference names
   are checked too. **Unknown Spring configuration overrides are blocked**:
   `SPRING_APPLICATION_JSON`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
   active custom profiles, Spring configuration overrides, ECS environment
   files, and container command/entrypoint replacements.
7. Exactly one ALB target is healthy and the public HTTPS health JSON
   explicitly reports `status=UP`, `mode=PAPER`, and the backend service.

If any check fails, the sanitized report verdict is `BLOCKED` and the
manual workflow fails. Under no circumstances should a report with a
failed or missing check be turned into a success using defaults.

The report `v4-paper-runtime-posture.json` includes **only bounded boolean
checks, an expected SHA, target region and public blocker *codes***. No
secret values, API tokens, AWS ARNs, IP addresses, model weights, test
features, trading signals or response bodies are uploaded. GitHub retains
the artifact for 14 days. Tests validate this property with synthetic
malicious/unsafe task snapshots.

### Strict acceptance boundary

Even if backend posture passes, status is only
`PAPER_BACKEND_POSTURE_ONLY_RESEARCH_UNVERIFIED`. The report always
returns FALSE for:

- Authenticated deployed Python worker/model identity attestation;
- Worker secret IAM-policy review / worker-to-Java network proof;
- Licensed NSE/BSE/news provenance;
- Sustained representative PAPER/SHADOW market soak;
- Broker account/order/fill reconciliation;
- LIVE execution and capital authority.

A present worker ECS service is recorded only as a *candidate* because
service naming and running count cannot prove it is the authenticated
model worker checked by V72. A public `/api/health` response alone is not
proof of the backend's database execution-authority state.

### CI and next required work

An isolated `v4-paper-runtime-posture-contract.yml` uses no AWS identity.
It runs Python stdlib synthetic unit tests on PRs and `dev` pushes:
wrong account/region, latest/old image, LIVE mode, enabled research
toggles, plaintext secrets, duplicate environment, unhealthy ALB/health,
partial rollout, invalid SHA, and a worker service present without any
claim of real attestation.

**V73 is software preparation until the manual AWS inspection is actually
dispatched and its artifact reviewed.** Do not mark AWS deployment verified
from a green offline CI test.

For later V74: independently verify deployed Python worker image digest,
private token/Secret Manager/IAM delivery, authenticated V72 model manifest
match against a real Java V56 session, rollout rollback, health metrics
and repeated PAPER/SHADOW runtime soak using licensed market inputs. Do
not enable LIVE or research worker dispatch as part of this check.
