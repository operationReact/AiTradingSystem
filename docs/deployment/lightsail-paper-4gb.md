# PAPER Lightsail 4 GB — separate from legacy ECS and Alpha Vidhyarthi

**Status: GitHub deployment preparation only.** This path does not change the existing `infra/terraform/` ECS/RDS plan, migrate its Terraform state, create a database, or enable any broker orders. Cloud provisioning requires a separately authorized manual workflow run after IAM and AWS credit verification.

## Targets and budget

- GitHub repository: `operationReact/AiTradingSystem`; allowed branch: `dev` only.
- AWS account: `816344830716`; region: `us-east-1` (the existing AI Trading infrastructure target). **Do not confuse with Alpha Vidhyarthi's AWS account or Mumbai region.**
- Single named server: `aitradingsystem-paper-dev`, Ubuntu 24.04 LTS, Lightsail Linux/Unix `medium_3_0`, **4 GB RAM / 2 vCPU / 80 GB disk / $24 USD per month** with public IPv4.
- The initial plan deliberately contains no managed Lightsail PostgreSQL, RDS, ALB, NAT Gateway, ECS cluster, Python trained-model worker or LIVE execution. Later deployment may run a local PostgreSQL volume on this **non-high-availability pilot**; backups are mandatory before accepting actual user data.
- Separate 4 GB Alpha instance would add another ~$24/mo. Two base instance charges ~$48/mo **only if the services are eligible under applicable AWS account promotional credits**. Verify the exact credit balance, eligible services, account, expiry and billing scope in AWS Console > Billing and Cost Management > Credits *before* running `provision`. Credits are not assumed transferable between AWS accounts. Snapshot, bandwidth overages, domain, taxes and managed database costs are additional.
- Lightsail instances incur usage charges while stopped as well as when running; deleting the instance ends its usage billing but risks data loss. No workflow here deletes any resource.

## One-time AWS/IAM steps (no local PC tooling)

1. In the **AI Trading** AWS account `816344830716`, confirm IAM > Identity providers contains `token.actions.githubusercontent.com` with audience `sts.amazonaws.com`. You said this provider already exists; **do not create a duplicate**.
2. In IAM > Policies, create one customer-managed policy using the exact JSON at [iam-paper-provision-policy.json](../../infra/lightsail/iam-paper-provision-policy.json), e.g. name `aitradingsystem-lightsail-paper-policy`. The policy permits inspection of Lightsail regional plans and instance inventory, creation of a request-tagged PAPER instance, and access/port changes only to tagged AI Trading PAPER instances. It has no delete, RDS, ECS, EC2, IAM, Secrets Manager, LIVE trading, or billing permissions.
3. In IAM > Roles, create a new **Web identity** GitHub OIDC role named `aitradingsystem-github-lightsail-paper`. Use the [iam-trust-dev.json](../../infra/lightsail/iam-trust-dev.json) as its trust policy (update via **Trust relationships > Edit trust policy** if the console generated a broader default). The `aud` and `sub` conditions must match the exact dev-branch GitHub OIDC token. Attach **only** the policy from step 2. Do not attach AdministratorAccess or AmazonLightsailFullAccess. This is a **new Lightsail-specific role**; the existing ECS role can remain untouched.
4. In GitHub > `operationReact/AiTradingSystem` > Settings > Secrets and variables > Actions > **Variables**, add `AWS_LIGHTSAIL_ROLE_ARN` with value:

   `arn:aws:iam::816344830716:role/aitradingsystem-github-lightsail-paper`

   No AWS access key, secret access key, SSH private key, broker token or database password is required in GitHub.
5. Verify your GitHub OIDC subject format. New repositories / customized OIDC subjects can use immutable repository IDs. If an OIDC assume-role call is rejected, use the repository's existing *diagnostic* workflow to inspect nonsecret claims and update the trust policy to the exact subject; never replace it with a wildcard.

## Provision through GitHub Actions

Open [AI Trading PAPER Lightsail 4GB bootstrap](https://github.com/operationReact/AiTradingSystem/actions/workflows/lightsail-paper-bootstrap.yml) and select `dev`.

- First choose `operation=preflight`. It only checks OIDC, exact account/region, current Lightsail 4 GB bundle, Ubuntu image, availability zone and whether the dedicated named instance exists. It **cannot incur instance-creation charges**.
- Once AWS credits are confirmed in the exact account, run the same workflow with `operation=provision` and enter the exact confirmation **`CREATE_PAPER_4GB`**. Creation is skipped when the correct instance already exists; it never resizes/adopts a different server, even if named similarly. Provisioning invokes one AWS Lightsail `CreateInstances` for the fixed 4 GB bundle.
- The one-time launch script installs Ubuntu Docker and Docker Compose, generates a private root-owned database secret file on the VM, and does **not start the backend or worker**. It restricts instance public ports to Lightsail browser SSH only; application, database, model and broker ports remain closed. If firewall lockdown fails, the job fails with an explicit manual follow-up warning.
- Provisioning is the point where AWS resource billing can begin. Merely merging this PR or running ordinary PR CI does not spend on Lightsail.

## Before application rollout

- Assign a Lightsail **static public IP** to this single instance (one attached IP is included at no extra Lightsail IP charge). Configure a real HTTPS hostname whose DNS points to that address. Avoid exposing login/cookie authentication over HTTP.
- Review a separate backend + Flutter + PostgreSQL Docker Compose rollout, 4 GB memory caps, HTTPS proxy, data snapshots/backups, restore drill, restricted SSH, audit retention and rollback. Do not expose PostgreSQL 5432 or research worker port 8090 publicly.
- Validate an actual deployed backend/Flutter session and `/api/health` behind HTTPS. Run PAPER-only integration and broker *read-only* reconciliation; keep model research flags OFF until independent verified model licensing, artifact custody, worker TLS and sustained PAPER/SHADOW evidence exist.
- LIVE broker order routing and capital authorization remain disabled.

## Rollback and failure policy

Do **not** automatically delete an instance on any failure: it might contain application data. If the manual `provision` run fails after AWS creates the instance, inspect Lightsail for `aitradingsystem-paper-dev`, secure firewall ports, and resolve that resource rather than attempting a blind second creation. To avoid continuing charges for an unused server, explicitly delete it from the Lightsail console only after confirming that no needed data is present and retaining any required snapshots. The workflow has no delete permissions.

AWS references: [Lightsail bundles](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html), [OIDC GitHub trust](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [AWS Lightsail pricing](https://aws.amazon.com/lightsail/pricing/).
