# Manual EC2 Start / Stop / Status — AI Trading PAPER

In [GitHub Actions](https://github.com/operationReact/AiTradingSystem/actions/workflows/paper-ec2-manual-control.yml), select **PAPER EC2 - manual Start / Stop / Check status**, **Run workflow**, branch **main**, then choose:

- **status** — read the actual AWS instance state and validate the 22:00 IST stop schedule. Makes no changes.
- **start** — only starts the approved stopped PAPER EC2. If it is already running, succeeds with a no-op. If it is stopping, waits for it to stop before starting.
- **stop** — only stops that PAPER EC2. If it is already stopped, succeeds with a no-op. If it is pending, waits for startup before stopping.

The workflow handles transitions, state races and repeated clicks without issuing multiple start/stop mutations per run. Its shared `concurrency.queue: max` group serializes and queues up to **100** manual requests instead of cancelling pending requests. GitHub does **not** guarantee FIFO execution order; inspect **status** after rapid mixed Start/Stop clicks to verify the intended final state.

## Safety and cost

- **AWS account:** `098859530205`; **region:** `ap-south-1`.
- **Only instance:** `i-09c01b470fb39b85a` (`t4g.medium`, PAPER).
- If the stopped `t4g.medium` cannot start because AWS reports `InsufficientInstanceCapacity`, you may use AWS EC2 Console → select the **same stopped instance** → Actions → Instance settings → Change instance type, then select `m6g.medium` (or `m7g.medium` if unavailable). These are 4 GiB ARM64 options; hourly costs can be higher. **Do not launch a replacement instance or change Availability Zone/subnet.** The lifecycle script accepts only `t4g.medium`, `m6g.medium`, or `m7g.medium` on the exact original instance. You must separately reconcile the Terraform instance_type variable before any Terraform apply, and update the dev SSM preflight's type check before a new deployment. Automatic 22:00 IST stop remains unchanged.

The workflow uses a dedicated **main-branch OIDC role** with just exact-instance EC2 Start/Stop, region-bound instance Describe and exact nightly-stop schedule Describe permissions. There is no access to RunInstances, TerminateInstances, RebootInstances, Terraform state, IAM modification, trading execution, or other AWS services.
- **Before a new start**, the workflow verifies the existing enabled EventBridge stop schedule at 22:00 Asia/Kolkata. It does not change that schedule.
- If started **after 22:00 IST**, the next scheduled stop occurs **the following day at 22:00 IST** unless manually stopped first. Manual Stop is always available.
- **Stopped** EC2 compute does not accrue running-instance charges, but the encrypted **35 GiB EBS** storage and any other billable storage continue to accrue charges.
- This controls **only EC2**. PostgreSQL/application services are not deployed on this host yet; no independent RDS service has been provisioned. Future instances/databases require separately authorized service-specific IAM and explicit allowlisted controls.

## One-time AWS setup

After the control is merged to main, update the existing CloudFormation stack `aitrading-paper-ec2-bootstrap` in **us-east-1** using the latest [dev bootstrap template](https://github.com/operationReact/AiTradingSystem/blob/dev/infra/ec2-paper/bootstrap/state-preflight.cfn.json). Review that the update **creates only one IAM role** (`aitrading-paper-ec2-manual-control`) and its CloudFormation output, without replacing or deleting existing resources. Until this role exists, GitHub Actions will reject Start/Stop/Status at the OIDC assume-role step; no instance is modified.

Do **not** add AWS access keys as GitHub Secrets, and do not start the instance just to verify the workflow's appearance.
