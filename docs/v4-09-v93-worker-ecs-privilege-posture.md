# V4-09 / V93 — ECS worker non-root privilege posture (read-only)

## Gap

V92 makes the dedicated release image reject attempts to bypass the model startup gate through ECS `command` overrides. Existing V75 AWS read-only worker-image inspection rejects overridden `entryPoint` and `command`, but its sanitized task-definition evidence did not reject an explicit root user, privileged container, or task-injected Linux capabilities. A healthy, digest-matched worker is not automatically safe.

## Change

- The default-disabled Terraform research-worker scaffold now explicitly declares `user = "65532:65532"` on the ECS container, matching the release Dockerfile's `USER 65532:65532`.
- The **read-only** V75 inspector now fails closed with only the sanitized blocker code `WORKER_PRIVILEGE_OVERRIDE_UNVERIFIED` unless the task declares exactly that nonroot UID:GID and has no privileged root override, interactive/TTY settings, `linuxParameters`, `dockerSecurityOptions`, or unexpected `extraHosts`.
- The existing V75 checks continue to reject ECS task `command` or `entryPoint` overrides, plaintext credentials, unexpected trading modes and disabled strict boot.
- Regression tests verify a legitimate synthetic task matches; attempted root, privileged, capability and interactive task configurations are rejected. Assertions also require Terraform's worker desired count stays zero.

## What it does not prove

This is task-definition **metadata only**, not a worker image attestation or deployed validation. A task-definition UID/GID declaration and an ECR digest do not themselves prove the image is trustworthy, that IAM cannot alter it, or that secrets, market data licenses, signed real model bytes, backend HTTPS, PAPER/SHADOW or broker reconciliations work. The proposed worker service remains `desired_count=0` and no AWS role, resource, token or deployment is created.

Approval of a private research worker still requires separately managed task-definition IAM controls, independent actual runtime inspection and model/data licensing; do not enable LIVE or capital execution from this report.
