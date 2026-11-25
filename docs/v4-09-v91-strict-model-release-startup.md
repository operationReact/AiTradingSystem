# V4-09/V91 — prevent strict-bootstrap disablement for model-containing research image

## Issue

V78's Python bootstrap has an intentionally lenient local-development mode when the environment variable AI_RESEARCH_STRICT_BOOT_REQUIRED is false. That is appropriate for the ordinary developer image, but the **dedicated V80/V90 model-containing research-release image must never silently inherit that lenience**.

Previously, the dedicated research-release Dockerfile set AI_RESEARCH_STRICT_BOOT_REQUIRED=true in its image defaults, but a container configuration could override that value to false. With a false override, the V78 Python startup CLI would report a development-only mode and then the release Dockerfile's shell command would continue to launch the HTTP server. An accidental ECS environment configuration change could therefore bypass the intended strict model/token startup preflight.

## Implementation and acceptance

V91 adds an explicit shell-level guard in the **model-containing research-release image's CMD**. The container now requires AI_RESEARCH_STRICT_BOOT_REQUIRED to be the exact string true **before executing** the V78 Python startup validation and before launching Uvicorn. Missing/false/other values fail closed. Valid true still requires PAPER mode, a strong private worker token and actual allowlisted model artifacts under the V78 bootstrap.

The V90 disposable Docker job now checks both sides:
- No token with the strict flag true must produce the existing sanitized V78 rejection, exit 2.
- A **valid disposable test token** with AI_RESEARCH_STRICT_BOOT_REQUIRED=false must be refused by the release CMD itself with exit 1 and no service start. A timeout or Docker-daemon error cannot satisfy this check.
- With exact strict=true and a disposable token, an isolated network-none, read-only, non-root container must successfully answer authenticated readiness and real CatBoost/LightGBM research inference through its own loopback; unauthorized requests remain 401, and all trading/promotion authority false.

The ordinary Python development image retains V78's explicit opt-out seam for local development and unrelated Python tests. The release image differs intentionally: once built, its *default launch command* refuses a strict-off override.

## Limits

This is defense in depth against accidental or limited configuration drift. An IAM principal with permission to replace the **entire** container command/image/task definition can still bypass a Dockerfile CMD; ECS task-definition restrictions and separate IAM/operator review remain necessary. V91 does not cryptographically bind a runtime launch command or attest the actual AWS task definition.

No production model image is approved/pushed, no AWS role/ALB/service deployed, no licensed market-data custody attested, no real Java TLS handshake, no sustained PAPER/SHADOW market evidence and no broker reconciliation established. V76 ECS worker desiredCount remains zero and LIVE/capital authority disabled. Existing V90 synthetic images are disposable and never uploaded.
