# PAPER EC2 deployment via SSM — activation runbook

This implementation stays **inactive** until a separate one-file GitHub request is merged to `dev`. A code PR by itself does not create AWS infrastructure, start EC2, connect a broker or deploy an application.

## 1. Review and update the existing AWS bootstrap

Existing account `098859530205`, CloudFormation stack `aitrading-paper-ec2-bootstrap` in **us-east-1**. Update that exact stack using the latest reviewed `dev/infra/ec2-paper/bootstrap/state-preflight.cfn.json` template, after this PR merges. Review the change set before updating:

- CREATE `PaperReleaseArtifactBucket`: `aitrading-paper-releases-098859530205`, public access blocked, default SSE-S3, versioned, release objects expiring after 3 days.
- CREATE its TLS-only bucket policy allowing `s3:GetObject` on `releases/*` solely to the existing EC2 SSM instance role, with no public grants.
- CREATE `PaperGithubSsmDeployRole`: `aitrading-paper-ec2-ssm-deploy`, OIDC trusted only from the immutable GitHub `dev` identity, narrowly scoped `ssm:SendCommand` on the exact EC2 plus AWS-RunShellScript, `ssm:GetCommandInvocation`, SSM/EC2 inventory, exact Scheduler read and release-prefix S3 object upload.
- CREATE 2 output values for bucket name and deploy role ARN.

**Block the update** if the change set proposes replacing/deleting any existing state bucket, role, scheduled stop or EC2 resource. This stack creates no EC2 instances and should not alter the 22:00 IST nightly stop or main-branch manual controls.

## 2. Validate existing running host via SSM (one-time)

Use the [manual main-branch GitHub control](https://github.com/operationReact/AiTradingSystem/actions/workflows/paper-ec2-manual-control.yml) to **start** the existing `i-09c01b470fb39b85a` for an authorized testing session. Check its `Status` after startup. Starting EC2 incurs running-hour charges.

The SSM readiness workflow is activated only when a **separate one-file commit** adds `infra/ec2-paper/requests/ssm-preflight.json` to `dev` with the exact validator schema. It checks the EC2 identity, `running` state, SSM `Online`, daily stop schedule and host-side ARM64/Docker/Compose/AWS CLI/cloud-init/0600 secret file. It does not change application or database state.

If SSM is not Online, diagnose the existing EC2 role, VPC egress and cloud-init first. Do not open SSH, 8080, 8088 or PostgreSQL ingress.

## 3. Build and deploy the app

Only after readiness succeeds, a separate exact one-file `infra/ec2-paper/requests/ssm-release.json` commit to `dev` starts the release workflow:

1. Builds production Flutter Web at the reviewed GitHub SHA.
2. Builds/executes actual ARM64 Spring Boot Java 21 and ARM64 Nginx images **on the GitHub runner** (not on EC2).
3. Packs only `images.tar.gz`, `paper-compose.yml` and `run_paper.sh` in a tar archive; records an independent SHA256 digest.
4. Assumes the restricted **dev-only** SSM deployment role and confirms exact running PAPER EC2, SSM Online and nightly stop schedule.
5. Uploads the content-addressed encrypted artifact to `releases/{commit}/{sha256}/release.tar`, expiring after three days.
6. Runs only the version-controlled SSM host script. The host downloads the artifact using its instance role, verifies SHA256 and exact archive members, loads both images, reuses generated root-owned local secrets, backs up any running PostgreSQL before upgrades, and checks PostgreSQL-backed Java, Flutter static page and Flutter→Java proxy health.
7. The GitHub run marks success **only** when the SSM command returns Success after connected health checks.

SSM runs on the existing `t4g.medium`; neither workflow can create/start/stop EC2, replace the EBS volume, change security groups, perform Terraform applies, or enable LIVE broker orders. The independent 22:00 IST Scheduler can stop the host even during a rollout; the workflow must fail safely rather than silently restart it.

## 4. Before using with important retained user data

Host-local PostgreSQL volumes persist across EC2 stop/start, but a same-EBS `pg_dump` is not disaster recovery. Implement off-instance encrypted backups, retention and verified restores before retaining valuable data. The UI/API are bound to `127.0.0.1`; a separate explicitly reviewed secure access path is required before anyone can browse the remote Flutter application.
