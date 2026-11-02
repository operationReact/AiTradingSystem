from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .monte_carlo import MonteCarloEvidence

if TYPE_CHECKING:
    from .catboost_model import CatBoostModelArtifact
    from .lightgbm_model import LightGBMModelArtifact
    from .model_fair_comparison import ModelFairComparisonReport

REGISTRY_VERSION = "model-registry.v1"
MODEL_LAB_REGISTRY_VERSION = "model-lab-registry.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
GitSha = Annotated[str, Field(pattern=r"^[a-f0-9]{40,64}$")]
ModelFamily = Literal["LOGISTIC", "TREE", "PYTORCH", "LIGHTGBM", "CATBOOST"]
GradientBoostingFamily = Literal["LIGHTGBM", "CATBOOST"]


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


class ModelLabRegistryEntry(BaseModel):
    """Research registry evidence that cannot bypass downstream governance gates."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    registry_version: Literal["model-lab-registry.v1"] = MODEL_LAB_REGISTRY_VERSION
    model_family: GradientBoostingFamily
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    artifact_fingerprint: Hash64
    feature_names: list[str] = Field(min_length=1, max_length=128)
    feature_names_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    training_dataset_fingerprint: Hash64
    training_config_fingerprint: Hash64
    training_as_of: datetime
    evaluation_as_of: datetime
    comparison_config_fingerprint: Hash64
    fair_comparison_report_fingerprint: Hash64
    fair_comparison_metrics_fingerprint: Hash64
    fold_membership_fingerprint: Hash64
    code_sha: GitSha
    registered_at: datetime
    stage: Literal["RESEARCH_MODEL_LAB"] = "RESEARCH_MODEL_LAB"
    required_next_gate: Literal["V3_05_CALIBRATION"] = "V3_05_CALIBRATION"
    reproducibility_fingerprint: Hash64
    registry_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("training_as_of", "evaluation_as_of", "registered_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("registry timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_registry_entry(self) -> ModelLabRegistryEntry:
        if len(set(self.feature_names)) != len(self.feature_names):
            raise ValueError("feature_names cannot contain duplicates")
        expected_feature_fingerprint = canonical_fingerprint(
            {"feature_names": self.feature_names}
        )
        if self.feature_names_fingerprint != expected_feature_fingerprint:
            raise ValueError("feature_names_fingerprint does not match feature_names")
        if self.training_as_of > self.evaluation_as_of:
            raise ValueError("training_as_of cannot exceed evaluation_as_of")
        if self.evaluation_as_of > self.registered_at:
            raise ValueError("evaluation_as_of cannot exceed registered_at")
        return self


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


def register_gradient_boosting_model_lab_entry(
    *,
    model_family: GradientBoostingFamily,
    artifact: LightGBMModelArtifact | CatBoostModelArtifact,
    fair_comparison: ModelFairComparisonReport,
    code_sha: str,
    registered_at: datetime,
) -> ModelLabRegistryEntry:
    """Register accepted V3-04 research evidence without granting governance authority."""

    if registered_at.tzinfo is None or registered_at.utcoffset() is None:
        raise ValueError("registered_at must be timezone-aware")
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("model artifact cannot carry promotion or capital authority")
    if fair_comparison.promotion_allowed or fair_comparison.can_authorize_capital:
        raise ValueError("fair comparison cannot carry promotion or capital authority")

    expected_versions = {
        "LIGHTGBM": "lightgbm-binary.v1",
        "CATBOOST": "catboost-binary.v1",
    }
    if artifact.model_version != expected_versions[model_family]:
        raise ValueError("artifact model version does not match requested model family")
    if artifact.target_spec_fingerprint != fair_comparison.target_spec_fingerprint:
        raise ValueError("artifact target lineage does not match fair comparison")
    if list(artifact.feature_names) != fair_comparison.feature_names:
        raise ValueError("artifact feature order does not match fair comparison")
    if artifact.training_as_of > fair_comparison.evaluation_as_of:
        raise ValueError("artifact training cutoff cannot exceed comparison evaluation cutoff")
    if fair_comparison.evaluation_as_of > registered_at:
        raise ValueError("comparison evaluation cutoff cannot exceed registration time")

    matches = [
        model
        for model in fair_comparison.models
        if model.model_family == model_family
    ]
    if len(matches) != 1:
        raise ValueError("fair comparison must contain exactly one matching model family")
    metrics = matches[0]
    if metrics.model_version != artifact.model_version:
        raise ValueError("artifact model version does not match comparison metrics")
    if metrics.promotion_allowed or metrics.can_authorize_capital:
        raise ValueError("comparison metrics cannot carry promotion or capital authority")

    feature_names = list(artifact.feature_names)
    feature_names_fingerprint = canonical_fingerprint({"feature_names": feature_names})
    training_dataset_fingerprint = canonical_fingerprint(
        {
            "target_spec_fingerprint": artifact.target_spec_fingerprint,
            "training_examples": artifact.training_example_fingerprints,
            "validation_examples": artifact.validation_example_fingerprints,
        }
    )
    reproducibility_payload = {
        "registry_version": MODEL_LAB_REGISTRY_VERSION,
        "model_family": model_family,
        "model_version": artifact.model_version,
        "artifact_fingerprint": artifact.model_fingerprint,
        "feature_names_fingerprint": feature_names_fingerprint,
        "target_spec_fingerprint": artifact.target_spec_fingerprint,
        "training_dataset_fingerprint": training_dataset_fingerprint,
        "training_config_fingerprint": artifact.training_config_fingerprint,
        "training_as_of": artifact.training_as_of.isoformat(),
        "evaluation_as_of": fair_comparison.evaluation_as_of.isoformat(),
        "comparison_config_fingerprint": fair_comparison.comparison_config_fingerprint,
        "fair_comparison_report_fingerprint": fair_comparison.report_fingerprint,
        "fair_comparison_metrics_fingerprint": metrics.metrics_fingerprint,
        "fold_membership_fingerprint": metrics.fold_membership_fingerprint,
        "code_sha": code_sha,
        "stage": "RESEARCH_MODEL_LAB",
        "required_next_gate": "V3_05_CALIBRATION",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    reproducibility_fingerprint = canonical_fingerprint(reproducibility_payload)
    registry_payload = {
        **reproducibility_payload,
        "registered_at": registered_at.isoformat(),
        "reproducibility_fingerprint": reproducibility_fingerprint,
    }
    return ModelLabRegistryEntry(
        model_family=model_family,
        model_version=artifact.model_version,
        artifact_fingerprint=artifact.model_fingerprint,
        feature_names=feature_names,
        feature_names_fingerprint=feature_names_fingerprint,
        target_spec_fingerprint=artifact.target_spec_fingerprint,
        training_dataset_fingerprint=training_dataset_fingerprint,
        training_config_fingerprint=artifact.training_config_fingerprint,
        training_as_of=artifact.training_as_of,
        evaluation_as_of=fair_comparison.evaluation_as_of,
        comparison_config_fingerprint=fair_comparison.comparison_config_fingerprint,
        fair_comparison_report_fingerprint=fair_comparison.report_fingerprint,
        fair_comparison_metrics_fingerprint=metrics.metrics_fingerprint,
        fold_membership_fingerprint=metrics.fold_membership_fingerprint,
        code_sha=code_sha,
        registered_at=registered_at,
        reproducibility_fingerprint=reproducibility_fingerprint,
        registry_fingerprint=canonical_fingerprint(registry_payload),
    )
