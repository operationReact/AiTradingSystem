# MintTick — Angel One SSM-backed read-only market-data pilot

## Scope and safety boundary

The existing `dev` Spring Boot/Flutter product can use a broker-neutral
`MarketDataProvider`. For a private personal pilot, select
`MARKET_DATA_PROVIDER=ANGEL_ONE_LAMBDA`. The backend invokes
`minttick-dev-angelone-feed` using the existing AWS EC2 instance role.

The Angel One API key, client code, login PIN and TOTP seed stay encrypted in
AWS Systems Manager Parameter Store. Only the Lambda execution role may read
them. EC2/Flutter/GitHub never retrieve those values; no fixed AWS access keys
are needed. All order placement, change, cancellation and real-money execution
remain outside this service. Market-data access does not authorize commercial
redistribution to other MintTick users.

## SecureString parameters already created by the operator

AWS account **098859530205**, region **ap-south-1** (Mumbai):

| Exact parameter name | Type |
| --- | --- |
| `/minttick/dev/angelone/api-key` | SecureString |
| `/minttick/dev/angelone/client-code` | SecureString |
| `/minttick/dev/angelone/login-pin` | SecureString |
| `/minttick/dev/angelone/totp-secret` | SecureString |

The last value is the long-term base32 TOTP enrollment seed, NOT a six-digit
one-time code. If a customer-managed KMS key was chosen, configure repository
variable `MINTTICK_FEED_SSM_CUSTOM_KMS_KEY_ARN` with the key ARN; otherwise
leave it unset for the default AWS-managed `aws/ssm` key.

No actual parameter value belongs in chat, GitHub variables or Actions logs.
The Terraform module references parameter **ARNs**, never the secret values.

## Step 1: one-time IAM bootstrap, without opening CloudShell or SSH

After this PR has merged to `dev`:

1. AWS Console -> CloudFormation -> **us-east-1 (N. Virginia)** -> Create stack.
2. Upload the repository template
   `infra/minttick-market-feed/bootstrap-github-oidc-role.yml`.
3. Confirm the change set contains only `MintTickFeedGithubRole` and
   `MintTickFeedPaperInvokeOnly` (IAM role and fixed read-only invocation policy),
   and acknowledge **CAPABILITY_NAMED_IAM**.
4. This requires that the repository's existing GitHub OIDC identity provider is
   still configured for the custom subject containing repository and owner IDs.
   No new OIDC provider should be created.
5. In GitHub -> repository -> Settings -> Secrets and variables -> Actions ->
   **Variables**, set:
   `MINTTICK_FEED_DEPLOY_ROLE_ARN=arn:aws:iam::098859530205:role/minttick-feed-github-deploy`.
   
This IAM bootstrap alone creates no EC2, ALB, Gateway, RDS, or Lambda runtime.

## Step 2: Terraform through GitHub Actions (no local setup)

Open GitHub Actions -> **MintTick market feed - explicit plan or apply**,
select **dev**, choose `operation=plan` first. Read the Terraform diff and
expected charges. Only then explicitly select `operation=apply` in a new
manual run. This creates:

- One ARM64 Python 3.12 Lambda, on-demand, 256 MiB, concurrency reserved to 1.
- One Lambda execution IAM role scoped to exact four `ssm:GetParameters` ARNs.
- Optional KMS decrypt through SSM if customer-managed key ARN is configured.
- One 3-day retention CloudWatch log group.
- No public HTTP endpoint, ALB, VPC, NAT, static IP, EC2, RDS, or order authority.

State uses the existing encrypted versioned S3 bucket with a **separate key** in
`us-east-1`, S3 lockfile and no local GitHub runner state. Terraform does not
contain or retrieve the plaintext SecureString values.

AWS Lambda, S3 state, logging, KMS and usage can incur charges, even if likely
low for the private pilot. No fee guarantee or automatic deployment is implied.

## Step 3: configure the existing PAPER backend without broker secrets

In the restricted host-local EC2 `runtime.env` (not in Flutter, GitHub, or
Terraform state), set only these **nonsecret** values:

```dotenv
MARKET_DATA_PROVIDER=ANGEL_ONE_LAMBDA
MARKET_DATA_LAMBDA_FUNCTION_NAME=minttick-dev-angelone-feed
```

The existing `infra/ec2-paper/paper-compose.yml` forwards these two values
only to Spring Boot. It still hard-blocks all Angel One credentials and
preserves `TRADING_MODE=PAPER`. A separately approved SSM release on the
running EC2 instance is required before the new image and configuration are
operational. The existing nightly 22:00 IST stop applies. The application is
currently loopback-only; this work does not make it publicly accessible.

## Step 4: acceptance with no secret leakage

1. In AWS Lambda Console (Mumbai), invoke private test event
   `{"operation":"status"}`. It returns `ok=true` only if all four SecureStrings
   were fetched (it never echoes them).
2. Test a **real, currently active** NSE equity instrument using its verified
   trading symbol and provider token. Do not invent tokens or scrape unofficial
   market feeds.
3. Query existing authenticated Spring API
   `GET /api/market-data/instruments?query=RELIANCE&exchange=NSE`, then
   `GET /api/market-data/quotes?instrumentId=<activeId>`.
4. Run an authenticated bounded historical backfill and query normalized candles.
5. Confirm verified live timestamps, market-hours behavior, expiry/re-login,
   no cross-user quote sharing, and no permission to place orders.
6. After successful private pilot, consider index support and streaming. Full
   live Home-screen connectivity requires separate Flutter work and verified
   market-data display rights.

## References

- Angel One SmartAPI: https://smartapi.angelone.in/docs
- AWS SSM SecureString: https://docs.aws.amazon.com/systems-manager/latest/userguide/secure-string-parameter-kms-encryption.html
