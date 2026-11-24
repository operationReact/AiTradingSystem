# MintTick News Intelligence — execution record and checkpoint

Runbook: https://app.notion.com/p/42236a828cb68155930cc1d4085c27fa
Scope of this file: MI-PR01 read-only lineage audit, then the checkpoint that later MI-PR work
updates. Evidence is recorded as observed. Nothing here claims DEPLOYED or REAL-DATA VERIFIED.

## 1. MI-PR01 — release lineage audit (read-only, 2026-11-24)

Method: `git fetch --unshallow origin` then `git rev-list --left-right --count origin/main...origin/dev`
and `git ls-tree` on each branch. No AWS mutation, no workflow dispatch.

| Check | Result (observed) |
| --- | --- |
| `origin/main` SHA | `bd1aaf42e` (`fix(ec2): allow 4 GiB ARM64 capacity fallback on existing instance (#554)`) |
| `origin/dev` SHA at audit | `1ac4d5180` (`audit(fno): collect real STR-176 rights and NSE calendar on PAPER (#1837)`) |
| Merge base | `fe1157742` (full history fetched; the clone was shallow before this audit) |
| Divergence `main...dev` | `main` has 11 commits not in `dev`; `dev` has 4,653 commits not in `main` |
| News code on `main` | 0 files under `in/aitradingsystem/news/` |
| Flyway migrations on `main` | Only `V1__initial_schema.sql` under `backend/src/main/resources/db/migration`. `dev` runs through `V127`. |
| What the 11 `main`-only commits change | EC2 manual start/stop/status controls and OIDC diagnostics (`.github/workflows`, `infra`). No application code. |
| PAPER release workflow | `ec2-paper-ssm-app-deploy.yml` exists on `dev`, absent on `main`. It is an exact one-file request to `dev`, pinned to one PAPER EC2 instance, role `aitrading-paper-ec2-ssm-deploy`, region `ap-south-1`. It does not establish a production or `main` deployment. |

Conclusions:

1. `main` cannot run the current News Intelligence code. Its schema and application code are behind `dev`.
2. A bulk merge of `dev` into `main` is not a safe release path. It needs a separately reviewed promotion plan
   (exact SHA, migration plan, rollback, identity and approvals). That plan is outside this PR.
3. No running production SHA is verified. Current evidence only proves what is on `dev` and what the PAPER
   workflow targets. Running-SHA verification requires authorized read-only access that is not available here.

## 2. Access and capability preflight (A3, recorded; no secrets)

| Item | Classification | Evidence |
| --- | --- | --- |
| Direct AWS CLI identity | `INVALID_TOKEN` | Earlier `aws sts get-caller-identity` returned `InvalidClientTokenId`. Not retried, per runbook A3. |
| GitHub Actions OIDC read-only evidence | Not verified in this session | Needs a read-only authorized workflow run; none was dispatched. |
| Gemini configuration | `CODE_SUPPORT` only | `application.yml` binds `GEMINI_API_KEY` and `GEMINI_MODEL` from environment. No Gemini SSM parameter name appears in `infra/` or `.github/` on `dev`. |
| Gemini readiness | Not `PARAMETER_NAME_KNOWN`, not `SECRET_EXISTS_VERIFIED`, not `RUNTIME_PROVIDER_READY`, not `REAL_ANALYSIS_SUCCESS` | No secret was read or printed. No real Gemini call was made. |
| Source rights | `UNKNOWN` for every publisher | Approved-source list is empty by default (`NEWS_ANALYZER_APPROVED_SOURCES`). No rights record exists yet. See MI-PR02. |
| Java / PostgreSQL | Available locally | Local PostgreSQL 16 and Maven. Docker daemon not available here. |
| Flutter | Available locally | Flutter 3.47.0 (the CI pin) and Android SDK installed. |
| Android device / emulator | `BLOCKED` | No `/dev/kvm`. No device attached. |
| Android signing | `BLOCKED` | No release signing secrets in this environment. CI debug validation artifacts are not signed production APKs. |

## 3. Execution checkpoint

```txt
MINTTICK NEWS INTELLIGENCE | EXECUTION CHECKPOINT
Notion runbook: https://app.notion.com/p/42236a828cb68155930cc1d4085c27fa
Captured at: 2026-11-24 (UTC), in this file on the MI-PR01 branch
Current main SHA: bd1aaf42e; dev SHA: 1ac4d5180 (divergence 11 / 4653)
Running production / PAPER SHA: ACCESS_BLOCKED (no valid AWS identity; PAPER workflow not dispatched)
Last completed: #1825 merged e1300e4f; #1828 merged 8e2f005f; #1829 PARTIAL-health work (see GitHub)
Merged: #1839 MI-PR02 source rights, squash 8a19b8be7 (all 11 exact-head checks green on 75c78d7c)
Active PR: #1840 MI-PR03 event ledger, rebased onto dev 8a19b8be7 as 9166df286 (CI pending at capture)
Task IDs accepted: REL-001, REL-002, REL-003, OPS-011 (MI-PR01); SRC-001..003, SRC-011, REL-005 (MI-PR02)
Source rights: NO source APPROVED. V128 seeds REVIEW_PENDING. Empty NEWS_ANALYZER_APPROVED_SOURCES. No credential values recorded.
Gemini readiness: CODE_SUPPORT only; parameter name not found in repo; runtime unverified
Device/release readiness: Android device BLOCKED (no KVM); signing BLOCKED (no secrets); no emulator run
BLOCKED only: (1) AWS valid read-only identity or OIDC evidence for running-SHA audit; (2) main promotion plan
  approval; (3) publisher rights decisions; (4) Gemini SSM parameter name and runtime confirmation
Next READY task: MI-PR02 source rights registry (SRC-001..003, SRC-011, REL-005)
Risk and trading rights: UNCHANGED (no LIVE, no order, no rights flag changes)
```
