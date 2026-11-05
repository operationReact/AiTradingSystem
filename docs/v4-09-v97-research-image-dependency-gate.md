# V4-09 / V97 — fail-closed model release direct dependency gate

## Gap

V90 proves that a research-only image with disposable trained CatBoost and LightGBM models can build, start with authenticated strict bootstrap and score genuine synthetic inference. But `intelligence/Dockerfile.research-release` previously ran `pip install .` and accepted any resolved dependency graph without an explicit package-consistency check or assertion that the three directly pinned model-runtime versions remained intact.

## Implementation

- New `aitrading_intelligence.research_dependency_gate` uses only Python standard library metadata and offline `pip check`.
- The dedicated model-containing release Dockerfile runs `python -m aitrading_intelligence.research_dependency_gate` immediately after `pip install .`, **before** importing private staged model artifacts.
- The gate requires Python 3.11, reviewed `catboost==1.2.10`, `hmmlearn==0.3.3`, `lightgbm==4.7.0` both as direct package requirements and actual installed distributions; it rejects `pytest`, `ruff`, `torch` and `xgboost` in the core worker image. It checks the entire resolved package graph through offline `pip check`.
- On any mismatch, the Docker build fails with only `V97 research image dependency posture BLOCKED (sanitized)`, without dumping local paths, installed metadata, model files or credentials.
- Adversarial unit tests exercise drift, unpinned direct model requirements, wrong interpreter, conflicting installed packages, accidental dev package installation and sanitized refusal. The V90 actual Docker synthetic model smoke workflow triggers when the gate code/tests change and builds the modified Dockerfile on its exact PR head.

## Important limits

**This is not a reproducible supply-chain lock or production artifact approval.** The Docker base image is still referenced by floating `python:3.11-slim`; apt `libgomp1` is unpinned; transitive Python package versions and wheel hashes are not frozen. A valid resolved graph at one build does not establish reproducible bytes on another day. A separately reviewed digest-pinned base, hashes/lockfile, vulnerability/license approval, signed source/model receipt, and independent signer custody are prerequisites for a real release.

This change does not push ECR images, provision AWS, activate worker ECS desiredCount, import production models, access brokers, accept PAPER/SHADOW results, or enable LIVE/capital authority.
