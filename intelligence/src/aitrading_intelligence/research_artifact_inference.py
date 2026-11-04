from __future__ import annotations

import hashlib
import hmac
import math
import os
import re
from datetime import UTC, datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Annotated, Literal

import catboost as cb
import lightgbm as lgb
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .catboost_model import CatBoostModelArtifact
from .fingerprints import canonical_fingerprint
from .lightgbm_model import LightGBMModelArtifact

VERSION = "bounded-artifact-research-inference.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelFamily = Literal["CATBOOST", "LIGHTGBM"]
ModelAction = Literal["ENTER", "WAIT", "ABSTAIN"]


class EnabledModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version_id: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    family: ModelFamily
    artifact_filename: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.json$")
    artifact_sha256: Hash64
    artifact_fingerprint: Hash64
    wait_below: Annotated[float, Field(ge=0.0, le=1.0)] = 0.4
    enter_at_or_above: Annotated[float, Field(ge=0.0, le=1.0)] = 0.6

    @model_validator(mode="after")
    def valid_policy(self) -> EnabledModel:
        if self.wait_below >= self.enter_at_or_above:
            raise ValueError("WAIT and ENTER thresholds must define an abstain band")
        return self


class InferenceManifest(BaseModel):
    """Operator-controlled, local allowlist; never supplied by an inference caller."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    contract_version: Literal["bounded-artifact-research-inference.v1"] = VERSION
    models: list[EnabledModel] = Field(min_length=1, max_length=16)

    @model_validator(mode="after")
    def no_duplicates(self) -> InferenceManifest:
        if len({model.model_version_id for model in self.models}) != len(self.models):
            raise ValueError("duplicate model IDs in inference manifest")
        return self


class BoundedInferenceRequest(BaseModel):
    """Pre-decision feature snapshot: no future label or market outcome fields."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    model_version_id: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    case_key: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")
    symbol: str = Field(pattern=r"^[A-Z0-9._-]{2,30}$")
    source_input_fingerprint: Hash64
    feature_as_of: datetime
    latest_feature_available_at: datetime
    features: dict[str, float] = Field(min_length=1, max_length=128)

    @field_validator("features", mode="before")
    @classmethod
    def reject_boolean_features(cls, value: object) -> object:
        if isinstance(value, dict) and any(
            isinstance(item, bool) for item in value.values()
        ):
            raise ValueError("model features must be numeric values, not booleans")
        return value

    @model_validator(mode="after")
    def validate_point_in_time(self) -> BoundedInferenceRequest:
        for timestamp in (self.feature_as_of, self.latest_feature_available_at):
            if timestamp.tzinfo is None or timestamp.utcoffset() is None:
                raise ValueError("feature timestamps must include timezone")
        if self.latest_feature_available_at > self.feature_as_of:
            raise ValueError("future source evidence cannot become a model input")
        if any(not math.isfinite(value) or abs(value) > 1e12
               for value in self.features.values()):
            raise ValueError("feature values must be finite and bounded")
        if any(not re.fullmatch(r"[a-zA-Z0-9_.-]{2,128}", key)
               for key in self.features):
            raise ValueError("unexpected feature name")
        return self


class BoundedInferenceResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["bounded-artifact-research-inference.v1"] = VERSION
    case_key: str
    symbol: str
    model_version_id: str
    model_family: ModelFamily
    model_artifact_fingerprint: Hash64
    source_input_fingerprint: Hash64
    feature_payload_fingerprint: Hash64
    evaluated_at: datetime
    probability: Annotated[float, Field(ge=0.0, le=1.0)]
    action: ModelAction
    status: Literal["ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY"] = (
        "ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY"
    )
    independent_input_custody_verified: Literal[False] = False
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    response_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_response(self) -> BoundedInferenceResponse:
        if self.response_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"response_fingerprint"})
        ):
            raise ValueError("inference response fingerprint mismatch")
        return self


class InferenceUnavailable(ValueError):
    """Sanitized rejection for unconfigured or invalid research artifacts."""


def load_allowlisted_model(
    manifest_path: Path, model_id: str
) -> tuple[EnabledModel, CatBoostModelArtifact | LightGBMModelArtifact]:
    """Validate local manifest and raw artifact bytes; no HTTP-supplied paths."""
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise InferenceUnavailable("research manifest unavailable")
    if manifest_path.stat().st_size > 64 * 1024:
        raise InferenceUnavailable("research manifest too large")
    try:
        manifest = InferenceManifest.model_validate_json(
            manifest_path.read_bytes()
        )
    except (ValueError, OSError) as exc:
        raise InferenceUnavailable("invalid research manifest") from exc
    member = next((x for x in manifest.models if x.model_version_id == model_id), None)
    if member is None:
        raise InferenceUnavailable("requested research model is not allowlisted")
    # No traversal, absolute path, or symlink escape from the trusted manifest directory.
    directory = manifest_path.resolve().parent
    target = directory / member.artifact_filename
    if target.is_symlink() or not target.is_file() or target.resolve().parent != directory:
        raise InferenceUnavailable("research artifact unavailable")
    if target.stat().st_size > 16 * 1024 * 1024:
        raise InferenceUnavailable("research artifact too large")
    try:
        raw = target.read_bytes()
        if hashlib.sha256(raw).hexdigest() != member.artifact_sha256:
            raise InferenceUnavailable("research artifact byte hash mismatch")
        kind = CatBoostModelArtifact if member.family == "CATBOOST" else LightGBMModelArtifact
        artifact = kind.model_validate_json(raw)
    except (ValueError, OSError) as exc:
        raise InferenceUnavailable("research artifact integrity check failed") from exc
    if artifact.model_fingerprint != member.artifact_fingerprint:
        raise InferenceUnavailable("model identity differs from trusted manifest")
    return member, artifact


def infer_from_trained_artifact(
    request: BoundedInferenceRequest,
    allowlisted: EnabledModel,
    artifact: CatBoostModelArtifact | LightGBMModelArtifact,
    *,
    evaluated_at: datetime,
) -> BoundedInferenceResponse:
    """Execute real persisted CatBoost/LightGBM weights, without training or ordering."""
    if evaluated_at.tzinfo is None or evaluated_at.utcoffset() is None:
        raise ValueError("inference evaluation time must be timezone-aware")
    if request.feature_as_of > evaluated_at:
        raise ValueError("cannot score features from the future")
    if request.feature_as_of <= artifact.training_as_of:
        raise ValueError("model cannot score observations before its trained artifact existed")
    if request.model_version_id != allowlisted.model_version_id:
        raise ValueError("model request and trusted manifest mismatch")
    if artifact.model_fingerprint != allowlisted.artifact_fingerprint:
        raise ValueError("artifact identity does not match trusted manifest")
    if (allowlisted.family == "CATBOOST") != isinstance(artifact, CatBoostModelArtifact):
        raise ValueError("model family and artifact type differ")
    if set(request.features) != set(artifact.feature_names):
        raise ValueError("exact trained feature schema is required")
    values = [request.features[name] for name in artifact.feature_names]
    if allowlisted.family == "CATBOOST":
        assert isinstance(artifact, CatBoostModelArtifact)
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "model.json"
            path.write_text(artifact.model_json, encoding="utf-8")
            model = cb.CatBoostClassifier()
            model.load_model(str(path), format="json")
        pool = cb.Pool([values], feature_names=artifact.feature_names)
        probability = float(model.predict_proba(pool)[0][1])
    else:
        assert isinstance(artifact, LightGBMModelArtifact)
        booster = lgb.Booster(model_str=artifact.booster_model)
        probability = float(booster.predict([values])[0])
    if not math.isfinite(probability) or not 0.0 <= probability <= 1.0:
        raise ValueError("nonfinite/out-of-range model prediction")
    action: ModelAction = (
        "ENTER" if probability >= allowlisted.enter_at_or_above
        else "WAIT" if probability < allowlisted.wait_below
        else "ABSTAIN"
    )
    payload = {
        "contract_version": VERSION,
        "case_key": request.case_key,
        "symbol": request.symbol,
        "model_version_id": request.model_version_id,
        "model_family": allowlisted.family,
        "model_artifact_fingerprint": artifact.model_fingerprint,
        "source_input_fingerprint": request.source_input_fingerprint,
        "feature_payload_fingerprint": canonical_fingerprint(
            request.model_dump(mode="json")
        ),
        "evaluated_at": evaluated_at.isoformat(),
        "probability": probability,
        "action": action,
        "status": "ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY",
        "independent_input_custody_verified": False,
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    # Pydantic canonicalizes UTC timestamps to Z before the persisted hash.
    temporary = BoundedInferenceResponse.model_construct(
        **payload, response_fingerprint="0" * 64
    )
    return BoundedInferenceResponse(
        **payload,
        response_fingerprint=canonical_fingerprint(
            temporary.model_dump(mode="json", exclude={"response_fingerprint"})
        ),
    )


def score_allowlisted_request(
    request: BoundedInferenceRequest,
    *,
    manifest_path: Path,
    evaluated_at: datetime | None = None,
) -> BoundedInferenceResponse:
    member, artifact = load_allowlisted_model(manifest_path, request.model_version_id)
    return infer_from_trained_artifact(
        request, member, artifact, evaluated_at=evaluated_at or datetime.now(UTC)
    )


def authorize_research_worker(header_token: str | None) -> Path:
    """Fail closed unless both a secret and artifact manifest are configured."""
    token = os.getenv("AI_RESEARCH_WORKER_TOKEN", "")
    manifest = os.getenv("AI_RESEARCH_MODEL_MANIFEST", "")
    if len(token) < 32 or not manifest:
        raise InferenceUnavailable("research inference worker disabled")
    if header_token is None or not hmac.compare_digest(
        token.encode("utf-8"), header_token.encode("utf-8")
    ):
        raise PermissionError("research worker token invalid")
    return Path(manifest)
