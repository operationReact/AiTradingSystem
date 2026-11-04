# V4-09 / V84 — Exact-commit Git source for an offline research model image

## V80 gap

The V80 model receipt binds trained CatBoost/LightGBM files to an **operator-supplied** Git SHA. Its separately selected Dockerfile validated the model manifest and receipt during build, but could still copy Python source files from a dirty, untracked or gitignored checkout. Simply supplying a 40-character string as `RESEARCH_SOURCE_SHA` did **not** prove what Python code bytes were built.

V84 provides an **operator-only** preflight which creates a new private Docker build context from **`git archive` of the exact local `HEAD` commit**, never from mutable working-tree source files, and adds only a fresh copy of the already verified V80 private model release. This is a local reproducibility and accidental-source-substitution safety step, **not** a signed/SLSA attestation or proof of approval for model licensing, publishing or trading.

## Workflow — after independent source and model review

The operator must already have (1) a reviewed `dev` Git checkout with an exact 40-character commit SHA, (2) a `manifest.json` with actual trained and rights-reviewed CatBoost/LightGBM files, and (3) a V80 release bundle staged under `intelligence/.research-release` using that exact same SHA. Do **not** provide synthetic CI models as commercial artifacts.

Run from a Python 3.11 environment with the `aitrading-intelligence` package installed. Use a **private filesystem** for the new destination outside the repository; never place the output in Git, a public Docker registry, cloud build cache, CI artifact or shared directory.

```bash
# Illustrative only: no build context or model files are available in AWS today.
REPO=/reviewed/AiTradingSystem
COMMIT=$(git -C "$REPO" rev-parse HEAD)
CONTEXT=/private/operator-only/research-context-$COMMIT

python -m aitrading_intelligence.research_image_context prepare \
  --repository-root "$REPO" \
  --model-bundle "$REPO/intelligence/.research-release" \
  --destination "$CONTEXT" \
  --code-sha "$COMMIT"

python -m aitrading_intelligence.research_image_context verify \
  --context "$CONTEXT" --code-sha "$COMMIT"
```

The output contains **only** the committed `src/` subtree, `pyproject.toml`, `README.md`, `Dockerfile.research-release`, that Dockerfile's allowlist ignore file, the exact verified `.research-release` model manifest/artifacts/receipt and `source-context-evidence.json`. Everything uses private 0700 directories and 0600 files. The source evidence lists exact source paths, sizes and SHA-256 checksums, the real checkout commit, and the paired model receipt fingerprint. `deployment_verified` and `can_authorize_capital` are always false. It never contains model weights, tokens, secrets or absolute source paths.

The preflight refuses a mismatched HEAD SHA, missing tracked build files, symlink or hardlink Git entries, a destination inside Git, an existing destination, unrecognized files in the prepared context, wrong model receipt hashes and any source modification after preparation. It never prints sensitive paths, model bytes or token values on failure.

Once independent model rights/source custody, code review and build plan checks have been documented, a **separate manual** local build may consume that prepared context:

```bash
# Local image preflight ONLY. Do not push to ECR or run/scale ECS.
docker build -f "$CONTEXT/Dockerfile.research-release" \
  --build-arg RESEARCH_SOURCE_SHA="$COMMIT" \
  --tag research-worker:unpublished-preflight "$CONTEXT"
```

The V80 Dockerfile again validates the private model receipt, pins `TRADING_MODE=PAPER`, runs the strict V78 artifact bootstrap gate and never takes a private worker token at build time. The standalone V84 verifier is run **before** this manual build. A malicious local operator could still forge unsigned metadata or modify the context between verification and the build: this is **not** cryptographic provenance, binary attestation or a deployment authorization; a later independently reviewed signing/publishing pipeline must close that separate trust boundary.

## Safety gates and remaining blockers

- No AWS credentials, Docker execution, model weights, ECR push, IAM/OIDC creation, new ECS tasks, Java research enablement, broker API or capital permission are part of V84.
- The V76/V77/V81/V82 private worker network is default-disabled and cost-bearing. Worker ECS remains `desired_count=0`. V83 Java HTTPS origin pin and separate token preparation are default-off and untested against real AWS runtime.
- Before any **operational** model release, verify genuine independently licensed Indian market/news evidence, accurate model artifact custody, private ACM DNS/TLS and caller security groups, real V72 ADMIN original model enrollment/worker roster equality, V73/V75 read-only AWS runtime posture, sustained real-money-free PAPER/SHADOW soak and broker reconciliation. LIVE/capital is still disabled.
- Synthetic Git/model test fixtures validate only software behavior and **never** establish market, legal, performance or AWS readiness.

No model artifacts or deployment credentials are bundled in this change.
