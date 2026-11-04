from datetime import UTC, datetime

from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException

from . import CONTRACT_VERSION
from .contracts import EvidenceContractRequest, EvidenceContractResponse
from .fingerprints import request_fingerprint
from .fusion import FusionRequest, RankedOpportunity, fuse_signals
from .frozen_research_inference import (
    ResearchInferenceEvidence,
    ResearchInferenceRequest,
    authenticate_research_inference,
    configured_research_registry,
)
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


@app.post(
    "/v1/research/model-inference",
    response_model=ResearchInferenceEvidence,
    dependencies=[Depends(authenticate_research_inference)],
)
def research_model_inference(request: ResearchInferenceRequest) -> ResearchInferenceEvidence:
    """Trained artifact scoring only; never exposes a broker/order execution action."""
    try:
        registry = configured_research_registry()
    except (ValueError, OSError):
        raise HTTPException(
            status_code=503, detail="Frozen research model registry is unavailable"
        ) from None
    try:
        return registry.infer(request)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from None
