from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .fingerprints import canonical_fingerprint
from .monte_carlo import MonteCarloEvidence

REGISTRY_VERSION = "model-registry.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
GitSha = Annotated[str, Field(pattern=r"^[a-f0-9]{40,64}$")]
ModelFamily = Literal["LOGISTIC", "TREE", "PYTORCH"]


class ModelGovernanceCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    registry_version: Literal["model-registry.v1"] = REGISTRY_VERSION
    model_family: ModelFamily
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    artifact_fingerprint: Hash64
    feature_schema_fingerprint: Hash64
    training_dataset_fingerprint: Hash64
    training_config_fingerprint: Hash64
    code_sha: GitSha
    evaluation_fingerprint: Hash64
    monte_carlo_evidence_fingerprint: Hash64
    registered_at: datetime
    challenger_of: Hash64 | None = None
    stage: Literal["GOVERNANCE_CANDIDATE"] = "GOVERNANCE_CANDIDATE"
    required_next_gate: Literal["V2_09_SHADOW_PAPER_EVALUATION"] = (
        "V2_09_SHADOW_PAPER_EVALUATION"
    )
    registry_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("registered_at")
    @classmethod
    def registered_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("registered_at must be timezone-aware")
        return value


def register_governance_candidate(
    *,
    model_family: ModelFamily,
    model_version: str,
    artifact_fingerprint: str,
    feature_schema_fingerprint: str,
    training_dataset_fingerprint: str,
    training_config_fingerprint: str,
    code_sha: str,
    evaluation_fingerprint: str,
    monte_carlo_evidence: MonteCarloEvidence,
    registered_at: datetime,
    challenger_of: str | None = None,
) -> ModelGovernanceCandidate:
    if monte_carlo_evidence.assessment != "V2_09_GOVERNANCE_CANDIDATE":
        raise ValueError("model cannot enter governance without passing V2-08 robustness gates")
    if monte_carlo_evidence.next_gate != "V2_09_OUTCOME_GOVERNANCE":
        raise ValueError("Monte Carlo evidence must advance specifically to V2-09 governance")
    if monte_carlo_evidence.promotion_allowed or monte_carlo_evidence.can_authorize_capital:
        raise ValueError("research evidence cannot carry promotion or capital authority")
    if registered_at.tzinfo is None or registered_at.utcoffset() is None:
        raise ValueError("registered_at must be timezone-aware")

    payload = {
        "registry_version": REGISTRY_VERSION,
        "model_family": model_family,
        "model_version": model_version,
        "artifact_fingerprint": artifact_fingerprint,
        "feature_schema_fingerprint": feature_schema_fingerprint,
        "training_dataset_fingerprint": training_dataset_fingerprint,
        "training_config_fingerprint": training_config_fingerprint,
        "code_sha": code_sha,
        "evaluation_fingerprint": evaluation_fingerprint,
        "monte_carlo_evidence_fingerprint": monte_carlo_evidence.evidence_fingerprint,
        "registered_at": registered_at.isoformat(),
        "challenger_of": challenger_of,
        "stage": "GOVERNANCE_CANDIDATE",
        "required_next_gate": "V2_09_SHADOW_PAPER_EVALUATION",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelGovernanceCandidate(
        model_family=model_family,
        model_version=model_version,
        artifact_fingerprint=artifact_fingerprint,
        feature_schema_fingerprint=feature_schema_fingerprint,
        training_dataset_fingerprint=training_dataset_fingerprint,
        training_config_fingerprint=training_config_fingerprint,
        code_sha=code_sha,
        evaluation_fingerprint=evaluation_fingerprint,
        monte_carlo_evidence_fingerprint=monte_carlo_evidence.evidence_fingerprint,
        registered_at=registered_at,
        challenger_of=challenger_of,
        registry_fingerprint=canonical_fingerprint(payload),
    )
