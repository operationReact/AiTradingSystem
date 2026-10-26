# AI Trading System

News-driven research and paper-trading platform for Indian cash equities. This repository starts in **paper-only mode**. It does not place live orders.

## Architecture

- `backend/`: Java 21, Spring Boot, PostgreSQL, REST API.
- `frontend/`: React + Vite dashboard.
- Six logical agents: market scanner, news analyst, strategy analyst, risk manager, research analyst, paper execution/monitor. These are modules, not six separate services or subscriptions.
- Angel One SmartAPI is intended as the market-data integration. Credentials and API details must be configured locally; do not commit secrets.
- Gemini is an optional AI provider for news analysis. Core trade/risk calculations must be deterministic Java code.

## Safety defaults

- Paper execution only; no live broker-order adapter is implemented.
- AI output is untrusted input and cannot bypass risk checks.
- No order is created without valid prices and passing risk checks.
- All sample prices and sample signals are synthetic demo data, not live quotes or investment recommendations.

## Prerequisites

- Java 21
- Node.js 20+
- Docker + Docker Compose (or a local PostgreSQL 16 instance)

## Run locally

1. Copy `.env.example` to `.env` and set values you have. Never commit `.env`.
2. Start PostgreSQL: `docker compose up -d postgres`
3. Start backend: `cd backend && mvn spring-boot:run`.
4. Start frontend in another terminal: `cd frontend && npm install && npm run dev`.
5. Open `http://localhost:5173`; backend defaults to `http://localhost:8080`.

The app starts with demo dashboard data and a paper-only status. Provider integrations remain disabled until explicitly configured.

## Initial API

- `GET /api/health`
- `GET /api/dashboard/summary`
- `GET /api/signals`
- `GET /api/paper-trades`
- `POST /api/paper-trades/simulate` (paper-only simulated order; validates inputs and risk budget)

## Environment variables

See `.env.example`. Do not place keys in React/Vite variables: frontend environment values are public. Gemini and Angel One secrets belong only in the Spring Boot environment/secret manager.

## Roadmap

1. Foundation and demo dashboard.
2. Angel One market-data adapter (read-only), instrument master and candle persistence.
3. News ingestion and optional Gemini summarization with source/time provenance.
4. Deterministic strategies and realistic paper fills with brokerage/slippage.
5. Backtesting, out-of-sample validation, monitoring and audit trail.
6. Evaluate current exchange/broker requirements before considering any live execution in a separately reviewed change.

This software is for research and testing. It does not provide financial advice or guarantee results.