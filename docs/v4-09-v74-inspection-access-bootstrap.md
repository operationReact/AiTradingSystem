# V4-09 / V74 prerequisite — GitHub OIDC role for V73 read-only inspection

This is **configuration preparation**, not an AWS resource change or evidence that the backend/worker is deployed. The V73 workflow targets the **AiTradingSystem** account `816344830716`, region `us-east-1` (not the Bro & Bro AWS account).

## Exact operator setup (AWS Console + GitHub)

1. In AWS account `816344830716`, confirm IAM has the OIDC provider `token.actions.githubusercontent.com` with audience `sts.amazonaws.com`. If missing, create the standard GitHub Actions IAM OIDC provider. Never create access keys for this workflow.
2. Create an IAM role named `ai-trading-paper-inspection-readonly-dev` using [the repository's trust document](../infra/aws/readonly-v73-oidc-trust.json). The **exact** OIDC subject is `repo:operationReact/AiTradingSystem:ref:refs/heads/dev` and audience is `sts.amazonaws.com`. No wildcard repository, branch, environment or principal.
3. Attach ONLY [the repository's read-only permissions document](../infra/aws/readonly-v73-inspection-policy.json) to that role. It allows STS identity, known ECS cluster/service Describe, ECS task-definition metadata Describe, and ELB target group/health Describe. AWS does not expose a resource type for `ecs:DescribeTaskDefinition`; that read action therefore requires `Resource: "*"`. It does not allow task execution, registration or service changes. ELB Describe actions use `Resource: "*"` because this discovery requires list-style access; no mutation actions are present. Do **not** attach AdministratorAccess, deployment policies, ECR push or secret-read permissions. IAM policy boundaries/SCPs may further restrict it.
4. In GitHub repo `operationReact/AiTradingSystem`, open **Settings → Secrets and variables → Actions → Variables**. Add `AWS_READONLY_ROLE_ARN` with the ARN of that newly created role. This is a non-secret role identifier, not an AWS key. Do not overwrite `AWS_ROLE_ARN` (deployment role).
5. Open **Actions → Inspect V4 PAPER runtime posture (read-only, not release acceptance) → Run workflow → dev**. Inspect the verdict and sanitized `v4-paper-runtime-posture-readonly` artifact. Any `BLOCKED` verdict means the backend posture is unverified; correct only the underlying safe mismatch after independent review. Do **not** enable LIVE, research dispatch, extra credentials or execution to make this check green.

## Scope and unverified prerequisites

- The role applies ONLY to the manually dispatched V73 backend posture workflow. V74 worker identity/digest, secret IAM access, private network reachability and live V72 ADMIN session-to-manifest reconciliation require **separate verification**, potentially additional narrowly scoped read-only permissions and operator-authorized deployment/network setup. **Do not silently add permissions to this role.**
- A new merged `dev` SHA becomes the expected image tag. If the ECS backend still runs an older commit, the workflow blocks by design; do not falsely report an outdated backend as current.
- The existing GitHub connector cannot provision IAM roles, update GitHub repository variables or dispatch Actions here. These steps need an authorized AWS/GitHub operator.
- Independent NSE/BSE/news feed rights, real PAPER/SHADOW soaking, broker reconciliation and LIVE/capital authority are **not** established by this IAM change.
