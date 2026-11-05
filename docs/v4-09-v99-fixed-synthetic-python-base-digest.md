# V4-09 / V99 — fixed, source-controlled Python base digest for synthetic worker CI

## Previous gap

V98 prohibited mutable base image *references* in the model-containing release Dockerfile, but synthetic V90 CI still pulled floating `python:3.11-slim` to discover a different official-Python digest on each future run. This protected a single run from mid-build drift, not reproducibility across runs.

## Change

- Introduced `intelligence/research-ci-base-image.txt` as the exact **CI-only** Python image reference `docker.io/library/python@sha256:db20d8506e2f15b6db39b40b6015ca46bae855dec6dd326d462cfe79ae972893`.
- The digest is grounded in the successfully completed V98 synthetic-image build for PR #453's corrected head; its Docker build logs showed this specific resolved base reference.
- V90 CI now reads the committed digest, validates it using V98 `research_base_digest_gate verify`, pulls that immutable exact image for linux/amd64 and passes the same value to `RESEARCH_PYTHON_BASE_IMAGE`. It never contacts the mutable Python tag for base selection.
- The workflow path filter includes the pin file so any change triggers a new actual synthetic CatBoost/LightGBM image build and authenticated model API smoke. Contract tests enforce the exact committed reference and prohibit reintroducing tag-discovery.
- The dedicated research Dockerfile remains `FROM ${RESEARCH_PYTHON_BASE_IMAGE}` with no default. All model/capital authority checks stay fail-closed.

## Boundaries, approvals and remaining work

This digest is a **previously CI-tested synthetic base pin**, not an independently reviewed production release artifact or a claim of verified publisher signature. A production signer must independently validate the selected digest, image provenance, operating system and package vulnerability state before promotion. Apt `libgomp1`, Python transitive dependencies, build-system wheels and wheel hashes are still not pinned and can vary across builds despite this fixed base.

No AWS role/resource modification, ECR image push, worker activation, model approval, licensed market-data ingestion, PAPER/SHADOW operational validation, LIVE execution or broker orders are performed.
