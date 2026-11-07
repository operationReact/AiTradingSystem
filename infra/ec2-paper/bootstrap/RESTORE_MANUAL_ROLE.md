# Restore existing EC2 manual Start / Stop role without replacing resources

The IAM screen from 2026-11-07 shows that role `aitrading-paper-ec2-manual-control` is **missing**. GitHub Actions run `42344266440` failed at `sts:AssumeRoleWithWebIdentity`, before any EC2 state change.

## Recovery: CREATE ONLY, never update an existing stack

Use the dedicated CloudFormation template `infra/ec2-paper/bootstrap/manual-lifecycle-iam-only.yml` in AWS account `098859530205`.

1. AWS console → CloudFormation → **Create stack** → **With new resources (standard)**.
2. Region: **us-east-1** (IAM is global; region here is only the stack location). Upload this exact one-resource template.
3. Enter the **new and unique** stack name `minttick-paper-manual-control-iam`.
4. In the change summary, verify **one IAM role** with the exact name `aitrading-paper-ec2-manual-control`; **zero replacements or deletions**.
5. Acknowledge named-IAM capabilities and create. Verify `CREATE_COMPLETE`.
6. Return to [GitHub EC2 manual control](https://github.com/operationReact/AiTradingSystem/actions/workflows/paper-ec2-manual-control.yml), choose branch `main`, operation `status` first to verify permissions and the daily stop schedule; then choose `start`.

**Never select `aitrading-paper-ec2-bootstrap`, `minttick-auth-oidc-bootstrap`, or another existing stack for this repair.** Never use **Update stack / Replace current template** to add this role. Doing so may remove resources that are not defined in the replacement template.

The newly created IAM role trusts the two exact existing repository OIDC subject formats on branch `main`; it trusts no wildcard branch. It can start/stop only `i-09c01b470fb39b85a` in Mumbai with matching tags, and read the scheduled stop. It cannot create, terminate or reboot an instance.

The template has `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, to reduce accidental role deletion on stack removal/replacement. These guards do **not** prevent an administrator from deliberately deleting the IAM role, modifying its policy, or deleting other resources in other stacks.

**Existing stack protection:** enable CloudFormation termination protection for valuable existing stacks (CloudFormation → select stack → Stack actions → Edit termination protection). Termination protection does not prevent damaging **stack updates**. For any update to a resource-owning stack, review the CloudFormation change set and reject any unexpected Delete or Replace action. Do not install a replacement template that omits existing resources.

This is an isolated one-time IAM recovery. It does not itself deploy a backend, expose any application port, or create a new EC2 instance.
