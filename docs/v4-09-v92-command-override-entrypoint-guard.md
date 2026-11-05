# V4-09 / V92 — research-release image command-override guard

## Security gap

V90 proved that a real disposable model-containing Docker image builds, strictly refuses missing tokens, and serves authenticated synthetic CatBoost/LightGBM inference. V91 prevented `AI_RESEARCH_STRICT_BOOT_REQUIRED=false` from skipping startup via the standard image command. However, the dedicated release Dockerfile still used Docker `CMD`, which a runtime or ECS task-definition `command` override could replace in full with an arbitrary startup command, bypassing the strict V78 gate.

## Implementation

- The dedicated `intelligence/Dockerfile.research-release` uses a fixed exec-form Docker `ENTRYPOINT`, not `CMD`. It requires `AI_RESEARCH_STRICT_BOOT_REQUIRED=true`, executes `aitrading_intelligence.research_startup_gate`, and only then launches the built-in Uvicorn HTTP service.
- Docker/ECS *command* overrides are additional unused arguments to `sh -c`, not replacement executables. This image intentionally does not support changing its command.
- The **real V90 disposable-image CI** now runs with an unauthorized `python -c` command override and no worker token. It requires the V78 sanitized denial and exit code 2, rejects successful execution of the supplied text, and rejects timeouts/daemon errors as success.
- Its positive synthetic dual-model roster/inference probe now launches the container while an `exit 86` command override is present, proving the original secure entrypoint still starts the worker after strict token/artifact checks.
- Existing no-token rejection, strict=false denial with valid token, and synthetic authenticated model inference remain tested. Tests also assert the Dockerfile uses ENTRYPOINT rather than CMD.

## Authority boundary and limits

**CI-only, not a deployed release.** No licensed real model, provider or exchange data, production signer approval, image push, AWS changes, backend-to-worker TLS handshake, worker activation, broker order, PAPER/SHADOW performance evidence, or LIVE/capital authorization.

An operator with permission to override the **ECS task-definition `entryPoint`** or replace the image can still bypass image-level startup enforcement. Enforce task-definition immutability, least-privilege IAM and independent post-deployment inspection before any operational acceptance. This V92 change does not claim to solve privileged entrypoint or image substitution.

## Validation

The `.github/workflows/v4-synthetic-research-container-smoke.yml` job performs disposable Docker build and execution on each relevant PR head. `scripts/tests/test_v4_synthetic_release_container_contract.py` catches accidental removal of the guard or tests without requiring Docker.

Do not mark this milestone complete until the PR's required exact-head CI jobs pass and the PR merges to `dev`.
