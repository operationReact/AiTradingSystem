# MintTick public DNS and certificate bootstrap

**Intended domain:** minttick.com, www.minttick.com, api.minttick.com.
**Existing AWS account:** `098859530205`. **Public DNS:** Route 53 (global).
**CloudFormation + CloudFront-viewer certificate region:** `us-east-1`.
**Existing application runtime:** AWS Mumbai `ap-south-1`, unchanged.

## Safety and deployment status

These files are infrastructure **source**, not evidence that a hosted zone,
certificate, public website, public API or customer login already exists.
The existing PAPER EC2/SSM/Cognito roles cannot create Route 53 zones. This
chat's available integrations do not include an AWS resource-write connector.
No unverified DNS records should be entered at Hostinger.

Do not reuse or update the historical
`aitrading-paper-ec2-bootstrap` CloudFormation stack. This bootstrap is
standalone and creates **exactly two resources**: a retained public hosted
zone and a narrowly scoped dev-branch GitHub OIDC IAM role. It does **not**
create a new instance, load balancer, CloudFront distribution or public API.
A public hosted zone has a small ongoing AWS charge.

## One-time AWS action (only after code review/merge)

1. In AWS account **098859530205**, inspect Route 53 hosted zones first.
   If `minttick.com` already has a public hosted zone in this account,
   **stop**; reconcile/import the existing zone instead of creating a second
   billable hosted zone.
2. Open CloudFormation in **us-east-1** → **Create stack (with new resources)**.
   Upload `infra/minttick-domain/bootstrap-route53.yml` from this exact
   reviewed repository revision; stack name: `minttick-domain-bootstrap`.
3. Review that the change set creates only `AWS::Route53::HostedZone` and
   `AWS::IAM::Role`. Acknowledge **CAPABILITY_NAMED_IAM** and create.
4. Confirm **CREATE_COMPLETE**, then read the
   `PublicHostedZoneId`, `PublicNameServers` and `DomainDeployRoleArn`
   stack outputs. The four nameservers are public, not secrets.

The route53 zone is **retained** on stack deletion. Do not delete/recreate it
after Hostinger delegates nameservers, because Route 53 allocates different
nameservers per zone.

## Stage SSL validation automatically (after role exists)

One reviewed commit changing **only**
`infra/minttick-domain/requests/certificate.json` on `dev` triggers
`.github/workflows/minttick-domain-certificate.yml`. The exact contents
must be:

```json
{
  "domain": "minttick.com",
  "environment": "dev",
  "aws_account": "098859530205",
  "operation": "stage-acm-validation",
  "authorization": "USER_APPROVED_ROUTE53_ONE_TIME_DNS_2026-11-07"
}
```

The one-shot workflow checks AWS account + unique public zone, reuses or
requests a single ACM certificate for **minttick.com, www.minttick.com,
api.minttick.com** in us-east-1, then writes only ACM's DNS-validation CNAMEs
to the new zone. It prints the actual four nameservers.

**Do not trigger the request before the CloudFormation stack is created.**
The certificate can remain `PENDING_VALIDATION` before Route 53 delegation;
this is expected.

## One-time Hostinger change (only after nameservers are verified)

Once the workflow has staged ACM's validation CNAMEs, use
Hostinger hPanel → Domains → minttick.com → **Nameservers** → custom
nameservers. Replace the Hostinger nameservers with **all four exact values**
reported by the AWS stack/workflow. This is a nameserver change at the
registrar, **not** four DNS A records; do not paste placeholder nameservers.
The domain stays registered at Hostinger; future zone records live in Route 53.

Confirm the external registrar delegation with
`dig +short NS minttick.com`, and confirm ACM status is **ISSUED**. DNS
propagation may be delayed; do not claim issuance until ACM confirms it.

## After DNS and SSL

Provision and verify HTTPS website and isolated account API origins first,
then publish only the verified `www`, `api` and apex redirect targets.
Keep the existing trading, broker, admin and database services private. The
new hosted zone and certificate **do not publish the website by themselves**.
An always-on public API may require a different cost design from the
existing PAPER EC2 nightly auto-stop; do not silently remove the cost stop.
