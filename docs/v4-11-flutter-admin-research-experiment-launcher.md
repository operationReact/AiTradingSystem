# V4-11 — Connected Flutter research experiment launcher

This increment connects the existing V4-02 Java research registry and experiment queue to the actual Flutter Admin product navigation. It does not add an inference worker or an order execution path.

## What is implemented

- Desktop rail and mobile More navigation expose `/admin/experiments` (ADMIN-only backend RBAC).
- Read existing registered immutable experiment specifications from `GET /api/admin/model-governance/experiments`. No speculative client-side datasets, fees, model scores or training claims are invented.
- Read Java durable research job status from `GET /api/admin/model-governance/experiment-jobs`.
- Select an existing immutable experiment UUID, one Java-registered runner type (`PYTHON_INTELLIGENCE` / `JAVA_BACKTEST`), an exact bounded runner version and max attempts (UI defaults 1, supports up to 3).
- Validate the request before a separate **confirm queue** dialog, then submit `POST /api/admin/model-governance/experiment-jobs`. Java remains responsible for model/emergency governance and idempotent orchestrator fingerprint.
- Display the actual Java job status (`QUEUED`, `CLAIMED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`) without equating queued with executed. Read an existing immutable result via `GET /api/admin/model-governance/experiment-jobs/{jobId}/result` only for successful jobs, and label every result research-only.
- Show loading, empty, 401/403 permission-denied with cache purge, stale/error, and confirmation cancel/no-submit states. Typed HTTP tests reject fabricated capital-authorizing responses.

## Security boundaries

- Flutter only **enqueues** research. No claim/start/result injection, model promotion, trading execution, LIVE controls or broker orders.
- The backend validates the exact model version, registered immutable experiment spec, effective emergency-disable policy and max-attempt rules.
- Neither a research job nor an immutable result certifies source licensing, actual trading performance, successful real model inference or PAPER/SHADOW soak. An authorized research worker must execute a job for results to appear. UI does not attest a worker exists.
- Launching a research job is an explicit user action; page load never queues or mutates jobs.
- This is a partial V4-11 **Experiment Launcher**, not the complete multi-model Admin Control Center, and V4-09 simultaneous PAPER/SHADOW model inference remains unresolved.

## Acceptance and follow-ups

Flutter repository tests cover exact ADMIN routes, request payload, response validation, authorized denial and research-only result guards. Widget tests cover queue confirmation, cancellation, invalid input, empty/permission-denied state, and immutable result readback. Merge only after exact-head Flutter analyze/tests/web, responsive/mobile quality, visual golden, Android APK, Java Phase 0 and other required CI workflows pass.

Next: register real licensed artifact and market-data evidence, attach authenticated model runners to Java job leases, persist server-verified runtime/cost/readiness evidence and complete V4-09 inference event pipeline. No environment variables or paid API activation are required for this UI implementation.
