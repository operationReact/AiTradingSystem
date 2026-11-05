# GitHub Actions inventory and operating policy

Updated 2026-11-06. Repository `operationReact/AiTradingSystem`.

## Guiding rule

**Workflows are executable automation, not an archive.** Completed rescue, temporary
bootstrap, one-time diagnostics, and superseded deployment workflow YAML should be
removed from `.github/workflows` after its success has been confirmed. Git
history retains the source code. The approved Actions cleanup also permanently
removed 80 old run histories; those run URLs no longer resolve. Its run metadata
index was retained as a 90-day artifact on [cleanup run 42130915200](https://github.com/operationReact/AiTradingSystem/actions/runs/42130915200).

Do not delete the underlying tested scripts, database migrations, Terraform,
contracts, or safety tests simply to reduce the list of visible Actions.

## Everyday operator controls

- **`main`** — `paper-ec2-manual-control.yml`: manually Start, Stop or
  check Status for the exact, protected PAPER EC2
  `i-09c01b470fb39b85a` in account `098859530205`, Mumbai
  `ap-south-1`. Uses the existing main-only OIDC IAM role and the 22:00 IST
  nightly stop guard. This is the one operator-facing GitHub workflow.
- **`dev`** — `ec2-paper-ssm-readiness.yml`: read-only exact EC2/SSM
  readiness before software deployment; intentionally requires one-file review.
- **`dev`** — `ec2-paper-ssm-app-deploy.yml`: real PAPER ARM64 Java,
  PostgreSQL 16 and Flutter Web deployment over restricted SSM; requires
  exact one-file request and checks health. Does not create/terminate/start
  EC2, expose network ports or enable LIVE orders.

## Keep CI and engineering quality gates

The phase-based validation, trading backtest/risk/accounting approval contracts,
dependency scanning, Flutter/mobile builds, Java tests and Python/PyTorch/XGBoost
checks, `ec2-paper-arm64-images.yml` and
`ec2-paper-cost-policy.yml` remain important and should be retained.
Many v4 worker/research contracts are **offline design and regression gates**,
not running cloud services. Do not mistake them for paid AWS deployments or
delete them without evaluating product coverage.

## Retired from active dev workflows

### One-time bootstrap/connected-smoke rescues

- `paper-ec2-bootstrap-curl-minimal-fix-once.yml`
- `paper-ec2-bootstrap-stepwise-repair-once.yml`
- `paper-ec2-compose-pinned-integrity-fix-once.yml`
- `paper-ec2-connected-smoke-audit-once.yml`
- `paper-ec2-connected-smoke-stdin-fix-once.yml`
- `paper-ec2-dnf-package-diagnosis-once.yml`
- `paper-ec2-ssm-bootstrap-repair-once.yml`
- `paper-ssm-bootstrap-audit-once.yml`

All were consumed during the successful November 2026 deployment.
The live host passed its first deployment, Flyway, backend and Flutter smoke:
[app deploy](https://github.com/operationReact/AiTradingSystem/actions/runs/42123887247)
and [independent audit](https://github.com/operationReact/AiTradingSystem/actions/runs/42125155692).
The host was then safely stopped:
[cost-control run](https://github.com/operationReact/AiTradingSystem/actions/runs/42125435558).

### One-time IAM/OIDC diagnostics

- `ec2-paper-ssm-iam-verification.yml`
- `ec2-paper-ssm-oidc-differential.yml`
- `diagnose-oidc-claims.yml`
- `verify-aws-oidc.yml`

The exact dev-only OIDC SSM role has been verified live. The daily deployment
still validates IAM when it runs. Do not leave a second OIDC diagnosis in Actions.

### Retired previous AWS architecture

- `deploy-flutter-web-on-request.yml` (old `us-east-1` ECS / former account)
- `deploy-paper-backend-on-request.yml` (old `us-east-1` ECS / former account)
- `deploy-paper-budget-aws.yml` (old `us-east-1` deployment)
- `deploy-live-aws.yml` (old ECS LIVE target, not authorized for current PAPER)
- `provision-live-aws.yml` (old LIVE Terraform infrastructure)

These are **not** the production-deployment pipeline for the new Mumbai PAPER
EC2. Removing old live provision/deploy entry points avoids accidental launch
into a different AWS account/region. The LIVE Terraform source and its offline
validation may still be retained for future planning, but must not be deployed
without separate authorization.

## Archived historical EC2 and Lightsail automation

The ten one-time EC2 paid provisioning, drift recovery, plan, and preflight
workflows, plus the retired Lightsail bootstrap/app deploy/contract, are retained
as **non-executable regression fixtures** under
`docs/operations/archived-actions/`. Their test references were redirected
from `.github/workflows` to the archived copies, and
`ec2-paper-cost-policy.yml` executes the offline tests during development.
Do not reactivate those deployments by simply restoring YAML: the original
AWS architecture and permissions are superseded.

The real disposable Java/PostgreSQL/Flutter connected integration test was
retained as `.github/workflows/paper-connected-db-e2e.yml`. It does not
use AWS or LIVE broker orders.

GitHub Actions exposes only workflow files under `.github/workflows`, so
the archived fixtures remain reviewable without appearing as actionable
workflow options. Their previous run histories may remain until a separately
reviewed metadata-preserving cleanup.

The many v4 worker/research contract checks are preserved: they are
offline application safety and regression checks, not cloud deployments.
Further consolidation can combine them without discarding test coverage.

## Recovery and deployment safeguards

- The latest PAPER application is deployed locally on the existing encrypted
  EBS-backed EC2 only. It is **not public**; no SSH, backend or PostgreSQL
  port is opened.
- The stopped EC2 incurs EBS storage cost, not running-hour compute cost;
  manual Start/Stop is on `main`, never `dev`.
- The scheduler still independently stops EC2 at 22:00 IST.
- If a retired workflow is needed for **forensic historical review**, inspect
  a commit predating the cleanup (e.g. `dd8e90260a207ce3878003c0ba4387e67651c46b`
  for the original dev YAML); Git history stores its exact content.
  Re-introduction as executable GitHub Actions requires a reviewed PR.
- GitHub can retain historical workflow registrations after YAML removal.
  A separately reviewed cleanup can disable those obsolete registrations and
  delete only their fully completed runs after archiving their metadata.
