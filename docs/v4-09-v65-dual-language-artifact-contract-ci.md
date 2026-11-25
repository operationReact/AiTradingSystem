# V4-09 / V65 — coupled Java/Python synthetic research contract CI

Prior Java-only inference PRs executed Java/PostgreSQL CI, while the existing
Python intelligence CI was path-filtered to changes under `intelligence/**`.
A Java client/roster change could therefore reach `dev` without freshly
exercising genuine trained Python CatBoost/LightGBM artifact computation in
the same commit.

This PR adds `.github/workflows/v4-research-artifact-contract.yml`
for changes to relevant Java inference/risk API, V6x research migrations,
Python inference code/tests, and the workflow itself.

Two independent jobs must both pass on the **same GitHub commit SHA**:
- Python 3.11: install the pinned research environment; Ruff lint and run
  `tests/test_research_artifact_inference.py`, which serializes real trained
  CatBoost and LightGBM synthetic artifacts, enforces the allowlist/manifest
  integrity, calls the real FastAPI scoring route and rejects forged
  fingerprints, identities, unsafe feature schema and capital authority.
- Java 21: run the independent canonical Python-response golden/hash checks,
  authenticated request/bounded endpoint validation, UNKNOWN/no-retry,
  per-model dispatch/terminal outcome and entire roster's strict success-only
  control-plane tests.

Both jobs are deterministic and do not require paid data, user secrets, broker
credentials, cloud deployment or actual capital.

**Limitation:** This is coupled Java/Python software contract regression,
not end-to-end network integration between deployed Java and deployed Python,
nor independent provider-licensed NSE/BSE data or economic-performance
certification. V4-09 still requires that separate live-worker acceptance,
point-in-time source custody, throughput/timeout tests and PAPER/SHADOW soak.
