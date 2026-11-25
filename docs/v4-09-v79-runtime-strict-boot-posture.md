# V4-09 / V79 — Fail-closed V75 runtime inspection requires V78 strict bootstrap

V75's read-only ECS/ECR check verified a single running healthy worker/image and a private token **reference** but did not assert that the task had opted into V78's mandatory actual model/artifact startup verification. This mismatch could create a false-positive worker *image posture* result when a task overrides the strict-boot flag or runs an old task definition.

V79 updates `scripts/v4_worker_ecs_digest.py` contract version to `v4-worker-ecs-digest-evidence.v2`. It adds `strict_worker_boot_declared` and a `WORKER_STRICT_BOOT_NOT_REQUIRED` blocker. Only a literal ECS container environment value `AI_RESEARCH_STRICT_BOOT_REQUIRED=true` qualifies. Missing, false, ambiguous, malformed or secret-sourced flags are rejected. The existing validated exact image/ECR digest, ACCOUNT/PAPER, no plaintext token, zero-override and single healthy task checks remain mandatory.

**This is declaration-only evidence**: ECS Describe metadata cannot prove that an image's startup command was used, that token bytes/model files exist or that the active task performed V72 model verification. The V78 non-root Docker image gate and registry digest are separate code/runtime prerequisites. V75/V79 always emit FALSE for model artifact attestation, secret IAM/token access, authenticated Java original-roster reconciliation, licensed NSE/BSE/news data, PAPER/SHADOW soak, broker reconciliation and LIVE/capital authority.

The V76/V77 worker remains `desired_count=0`, default-disabled and unprovisioned. This PR does not dispatch AWS Actions, rotate secrets, scale a service, submit inference, place orders or create trading authority.

Offline tests use synthetic ECS metadata and require no cloud credentials. A green V79 test means **validator logic** is correct, not AWS or model readiness.
