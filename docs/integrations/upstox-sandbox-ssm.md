# Upstox F&O non-funded sandbox — secure SSM workflow

The upstream Upstox developer Sandbox app is **separate** from the individual/algo
trading live application, live OAuth sessions, analytics tokens, and all PAPER
backend runtime parameters.

## Existing credential location

AWS account: `098859530205` (verify you are in the correct account);
region: `ap-south-1` (Mumbai).

| Setting | Exact value |
|---|---|
| SSM parameter | `/minttick/upstox/sandbox/access-token` |
| SSM type | `SecureString` |
| Value | Sandbox **access token** from Upstox, **not** API key or API secret |
| Encryption | AWS-managed `alias/aws/ssm` or a specifically authorized customer-managed KMS key |

Never paste the access token into a GitHub secret, workflow input, mobile app,
log, commit, or chat. Do not overwrite `/minttick/upstox/client-id`,
`/minttick/upstox/client-secret` or production customer OAuth session values.

## IAM bootstrap (one time, separate from trading infrastructure)

Deploy
`infra/ec2-paper/bootstrap/upstox-sandbox-ssm-reader-iam-only.yml`
as a **new** CloudFormation stack in the same AWS account, region `ap-south-1`,
for example `minttick-upstox-sandbox-ssm-reader`, acknowledging IAM capabilities.
It creates a dedicated role
`arn:aws:iam::098859530205:role/minttick-upstox-sandbox-ssm-reader`
that can only `ssm:GetParameter` the one sandbox token. It does not modify
existing PAPER/production instances or roles, and grants no broker order
credentials. This repo's GitHub OIDC uses a **customized dev-branch subject**
(`repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/dev`).
If GitHub changes its OIDC subject configuration, role assumption fails closed.
Do not broaden the trust policy without validating the actual claim.

For `alias/aws/ssm`, no extra customer-managed KMS grant is expected.
For a customer-managed KMS key, a separate narrowly scoped `kms:Decrypt`
permission on that key and an appropriate key policy are required;
**do not** grant global KMS decrypt.

## Validated dry-run and manual sandbox execution

On PRs to `dev`, the
`upstox-fno-sandbox-contract.yml` workflow executes zero-network unit tests.
No live/sandbox provider call is made on PR or push.

After role bootstrap and merging changes to `dev`, open **Actions →
Upstox F&O sandbox-only order lifecycle contract → Run workflow**.
Choose branch **dev**, choose confirmation **SANDBOX_ONLY**, and enter a
**source-verified NSE_FO contract key**, quantity in broker units, positive
limit price and its tick size. Do not use guessed contract keys or lot sizes.

The manually gated `provider-sandbox` job assumes the dedicated OIDC role,
loads the exact SSM parameter **into one Python process only**, and calls
only `https://api-sandbox.upstox.com` for one bounded
place → modify → cancel order lifecycle. The token is not written to
`GITHUB_ENV`, output, file, or logged. Missing SSM access, unexpected
execution context, invalid token, invalid instrument or ambiguous API result
fails closed without fallback to live host or production credentials.

Successful unit tests prove the mocked contract only.
A successful provider sandbox run proves simulated sandbox API acceptance
only, **not** live F&O entitlement, broker order placement capability,
market feed quality, strategy profitability or 43-strategy readiness.

GitHub workflow:
https://github.com/operationReact/AiTradingSystem/actions/workflows/upstox-fno-sandbox-contract.yml
