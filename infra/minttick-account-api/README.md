# MintTick dev customer-account API (Mumbai)

This is an **isolated, opt-in, paid** AWS deployment. It does **not** reuse, destroy, import, or expose the original PAPER EC2 host and does not change Cognito Terraform state.

## Infrastructure

* AWS account: `098859530205`; region: `ap-south-1`
* Cost-constrained ARM64 `t4g.small` EC2 with encrypted persistent 30 GiB gp3 EBS.
* Dedicated VPC; only inbound TCP 80 (ACME challenge/redirect) and 443 (HTTPS). **No SSH, PostgreSQL, 8080, or administrative route exposure**.
* Amazon SSM for admin control; ECR for immutable application images; separate public Route53 A record and static IPv4.
* Dedicated Terraform state prefix `ai-trading/minttick-account-api/dev/` in existing encrypted state bucket (us-east-1).
* The backend must start with `SPRING_PROFILES_ACTIVE=account-api,prod`, `MINTTICK_COGNITO_ENABLED=true`, the exact Cognito issuer/client ID, no legacy operator API key, a separate PostgreSQL database, and the existing protected customer-only endpoint filter.
* TLS should be terminated by Caddy with ACME validation for the owned hostname; run the readiness and 401/403 account API checks before APK distribution.
* Persist and independently back up PostgreSQL and TLS data. A sole EBS volume is **not** a disaster-recovery backup.

## Unresolved prerequisites

This PR defines the infrastructure and a host bootstrap. It **does not deploy the application** or create a public endpoint. Before any apply, supply an owned DNS hostname and Route53 hosted zone ID, prepare a narrow GitHub OIDC Terraform apply role, review estimated monthly EC2/EBS/public-IPv4/Route53 charges, and complete an explicit opt-in provisioning request. Terraform does not have AWS credentials embedded in this repository.

Provisioning defaults to **zero resources** unless `approval=PROVISION_MINTTICK_ACCOUNT_DEV`. Do not run Terraform with a local state file; use the dedicated S3 backend and lockfile.

### Reproducible offline checks

```bash
terraform -chdir=infra/minttick-account-api fmt -check -recursive
terraform -chdir=infra/minttick-account-api init -backend=false -input=false
terraform -chdir=infra/minttick-account-api validate
bash -n infra/minttick-account-api/bootstrap.sh
```

Existing Cognito (not recreated here):
* User pool: `ap-south-1_jz2T8qbiY`
* Public app client: `6jm18v2mdpcfraip92hrjhqjnd`

Never put a customer password, access JWT, TOTP seed, database password, or AWS credentials in GitHub, Terraform state, Android build inputs, or logs.
