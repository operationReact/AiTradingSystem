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
3. In IAM > Roles, create a new **Web identity** GitHub OIDC role named `aitradingsystem-github-lightsail-paper`. Use the [iam-trust-dev.json](../../infra/lightsail/iam-trust-dev.json) as its trust policy (update via **Trust relationships > Edit trust policy** if the console generated a broader default). The `aud` and `sub` conditions must match the exact dev-branch GitHub OIDC token. Attach **only** the policy from step 2. The reviewed policy also includes `lightsail:GetInstanceAccessDetails` for tagged AI Trading PAPER instances so GitHub can retrieve temporary SSH certificates at rollout, rather than using permanent SSH keys. Do not attach AdministratorAccess or AmazonLightsailFullAccess. This is a **new Lightsail-specific role**; the existing ECS role can remain untouched.
4. In GitHub > `operationReact/AiTradingSystem` > Settings > Secrets and variables > Actions > **Variables**, add `AWS_LIGHTSAIL_ROLE_ARN` with value:

   `arn:aws:iam::816344830716:role/aitradingsystem-github-lightsail-paper`

   No AWS access key, secret access key, SSH private key, broker token or database password is required in GitHub.
5. Verify your GitHub OIDC subject format. New repositories / customized OIDC subjects can use immutable repository IDs. If an OIDC assume-role call is rejected, use the repository's existing *diagnostic* workflow to inspect nonsecret claims and update the trust policy to the exact subject; never replace it with a wildcard.

## Starting the AWS workflows when the default branch is `main`

This repository's current default branch is **`main`**, but we deliberately deploy only code and GitHub OIDC from **`dev`**. GitHub's `workflow_dispatch` **Run workflow** button requires its workflow file on the default branch, so a manually selected workflow file existing only on `dev` might not have a usable button. Do **not** switch the default branch or merge the trading application to `main` just to launch deployment.

Instead, after setting up the IAM role, trigger the audited dev-only workflows by committing exactly one small, nonsecret request file **to `dev`**, using GitHub's **Add file > Create new file** UI and choosing **Commit directly to the dev branch** (or a PR merged to dev). No request file is committed during this setup; no AWS workflow executes merely because the workflows were merged.

For a **non-billable AWS inspection**, create `infra/lightsail/requests/bootstrap.request.json`. The offline CI validator now accepts an explicitly committed, correctly scoped request file instead of incorrectly requiring all request files to remain absent:

```json
{"kind":"bootstrap","target":"aitradingsystem-paper-dev","operation":"preflight","confirmation":""}
```

Read the [bootstrap workflow runs](https://github.com/operationReact/AiTradingSystem/actions/workflows/lightsail-paper-bootstrap.yml) on `dev`, and verify the expected $24 / 4GB / 2-vCPU Linux plan and account. Check Billing > Credits in **account 816344830716**, including eligible service, expiry, and available balance, before any paid action.

Only after confirming the credit and preflight should you **edit the same file**, committing this exact paid-creation request to `dev`:

```json
{"kind":"bootstrap","target":"aitradingsystem-paper-dev","operation":"provision","confirmation":"CREATE_PAPER_4GB"}
```

The explicit confirmation is a gate to one named server and AWS charges may begin after this request merges. The workflow will reject any mismatched existing server and will not create a second instance of a different size. Wait until Ubuntu cloud-init completes before the next step.

For **internal-only** Java/PostgreSQL/Flutter rollout, create `infra/lightsail/requests/deploy.request.json` as a new committed file on `dev`:

```json
{"kind":"deploy","target":"aitradingsystem-paper-dev","operation":"deploy","confirmation":"DEPLOY_PAPER_LOCAL_ONLY"}
```

This triggers [internal-only application deployment](https://github.com/operationReact/AiTradingSystem/actions/workflows/lightsail-paper-app-deploy.yml). It requires an already-running tagged Lightsail instance and does not open a public app port. Re-running requires a new **real commit affecting that exact request path**; ordinary application merges and PRs do not trigger any paid or deployment workflow. Only the request file's exact shape and branch are accepted. Application logins remain inaccessible publicly until a separate HTTPS/domain rollout.

## Provision through GitHub Actions (legacy manual-dispatch alternative)

When/if the workflow becomes registered on the default branch, its `workflow_dispatch` option can also be used on `dev`; otherwise use the audited dev request files above. Do not assume a **Run workflow** button is present today.

- First choose `operation=preflight`. It only checks OIDC, exact account/region, current Lightsail 4 GB bundle, Ubuntu image, availability zone and whether the dedicated named instance exists. It **cannot incur instance-creation charges**.
- Once AWS credits are confirmed in the exact account, run the same workflow with `operation=provision` and enter the exact confirmation **`CREATE_PAPER_4GB`**. Creation is skipped when the correct instance already exists; it never resizes/adopts a different server, even if named similarly. Provisioning invokes one AWS Lightsail `CreateInstances` for the fixed 4 GB bundle.
- The one-time launch script installs Ubuntu Docker and Docker Compose, generates a private root-owned database secret file on the VM, and does **not start the backend or worker**. It restricts instance public ports to Lightsail browser SSH only; application, database, model and broker ports remain closed. If firewall lockdown fails, the job fails with an explicit manual follow-up warning.
- Provisioning is the point where AWS resource billing can begin. Merely merging this PR or running ordinary PR CI does not spend on Lightsail.

## Connected Docker smoke — prove the actual 4 GB app stack before AWS deployment

The repo now includes a separate [Lightsail PAPER connected Docker smoke workflow](https://github.com/operationReact/AiTradingSystem/actions/workflows/lightsail-paper-connected-smoke.yml). It runs when the PAPER Compose/Nginx/Docker test contracts change, using GitHub-hosted Ubuntu and ephemeral **non-AWS** test credentials, with no OIDC token.

It builds the actual Java 21 backend image and Flutter web image, starts the same `infra/lightsail/paper-compose.yml` stack with PostgreSQL 16, waits for Java `GET /api/health` and Flutter Nginx-proxied `GET /api/health` plus Nginx `/health`, queries the **real Flyway history** in PostgreSQL, validates a `pg_dump` schema-only backup, and inspects Docker's live port bindings for the required `127.0.0.1` restriction. The stack and disposable database volume are destroyed by the job cleanup. This is much stronger than YAML-only inspection but **not** an actual Lightsail deployment or authenticated login acceptance.

Only merge changes to the deployment stack after this exact-head smoke and other required CI succeed. Passing it does not permit public application access or LIVE trading.

## Manual application rollout — internal PAPER acceptance first

After the host's GitHub preflight/provision is complete and the Ubuntu bootstrap has finished, use [AI Trading PAPER Lightsail internal-only app deploy](https://github.com/operationReact/AiTradingSystem/actions/workflows/lightsail-paper-app-deploy.yml) on the `dev` branch and type **`DEPLOY_PAPER_LOCAL_ONLY`**.

This workflow builds the **actual Java Spring Boot backend** and **Flutter web release** on the GitHub-hosted runner (no model training on the 2-vCPU host), reads only the exact tagged 4GB host through the dedicated OIDC role and opens SSH **temporarily for the Actions runner's public IP**. It obtains short-lived SSH key/certificate and verified host keys via Lightsail's `GetInstanceAccessDetails`; no static SSH key or AWS key is saved in GitHub. In the `always()` cleanup it restores browser-SSH-only firewall rules and destroys temporary identity files.

It streams the two image builds directly to the VM via SSH (no ECR charges), preserving the root-generated database password/API key in `/opt/aitradingsystem-paper/runtime.env`. `infra/lightsail/paper-compose.yml` starts PostgreSQL 16 with a named persistent Docker volume, a Java 21 backend with a 1 GiB JVM heap and 1.5 GiB container limit, and a Flutter Nginx frontend with 256 MiB limit. Ports 5432/8090 are **never published**, and both localhost API 8080 and localhost frontend 8088 bind only `127.0.0.1`; no public 80/443 endpoint is activated. CORS is localhost-only; the paper client still requires its legitimate operator authentication secret.

The remote script validates the exact release SHA and local image tags, performs a local logical database backup on subsequent deployments (not a substitute for external snapshots), starts the stack and requires healthy `http://127.0.0.1:8080/api/health`, frontend `/health` and frontend-to-Java `http://127.0.0.1:8088/api/health` responses. Failure means **not accepted**; inspect the remote host via the Lightsail browser SSH console. Do not print DB/API credentials in support screenshots.

This deliberately tests **only internal service integration**, not public browser login, domain/certificate correctness, real market-feed licensing, signed models, genuine paper/shadow soak or production readiness. It does not expose a public HTTP service. Use **external snapshots and a restore test** before entering valuable data, as on-VM SQL backups disappear with disk loss. Later configure an attached static IP, owned DNS hostname and verified HTTPS before allowing public login or remote clients.

## Before public application rollout

- Assign a Lightsail **static public IP** to this single instance (one attached IP is included at no extra Lightsail IP charge). Configure a real HTTPS hostname whose DNS points to that address. Avoid exposing login/cookie authentication over HTTP.
- Review a separate backend + Flutter + PostgreSQL Docker Compose rollout, 4 GB memory caps, HTTPS proxy, data snapshots/backups, restore drill, restricted SSH, audit retention and rollback. Do not expose PostgreSQL 5432 or research worker port 8090 publicly.
- Validate an actual deployed backend/Flutter session and `/api/health` behind HTTPS. Run PAPER-only integration and broker *read-only* reconciliation; keep model research flags OFF until independent verified model licensing, artifact custody, worker TLS and sustained PAPER/SHADOW evidence exist.
- LIVE broker order routing and capital authorization remain disabled.

## Rollback and failure policy

Do **not** automatically delete an instance on any failure: it might contain application data. If the manual `provision` run fails after AWS creates the instance, inspect Lightsail for `aitradingsystem-paper-dev`, secure firewall ports, and resolve that resource rather than attempting a blind second creation. To avoid continuing charges for an unused server, explicitly delete it from the Lightsail console only after confirming that no needed data is present and retaining any required snapshots. The workflow has no delete permissions.

AWS references: [Lightsail bundles](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html), [OIDC GitHub trust](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [AWS Lightsail pricing](https://aws.amazon.com/lightsail/pricing/).
