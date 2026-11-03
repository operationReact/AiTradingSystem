from datetime import UTC, datetime

from fastapi import FastAPI

from . import CONTRACT_VERSION
from .contracts import EvidenceContractRequest, EvidenceContractResponse
from .fingerprints import request_fingerprint
from .fusion import FusionRequest, RankedOpportunity, fuse_signals
from .selective_presentation import (
    SelectivePresentationRequest,
    SelectivePresentationV1,
    build_selective_presentation,
)

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
        warnings=[
            "Contract validated; evidence must still pass Java Risk Engine before execution."
        ],
        can_authorize_capital=False,
        generated_at=datetime.now(UTC),
    )


@app.post("/v1/fusion/rank", response_model=RankedOpportunity)
def rank_opportunity(request: FusionRequest) -> RankedOpportunity:
    return fuse_signals(request)


@app.post("/v1/selective/presentation", response_model=SelectivePresentationV1)
def selective_presentation(request: SelectivePresentationRequest) -> SelectivePresentationV1:
    return build_selective_presentation(request.decision, request.evaluation)
