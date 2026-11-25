from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient

from aitrading_intelligence.app import app

client = TestClient(app)


def test_health_exposes_contract_version() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "contract_version": "intelligence.v1"}


def test_contract_validation_never_authorizes_capital() -> None:
    now = datetime.now(UTC).replace(microsecond=0) - timedelta(seconds=5)
    response = client.post(
        "/v1/contracts/validate",
        json={
            "contract_version": "intelligence.v1",
            "request_id": "req-api-123456",
            "symbol": "RELIANCE",
            "as_of": now.isoformat(),
            "data_cutoff": (now - timedelta(seconds=1)).isoformat(),
            "feature_schema_version": "market.v1",
            "features": {"rsi_14": 61.2},
            "provenance": [],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["accepted"] is True
    assert body["can_authorize_capital"] is False
    assert body["evidence"] == []
    assert len(body["input_fingerprint"]) == 64


def test_fusion_api_returns_ranked_candidate_not_execution_authority() -> None:
    now = datetime(2026, 1, 2, 10, 0, tzinfo=UTC)
    response = client.post(
        "/v1/fusion/rank",
        json={
            "fusion_version": "signal-fusion.v1",
            "symbol": "RELIANCE",
            "as_of": now.isoformat(),
            "deterministic": {
                "family": "DETERMINISTIC",
                "provider": "java-strategy",
                "version": "SMA_TREND_VOLUME_V1",
                "score": 0.8,
                "confidence": 0.9,
                "observed_at": now.isoformat(),
                "fingerprint": "a" * 64,
                "reasons": ["trend", "volume"],
            },
            "quant": {
                "family": "QUANT",
                "provider": "python-logistic",
                "version": "logistic-baseline.v1",
                "score": 0.7,
                "confidence": 0.8,
                "observed_at": now.isoformat(),
                "fingerprint": "b" * 64,
                "reasons": ["baseline"],
            },
            "gemini": {
                "family": "GEMINI",
                "provider": "gemini",
                "version": "structured-news.v1",
                "score": 0.6,
                "confidence": 0.7,
                "observed_at": now.isoformat(),
                "fingerprint": "c" * 64,
                "reasons": ["positive catalyst"],
            },
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["action"] == "BUY"
    assert body["status"] == "CANDIDATE_NOT_EXECUTABLE"
    assert body["can_authorize_capital"] is False
    assert len(body["opportunity_fingerprint"]) == 64
