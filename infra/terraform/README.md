# AWS paper deployment infrastructure

This Terraform configuration describes a fresh AWS deployment in Hyderabad, region ap-south-2. Committing these files does not create AWS resources; resources are created only when an operator explicitly runs Terraform.

## Resources described

- VPC with public application/load-balancer subnets and private RDS subnets across two availability zones.
- Application Load Balancer, HTTPS certificate, HTTP-to-HTTPS redirect, Route 53 hosted zone and domain records.
- ECS Fargate cluster and backend/frontend services, initially set to desired count zero.
- ECR repositories, CloudWatch log groups, encrypted private PostgreSQL RDS with managed master credentials, Secrets Manager API key, and GitHub OIDC deploy role.
- GitHub OIDC trust is limited to operationReact/AiTradingSystem on branch dev.

## Cost and safety gate

DO NOT run terraform apply until the AWS account, access, current pricing, and budget are confirmed. Even with ECS desired count zero, resources such as the ALB, RDS, Route 53 hosted zone, secrets, ECR storage and logs can incur charges. The default RDS setting is a small single-AZ instance, but availability and price must be checked in the selected region. No free-tier or price guarantee is implied.

RDS deletion protection is enabled and automated backups are retained for seven days. The final snapshot is retained when the database is destroyed; do not disable these protections without a deliberate data-retention decision. The generated operator API key is stored in Secrets Manager. Terraform state includes sensitive infrastructure metadata and the generated key; keep state encrypted and access-controlled. Never commit Terraform state, plan files, credentials or generated secrets.

## Review before apply

1. Confirm the AWS account ID and that the operator is using the intended account.
2. Check current prices and service availability in ap-south-2; set a budget and billing alerts.
3. Decide whether to use the apex domain maargglobal.com (default) or a subdomain by setting app_subdomain.
4. Inspect the Terraform plan for every resource and its estimated ongoing cost.
5. Confirm the domain is controlled by you. Terraform creates a Route 53 hosted zone. The first apply uses the default enable_https=false and leaves HTTP returning a setup-pending 503, allowing Terraform to finish and output the route53_nameservers. Copy those nameservers into your domain registrar and wait for delegation to propagate. Then run a second plan/apply with -var='enable_https=true' to validate the ACM certificate and create HTTPS/redirect listeners.
6. Run Terraform from a trusted workstation with Terraform 1.8+ and AWS credentials for the intended account.

## Commands — only after the cost gate

    cd infra/terraform
    terraform init
    terraform fmt -check
    terraform validate
    terraform plan -out=tfplan

Inspect the saved plan and approve the costs/resources before running:

    terraform apply tfplan

For the second stage, after DNS delegation is verified, run:

    terraform plan -var='enable_https=true' -out=tfplan-https
    terraform apply tfplan-https

The Terraform backend is intentionally local for this initial scaffold. Before team/production use, move state to an encrypted, access-controlled remote backend with versioning and locking. Do not upload state to GitHub.

## First deployment

1. Apply the reviewed infrastructure plan.
2. Set GitHub repository variable AWS_ROLE_ARN to Terraform output github_actions_role_arn.
3. Verify Route 53 nameserver delegation and wait for ACM certificate validation.
4. Open GitHub Actions, select Deploy paper trading app to AWS, run it on dev, and choose yes-paper-only.
5. The workflow runs tests/builds, pushes immutable commit-SHA images to ECR, registers task-definition revisions, starts one task per service and waits for stability.
6. Verify https://maargglobal.com/api/health, authenticate with the operator API key from Secrets Manager, and test instrument search and paper-trade simulation only.

## Security notes

- RDS has no public access and accepts PostgreSQL traffic only from the ECS task security group.
- Application tasks receive public IPs in public subnets to avoid a NAT Gateway. Their inbound security group still permits application traffic only from the ALB. Review this trade-off before production.
- The ALB exposes ports 80 and 443 only. Port 80 redirects to HTTPS.
- Frontend build-time variables are public; never put credentials in VITE variables.
- The API key is generated and passed to ECS through Secrets Manager; it is not output as plaintext.
- Angel One and AI provider credentials are deliberately absent. TRADING_MODE=PAPER and NEWS_ANALYSIS_PROVIDER=NONE are deployment defaults. No live broker-order path is enabled.
- The deploy workflow is manually triggered only and uses OIDC rather than static AWS keys.
