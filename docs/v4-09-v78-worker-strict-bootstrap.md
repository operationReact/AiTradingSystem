# V4-09/V78 — Fail-closed Python worker bootstrap, not model deployment

The V76 dormant worker task definition declares a model-manifest path but currently mounts no real trained artifacts. The plain Python `/health` endpoint cannot establish whether private CatBoost/LightGBM weights match the registered V56 Java roster. This phase prevents a **strict** worker container from starting with a missing/invalid model manifest or token.

## Code behavior

- Docker's non-root entrypoint runs `python -m aitrading_intelligence.research_startup_gate` before starting Uvicorn. A failure terminates the container instead of serving a misleading HTTP health signal.
- `AI_RESEARCH_STRICT_BOOT_REQUIRED=true` is now mandatory in the **dormant Terraform ECS worker task definition**. With this value, boot checks explicit `TRADING_MODE=PAPER`, a 32–4096-character injected `AI_RESEARCH_WORKER_TOKEN`, absolute `AI_RESEARCH_MODEL_MANIFEST` and full V72 real allowlisted artifact/hash validation via `inspect_worker_roster`. It rejects unknown strict values, overrides, missing/manipulated manifest, model mismatch, failed hashes and a capital-authority flag.
- If strict boot is not enabled, local development and existing synthetic CI keep previous behavior. Production Terraform cannot select non-strict without a reviewed code/config change.
- Any failure emits only a generic diagnostic; it never prints the token, model bytes, filesystem paths, private HTTP responses or market data.
- Tests cover missing token/manifest, LIVE override, invalid flags, rejected roster, default development behavior, Docker order and Terraform strict-mode guarantee. Existing V72 tests cover actual trained model hash validation with synthetic fixture artifacts.

## Incomplete prerequisites (never interpret as RELEASE ACCEPTED)

1. A reviewed, commercially licensed model bundle must be supplied in the worker image or a **read-only** validated mounted volume at `/opt/research/manifest.json`, with every trained artifact referenced by SHA256, family, version and fingerprint.
2. Private AWS endpoints from V77 require explicit cost-bearing opt-in and AWS apply. V76 worker remains default-disabled at `desired_count=0`; V78 does **not** deploy it.
3. Java V72 requires a privately authenticated, trusted HTTPS worker URL and real ADMIN original-session roster equality; current private port 8090 by itself is not HTTPS. No worker token/IAM/network proof or stable DNS has been established.
4. Indian market/news licensing, real OOS after-cost economic results, sustained representative PAPER/SHADOW trials, broker reconciliation, LIVE/capital-authority release and manual V73/V75 AWS inspections remain unverified.

No broker orders, model promotion, LIVE mode, enabled dispatch, AWS secrets mutation, runtime deployment or capital authority are introduced by V78.
