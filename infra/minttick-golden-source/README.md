# Golden Source D20-04C — review-only AWS S3/KMS policy contract

**No AWS resources, cost, licences or permissions are created by this module.** It contains two Terraform `aws_iam_policy_document` data blocks and JSON outputs only. It is intentionally separate from `infra/terraform`, `infra/minttick-market-feed`, EC2 PAPER, and all deploy workflows.

The code assumes a *separately provisioned* S3 archive bucket with owner `098859530205`, region `ap-south-1`, versioning ENABLED, strict private public-access settings, SSE-KMS with a pinned customer-managed key, restricted IAM identity, auditing and lifecycle cost review.

## Read-only review procedure

- Inspect `variables.tf`, `policies.tf` and their static security tests. Required variables are a dedicated bucket name, same-region KMS key ARN, and a dedicated research worker role ARN. This is **not** the existing execution/broker role.
- CI checks `terraform fmt`, `terraform validate` and a dependency-free Python unittest using only a GitHub Actions runner with `contents: read` permission, no AWS credentials/OIDC. No `terraform plan`, `apply` or cloud provisioning runs.
- Policy JSON previews are constrained to this exact bucket, `golden-source/bronze/v1/` object prefix, pinned KMS key via S3, plus S3 bucket posture inspection needed by D20-04B. The worker cannot delete source objects or invoke brokers/orders using these outputs.
- The optional proposed bucket policy denies non-TLS access, incorrect or absent request SSE-KMS headers for bronze writes, and S3 bronze object access from any principal other than the dedicated research worker role. The existing S3 full Public Access Block, default encryption and bucket versioning must be verified independently before release.
- Do **not** attach these previews or provision anything until a human-reviewed model of legal retention, cost, region/account authorization, lifecycle, audit logging, versioning and deletion/revocation is approved. AWS IAM evaluation against the actual role and bucket must be separately tested. A green offline test does not prove live role permissions.
- In particular, future legal deletion, cross-account access and emergency rotation should use a separately audited path; this narrow role deliberately has no `DeleteObject`, no IAM mutation and no live-trading authority.

**D20-05 remains BLOCKED:** no purchased and independently verified rights for 20-year NSE/BSE history; no production provider, no calendar-aware daily price validator, no runtime storage bucket, no historical files in Golden Source. The platform can proceed with offline code and tests without pretending that full data is available.
