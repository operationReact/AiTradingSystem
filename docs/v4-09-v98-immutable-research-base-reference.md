# V4-09 / V98 — immutable official-Python base binding for research image builds

## Gap

The V97 research-release Docker image validates installed direct ML pins and the dependency graph but inherited the floating `FROM python:3.11-slim` tag. Different builds could silently select different base images. A model release build should not succeed from an unrestricted tag or from an unrelated registry.

## V98 implementation

- The dedicated model-containing `Dockerfile.research-release` requires explicit `RESEARCH_PYTHON_BASE_IMAGE` at build time; it no longer has a default `FROM` tag. The successful Docker build must receive `docker.io/library/python@sha256:` followed by exactly 64 lowercase hexadecimal digits.
- The standard-library `research_base_digest_gate` validates an immutable reference to the Docker Official Python image namespace, rejects tags and alternate registries, and sanitizes denials. The image runs this gate after installing dependencies and before copying privately staged model artifacts, alongside the existing V97 direct-dependency validation.
- On each disposable synthetic V90 CI run, the runner pulls `python:3.11-slim` for linux/amd64, reads Docker's local `RepoDigests`, refuses missing, foreign or contradictory digest entries, normalizes to one immutable canonical official-Python digest and builds from that digest. The selected base cannot drift between pull and Docker build in the same run.
- Synthetic no-Docker unit tests and V90 image-context contract assertions validate immutable references, rejected mutable and foreign inputs, ambiguity, sanitized errors, exact build wiring. The existing actual Docker build, strict-startup denial and authenticated CatBoost/LightGBM synthetic inference still run on the exact PR head.
- No AWS, OIDC, registry push, credentials, model promotion, broker order or real market data is involved.

## Remaining release gates — explicitly NOT solved

The tag used to discover the **test-only** base can point to a different digest on a future CI run. This PR does **not** commit a permanently approved base digest or independently attest registry signatures and provenance; real operator publication requires a reviewed digest selected by an independent reviewer and separately retained evidence. `libgomp1` apt packages, transitive Python wheel versions/hashes and build-tool dependencies remain unpinned. A fully reproducible and signed release still requires immutable lockfiles and reviewed license/vulnerability policy.

This is source + disposable synthetic CI only. The dormant research worker ECS desired count remains zero, AWS worker/Java HTTPS readiness and PAPER/SHADOW soak are not established, and LIVE/capital authority stays disabled.
