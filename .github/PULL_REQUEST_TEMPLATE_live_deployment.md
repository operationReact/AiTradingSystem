## Live deployment review checklist

- [ ] Flutter Web, not legacy React, is the deployed product client.
- [ ] Backend and Flutter validation pass.
- [ ] GitHub OIDC is used; no static AWS credentials are introduced.
- [ ] `DOMAIN_NAME` is a bare DNS name repository variable.
- [ ] Terraform remote state is encrypted, versioned, and locked.
- [ ] Route 53 delegation is completed before enabling HTTPS.
- [ ] ECS services stabilize and the live UI plus `/api/health` smoke tests pass.
- [ ] Deployment does not bypass server-side trading/risk/approval controls.
