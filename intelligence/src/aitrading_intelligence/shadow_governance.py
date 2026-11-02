from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

from .fingerprints import canonical_fingerprint

SHADOW_VERSION = "shadow-governance.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Assessment = Literal[
    "CHALLENGER_ELIGIBLE_FOR_GOVERNANCE_REVIEW",
    "CHAMPION_HOLDS",
    "INCONCLUSIVE",
]


class ShadowPredictionInput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    trade_id: Annotated[str, Field(min_length=1, max_length=128)]
    prediction_at: datetime
    outcome_available_at: datetime
    outcome_record_fingerprint: Hash64
    champion_registry_fingerprint: Hash64
    challenger_registry_fingerprint: Hash64
    champion_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    challenger_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    actual_positive: bool
    paper_mode: Literal["PAPER_SHADOW"] = "PAPER_SHADOW"

    @field_validator("prediction_at", "outcome_available_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("shadow timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_record(self) -> ShadowPredictionInput:
        if self.outcome_available_at <= self.prediction_at:
            raise ValueError("shadow outcome must become available after prediction time")
        if self.champion_registry_fingerprint == self.challenger_registry_fingerprint:
            raise ValueError("champion and challenger registry fingerprints must differ")
        return self


class ShadowPredictionRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    shadow_version: Literal["shadow-governance.v1"] = SHADOW_VERSION
    trade_id: str
    prediction_at: datetime
    outcome_available_at: datetime
    outcome_record_fingerprint: Hash64
    champion_registry_fingerprint: Hash64
    challenger_registry_fingerprint: Hash64
    champion_probability: float
    challenger_probability: float
    actual_positive: bool
    record_fingerprint: Hash64
    status: Literal["PAPER_SHADOW_EVIDENCE"] = "PAPER_SHADOW_EVIDENCE"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


class ShadowEvaluationConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_records: Annotated[int, Field(ge=20, le=100_000)] = 30
    minimum_brier_improvement: Annotated[float, Field(ge=0.0, le=1.0)] = 0.01
    maximum_ece_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.01
    champion_hold_brier_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.005
    champion_hold_log_loss_regression: Annotated[float, Field(ge=0.0, le=10.0)] = 0.01
    champion_hold_auc_regression: Annotated[float, Field(ge=0.0, le=1.0)] = 0.01

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class ShadowMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    rows: Annotated[int, Field(ge=20)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]
    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    expected_calibration_error: Annotated[float, Field(ge=0.0, le=1.0)]


class ShadowComparisonEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    shadow_version: Literal["shadow-governance.v1"] = SHADOW_VERSION
    config_fingerprint: Hash64
    champion_registry_fingerprint: Hash64
    challenger_registry_fingerprint: Hash64
    record_fingerprints: list[Hash64]
    champion: ShadowMetrics
    challenger: ShadowMetrics
    assessment: Assessment
    required_next_gate: Literal[
        "V2_09_EXPLICIT_GOVERNANCE_DECISION",
        "V2_09_CONTINUE_SHADOW",
    ]
    evidence_fingerprint: Hash64
    status: Literal["PAPER_SHADOW_COMPARISON_ONLY"] = "PAPER_SHADOW_COMPARISON_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def build_shadow_prediction(source: ShadowPredictionInput) -> ShadowPredictionRecord:
    payload = {
        "shadow_version": SHADOW_VERSION,
        "trade_id": source.trade_id,
        "prediction_at": source.prediction_at.isoformat(),
        "outcome_available_at": source.outcome_available_at.isoformat(),
        "outcome_record_fingerprint": source.outcome_record_fingerprint,
        "champion_registry_fingerprint": source.champion_registry_fingerprint,
        "challenger_registry_fingerprint": source.challenger_registry_fingerprint,
        "champion_probability": source.champion_probability,
        "challenger_probability": source.challenger_probability,
        "actual_positive": source.actual_positive,
        "status": "PAPER_SHADOW_EVIDENCE",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ShadowPredictionRecord(
        **payload,
        record_fingerprint=canonical_fingerprint(payload),
    )


def _expected_calibration_error(labels: list[int], probabilities: list[float]) -> float:
    total = len(labels)
    weighted_gap = 0.0
    for index in range(5):
        lower = index / 5.0
        upper = (index + 1) / 5.0
        members = [
            (label, probability)
            for label, probability in zip(labels, probabilities, strict=True)
            if lower <= probability < upper or (index == 4 and probability == 1.0)
        ]
        if not members:
            continue
        mean_probability = sum(probability for _, probability in members) / len(members)
        observed_rate = sum(label for label, _ in members) / len(members)
        weighted_gap += abs(mean_probability - observed_rate) * len(members)
    return weighted_gap / total


def _metrics(labels: list[int], probabilities: list[float]) -> ShadowMetrics:
    values = [float(value) for value in probabilities]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("shadow probabilities must be finite")
    return ShadowMetrics(
        rows=len(labels),
        positive_rows=sum(labels),
        negative_rows=len(labels) - sum(labels),
        roc_auc=float(roc_auc_score(labels, values)),
        brier_score=float(brier_score_loss(labels, values)),
        log_loss=float(log_loss(labels, values, labels=[0, 1])),
        expected_calibration_error=_expected_calibration_error(labels, values),
    )


def evaluate_shadow_challenger(
    records: list[ShadowPredictionRecord],
    *,
    config: ShadowEvaluationConfig | None = None,
) -> ShadowComparisonEvidence:
    config = config or ShadowEvaluationConfig()
    if len(records) < config.minimum_records:
        raise ValueError("not enough PAPER shadow records for governance comparison")
    if len({record.trade_id for record in records}) != len(records):
        raise ValueError("PAPER shadow records must be unique by trade_id")

    champion_ids = {record.champion_registry_fingerprint for record in records}
    challenger_ids = {record.challenger_registry_fingerprint for record in records}
    if len(champion_ids) != 1 or len(challenger_ids) != 1:
        raise ValueError("one shadow comparison must use one champion and one challenger")
    champion_id = next(iter(champion_ids))
    challenger_id = next(iter(challenger_ids))
    if champion_id == challenger_id:
        raise ValueError("champion and challenger must differ")

    ordered = sorted(records, key=lambda item: (item.prediction_at, item.trade_id))
    labels = [1 if record.actual_positive else 0 for record in ordered]
    if len(set(labels)) != 2:
        raise ValueError("PAPER shadow comparison requires both positive and negative outcomes")
    champion_probabilities = [record.champion_probability for record in ordered]
    challenger_probabilities = [record.challenger_probability for record in ordered]
    champion = _metrics(labels, champion_probabilities)
    challenger = _metrics(labels, challenger_probabilities)

    eligible = (
        challenger.brier_score
        <= champion.brier_score - config.minimum_brier_improvement
        and challenger.log_loss <= champion.log_loss
        and challenger.roc_auc >= champion.roc_auc
        and challenger.expected_calibration_error
        <= champion.expected_calibration_error + config.maximum_ece_regression
    )
    champion_holds = (
        challenger.brier_score
        > champion.brier_score + config.champion_hold_brier_regression
        or challenger.log_loss
        > champion.log_loss + config.champion_hold_log_loss_regression
        or challenger.roc_auc
        < champion.roc_auc - config.champion_hold_auc_regression
    )
    if eligible:
        assessment: Assessment = "CHALLENGER_ELIGIBLE_FOR_GOVERNANCE_REVIEW"
        required_next_gate = "V2_09_EXPLICIT_GOVERNANCE_DECISION"
    elif champion_holds:
        assessment = "CHAMPION_HOLDS"
        required_next_gate = "V2_09_CONTINUE_SHADOW"
    else:
        assessment = "INCONCLUSIVE"
        required_next_gate = "V2_09_CONTINUE_SHADOW"

    payload = {
        "shadow_version": SHADOW_VERSION,
        "config_fingerprint": config.fingerprint,
        "champion_registry_fingerprint": champion_id,
        "challenger_registry_fingerprint": challenger_id,
        "record_fingerprints": [record.record_fingerprint for record in ordered],
        "champion": champion.model_dump(mode="json"),
        "challenger": challenger.model_dump(mode="json"),
        "assessment": assessment,
        "required_next_gate": required_next_gate,
        "status": "PAPER_SHADOW_COMPARISON_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ShadowComparisonEvidence(
        config_fingerprint=config.fingerprint,
        champion_registry_fingerprint=champion_id,
        challenger_registry_fingerprint=challenger_id,
        record_fingerprints=[record.record_fingerprint for record in ordered],
        champion=champion,
        challenger=challenger,
        assessment=assessment,
        required_next_gate=required_next_gate,
        evidence_fingerprint=canonical_fingerprint(payload),
    )
