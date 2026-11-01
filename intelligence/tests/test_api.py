from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from aitrading_intelligence.app import app

client = TestClient(app)


def test_health_exposes_contract_version() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "contract_version": "intelligence.v1"}


def test_contract_validation_never_authorizes_capital() -> None:
    now = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(seconds=5)
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
