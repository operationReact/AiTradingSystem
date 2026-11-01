from datetime import datetime, timezone

from fastapi import FastAPI

from . import CONTRACT_VERSION
from .contracts import EvidenceContractRequest, EvidenceContractResponse
from .fingerprints import request_fingerprint

app = FastAPI(
    title="AiTradingSystem Intelligence",
    version="0.1.0",
    description="Evidence-only intelligence boundary. Java Risk Engine remains authoritative.",
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "contract_version": CONTRACT_VERSION}


@app.post("/v1/contracts/validate", response_model=EvidenceContractResponse)
def validate_contract(request: EvidenceContractRequest) -> EvidenceContractResponse:
    return EvidenceContractResponse(
        request_id=request.request_id,
        accepted=True,
        input_fingerprint=request_fingerprint(request),
        evidence=[],
        warnings=["Contract validated; no model inference is configured in V2-03."],
        can_authorize_capital=False,
        generated_at=datetime.now(timezone.utc),
    )
