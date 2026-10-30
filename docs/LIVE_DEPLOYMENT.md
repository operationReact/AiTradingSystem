# AiTradingSystem live deployment

AiTradingSystem currently uses one AWS environment only: `live`.

- Source branch: `dev`
- Product client: Flutter (`apps/trading_client`)
- Backend: Spring Boot (`backend`)
- Deployment target: AWS `ap-south-2`
- CI/CD authentication: GitHub OIDC; no static AWS access keys
- Runtime trading capabilities (PAPER/LIVE/emergency stop) are application controls, not separate AWS environments

## First provisioning

The provisioning workflow creates/updates the Terraform-managed live stack and stores Terraform state in an encrypted, versioned S3 bucket named from the AWS account and region. The repository variable `DOMAIN_NAME` must contain only the bare DNS name.

The first infrastructure apply runs with HTTPS disabled so Route 53 can return the authoritative nameservers without waiting for DNS delegation. After those nameservers are configured at the domain registrar, run `Provision live AWS infrastructure` manually with `enable_https=true`.

## Continuous deployment

After HTTPS is enabled, every validated push to `dev` runs `Deploy AiTradingSystem to live AWS`:

1. backend tests
2. Flutter analyze/test/release web build
3. OIDC authentication
4. immutable backend and Flutter-web image push to ECR
5. ECS task-definition registration
6. backend and Flutter-web ECS service update
7. service stabilization
8. live UI and `/api/health` smoke tests against `DOMAIN_NAME`

The Flutter web container serves the release build through nginx on port 8080. `/api/*` remains routed by the ALB to the backend target group.

## Required repository variables

- `AWS_ROLE_ARN` — existing GitHub OIDC deployment/provisioning role
- `DOMAIN_NAME` — live bare domain, for example `example.com`

No broker, database, provider, or application credentials belong in GitHub source or Flutter. Runtime credentials belong in AWS Secrets Manager.

## Deployment is not trading activation

The complete application may be deployed to the live environment while server-side policy still determines whether PAPER or LIVE order execution is permitted. Deployments must not bypass risk, approval, reconciliation, emergency-stop, or audit controls.
