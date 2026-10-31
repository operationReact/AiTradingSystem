# AI Trading System

News-driven research and **PAPER-only** trading platform for Indian cash equities. The backend remains the authority for risk, approvals, execution state, accounting and lifecycle. LIVE broker execution is not enabled.

## Production application architecture

- `backend/`: Java 21, Spring Boot, PostgreSQL, Flyway, REST API.
- `apps/trading_client/`: production Flutter client for Web, Android and iOS.
- `frontend/`: legacy React/Vite reference implementation only; it is not the production UI authority.
- Logical research/operations modules cover market scanning, news analysis, strategy research, deterministic risk, PAPER execution and monitoring.
- Provider credentials remain backend-only and must never be committed or embedded in Flutter/Web builds.

The Flutter workspace currently exposes Overview, Instruments, Market Data, Research, Scenario/Regime evidence, PAPER Backtests, PAPER Portfolio, PAPER Orders, Risk/Approvals, News, Notifications, Audit, Settings and System Health.

## Safety defaults

- PAPER execution only; no LIVE broker-order route is authorized by the client.
- AI output is untrusted input and cannot bypass deterministic backend risk controls.
- Missing/unknown evidence fails closed rather than being fabricated by the UI.
- Client appearance or local state never changes execution authority.
- Provider keys, API keys and secrets belong only in approved backend/local secret configuration.

## Prerequisites

- Java 21
- Flutter 3.47.x stable
- PostgreSQL 16 (local service or Docker Compose)
- Node.js 20+ only for repository tooling and legacy/reference surfaces

## Run the real Flutter UI locally

1. Copy `.env.example` to `.env` only if local backend configuration requires it. Never commit `.env`.
2. Start PostgreSQL: `docker compose up -d postgres`.
3. Start the backend:

   ```bash
   cd backend
   mvn spring-boot:run
   ```

4. In another terminal, start the production Flutter Web client:

   ```bash
   cd apps/trading_client
   flutter pub get
   flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080
   ```

Android and iOS use the same Flutter source tree. Native connected acceptance is intentionally run at phase/release boundaries rather than on every development commit to control CI time and cost.

## Current product surfaces

The implemented Flutter application includes backend-backed flows for:

- instrument catalogue and market-data evidence
- research scan → candidate → evaluation
- persisted Scenario/Regime evidence
- PAPER backtest run/history
- PAPER portfolio and order entry/history
- backend-authoritative risk decisions and approval lifecycle
- emergency stop / new-order halt controls
- provenance-aware news, notifications and audit evidence
- execution-mode/settings evidence and operations health

The client deliberately does not expose an authorized LIVE activation path while backend LIVE readiness is unavailable.

## Validation

Normal Flutter PR development uses fast Ubuntu validation (analyze, tests and Web build). Full connected Web + Android emulator + iOS Simulator validation is an explicit phase/release acceptance gate and must pass on the exact candidate head before Phase 9 can be called verified.

Visual regression snapshots are regression evidence, not automatic proof of Figma/design fidelity.

## Provenance-aware news ingestion

RSS/news ingestion and optional summarization are backend-controlled. Publisher provenance is retained, generated summaries are treated as untrusted evidence, and neither news content nor model output can directly authorize a trade or bypass deterministic risk policy.

## LIVE trading

LIVE trading is outside the current PAPER-only release authority. Any future LIVE path requires separately reviewed broker/regulatory readiness, risk controls, operational evidence and explicit approval. Do not infer LIVE readiness from UI availability.

## AWS deployment preparation

Infrastructure-as-code and deployment preparation exist in the repository, with the current standardized AWS region set by the infrastructure configuration. No cloud provisioning or production deployment is performed merely by merging application code or running ordinary CI. Terraform apply, production deployment, DNS, secrets, signing/store publishing and LIVE activation remain explicit approval gates.

This software is for research and testing. It does not provide financial advice or guarantee results.
