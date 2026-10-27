# Phase 0 — Baseline validation

This document records the repository baseline and gives repeatable commands for validating changes before implementing the market-data phases.

## Repository baseline

- Backend: Java 21, Spring Boot 3.5.6, Maven, PostgreSQL, Flyway, Spring Actuator.
- Frontend: React 18, Vite, npm lockfile.
- Persistence: PostgreSQL 16 via Docker Compose; schema managed by Flyway.
- Execution mode: paper-only. Live broker order execution must remain disabled.
- Existing backend tests include unit tests plus `*IT.java` integration tests. `backend/pom.xml` configures Surefire to include both `*Test.java` and `*IT.java`.
- At the time this baseline was recorded, branch `dev` had no GitHub Actions workflow and therefore no branch CI history.

## Local validation

Run from the repository root.

### Backend tests

Prerequisites: Java 21, Maven, Docker, and a working Docker daemon (for Testcontainers/PostgreSQL integration tests).

```bash
cd backend
mvn -B test
```

The Maven test command is configured to include both unit and integration-test class names. Do not report the suite as passing unless the command exits successfully and the expected PostgreSQL/Testcontainers tests actually execute.

If Docker/Testcontainers is unavailable, record that as an environment blocker; do not treat skipped database integration tests as a full pass.

### Frontend production build

Prerequisites: Node.js 20+ and npm.

```bash
cd frontend
npm ci
npm run build
```

The committed `package-lock.json` should be used for deterministic dependency installation. A successful build validates compilation/bundling, not browser behavior or API integration.

### Optional local PostgreSQL smoke check

From the repository root:

```bash
docker compose up -d postgres
```

Then run the backend tests. Inspect the Flyway migration output and confirm the application starts with the configured local datasource. Use only local development credentials; never put real broker or AI-provider secrets in the repository.

## Phase 0 findings and boundaries

- The repository currently has no workflow under `.github/workflows/`; the new workflow provides a repeatable pull-request/push quality gate.
- Frontend `package.json` defines `build`, but no lint or frontend test command is currently defined. This phase does not add an unrequested lint/test framework.
- Backend and frontend commands have not been executed by the GitHub connector. The first workflow run is the authoritative execution result; this document does not claim they passed.
- Existing Flyway migrations are immutable. Schema changes must be introduced as new migrations in later phases.
- Do not enable real order placement as part of baseline work.
- Do not expose or persist Angel One credentials, tokens, AI provider keys, or the operator API key in frontend assets, logs, or committed files.

## Exit checklist

- [ ] Backend unit and integration tests pass against PostgreSQL/Testcontainers.
- [ ] Frontend production build passes from the lockfile.
- [ ] CI runs on pushes to `dev` and pull requests targeting `dev`.
- [ ] Any environment-dependent failure is captured explicitly and not silently counted as a pass.
- [ ] Paper-only execution remains enforced; no live-order behavior is introduced.
