# V4-09/V90 — real Docker startup smoke with disposable synthetic models (no AWS)

## Why this matters

V80 checked genuine CatBoost/LightGBM model file hashes; V84 bound the private build context to exact Git HEAD source bytes; V85/V88 separately validated an external signature and its independently pinned reviewer trust snapshot. However, the *dedicated model-containing Dockerfile* had not been compiled and booted in CI with actual trained synthetic model files. A working Python unit suite is not proof that an actual strict-mode container will install, start or expose the two authenticated APIs.

V90 exercises that boundary **in disposable GitHub Actions CI**, without a production license claim or any operational cloud resource:

1. Check out exactly the PR merge or selected dev Git SHA.
2. Train **ephemeral synthetic** CatBoost and LightGBM artifacts from the repository's existing deterministic test fixtures. These are test models, not approved market models.
3. Stage the V80 synthetic release bundle bound to that SHA, prepare the V84 Docker context from the *committed* SHA archive (never dirty worktree), and verify every model/source checksum before building.
4. Actually run Docker build using the dedicated Dockerfile.research-release and the exact source SHA build argument. This builds an **unpublished local runner image only**; it is NOT a production release image, a signed provenance artifact or a licensed-model approval.
5. Confirm that starting the built image with **no worker secret** fails with the exact sanitized V78 strict-bootstrap refusal (exit 2). A Docker crash or timeout does not count as the intended security check.
6. Generate a **disposable test-only** 32-byte random worker token and run the non-root research image with no external network or published port, no added capabilities, no-new-privileges and a read-only filesystem.
7. Execute a Python script inside the running container. From its own 127.0.0.1:8090 loopback, prove unauthenticated and wrong-token requests are rejected, the correct-token readiness roster matches the hashes of **both real synthetic model artifacts**, and authenticated inference actually computes a response for each model while setting model promotion and capital authority false.
8. Tear down the Docker container, ephemeral model files, exact-Git context and local image on exit. The workflow does not upload model files, image layers or private test tokens as GitHub artifacts.

This is a **stronger build/runtime compatibility check**, not operational acceptance.

## How to inspect

The dedicated job is named **Build actual synthetic model image and probe isolated runtime**, in GitHub Actions workflow **V90 disposable synthetic research worker Docker runtime**. It runs on PRs to dev for relevant worker/container changes and can be dispatched manually. No AWS IAM role, GitHub OIDC, registry login or broker credential is available to it.

The job first runs the no-AWS unittest contract, then installs Python dev/test dependencies and builds from the short-lived runner context. An intentional V78 denial is required; later successful API calls happen only on the container's isolated loopback and are never externally published.

Existing Dockerfile.research-release downloads Python/PyPI dependencies at build time from public networks. **This is NOT a reproducible supply-chain build or production approval**; locking/verifying upstream image and Python dependency digests remains a separate security release requirement. The running container's network, however, is disabled entirely.

## Still blocked after a green job

No independently verified NSE/BSE/news source rights, approved commercial model artifacts, trusted production signing key/policy pin, production image digest/signature, ECR push, Terraform apply, configured AWS IAM/OIDC, running AWS worker, tested Java↔AWS private TLS reachability, V72 original ADMIN enrollment roster, sustained real-money-free PAPER/SHADOW soak or broker reconciliation is created by V90. Worker desiredCount stays zero in Terraform, Java research opt-ins OFF and LIVE/capital authorization false.

Only a separately reviewed actual-data/model release, immutable dependencies, operator-authorized AWS provisioning and real runtime/test evidence can advance those gates. A green Docker smoke cannot be promoted to capital authority.
