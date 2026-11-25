from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.contracts import EvidenceContractRequest, ProvenanceRef
from aitrading_intelligence.fingerprints import request_fingerprint

NOW = datetime.now(UTC).replace(microsecond=0) - timedelta(seconds=5)


def valid_request() -> EvidenceContractRequest:
    return EvidenceContractRequest(
        request_id="req-12345678",
        symbol="RELIANCE",
        as_of=NOW,
        data_cutoff=NOW - timedelta(seconds=1),
        feature_schema_version="market.v1",
        features={"rsi_14": 61.2, "atr_pct": 1.4},
        provenance=[
            ProvenanceRef(
                source="nse-bars",
                version="2026-11-01",
                observed_at=NOW - timedelta(seconds=2),
            )
        ],
    )


def test_fingerprint_is_deterministic() -> None:
    request = valid_request()
    assert request_fingerprint(request) == request_fingerprint(request)
    assert len(request_fingerprint(request)) == 64


def test_rejects_data_from_after_decision_time() -> None:
    with pytest.raises(ValidationError, match="data_cutoff cannot be later than as_of"):
        EvidenceContractRequest(
            request_id="req-12345678",
            symbol="RELIANCE",
            as_of=NOW,
            data_cutoff=NOW + timedelta(seconds=1),
            feature_schema_version="market.v1",
        )


def test_rejects_provenance_newer_than_cutoff() -> None:
    with pytest.raises(ValidationError, match="provenance observed_at"):
        EvidenceContractRequest(
            request_id="req-12345678",
            symbol="RELIANCE",
            as_of=NOW,
            data_cutoff=NOW - timedelta(seconds=2),
            feature_schema_version="market.v1",
            provenance=[
                ProvenanceRef(
                    source="late-feed",
                    version="v1",
                    observed_at=NOW - timedelta(seconds=1),
                )
            ],
        )


def test_rejects_non_finite_feature() -> None:
    with pytest.raises(ValidationError, match="must be finite"):
        EvidenceContractRequest(
            request_id="req-12345678",
            symbol="RELIANCE",
            as_of=NOW,
            data_cutoff=NOW,
            feature_schema_version="market.v1",
            features={"bad": float("nan")},
        )
