# V4-09 / V80 — verified local model bundle and opt-in Docker release gate

## Problem and actual readiness

V78 rejects a strict Python worker unless a full, pinned local model manifest and trained CatBoost/LightGBM artifacts are present. V76/V77 provision **no model bytes**. V80 introduces an explicit, operator-controlled way to stage *already trained and independently reviewed* files for a **separate Docker image**.

This is a **local build/preflight implementation**, not an AWS rollout, an independent legal/data-source approval, or proof of profitable strategies. V76 remains disabled by default with ECS `desired_count=0`. No script here calls AWS, pushes an image, places orders, modifies broker settings, accesses market feeds or authorizes capital.

## File layout

The source is a private operator-owned directory with `manifest.json` using the existing `bounded-artifact-research-inference.v1` schema (1–16 unique allowlisted entries), and **only** its named CatBoost/LightGBM `*.json` model artifact files are copied. This release cannot invent model files or bless synthetic fixtures as independently sourced.

The V80 Python script performs full *real* V72 `inspect_worker_roster` verification: manifest schema, one-to-one model ID/family/fingerprint match, per-artifact SHA-256 and local paths, reserved-name and size bounds, symlink rejection, exact release inventory, a canonical receipt tied to the supplied full Git commit SHA, and a second verification after copying.

The receipt contains only model identifiers and SHA/fingerprint metadata and **always** records these as `false`: source rights independently verified, custody independently verified, deployment verified, PAPER/SHADOW soak, broker reconciliation, LIVE and capital authority. It does not contain paths, credentials, tokens, trained weights or thresholds.

## Explicit offline operator preparation

From a reviewed checkout of the expected exact `dev` source commit:

```bash
cd intelligence
python -m aitrading_intelligence.research_release_bundle stage \
  --source-manifest /secure/operator-approved-models/manifest.json \
  --code-sha FULL_40_CHARACTER_DEV_SHA
```

The command requires the existing private source files. It creates `intelligence/.research-release/` with mode 0700 and 0600 files, refuses to overwrite previous staging, copies **only** named manifest/artifacts and emits a sanitized fingerprint. Both the generated local bundle and temporary directories are ignored by Git; never upload them as CI artifacts.

To verify the same local staging before building:

```bash
python -m aitrading_intelligence.research_release_bundle verify \
  --bundle .research-release --code-sha FULL_40_CHARACTER_DEV_SHA
```

**V84 supersedes direct Docker building from a mutable checkout.** Before any manual image build, run the exact-commit private Git archive and model receipt preflight from [V84](v4-09-v84-research-committed-source-image-context.md); the following V80 command is legacy guidance and should **not** be used directly against an arbitrary working tree.

To **build only** from a V84 prepared private context (operator-reviewed source/custody, source rights and model performance validation remain separate prerequisites; never publish automatically):

```bash
docker build --file "$CONTEXT/Dockerfile.research-release" \
  --build-arg RESEARCH_SOURCE_SHA=FULL_40_CHARACTER_DEV_SHA \
  --tag research-worker:local-preflight "$CONTEXT"
```

This optional Dockerfile differs from `intelligence/Dockerfile`: it deliberately fails when private staged files or the receipt are missing, altered, symlinked or tied to a different Git commit. It copies verified model files into `/opt/research` with ownership for UID 65532, validates the exact release receipt **during the image build**, and keeps the V78 strict runtime boot check. The runtime's private `AI_RESEARCH_WORKER_TOKEN` is **never** a build argument or image layer secret and must later come from a separately verified Secrets Manager reference.

The ordinary Python Docker build context excludes the local bundle. The release Dockerfile has its own explicit allowlist ignore file, so raw files are not inadvertently transmitted to an ordinary Docker build. Keep release images in a restricted private registry in any later independently approved workflow.

## Independent release gates still blocking

1. No independently approved, license-cleared actual India market/news source/model artifact package has been supplied to V80. **Synthetic test fixtures cannot authorize an AWS model release**.
2. No Docker image was built/pushed, no AWS Terraform apply performed, no worker scaled up; V73/V75 operator-run runtime inspections remain unexecuted. Real AWS read-only IAM OIDC configuration is a prerequisite.
3. Private ECR/Secrets/Logs/S3 endpoints are optional/cost-bearing and still disabled. Worker Java V72 requires a **stable, authenticated private HTTPS identity** (raw internal HTTP on 8090 is insufficient).
4. No independent model value after real India fees/slippage, representative PAPER/SHADOW soak, broker reconciliation, LIVE or capital release approval.

The correct response to any missing prerequisite is `BLOCKED`, not deployment or training with unapproved source data.
