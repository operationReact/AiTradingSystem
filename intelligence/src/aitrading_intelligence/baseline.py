from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal, Sequence

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.linear_model import LogisticRegression

from .features import FeatureRow
from .fingerprints import canonical_fingerprint

BASELINE_MODEL_VERSION = "logistic-baseline.v1"
DEFAULT_BASELINE_FEATURES: tuple[str, ...] = (
    "market.return_1",
    "market.range_pct",
    "market.volume_change_1",
    "event.AI_NEWS_EVIDENCE.direction_score",
    "event.AI_NEWS_EVIDENCE.confidence",
    "event.AI_NEWS_EVIDENCE.materiality",
)

Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class LogisticBaselineArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["logistic-baseline.v1"] = BASELINE_MODEL_VERSION
    feature_names: list[str] = Field(min_length=1, max_length=128)
    coefficients: list[float] = Field(min_length=1, max_length=128)
    intercept: float
    training_start: datetime
    training_end: datetime
    row_count: Annotated[int, Field(ge=20)]
    positive_count: Annotated[int, Field(ge=1)]
    negative_count: Annotated[int, Field(ge=1)]
    artifact_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @field_validator("training_start", "training_end")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("training timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_shape(self) -> LogisticBaselineArtifact:
        if len(self.feature_names) != len(self.coefficients):
            raise ValueError("feature_names and coefficients must have the same length")
        if len(set(self.feature_names)) != len(self.feature_names):
            raise ValueError("feature_names cannot contain duplicates")
        if self.training_start > self.training_end:
            raise ValueError("training_start cannot be after training_end")
        values = [self.intercept, *self.coefficients]
        if any(not math.isfinite(value) for value in values):
            raise ValueError("baseline parameters must be finite")
        return self


class BaselineScore(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["logistic-baseline.v1"] = BASELINE_MODEL_VERSION
    symbol: str
    as_of: datetime
    data_cutoff: datetime
    probability_up: Annotated[float, Field(ge=0.0, le=1.0)]
    directional_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    feature_contributions: dict[str, float]
    row_fingerprint: Hash64
    model_fingerprint: Hash64
    score_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False


def _feature_vector(row: FeatureRow, feature_names: Sequence[str]) -> list[float]:
    return [float(row.features.get(name, 0.0)) for name in feature_names]


def train_logistic_baseline(
    rows: Sequence[FeatureRow],
    labels: Sequence[int],
    *,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
) -> LogisticBaselineArtifact:
    if len(rows) != len(labels):
        raise ValueError("rows and labels must have the same length")
    if len(rows) < 20:
        raise ValueError("at least 20 labeled rows are required")
    if not feature_names or len(set(feature_names)) != len(feature_names):
        raise ValueError("feature_names must be non-empty and unique")
    if any(label not in (0, 1) for label in labels):
        raise ValueError("labels must contain only 0 or 1")
    if len(set(labels)) != 2:
        raise ValueError("both positive and negative labels are required")

    paired = sorted(zip(rows, labels, strict=True), key=lambda item: item[0].as_of)
    sorted_rows = [item[0] for item in paired]
    sorted_labels = [item[1] for item in paired]
    features = [_feature_vector(row, feature_names) for row in sorted_rows]

    model = LogisticRegression(
        solver="liblinear",
        random_state=42,
        max_iter=1000,
        C=1.0,
    )
    model.fit(features, sorted_labels)

    coefficients = [float(value) for value in model.coef_[0]]
    intercept = float(model.intercept_[0])
    payload = {
        "model_version": BASELINE_MODEL_VERSION,
        "feature_names": list(feature_names),
        "coefficients": coefficients,
        "intercept": intercept,
        "training_start": sorted_rows[0].as_of.isoformat(),
        "training_end": sorted_rows[-1].as_of.isoformat(),
        "row_count": len(sorted_rows),
        "positive_count": sum(sorted_labels),
        "negative_count": len(sorted_labels) - sum(sorted_labels),
        "training_rows": [row.fingerprint for row in sorted_rows],
    }
    return LogisticBaselineArtifact(
        feature_names=list(feature_names),
        coefficients=coefficients,
        intercept=intercept,
        training_start=sorted_rows[0].as_of,
        training_end=sorted_rows[-1].as_of,
        row_count=len(sorted_rows),
        positive_count=sum(sorted_labels),
        negative_count=len(sorted_labels) - sum(sorted_labels),
        artifact_fingerprint=canonical_fingerprint(payload),
    )


def score_logistic_baseline(
    row: FeatureRow,
    artifact: LogisticBaselineArtifact,
) -> BaselineScore:
    vector = _feature_vector(row, artifact.feature_names)
    contributions = {
        name: coefficient * value
        for name, coefficient, value in zip(
            artifact.feature_names,
            artifact.coefficients,
            vector,
            strict=True,
        )
    }
    logit = artifact.intercept + sum(contributions.values())
    if logit >= 0:
        probability = 1.0 / (1.0 + math.exp(-logit))
    else:
        exp_logit = math.exp(logit)
        probability = exp_logit / (1.0 + exp_logit)
    directional_score = (2.0 * probability) - 1.0
    confidence = abs(directional_score)
    payload = {
        "model_version": artifact.model_version,
        "symbol": row.symbol,
        "as_of": row.as_of.isoformat(),
        "data_cutoff": row.data_cutoff.isoformat(),
        "probability_up": probability,
        "directional_score": directional_score,
        "confidence": confidence,
        "feature_contributions": contributions,
        "row_fingerprint": row.fingerprint,
        "model_fingerprint": artifact.artifact_fingerprint,
    }
    return BaselineScore(
        model_version=artifact.model_version,
        symbol=row.symbol,
        as_of=row.as_of,
        data_cutoff=row.data_cutoff,
        probability_up=probability,
        directional_score=directional_score,
        confidence=confidence,
        feature_contributions=contributions,
        row_fingerprint=row.fingerprint,
        model_fingerprint=artifact.artifact_fingerprint,
        score_fingerprint=canonical_fingerprint(payload),
    )
