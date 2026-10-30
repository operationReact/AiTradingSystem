# Live Terraform migration notes

This branch converts the existing single AWS stack from a paper-named deployment scaffold into the single live deployment target for AiTradingSystem.

Before the first apply, set repository variable `DOMAIN_NAME` to the exact live domain. The existing `AWS_ROLE_ARN` OIDC variable is reused.

The current Terraform resource graph is intentionally preserved for the first live deployment to minimize infrastructure churn. Runtime trading state remains controlled by the application. Follow-up infrastructure hardening may rename legacy resource labels/tags where doing so does not force destructive replacement.
