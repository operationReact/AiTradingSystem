# MintTick customer authentication (Terraform-only)

This stack creates an Amazon Cognito **Essentials User Pool** and a **public**
Flutter app client in Mumbai by default. It intentionally does **not** create an
AWS Cognito Identity Pool or grant end users AWS IAM credentials. No broker
integration, PAN, Aadhaar, phone number or trading account details are part of
the customer registration flow.

## Configuration and deployment

Authenticate to AWS with a scoped, auditable deployment role (prefer GitHub OIDC).
Run `terraform init`, `terraform plan -out=tfplan`, review, then
`terraform apply tfplan`. Use a remote encrypted backend with state locking
for production. The provider must be pointed at the intended AWS account.
Do not run `terraform destroy` on a real identity directory.

Read outputs `cognito_user_pool_id`, `cognito_client_id`, and
`cognito_region`, and build Flutter with:
`--dart-define=MINTTICK_COGNITO_REGION=ap-south-1`,
`--dart-define=MINTTICK_COGNITO_USER_POOL_ID=<pool-id>`,
`--dart-define=MINTTICK_COGNITO_CLIENT_ID=<public-client-id>`.
These IDs are configuration, **not passwords**. They must still be checked
against the intended environment to prevent dev/prod cross-login.

Cognito enforces **15+ character passwords**, email OTP confirmation,
SRP-based password authentication, short-lived access tokens, revocable
refresh tokens, and optional TOTP enrollment for future MFA UI support.
Identity-provider tokens must be verified server-side against the exact
issuer and app client ID before issuing any application permissions.

## Permanent identity and disaster recovery

MintTick PostgreSQL owns its permanent `app_user.id` UUID. Link Cognito
`sub` through `app_auth_identity`; **never** use Cognito subject as
foreign keys in watchlists, positions or orders. Store no passwords or
password hashes in PostgreSQL.

Back up PostgreSQL with encrypted, restore-tested snapshots. Schedule
encrypted Cognito `ListUsers` attribute exports with restrictive access,
audit logs and a retention policy. Such exports **cannot restore passwords**.
In an unplanned loss of the pool, users will need secure password
re-enrollment unless replicated credentials are independently available.
Never auto-link identities by matching unverified email addresses.

When replacing Cognito, deploy and test the replacement IdP, import identity
attributes, re-enroll credentials as necessary, and atomically link verified
replacement identities to the existing MintTick UUIDs. Do **not** delete
Cognito before login, rollback and account recovery have been exercised.

## Cost and operational safeguards

The stack provisions only the User Pool and a public app client. AWS may
charge for Cognito MAUs, email/SMS delivery, threat-protection options and
other features outside the base free tier. Set a billing budget separately.
Protect the pool from deletion and keep AWS account backups outside the
primary deployment account where feasible.

**Status:** Terraform source only. It does not establish that an AWS User Pool
has been deployed or that real customer registration is available.
