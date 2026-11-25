# AWS OIDC deployment readiness — 2026-10-28

## Verified identity

- GitHub Actions read-only OIDC verification passed: https://github.com/operationReact/AiTradingSystem/actions/runs/40741240725
- Account: `816344830716`
- Role: `arn:aws:iam::816344830716:role/aitradingsystem-github-deploy`
- Region used for verification: `us-east-1`
- GitHub custom subject: `repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/dev`

The verified role is an **existing manually configured AWS role**. It does not imply that either Terraform stack has been applied or imported into state. OIDC authentication proves identity only, not deployment authorization.

## Findings and guardrails

1. `infra/terraform` defaults to `ap-south-2` and describes ECS/RDS/ALB; `infra/terraform-budget` defaults to `ap-south-1` and describes EC2/SSM. Both differ from the verified `us-east-1` region.
2. Both Terraform stacks used the standard GitHub subject `repo:owner/repo:ref:...`, which does not match this repository's custom organization/repository-ID subject. The trust strings are updated to match the observed identity; do not relax to wildcards.
3. Both existing deploy workflows now **fail before AWS authentication** if the configured AWS account role or workflow region differs from the verified target. Both workflows currently fail this guard intentionally, until a single architecture and region are chosen.
4. Both stacks define a GitHub deploy role; the ECS stack also defines an OIDC provider. Applying either stack against an existing role/provider without importing or redesigning ownership can conflict with existing AWS resources. Never run both stacks.
5. The workflows' current `AWS_REGION` values are intentionally **not** silently rewritten: a region change affects AMIs, VPC/subnets, DNS, pricing, availability and state, and requires an explicit architecture decision.
6. Terraform state is currently local by default. Before provisioning, select an encrypted, access-controlled remote backend with locking and confirm no pre-existing infrastructure will be duplicated.
7. The deployment role currently proves authentication, not permissions. Grant only audited, scoped permissions needed by the chosen stack, and verify IAM separately. Avoid `AdministratorAccess`.
8. Review ownership of `maargglobal.com`, Route 53 nameservers, and any existing Bro & Bro resources before applying. No changes to those resources are authorized by this PR.
9. PAPER-only remains the only permitted execution mode. No real-money LIVE broker path is activated.

## Next safe implementation step

- Choose **one** stack and reconcile its region to `us-east-1`, or deliberately migrate the verified AWS role/region after confirming costs and service availability.
- Inventory existing AWS resources and Terraform state; resolve role/OIDC provider ownership by import or by referencing existing resources.
- Review IAM least privilege, public networking, data backups, budget notifications, and database restore plan.
- Run `terraform fmt -check`, `terraform init -backend=false`, and `terraform validate` in CI; produce a reviewed **plan** against the correct state. Do not run `apply` from this PR.
- Require separate operator authorization and explicit PAPER-only release gates before provisioning or deploying.

**Status:** OIDC verified; deployment **blocked pending region/stack/state reconciliation**. No infrastructure provisioned by these changes.
