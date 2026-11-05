# AI Trading System — cost-optimized EC2 PAPER pilot (November 2026)

## Decision

**Replace Lightsail**. The pilot should start with **one on-demand EC2 `t4g.medium` (2 vCPU, 4 GiB, Graviton ARM64) in Mumbai `ap-south-1`**, account **`098859530205`**, plus a 35 GiB encrypted gp3 EBS root disk. Host Spring Boot, local Docker PostgreSQL 16 and Flutter static Nginx web using the already-tested internal `infra/lightsail/paper-compose.yml` configuration as an eventual migration source — it contains **no public app/DB/worker port mappings**. File name is historical, not authorization to use the abandoned Lightsail service.

The dedicated Terraform root is **`infra/ec2-paper`**. The existing **`infra/terraform`** root deploys a different ECS+RDS+ALB topology and must **not** be applied for this cost pilot. Old Lightsail bootstrap and deployment GitHub jobs have been hard-disabled by `if: ${{ false }}` and must not be re-enabled accidentally.

This is a **PAPER-only development/evaluation environment**. Not a highly available trading service; no true financial trades, licensed market-provider data or capital authority may be enabled. Market research worker disabled by default. No public HTTPS or Android/iOS login is exposed in this phase.

## Why one EC2 with local PostgreSQL first?

With a single 4 GiB budget, a managed RDS would add separate compute/storage charges and can't be relied on for an indefinite shutdown: RDS automatically restarts after **7 days stopped**, and still charges storage and backup bytes. Postgres on the **persistent encrypted EBS** disk stops when the EC2 host stops, eliminating both database and backend compute usage. **This is a cost vs. availability compromise**, not an argument that on-instance PostgreSQL is as resilient as managed RDS. Before collecting valuable real user data, implement and successfully restore **independent S3 backups or EBS snapshots**.

Use AWS Systems Manager (SSM) to manage the VM: an EC2 instance profile with only `AmazonSSMManagedInstanceCore`, SSM Agent on Amazon Linux 2023, and **zero inbound security-group rules**. A public dynamic IPv4 address is used for outbound SSM/OS updates without a NAT Gateway; it is not a public application endpoint. No SSH key, NAT Gateway, Elastic IP, ALB, RDS or ECR resources are created in this Terraform root. Do not configure a DNS record to this changing address.

## Compute budgets (USD, on-demand estimates)

| Region and running hours per month | EC2 t4g.medium compute |
| :-- | --: |
| Mumbai ap-south-1, 100 h | $2.24 |
| Mumbai ap-south-1, 176 h | $3.94 |
| Mumbai ap-south-1, 730 h (always on) | $16.35 |
| N. Virginia us-east-1, 730 h for comparison | $24.53 |

These are **published approximate Linux on-demand compute prices**, not a live AWS quotation or an all-in bill. **Even while stopped**, the 35 GiB EBS volume, any snapshots/backups and managed state storage continue incurring charges. In-use/allocated public IPv4 is separately billed (published $0.005 per hour), and outbound data transfer and taxes may apply. Do **not** buy Savings Plans or RIs for an intermittent pilot; they involve continuing commitments. Validate **Billing > Credits in account `098859530205`** before apply: promotional credits are account-scoped and eligibility/expiry vary.

References:
- AWS [EC2 stopped billing](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-lifecycle.html), [RDS 7-day stop limit](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html), [SSM no inbound SSH](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/connect-with-systems-manager-session-manager.html), [EventBridge Scheduler universal targets](https://docs.aws.amazon.com/scheduler/latest/UserGuide/managing-targets-universal.html), [IPv4 charges](https://aws.amazon.com/vpc/pricing/)
- [Mumbai Graviton price comparison](https://stackrates.ai/cloud/aws/ap-south-1/) and [N. Virginia published price](https://www.calculator.holori.com/aws/ec2/t4g.medium?region=us-east-1)

## Cost shutdown policy

- **Automatic STOP every day at 22:00 Asia/Kolkata** through a dedicated EventBridge Scheduler universal EC2 `StopInstances` target. Default `daily_stop_enabled=true`. This schedule and its narrow IAM role are created **only** when explicitly provisioning the EC2 stack; a merged PR itself creates nothing.
- **No automatic START**: start the named, tagged EC2 instance only when working, from EC2 Console **Start instance** or a separately reviewed OIDC-bound GitHub/SSM workflow. Docker services configured `restart: unless-stopped` will run again once the server reboots and Docker is installed, if already deployed.
- The Scheduler IAM role can stop **only** this exact instance ID/ARN with `Application=ai-trading-system` and `Environment=paper` tags. It cannot start, terminate, resize, change RDS, invoke other apps or modify IAM policies.
- This will deliberately interrupt ongoing training, research or PAPER trades if left running at 22:00 IST. Do not rely on this environment for continuous exchange-hour operations, fill reconciliation or 24/7 monitoring.
- Do not allocate an idle Elastic IP. The public address can change on start. Use SSM, not a changing public HTTP hostname.

## Source preparation and application build architecture

The Terraform source is **planning-ready, not deployed**. Its variable `approve_paid_provision` defaults to empty, so it creates **zero managed resources**. Provisioning requires the exact opt-in token `PROVISION_PAPER_EC2_4GB`, an operator-reviewed plan, and working IAM rights in account `098859530205`. The AWS provider refuses any other account and only permits region `ap-south-1`. Terraform state MUST be stored in a properly secured dedicated S3 backend with locking, encryption, versioning, and restricted access; do not apply with a local GitHub runner state. A remote state bucket's bootstrap and role trust are **not implemented in this PR**.

Bootstrapping uses Amazon Linux 2023 ARM64 with SSM Agent and Docker. Compose is fetched from an explicitly versioned official ARM64 release with checksum verification; no app containers are launched in user-data. The root volume is encrypted and protected from accidental Terraform/API termination. Future image delivery must use **GitHub Actions Buildx linux/arm64** for Java and Nginx; Flutter Web is a static compile and PostgreSQL's official image supports ARM64. The existing x86-only Lightsail image-transfer workflow **must never** be reused without review.

Before a manual AWS provisioning or app deploy, perform these steps in order:

1. Confirm EC2 t4g.medium availability, quota, AWS credit eligibility and acceptable disk/IPv4/network cost **inside account 098859530205, Mumbai**. Do not assume the earlier Lightsail restrictions also prevent (or permit) EC2.
2. Correct/create a **separate restricted EC2 PAPER GitHub OIDC role** in this account with exact `repo:operationReact/AiTradingSystem:ref:refs/heads/dev` audience/subject. The previous Lightsail trust or a role for Bro & Bro is not transferable. Avoid `AdministratorAccess` and wildcard OIDC subjects.
3. Bootstrap dedicated encrypted S3 Terraform remote state with locking and least-privilege access. Configure a future **manual plan** GitHub Action. Validate `terraform fmt`, `init -backend=false`, `validate` offline first, then a real `plan` on the intended account. Review resources and estimated recurring charges.
4. Only after credit/quota/plan approval, explicitly apply the intended resource set using the approval variable. Verify SSM reachability, 4 GiB memory, encrypted gp3 storage, strict no-ingress, 22:00 IST nightly stop and actual start/stop cycle.
5. Build and deploy actual **ARM64** Java/Flutter Docker images and PostgreSQL over AWS SSM; run existing real Postgres/Flyway/Java/Flutter same-origin API integration smoke. Back up database to an independent store and test restore. Add a public HTTPS domain only after deliberate SSL/network design; no insecure HTTP auth.
6. Use only PAPER and SHADOW after required safety, data licensing, audit and broker reconciliation checks. LIVE orders, research model promotion and capital authorization remain disabled.

## Deployment status

**Only the source and offline CI exist.** No EC2, EBS, S3, Scheduler, SSM session, domain, model worker or RDS resources have been provisioned through this path. Existing Lightsail OIDC preflight in the corrected account failed with `sts:AssumeRoleWithWebIdentity`; therefore account-specific IAM setup is still a blocker for any new OIDC deployment and must not be circumvented by broadening trust permissions.
