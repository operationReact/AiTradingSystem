# First live deployment blockers

The live pipeline is ready to consume the existing GitHub OIDC role, but the first deployment remains intentionally blocked until these prerequisites are satisfied:

1. Repository variable `DOMAIN_NAME` contains the exact finalized live domain.
2. The infrastructure provisioning workflow succeeds and outputs Route 53 nameservers.
3. The domain registrar is delegated to those Route 53 nameservers.
4. The provisioning workflow is rerun with `enable_https=true` and ACM/HTTPS completes.
5. The Flutter connected-runtime correction currently tracked separately is merged so `dev` contains the corrected API contract before live release acceptance.

After those are true, the live deployment workflow can build and deploy the full current application from `dev` and verify both the Flutter UI and backend health through the live domain.
