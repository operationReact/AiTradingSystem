from __future__ import annotations

import hashlib
import json
import math
import os
import secrets
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Annotated, Literal

import catboost as cb
import lightgbm as lgb
from fastapi import Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .catboost_model import CatBoostModelArtifact
from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .lightgbm_model import LightGBMModelArtifact

VERSION = "v4-frozen-research-model-inference.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Family = Literal["CATBOOST", "LIGHTGBM"]
Decision = Literal["ENTER", "WAIT", "ABSTAIN"]
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024


class RegisteredResearchArtifact(BaseModel):
    """Local artifact attestation, configured by the operator, not by API callers."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    model_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")]
    family: Family
    artifact_path: Path
    artifact_sha256: Hash64
    expected_model_fingerprint: Hash64

    @model_validator(mode="after")
    def check_path(self) -> RegisteredResearchArtifact:
        if not self.artifact_path.is_absolute():
            raise ValueError("research artifact path must be absolute")
        return self


class ResearchArtifactManifest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-frozen-research-model-inference.v1"] = VERSION
    artifacts: list[RegisteredResearchArtifact] = Field(min_length=1, max_length=16)

    @model_validator(mode="after")
    def check_registry(self) -> ResearchArtifactManifest:
        if len({a.model_id for a in self.artifacts}) != len(self.artifacts):
            raise ValueError("research model identifiers must be unique")
        return self


class ResearchInferenceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")]
    target_spec_fingerprint: Hash64
    row: FeatureRow

    @model_validator(mode="after")
    def check_feature_fingerprint(self) -> ResearchInferenceRequest:
        # FeatureRow's builder uses datetime.isoformat() (+00:00 for UTC).
        # Preserve that canonical contract when reading JSON timestamps ending Z.
        payload = self.row.model_dump(mode="json", exclude={"fingerprint"})
        payload["as_of"] = self.row.as_of.isoformat()
        payload["data_cutoff"] = self.row.data_cutoff.isoformat()
        if canonical_fingerprint(payload) != self.row.fingerprint:
            raise ValueError("feature row fingerprint does not match supplied causal features")
        if any(
            token in key.lower()
            for key in self.row.features
            for token in ("future_", "forward_return", "target_", "label_", "outcome_", "pnl_")
        ):
            raise ValueError("future outcome or target fields are prohibited at inference")
        return self


class ResearchInferenceEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["v4-frozen-research-model-inference.v1"] = VERSION
    model_id: str
    family: Family
    model_fingerprint: Hash64
    artifact_sha256: Hash64
    target_spec_fingerprint: Hash64
    feature_row_fingerprint: Hash64
    symbol: str
    as_of: datetime
    data_cutoff: datetime
    predicted_at: datetime
    probability_up: Annotated[float, Field(ge=0.0, le=1.0)]
    action: Decision
    status: Literal["UNATTESTED_RESEARCH_INFERENCE_ONLY"] = (
        "UNATTESTED_RESEARCH_INFERENCE_ONLY"
    )
    trained_model_execution: Literal[True] = True
    source_provider_verified: Literal[False] = False
    external_timestamp_witness_verified: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    actual_paper_fill_verified: Literal[False] = False
    live_order_execution_enabled: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    inference_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_fingerprint(self) -> ResearchInferenceEvidence:
        if self.inference_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"inference_fingerprint"})
        ):
            raise ValueError("research inference evidence fingerprint mismatch")
        return self


class FrozenResearchModel:
    def __init__(self, entry: RegisteredResearchArtifact):
        path = entry.artifact_path
        if path.is_symlink() or not path.is_file():
            raise ValueError("research artifact must be a regular, non-symlinked file")
        if path.stat().st_size > MAX_ARTIFACT_BYTES:
            raise ValueError("research artifact exceeds bounded model size")
        content = path.read_bytes()
        if hashlib.sha256(content).hexdigest() != entry.artifact_sha256:
            raise ValueError("research artifact bytes differ from frozen registry digest")
        payload = json.loads(content)
        if entry.family == "CATBOOST":
            artifact = CatBoostModelArtifact.model_validate(payload)
            if artifact.catboost_version != cb.__version__:
                raise ValueError("CatBoost runtime does not match frozen model version")
            with TemporaryDirectory() as directory:
                model_path = Path(directory) / "model.json"
                model_path.write_text(artifact.model_json, encoding="utf-8")
                model = cb.CatBoostClassifier()
                model.load_model(str(model_path), format="json")
            if model.feature_names_ != artifact.feature_names:
                raise ValueError("CatBoost artifact feature order differs from fitted model")
        else:
            artifact = LightGBMModelArtifact.model_validate(payload)
            if artifact.lightgbm_version != lgb.__version__:
                raise ValueError("LightGBM runtime does not match frozen model version")
            model = lgb.Booster(model_str=artifact.booster_model)
            if model.feature_name() != artifact.feature_names:
                raise ValueError("LightGBM artifact feature order differs from fitted model")

        if artifact.model_fingerprint != entry.expected_model_fingerprint:
            raise ValueError("frozen model identifier differs from artifact metadata")
        self.entry = entry
        self.artifact = artifact
        self.model = model

    def score(self, request: ResearchInferenceRequest) -> ResearchInferenceEvidence:
        if request.model_id != self.entry.model_id:
            raise ValueError("request model identifier does not match loaded artifact")
        artifact = self.artifact
        if request.target_spec_fingerprint != artifact.target_spec_fingerprint:
            raise ValueError("model and feature target specifications differ")
        if request.row.as_of <= artifact.training_as_of:
            raise ValueError("inference must be strictly after the training cutoff")
        if any(name not in request.row.features for name in artifact.feature_names):
            raise ValueError("missing a fitted model feature; implicit zero fill is forbidden")
        vector = [float(request.row.features[name]) for name in artifact.feature_names]
        if any(not math.isfinite(value) for value in vector):
            raise ValueError("model feature vector must contain finite values")

        if self.entry.family == "CATBOOST":
            pool = cb.Pool([vector], feature_names=artifact.feature_names)
            probability = float(self.model.predict_proba(pool)[0][1])
        else:
            probability = float(self.model.predict([vector], num_threads=1)[0])
        if not math.isfinite(probability) or not (0 <= probability <= 1):
            raise ValueError("trained model returned an invalid probability")

        # Deliberately a research classification, not a Java strategy/BUY order.
        action: Decision = (
            "ENTER" if probability >= 0.60 else "WAIT" if probability <= 0.40 else "ABSTAIN"
        )
        payload = {
            "contract_version": VERSION,
            "model_id": self.entry.model_id,
            "family": self.entry.family,
            "model_fingerprint": artifact.model_fingerprint,
            "artifact_sha256": self.entry.artifact_sha256,
            "target_spec_fingerprint": request.target_spec_fingerprint,
            "feature_row_fingerprint": request.row.fingerprint,
            "symbol": request.row.symbol,
            "as_of": request.row.as_of,
            "data_cutoff": request.row.data_cutoff,
            "predicted_at": datetime.now(UTC),
            "probability_up": probability,
            "action": action,
            "status": "UNATTESTED_RESEARCH_INFERENCE_ONLY",
            "trained_model_execution": True,
            "source_provider_verified": False,
            "external_timestamp_witness_verified": False,
            "model_promotion_allowed": False,
            "actual_paper_fill_verified": False,
            "live_order_execution_enabled": False,
            "can_authorize_capital": False,
        }
        draft = ResearchInferenceEvidence.model_construct(
            **payload, inference_fingerprint="0" * 64
        )
        return ResearchInferenceEvidence(
            **payload,
            inference_fingerprint=canonical_fingerprint(
                draft.model_dump(mode="json", exclude={"inference_fingerprint"})
            ),
        )


class FrozenResearchRegistry:
    """Load bounded artifacts once; callers cannot upload or override model bytes."""

    def __init__(self, manifest: ResearchArtifactManifest):
        self.models = {
            entry.model_id: FrozenResearchModel(entry) for entry in manifest.artifacts
        }

    @classmethod
    def from_file(cls, path: Path) -> FrozenResearchRegistry:
        if not path.is_absolute() or path.is_symlink() or not path.is_file():
            raise ValueError("research manifest must be an absolute, non-symlinked file")
        if path.stat().st_size > 32_768:
            raise ValueError("research manifest exceeds bounded size")
        return cls(ResearchArtifactManifest.model_validate_json(path.read_bytes()))

    def infer(self, request: ResearchInferenceRequest) -> ResearchInferenceEvidence:
        model = self.models.get(request.model_id)
        if model is None:
            raise ValueError("model is not in the operator's frozen research registry")
        return model.score(request)


@lru_cache(maxsize=1)
def configured_research_registry() -> FrozenResearchRegistry:
    raw_path = os.environ.get("APP_RESEARCH_ARTIFACT_MANIFEST")
    if not raw_path:
        raise ValueError("frozen research artifact manifest not configured")
    return FrozenResearchRegistry.from_file(Path(raw_path))


def authenticate_research_inference(
    x_research_service_token: Annotated[str | None, Header()] = None,
) -> None:
    expected = os.environ.get("APP_RESEARCH_INFERENCE_TOKEN", "")
    if len(expected) < 24 or not os.environ.get("APP_RESEARCH_ARTIFACT_MANIFEST"):
        raise HTTPException(status_code=503, detail="Research inference not configured")
    if not secrets.compare_digest(x_research_service_token or "", expected):
        raise HTTPException(status_code=401, detail="Research inference authorization required")
