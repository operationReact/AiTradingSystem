# Budget-oriented AWS deployment (PAPER only)

This is a separate, cost-oriented alternative to `infra/terraform`. It is not provisioned by committing the code. Use only one stack, not both. It removes ECS, the Application Load Balancer, and RDS to reduce fixed monthly costs.

## Architecture

- One ARM64 EC2 `t4g.small` instance in `ap-south-2`, with an encrypted 20 GiB gp3 root volume.
- Docker Compose runs Spring Boot, React/Nginx, PostgreSQL, and Caddy HTTPS on the same instance.
- PostgreSQL data persists on EBS; nightly compressed dumps are copied to a private encrypted S3 bucket and expire after 7 days.
- ECR stores immutable backend/frontend images.
- SSM Session Manager is the administration channel; no SSH ingress is opened.
- GitHub Actions uses OIDC and SSM to deploy immutable commit-tagged ARM64 images.
- A USD 25 monthly AWS Budget emails alerts at 80% actual, 100% actual, and 100% forecasted spend.
- The app is configured with `TRADING_MODE=PAPER` and `NEWS_ANALYSIS_PROVIDER=NONE`. No live broker credentials are created.

## Cost gate — estimates, not a quote

USD 25/month after credits is a target, not a guarantee. EC2, public IPv4, EBS, Route 53, S3 backups, ECR storage, outbound transfer, and incidental usage can be billable. Prices and Free account plan service access differ by region and account. Check the AWS Pricing Calculator for ap-south-2 before applying. AWS Budgets is an alert, not a hard spending cap.

For low traffic, use a rough planning range of USD 18–30/month before tax and credits. Traffic, image retention, storage, or support charges can increase it. If the live estimate exceeds USD 25, do not apply. This single-instance design sacrifices high availability: one instance failure takes down the app and database. Nightly S3 backups reduce data-loss risk but do not provide automatic failover.

## Prerequisites

1. Confirm the AWS account plan permits EC2, ECR, SSM, Secrets Manager, S3, Route 53, and AWS Budgets. The Free account plan restricts some services; do not upgrade to Paid without reviewing the terms and cost implications.
2. Confirm you control `maargglobal.com` and can update registrar nameservers.
3. Supply a monitored alert email address.
4. Confirm the default VPC exists in `ap-south-2`; this stack uses its first subnet.
5. Review the Terraform plan and current AWS pricing. Never run apply without inspecting the full plan.

## Plan only — do not apply until reviewed

```sh
cd infra/terraform-budget
terraform init
terraform fmt -check
terraform validate
aws sts get-caller-identity
terraform plan -var='budget_alert_email=YOUR_ALERT_EMAIL' -out=tfplan
terraform show tfplan
```

Confirm the account ID, region, instance type, EIP, encrypted volume, secrets, S3 bucket, ECR repositories, IAM/OIDC role, DNS zone, and USD 25 budget. Do not run `terraform apply` yet. Keep `tfplan`, Terraform state, and generated secrets private; never commit them.

After the plan is reviewed and separately approved:
1. Delegate Route 53 nameservers at the registrar and wait for DNS propagation.
2. Add GitHub repository variables `AWS_ROLE_ARN` and `AWS_INSTANCE_ID` from Terraform outputs.
3. Run the manual workflow `Deploy paper trading app to budget EC2` on `dev` and confirm PAPER-only.
4. Verify `https://maargglobal.com/api/health`, inspect logs, confirm backups appear in S3, and test paper simulation only.
5. Check AWS Budgets and Cost Explorer daily during the first week.

## Data warning

The database is hosted on the EC2 instance to avoid RDS fixed cost. Keep EBS and S3 backups. Do not destroy the stack or backups without exporting and verifying data. Restore drills are required before production use.
