from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .catboost_model import CatBoostModelArtifact, CatBoostTrainingConfig
from .fingerprints import canonical_fingerprint
from .lightgbm_model import LightGBMModelArtifact, LightGBMTrainingConfig
from .model_fair_comparison import FairComparisonConfig, ModelFairComparisonReport
from .monte_carlo import MonteCarloEvidence
from .pytorch_model import TorchModelArtifact, TorchTrainingConfig

REGISTRY_VERSION = "model-registry.v1"
REGISTRY_SNAPSHOT_VERSION = "model-registry-snapshot.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
GitSha = Annotated[str, Field(pattern=r"^[a-f0-9]{40,64}$")]
ModelFamily = Literal["LOGISTIC", "TREE", "PYTORCH", "LIGHTGBM", "CATBOOST"]
ModelLabFamily = Literal["PYTORCH", "LIGHTGBM", "CATBOOST"]


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
    """Research-stage registry entry; never equivalent to a governance candidate."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    registry_version: Literal["model-registry.v1"] = REGISTRY_VERSION
    model_family: ModelLabFamily
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    artifact_fingerprint: Hash64
    feature_schema_fingerprint: Hash64
    training_dataset_fingerprint: Hash64
    target_spec_fingerprint: Hash64
    training_config_fingerprint: Hash64
    code_sha: GitSha
    training_as_of: datetime
    evaluation_as_of: datetime
    evaluation_report_fingerprint: Hash64
    evaluation_metrics_fingerprint: Hash64
    registered_at: datetime
    stage: Literal["V3_MODEL_LAB_REGISTERED"] = "V3_MODEL_LAB_REGISTERED"
    required_next_gate: Literal["V3_05_CALIBRATION"] = "V3_05_CALIBRATION"
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
    def validate_temporal_lineage(self) -> ModelLabRegistryEntry:
        if self.training_as_of > self.evaluation_as_of:
            raise ValueError("training_as_of cannot exceed evaluation_as_of")
        if self.evaluation_as_of > self.registered_at:
            raise ValueError("evaluation_as_of cannot exceed registered_at")
        return self


class ModelRegistrySnapshot(BaseModel):
    """Point-in-time immutable view of research-stage model registrations."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-registry-snapshot.v1"] = REGISTRY_SNAPSHOT_VERSION
    registry_as_of: datetime
    entries: list[ModelLabRegistryEntry] = Field(min_length=1, max_length=1_000)
    snapshot_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("registry_as_of")
    @classmethod
    def registry_as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("registry_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_entries(self) -> ModelRegistrySnapshot:
        if any(entry.registered_at > self.registry_as_of for entry in self.entries):
            raise ValueError("registry snapshot cannot contain future registrations")
        fingerprints = [entry.registry_fingerprint for entry in self.entries]
        if len(set(fingerprints)) != len(fingerprints):
            raise ValueError("registry snapshot cannot contain duplicate entries")
        canonical_order = sorted(
            self.entries,
            key=lambda entry: (
                entry.model_family,
                entry.model_version,
                entry.artifact_fingerprint,
                entry.registry_fingerprint,
            ),
        )
        if self.entries != canonical_order:
            raise ValueError("registry snapshot entries must use canonical order")
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


def _register_model_lab_entry(
    *,
    model_family: ModelLabFamily,
    model_version: str,
    artifact_fingerprint: str,
    feature_names: Sequence[str],
    target_spec_fingerprint: str,
    training_config_fingerprint: str,
    training_as_of: datetime,
    training_example_fingerprints: Sequence[str],
    validation_example_fingerprints: Sequence[str],
    expected_training_config_fingerprint: str,
    comparison: ModelFairComparisonReport,
    comparison_config: FairComparisonConfig,
    code_sha: str,
    registered_at: datetime,
) -> ModelLabRegistryEntry:
    if comparison.promotion_allowed or comparison.can_authorize_capital:
        raise ValueError("fair comparison cannot carry promotion or capital authority")
    if comparison_config.fingerprint != comparison.comparison_config_fingerprint:
        raise ValueError("comparison config does not match the report fingerprint")
    if target_spec_fingerprint != comparison.target_spec_fingerprint:
        raise ValueError("model target does not match fair-comparison target")
    if list(feature_names) != comparison.feature_names:
        raise ValueError("model features do not match fair-comparison features")
    if training_config_fingerprint != expected_training_config_fingerprint:
        raise ValueError("model training config does not match fair-comparison config")
    if registered_at.tzinfo is None or registered_at.utcoffset() is None:
        raise ValueError("registered_at must be timezone-aware")
    if training_as_of > comparison.evaluation_as_of:
        raise ValueError("model training cutoff cannot exceed fair-comparison cutoff")
    if comparison.evaluation_as_of > registered_at:
        raise ValueError("fair comparison must exist before registry registration")

    matching_metrics = [
        metrics
        for metrics in comparison.models
        if metrics.model_family == model_family and metrics.model_version == model_version
    ]
    if len(matching_metrics) != 1:
        raise ValueError("fair comparison must contain exactly one matching model metric")
    metrics = matching_metrics[0]
    if metrics.promotion_allowed or metrics.can_authorize_capital:
        raise ValueError("model metrics cannot carry promotion or capital authority")

    feature_schema_fingerprint = canonical_fingerprint(
        {"feature_names": list(feature_names)}
    )
    training_dataset_fingerprint = canonical_fingerprint(
        {
            "training_examples": list(training_example_fingerprints),
            "validation_examples": list(validation_example_fingerprints),
        }
    )
    payload = {
        "registry_version": REGISTRY_VERSION,
        "model_family": model_family,
        "model_version": model_version,
        "artifact_fingerprint": artifact_fingerprint,
        "feature_schema_fingerprint": feature_schema_fingerprint,
        "training_dataset_fingerprint": training_dataset_fingerprint,
        "target_spec_fingerprint": target_spec_fingerprint,
        "training_config_fingerprint": training_config_fingerprint,
        "code_sha": code_sha,
        "training_as_of": training_as_of.isoformat(),
        "evaluation_as_of": comparison.evaluation_as_of.isoformat(),
        "evaluation_report_fingerprint": comparison.report_fingerprint,
        "evaluation_metrics_fingerprint": metrics.metrics_fingerprint,
        "registered_at": registered_at.isoformat(),
        "stage": "V3_MODEL_LAB_REGISTERED",
        "required_next_gate": "V3_05_CALIBRATION",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelLabRegistryEntry(
        **payload,
        registry_fingerprint=canonical_fingerprint(payload),
    )


def register_lightgbm_model_lab_entry(
    *,
    artifact: LightGBMModelArtifact,
    comparison: ModelFairComparisonReport,
    comparison_config: FairComparisonConfig,
    code_sha: str,
    registered_at: datetime,
) -> ModelLabRegistryEntry:
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("LightGBM artifact cannot carry promotion or capital authority")
    expected_config = LightGBMTrainingConfig(seed=comparison_config.seed)
    return _register_model_lab_entry(
        model_family="LIGHTGBM",
        model_version=artifact.model_version,
        artifact_fingerprint=artifact.model_fingerprint,
        feature_names=artifact.feature_names,
        target_spec_fingerprint=artifact.target_spec_fingerprint,
        training_config_fingerprint=artifact.training_config_fingerprint,
        training_as_of=artifact.training_as_of,
        training_example_fingerprints=artifact.training_example_fingerprints,
        validation_example_fingerprints=artifact.validation_example_fingerprints,
        expected_training_config_fingerprint=expected_config.fingerprint,
        comparison=comparison,
        comparison_config=comparison_config,
        code_sha=code_sha,
        registered_at=registered_at,
    )


def register_catboost_model_lab_entry(
    *,
    artifact: CatBoostModelArtifact,
    comparison: ModelFairComparisonReport,
    comparison_config: FairComparisonConfig,
    code_sha: str,
    registered_at: datetime,
) -> ModelLabRegistryEntry:
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("CatBoost artifact cannot carry promotion or capital authority")
    expected_config = CatBoostTrainingConfig(seed=comparison_config.seed)
    return _register_model_lab_entry(
        model_family="CATBOOST",
        model_version=artifact.model_version,
        artifact_fingerprint=artifact.model_fingerprint,
        feature_names=artifact.feature_names,
        target_spec_fingerprint=artifact.target_spec_fingerprint,
        training_config_fingerprint=artifact.training_config_fingerprint,
        training_as_of=artifact.training_as_of,
        training_example_fingerprints=artifact.training_example_fingerprints,
        validation_example_fingerprints=artifact.validation_example_fingerprints,
        expected_training_config_fingerprint=expected_config.fingerprint,
        comparison=comparison,
        comparison_config=comparison_config,
        code_sha=code_sha,
        registered_at=registered_at,
    )


def register_pytorch_model_lab_entry(
    *,
    artifact: TorchModelArtifact,
    comparison: ModelFairComparisonReport,
    comparison_config: FairComparisonConfig,
    code_sha: str,
    registered_at: datetime,
) -> ModelLabRegistryEntry:
    if artifact.promotion_allowed or artifact.can_authorize_capital:
        raise ValueError("PyTorch artifact cannot carry promotion or capital authority")
    expected_config = TorchTrainingConfig(seed=comparison_config.seed)
    return _register_model_lab_entry(
        model_family="PYTORCH",
        model_version=artifact.model_version,
        artifact_fingerprint=artifact.model_fingerprint,
        feature_names=artifact.feature_names,
        target_spec_fingerprint=artifact.target_spec_fingerprint,
        training_config_fingerprint=artifact.training_config_fingerprint,
        training_as_of=artifact.training_as_of,
        training_example_fingerprints=artifact.training_example_fingerprints,
        validation_example_fingerprints=artifact.validation_example_fingerprints,
        expected_training_config_fingerprint=expected_config.fingerprint,
        comparison=comparison,
        comparison_config=comparison_config,
        code_sha=code_sha,
        registered_at=registered_at,
    )


def build_model_registry_snapshot(
    entries: Sequence[ModelLabRegistryEntry],
    *,
    registry_as_of: datetime,
) -> ModelRegistrySnapshot:
    if registry_as_of.tzinfo is None or registry_as_of.utcoffset() is None:
        raise ValueError("registry_as_of must be timezone-aware")
    eligible = [entry for entry in entries if entry.registered_at <= registry_as_of]
    if not eligible:
        raise ValueError("registry snapshot requires at least one causal registration")
    ordered = sorted(
        eligible,
        key=lambda entry: (
            entry.model_family,
            entry.model_version,
            entry.artifact_fingerprint,
            entry.registry_fingerprint,
        ),
    )
    fingerprints = [entry.registry_fingerprint for entry in ordered]
    if len(set(fingerprints)) != len(fingerprints):
        raise ValueError("registry snapshot cannot contain duplicate entries")
    payload = {
        "contract_version": REGISTRY_SNAPSHOT_VERSION,
        "registry_as_of": registry_as_of.isoformat(),
        "entries": [entry.model_dump(mode="json") for entry in ordered],
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelRegistrySnapshot(
        registry_as_of=registry_as_of,
        entries=ordered,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
