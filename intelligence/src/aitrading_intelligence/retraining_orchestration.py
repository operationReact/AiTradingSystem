from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timedelta
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .datasets import DatasetManifest
from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .learning_dataset import LearningDatasetManifest
from .model_monitoring import ModelMonitoringReport
from .model_registry import ModelGovernanceCandidate
from .pytorch_evaluation import PyTorchEvaluation, evaluate_pytorch_challenger
from .pytorch_model import TorchTrainingConfig
from .pytorch_target import LabeledExample

RETRAINING_ORCHESTRATION_VERSION = "scheduled-retraining.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
GitSha = Annotated[str, Field(pattern=r"^[a-f0-9]{40,64}$")]


class RetrainingScheduleConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_cooldown_hours: Annotated[int, Field(ge=1, le=720)] = 24
    minimum_learning_rows: Annotated[int, Field(ge=20, le=1_000_000)] = 100
    minimum_research_rows: Annotated[int, Field(ge=40, le=10_000_000)] = 50

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CandidateRetrainingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["scheduled-retraining.v1"] = RETRAINING_ORCHESTRATION_VERSION
    source_registry_fingerprint: Hash64
    monitoring_report_fingerprint: Hash64
    learning_dataset_fingerprint: Hash64
    research_dataset_fingerprint: Hash64
    research_feature_schema_version: Annotated[str, Field(min_length=1, max_length=120)]
    source_feature_schema_fingerprint: Hash64
    training_config_fingerprint: Hash64
    scheduler_config_fingerprint: Hash64
    code_sha: GitSha
    requested_at: datetime
    scheduled_for: datetime
    trigger_reason_codes: list[Annotated[str, Field(min_length=1, max_length=160)]]
    request_fingerprint: Hash64
    status: Literal["RESEARCH_RETRAINING_SCHEDULED"] = "RESEARCH_RETRAINING_SCHEDULED"
    required_next_gate: Literal["V2_09_RUN_CANDIDATE_RETRAINING"] = (
        "V2_09_RUN_CANDIDATE_RETRAINING"
    )
    activation_allowed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("requested_at", "scheduled_for")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("retraining timestamps must be timezone-aware")
        return value


class CandidateRetrainingRun(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["scheduled-retraining.v1"] = RETRAINING_ORCHESTRATION_VERSION
    request_fingerprint: Hash64
    source_registry_fingerprint: Hash64
    monitoring_report_fingerprint: Hash64
    learning_dataset_fingerprint: Hash64
    research_dataset_fingerprint: Hash64
    evaluation_fingerprint: Hash64
    challenger_model_fingerprint: Hash64
    evaluation_comparison: Literal[
        "PYTORCH_ADVANCES_TO_REPLAY",
        "BASELINES_HOLD",
        "INCONCLUSIVE",
    ]
    replay_candidate: bool
    started_at: datetime
    completed_at: datetime
    required_next_gate: Literal[
        "V2_08_REPLAY_WALK_FORWARD",
        "V2_09_RETRAINING_RESEARCH_REVIEW",
    ]
    run_fingerprint: Hash64
    status: Literal["RESEARCH_RETRAINING_EVALUATED"] = "RESEARCH_RETRAINING_EVALUATED"
    activation_allowed: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("started_at", "completed_at")
    @classmethod
    def run_timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("retraining run timestamps must be timezone-aware")
        return value


def _request_identity_payload(
    *,
    source_registry_fingerprint: str,
    monitoring_report_fingerprint: str,
    learning_dataset_fingerprint: str,
    research_dataset_fingerprint: str,
    research_feature_schema_version: str,
    source_feature_schema_fingerprint: str,
    training_config_fingerprint: str,
    scheduler_config_fingerprint: str,
    code_sha: str,
    trigger_reason_codes: list[str],
) -> dict[str, object]:
    return {
        "contract_version": RETRAINING_ORCHESTRATION_VERSION,
        "source_registry_fingerprint": source_registry_fingerprint,
        "monitoring_report_fingerprint": monitoring_report_fingerprint,
        "learning_dataset_fingerprint": learning_dataset_fingerprint,
        "research_dataset_fingerprint": research_dataset_fingerprint,
        "research_feature_schema_version": research_feature_schema_version,
        "source_feature_schema_fingerprint": source_feature_schema_fingerprint,
        "training_config_fingerprint": training_config_fingerprint,
        "scheduler_config_fingerprint": scheduler_config_fingerprint,
        "code_sha": code_sha,
        "trigger_reason_codes": sorted(trigger_reason_codes),
    }


def _request_identity(request: CandidateRetrainingRequest) -> dict[str, object]:
    return _request_identity_payload(
        source_registry_fingerprint=request.source_registry_fingerprint,
        monitoring_report_fingerprint=request.monitoring_report_fingerprint,
        learning_dataset_fingerprint=request.learning_dataset_fingerprint,
        research_dataset_fingerprint=request.research_dataset_fingerprint,
        research_feature_schema_version=request.research_feature_schema_version,
        source_feature_schema_fingerprint=request.source_feature_schema_fingerprint,
        training_config_fingerprint=request.training_config_fingerprint,
        scheduler_config_fingerprint=request.scheduler_config_fingerprint,
        code_sha=request.code_sha,
        trigger_reason_codes=request.trigger_reason_codes,
    )


def plan_candidate_retraining(
    *,
    monitoring: ModelMonitoringReport,
    source_candidate: ModelGovernanceCandidate,
    learning_dataset: LearningDatasetManifest,
    research_dataset: DatasetManifest,
    code_sha: str,
    requested_at: datetime,
    training_config: TorchTrainingConfig | None = None,
    scheduler_config: RetrainingScheduleConfig | None = None,
    previous_request: CandidateRetrainingRequest | None = None,
) -> CandidateRetrainingRequest:
    training_config = training_config or TorchTrainingConfig()
    scheduler_config = scheduler_config or RetrainingScheduleConfig()
    if requested_at.tzinfo is None or requested_at.utcoffset() is None:
        raise ValueError("requested_at must be timezone-aware")
    if monitoring.assessment != "RETRAINING_RECOMMENDED":
        raise ValueError("scheduled retraining requires RETRAINING_RECOMMENDED monitoring")
    if not monitoring.candidate_training_allowed:
        raise ValueError("monitoring evidence does not allow candidate training")
    if monitoring.required_next_gate != "V2_09_CANDIDATE_RETRAINING":
        raise ValueError("monitoring evidence must advance to candidate retraining")
    if monitoring.registry_fingerprint != source_candidate.registry_fingerprint:
        raise ValueError("monitoring registry must match the exact source model")
    if source_candidate.promotion_allowed or source_candidate.can_authorize_capital:
        raise ValueError("source registry evidence cannot carry capital authority")
    if learning_dataset.row_count < scheduler_config.minimum_learning_rows:
        raise ValueError("learning dataset is below the retraining minimum")
    if research_dataset.row_count < scheduler_config.minimum_research_rows:
        raise ValueError("research dataset is below the retraining minimum")
    if learning_dataset.data_cutoff > requested_at:
        raise ValueError("learning dataset cutoff cannot be after the retraining request")
    if research_dataset.data_cutoff > requested_at:
        raise ValueError("research dataset cutoff cannot be after the retraining request")

    identity = _request_identity_payload(
        source_registry_fingerprint=source_candidate.registry_fingerprint,
        monitoring_report_fingerprint=monitoring.report_fingerprint,
        learning_dataset_fingerprint=learning_dataset.dataset_fingerprint,
        research_dataset_fingerprint=research_dataset.fingerprint,
        research_feature_schema_version=research_dataset.feature_schema_version,
        source_feature_schema_fingerprint=source_candidate.feature_schema_fingerprint,
        training_config_fingerprint=training_config.fingerprint,
        scheduler_config_fingerprint=scheduler_config.fingerprint,
        code_sha=code_sha,
        trigger_reason_codes=monitoring.reason_codes,
    )
    if previous_request is not None:
        if _request_identity(previous_request) == identity:
            return previous_request
        cooldown_end = previous_request.scheduled_for + timedelta(
            hours=scheduler_config.minimum_cooldown_hours
        )
        if requested_at < cooldown_end:
            raise ValueError("retraining cooldown has not elapsed")

    payload = {
        **identity,
        "requested_at": requested_at.isoformat(),
        "scheduled_for": requested_at.isoformat(),
        "status": "RESEARCH_RETRAINING_SCHEDULED",
        "required_next_gate": "V2_09_RUN_CANDIDATE_RETRAINING",
        "activation_allowed": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CandidateRetrainingRequest(
        source_registry_fingerprint=source_candidate.registry_fingerprint,
        monitoring_report_fingerprint=monitoring.report_fingerprint,
        learning_dataset_fingerprint=learning_dataset.dataset_fingerprint,
        research_dataset_fingerprint=research_dataset.fingerprint,
        research_feature_schema_version=research_dataset.feature_schema_version,
        source_feature_schema_fingerprint=source_candidate.feature_schema_fingerprint,
        training_config_fingerprint=training_config.fingerprint,
        scheduler_config_fingerprint=scheduler_config.fingerprint,
        code_sha=code_sha,
        requested_at=requested_at,
        scheduled_for=requested_at,
        trigger_reason_codes=sorted(monitoring.reason_codes),
        request_fingerprint=canonical_fingerprint(payload),
    )


def execute_candidate_retraining(
    request: CandidateRetrainingRequest,
    *,
    rows: Sequence[FeatureRow],
    examples: Sequence[LabeledExample],
    research_dataset: DatasetManifest,
    training_config: TorchTrainingConfig | None = None,
    started_at: datetime,
    completed_at: datetime,
) -> CandidateRetrainingRun:
    training_config = training_config or TorchTrainingConfig()
    if started_at.tzinfo is None or started_at.utcoffset() is None:
        raise ValueError("started_at must be timezone-aware")
    if completed_at.tzinfo is None or completed_at.utcoffset() is None:
        raise ValueError("completed_at must be timezone-aware")
    if started_at < request.scheduled_for:
        raise ValueError("retraining cannot start before its scheduled time")
    if completed_at <= started_at:
        raise ValueError("completed_at must be after started_at")
    if research_dataset.fingerprint != request.research_dataset_fingerprint:
        raise ValueError("materialized research dataset does not match scheduled lineage")
    if research_dataset.feature_schema_version != request.research_feature_schema_version:
        raise ValueError("research feature schema changed after scheduling")
    if research_dataset.row_count != len(rows):
        raise ValueError("materialized research rows do not match the dataset manifest")
    if training_config.fingerprint != request.training_config_fingerprint:
        raise ValueError("training configuration changed after scheduling")
    if len(examples) < 40:
        raise ValueError("at least 40 labeled examples are required for retraining")
    if any(row.feature_schema_version != request.research_feature_schema_version for row in rows):
        raise ValueError("materialized feature rows do not match the scheduled schema")
    if any(row.data_cutoff > research_dataset.data_cutoff for row in rows):
        raise ValueError("feature row exceeds the scheduled research dataset cutoff")

    evaluation: PyTorchEvaluation = evaluate_pytorch_challenger(
        rows,
        examples,
        config=training_config,
    )
    next_gate = (
        "V2_08_REPLAY_WALK_FORWARD"
        if evaluation.replay_candidate
        else "V2_09_RETRAINING_RESEARCH_REVIEW"
    )
    payload = {
        "contract_version": RETRAINING_ORCHESTRATION_VERSION,
        "request_fingerprint": request.request_fingerprint,
        "source_registry_fingerprint": request.source_registry_fingerprint,
        "monitoring_report_fingerprint": request.monitoring_report_fingerprint,
        "learning_dataset_fingerprint": request.learning_dataset_fingerprint,
        "research_dataset_fingerprint": request.research_dataset_fingerprint,
        "evaluation_fingerprint": evaluation.evaluation_fingerprint,
        "challenger_model_fingerprint": evaluation.pytorch_model_fingerprint,
        "evaluation_comparison": evaluation.comparison,
        "replay_candidate": evaluation.replay_candidate,
        "started_at": started_at.isoformat(),
        "completed_at": completed_at.isoformat(),
        "required_next_gate": next_gate,
        "status": "RESEARCH_RETRAINING_EVALUATED",
        "activation_allowed": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CandidateRetrainingRun(
        request_fingerprint=request.request_fingerprint,
        source_registry_fingerprint=request.source_registry_fingerprint,
        monitoring_report_fingerprint=request.monitoring_report_fingerprint,
        learning_dataset_fingerprint=request.learning_dataset_fingerprint,
        research_dataset_fingerprint=request.research_dataset_fingerprint,
        evaluation_fingerprint=evaluation.evaluation_fingerprint,
        challenger_model_fingerprint=evaluation.pytorch_model_fingerprint,
        evaluation_comparison=evaluation.comparison,
        replay_candidate=evaluation.replay_candidate,
        started_at=started_at,
        completed_at=completed_at,
        required_next_gate=next_gate,
        run_fingerprint=canonical_fingerprint(payload),
    )
