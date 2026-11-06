"""V4-07.2 isolated holdout measurement of research-only anomaly warnings.

Labels are self-declared evidence, never independent proof of actual market
disruption, genuine India data or authorization to affect live capital.
"""
from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .regime_anomaly import IsolationAnomalyObservation

CONTRACT_VERSION = "india-anomaly-holdout-evaluation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Result = Literal[
    "RESEARCH_SCREEN_PASSED", "RESEARCH_SCREEN_FAILED", "INCONCLUSIVE"
]


class AnomalyHoldoutConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_resolved_observations: Annotated[int, Field(ge=4, le=100_000)] = 50
    minimum_abnormal_labels: Annotated[int, Field(ge=1, le=50_000)] = 10
    minimum_normal_labels: Annotated[int, Field(ge=1, le=50_000)] = 10
    minimum_recall: Annotated[float, Field(gt=0.0, le=1.0)] = 0.60
    maximum_false_positive_rate: Annotated[float, Field(ge=0.0, lt=1.0)] = 0.15

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class AnomalyHoldoutLabel(BaseModel):
    """A point-in-time *retrospective* label, never a predictive feature."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    label_version: Literal["india-anomaly-retrospective-label.v1"] = (
        "india-anomaly-retrospective-label.v1"
    )
    decision_at: datetime
    observed_at: datetime
    source_snapshot_fingerprint: Hash64
    abnormal_market_outcome: bool
    label_fingerprint: Hash64
    evidence_status: Literal["SELF_DECLARED_UNVERIFIED"] = "SELF_DECLARED_UNVERIFIED"

    @field_validator("decision_at", "observed_at")
    @classmethod
    def aware_times(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("anomaly label timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def observed_only_after_decision(self) -> AnomalyHoldoutLabel:
        if self.observed_at <= self.decision_at:
            raise ValueError("retrospective label must follow the decision")
        return self


class AnomalyHoldoutReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-anomaly-holdout-evaluation.v1"] = CONTRACT_VERSION
    evaluated_as_of: datetime
    config_fingerprint: Hash64
    model_fingerprint: Hash64
    threshold: float
    eligible_observations: Annotated[int, Field(ge=1)]
    resolved_observations: Annotated[int, Field(ge=0)]
    unresolved_observations: Annotated[int, Field(ge=0)]
    actual_abnormal_count: Annotated[int, Field(ge=0)]
    actual_normal_count: Annotated[int, Field(ge=0)]
    true_positive: Annotated[int, Field(ge=0)]
    false_positive: Annotated[int, Field(ge=0)]
    true_negative: Annotated[int, Field(ge=0)]
    false_negative: Annotated[int, Field(ge=0)]
    recall: Annotated[float, Field(ge=0.0, le=1.0)] | None
    false_positive_rate: Annotated[float, Field(ge=0.0, le=1.0)] | None
    alert_rate: Annotated[float, Field(ge=0.0, le=1.0)] | None
    assessment: Result
    reason_codes: list[str] = Field(min_length=1, max_length=12)
    source_anomaly_fingerprints: list[Hash64] = Field(min_length=1, max_length=100_000)
    source_label_fingerprints: list[Hash64] = Field(default_factory=list, max_length=100_000)
    label_custody: Literal["SELF_DECLARED_UNVERIFIED"] = "SELF_DECLARED_UNVERIFIED"
    real_market_acceptance: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @field_validator("evaluated_as_of")
    @classmethod
    def aware_cutoff(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluation cutoff must be timezone-aware")
        return value

    @model_validator(mode="after")
    def counts_must_reconcile(self) -> AnomalyHoldoutReport:
        if self.resolved_observations + self.unresolved_observations != self.eligible_observations:
            raise ValueError("resolved+unresolved must equal eligible observations")
        if sum((self.true_positive, self.false_positive, self.true_negative,
                self.false_negative)) != self.resolved_observations:
            raise ValueError("confusion matrix must reconcile to resolved observations")
        if self.actual_abnormal_count != self.true_positive + self.false_negative:
            raise ValueError("abnormal labels must reconcile")
        if self.actual_normal_count != self.true_negative + self.false_positive:
            raise ValueError("normal labels must reconcile")
        return self


def _validated_fingerprint(item: BaseModel, field: str, *, timestamps: tuple[str, ...]) -> str:
    value = item.model_dump(mode="python", exclude={field})
    for key in timestamps:
        value[key] = value[key].isoformat()
    expected = canonical_fingerprint(value)
    if getattr(item, field) != expected:
        raise ValueError(f"{field} mismatch in self-declared source")
    return expected


def evaluate_anomaly_holdout(
    observations: Sequence[IsolationAnomalyObservation],
    labels: Sequence[AnomalyHoldoutLabel],
    *,
    evaluated_as_of: datetime,
    config: AnomalyHoldoutConfig | None = None,
) -> AnomalyHoldoutReport:
    cfg = config or AnomalyHoldoutConfig()
    if evaluated_as_of.tzinfo is None or evaluated_as_of.utcoffset() is None:
        raise ValueError("evaluated_as_of must be timezone-aware")
    eligible = sorted(
        (o for o in observations if o.decision_at <= evaluated_as_of),
        key=lambda o: (o.decision_at, o.source_snapshot_fingerprint),
    )
    if not eligible or len(eligible) > 100_000:
        raise ValueError("holdout requires 1..100000 eligible anomaly observations")

    model_fingerprints: set[str] = set()
    threshold_values: set[float] = set()
    unique: set[tuple[datetime, str]] = set()
    for observation in eligible:
        checked = IsolationAnomalyObservation.model_validate(
            observation.model_dump(mode="python")
        )
        _validated_fingerprint(
            checked, "evidence_fingerprint",
            timestamps=("decision_at", "data_cutoff", "training_data_cutoff"),
        )
        if checked.training_data_cutoff >= checked.decision_at:
            raise ValueError("holdout anomaly must follow its training cutoff")
        if checked.data_cutoff > checked.decision_at:
            raise ValueError("future data cutoff not permitted")
        if checked.elevated_anomaly != (
            checked.action == "REDUCE_SIGNAL_ELIGIBILITY"
        ):
            raise ValueError("anomaly action/score gate mismatch")
        if checked.elevated_anomaly != (checked.anomaly_score > checked.threshold):
            raise ValueError("anomaly score and threshold mismatch")
        key = (checked.decision_at, checked.source_snapshot_fingerprint)
        if key in unique:
            raise ValueError("duplicate anomaly decision observation")
        unique.add(key)
        model_fingerprints.add(checked.model_fingerprint)
        threshold_values.add(checked.threshold)

    if len(model_fingerprints) != 1 or len(threshold_values) != 1:
        raise ValueError("holdout requires one frozen model version and threshold")

    eligible_labels: dict[tuple[datetime, str], AnomalyHoldoutLabel] = {}
    for label in labels:
        if label.observed_at > evaluated_as_of:
            continue
        checked = AnomalyHoldoutLabel.model_validate(label.model_dump(mode="python"))
        _validated_fingerprint(
            checked, "label_fingerprint", timestamps=("decision_at", "observed_at")
        )
        key = (checked.decision_at, checked.source_snapshot_fingerprint)
        if key not in unique:
            raise ValueError("realized holdout label lacks matching decision")
        if key in eligible_labels:
            raise ValueError("duplicate realized label for one decision")
        eligible_labels[key] = checked

    tp = fp = tn = fn = 0
    source_labels: list[str] = []
    for observation in eligible:
        key = (observation.decision_at, observation.source_snapshot_fingerprint)
        label = eligible_labels.get(key)
        if label is None:
            continue
        source_labels.append(label.label_fingerprint)
        if observation.elevated_anomaly and label.abnormal_market_outcome:
            tp += 1
        elif observation.elevated_anomaly:
            fp += 1
        elif label.abnormal_market_outcome:
            fn += 1
        else:
            tn += 1

    resolved = tp + fp + tn + fn
    positive = tp + fn
    negative = tn + fp
    recall = tp / positive if positive else None
    fpr = fp / negative if negative else None
    alert_rate = (tp + fp) / resolved if resolved else None
    reasons = ["INDEPENDENT_INDIA_MARKET_AND_LABEL_CUSTODY_UNVERIFIED"]
    insufficient = False
    if resolved < cfg.minimum_resolved_observations:
        reasons.append("INSUFFICIENT_REALIZED_HOLDOUT_OBSERVATIONS")
        insufficient = True
    if positive < cfg.minimum_abnormal_labels:
        reasons.append("INSUFFICIENT_ABNORMAL_MARKET_EVENTS")
        insufficient = True
    if negative < cfg.minimum_normal_labels:
        reasons.append("INSUFFICIENT_NORMAL_MARKET_EVENTS")
        insufficient = True
    if insufficient:
        assessment: Result = "INCONCLUSIVE"
    elif (
        recall is not None and recall >= cfg.minimum_recall
        and fpr is not None and fpr <= cfg.maximum_false_positive_rate
    ):
        assessment = "RESEARCH_SCREEN_PASSED"
        reasons.append("SELF_DECLARED_HOLDOUT_FILTERS_PASSED_NOT_PRODUCTION_APPROVAL")
    else:
        assessment = "RESEARCH_SCREEN_FAILED"
        reasons.append("RECALL_OR_FALSE_POSITIVE_POLICY_FAILED")
    payload = {
        "contract_version": CONTRACT_VERSION,
        "evaluated_as_of": evaluated_as_of.isoformat(),
        "config_fingerprint": cfg.fingerprint,
        "model_fingerprint": next(iter(model_fingerprints)),
        "threshold": next(iter(threshold_values)),
        "eligible_observations": len(eligible),
        "resolved_observations": resolved,
        "unresolved_observations": len(eligible) - resolved,
        "actual_abnormal_count": positive,
        "actual_normal_count": negative,
        "true_positive": tp,
        "false_positive": fp,
        "true_negative": tn,
        "false_negative": fn,
        "recall": recall,
        "false_positive_rate": fpr,
        "alert_rate": alert_rate,
        "assessment": assessment,
        "reason_codes": sorted(reasons),
        "source_anomaly_fingerprints": [o.evidence_fingerprint for o in eligible],
        "source_label_fingerprints": source_labels,
        "label_custody": "SELF_DECLARED_UNVERIFIED",
        "real_market_acceptance": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return AnomalyHoldoutReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
