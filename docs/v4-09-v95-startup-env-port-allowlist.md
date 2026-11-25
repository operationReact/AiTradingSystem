# V4-09 / V95 — research worker startup configuration and exposed-port allowlist

## Gap

V92–V94 reject several image, container privilege and task runtime substitutions, but the V75 metadata evaluator still allowed extra plaintext environment variables, unexpected Secrets Manager injections and arbitrary additional task-defined ports. These can materially affect a model worker even if its image digest, non-root identity and IAM role identities match.

## New offline inspection checks

- Require the exact documented startup environment names: `TRADING_MODE`, `AI_RESEARCH_STRICT_BOOT_REQUIRED`, and `AI_RESEARCH_MODEL_MANIFEST`. Existing value checks continue to require PAPER, strict=true and an absolute manifest path.
- Require exactly one secret name, `AI_RESEARCH_WORKER_TOKEN`, subject to existing Secrets Manager ARN scope validation. Additional secret refs or env flags are rejected even if they do not look like credentials.
- Require exactly one TCP container port mapping `8090:8090`. No UDP, secondary ports, host-port substitution, range, app protocol, or extra named mapping.
- Report only `WORKER_STARTUP_CONFIG_NOT_ALLOWLISTED` or `WORKER_PORT_SURFACE_UNVERIFIED` sanitized blockers and additive booleans. Neither worker values nor the secret reference, ARN, image digest, or exact environment map appears in reports.
- V75 offline regression tests verify accepted exact fixture and fail-closed injected flags, extra secrets and port mappings; cross-check the existing dormant Terraform scaffold.

No AWS permissions are added and the manually dispatched inspector remains read-only. The default-disabled Terraform worker retains desiredCount=0.

## Not sufficient for production acceptance

The reported TCP 8090 mapping does not prove a working private ALB/ACM certificate, correct security-group ingress/egress, DNS, backend TLS identity, model rights, signer approval, source and feature provenance, model bytes, safe paper/shadow outcomes or broker reconciliation. ECS IAM privileges, task mutation permissions and actual running model bytes also require separate independent inspection.

No AWS deploy, broker trade, model promotion, or LIVE/capital authority is implied or granted.
