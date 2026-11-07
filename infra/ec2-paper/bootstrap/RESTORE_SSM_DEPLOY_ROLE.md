# MintTick: restore only the existing EC2 SSM deploy IAM role

## Why

The verified EC2 start succeeded, but the dev SSM readiness workflow
[run 42351671393](https://github.com/operationReact/AiTradingSystem/actions/runs/42351671393)
failed before contacting EC2 with `Not authorized to perform sts:AssumeRoleWithWebIdentity`.
This may indicate the named role is missing or its trust policy does not match.
Do not re-use or replace the historical CloudFormation bootstrap stack.

## Safe one-time recovery (operator in AWS IAM/CloudFormation)

1. Check IAM Roles in AWS account **098859530205** for
   `aitrading-paper-ec2-ssm-deploy`.
2. **If missing only:** Use AWS CloudFormation **Create stack → With new resources**
   with `infra/ec2-paper/bootstrap/ssm-deploy-iam-only.json`.
   Use a NEW stack name `minttick-paper-ssm-deploy-iam`, acknowledge
   `CAPABILITY_NAMED_IAM` and inspect the change set.
   It MUST show **CREATE one AWS::IAM::Role** only — no replacements,
   deletions, existing CloudFormation stack changes, S3, or EC2 resources.
   IAM is global, though CloudFormation can be created in the region used
   for the other IAM recovery stack.
3. **If role exists:** DO NOT create a duplicate stack. Inspect IAM trust for
   GitHub OIDC subject
   `repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/dev`
   with audience `sts.amazonaws.com`. Restore the exact role's narrowly scoped
   policy and trust under the existing owner; do not wildcard the subject.
4. After the new role exists, start a **fresh read-only** SSM readiness request
   through `.github/workflows/ec2-paper-ssm-readiness.yml` (a new single-file
   `infra/ec2-paper/requests/ssm-preflight.json` commit to `dev`).
   Do not rerun an older workflow revision.

## Separate deployment prerequisites

- The SSM deploy IAM role does NOT create the release artifact bucket. Before
  full deploy, confirm existing bucket `aitrading-paper-releases-098859530205`
  exists and its policy allows the PAPER EC2 instance role to read
  `releases/*`. If missing, use a separate **create-only** recovery after
  verification; never update/replace `aitrading-paper-ec2-bootstrap`.
- The SSM-managed EC2 agent must be Online, the instance must be running, the
  host must have its local 0600 runtime secrets, and the nightly 22:00 IST
  shutdown must remain enabled.
- Full app deploy is separately triggered via an exact one-file
  `infra/ec2-paper/requests/ssm-release.json` commit. Then Cognito opt-in
  is a third one-file `enable-cognito.json` request.
- The market-data Lambda `minttick-dev-angelone-feed` uses a **different**
  isolated IAM bootstrap and Terraform workflow. No private Angel One
  credentials or order execution belong on EC2, Flutter, or in GitHub.

This recovery template is intentionally IAM-only; successful creation does
not itself deploy any application or prove market feed connectivity.
