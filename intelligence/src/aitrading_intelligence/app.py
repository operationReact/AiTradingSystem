from datetime import UTC, datetime

from fastapi import FastAPI, Header, HTTPException

from . import CONTRACT_VERSION
from .contracts import EvidenceContractRequest, EvidenceContractResponse
from .fingerprints import request_fingerprint
from .fusion import FusionRequest, RankedOpportunity, fuse_signals
from .research_artifact_inference import (
    BoundedInferenceRequest,
    BoundedInferenceResponse,
    InferenceUnavailable,
    VerifiedWorkerRoster,
    authorize_research_worker,
    inspect_worker_roster,
    score_allowlisted_request,
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


@app.get(
    "/v1/research/artifact-readiness",
    response_model=VerifiedWorkerRoster,
)
def verified_artifact_readiness(
    x_research_worker_token: str | None = Header(default=None, alias="X-Research-Worker-Token"),
) -> VerifiedWorkerRoster:
    """Authenticated local artifact/allowlist probe, research-only and read-only."""
    try:
        manifest = authorize_research_worker(x_research_worker_token)
    except PermissionError as exc:
        raise HTTPException(status_code=401, detail="Research worker unauthorized") from exc
    except InferenceUnavailable as exc:
        raise HTTPException(status_code=503, detail="Research worker not configured") from exc
    try:
        return inspect_worker_roster(manifest)
    except InferenceUnavailable as exc:
        raise HTTPException(status_code=503, detail="Research worker roster unavailable") from exc


@app.post(
    "/v1/research/artifact-inference",
    response_model=BoundedInferenceResponse,
)
def actual_artifact_inference(
    request: BoundedInferenceRequest,
    x_research_worker_token: str | None = Header(default=None, alias="X-Research-Worker-Token"),
) -> BoundedInferenceResponse:
    """Internal, opt-in research scoring; never an API to place trades."""
    try:
        manifest = authorize_research_worker(x_research_worker_token)
    except PermissionError as exc:
        raise HTTPException(status_code=401, detail="Research worker unauthorized") from exc
    except InferenceUnavailable as exc:
        raise HTTPException(status_code=503, detail="Research inference unavailable") from exc
    try:
        return score_allowlisted_request(request, manifest_path=manifest)
    except InferenceUnavailable as exc:
        raise HTTPException(status_code=503, detail="Research artifact unavailable") from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail="Research snapshot not eligible") from exc
