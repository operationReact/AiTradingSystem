from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

import lightgbm as lgb
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

from .baseline import DEFAULT_BASELINE_FEATURES
from .fingerprints import canonical_fingerprint
from .pytorch_target import LabeledExample

LIGHTGBM_MODEL_VERSION = "lightgbm-binary.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class LightGBMTrainingConfig(BaseModel):
    """Deterministic research configuration for the LightGBM binary challenger."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["lightgbm-binary.v1"] = LIGHTGBM_MODEL_VERSION
    n_estimators: Annotated[int, Field(ge=20, le=2_000)] = 120
    learning_rate: Annotated[float, Field(gt=0.0, le=0.5)] = 0.05
    num_leaves: Annotated[int, Field(ge=2, le=255)] = 15
    max_depth: Annotated[int, Field(ge=2, le=32)] = 5
    min_child_samples: Annotated[int, Field(ge=2, le=1_000)] = 10
    reg_lambda: Annotated[float, Field(ge=0.0, le=1_000.0)] = 1.0
    train_fraction: Annotated[float, Field(ge=0.5, le=0.8)] = 0.75
    seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class LightGBMValidationMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    rows: Annotated[int, Field(ge=10)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]


class LightGBMModelArtifact(BaseModel):
    """Serializable challenger evidence; never a capital-authorizing model artifact."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["lightgbm-binary.v1"] = LIGHTGBM_MODEL_VERSION
    lightgbm_version: str
    feature_names: list[str] = Field(min_length=1, max_length=128)
    target_spec_fingerprint: Hash64
    training_config_fingerprint: Hash64
    training_as_of: datetime
    latest_label_observed_at: datetime
    train_start: datetime
    train_end: datetime
    validation_start: datetime
    validation_end: datetime
    train_rows: Annotated[int, Field(ge=20)]
    validation_rows: Annotated[int, Field(ge=10)]
    training_example_fingerprints: list[Hash64]
    validation_example_fingerprints: list[Hash64]
    validation_probabilities_fingerprint: Hash64
    booster_model: str = Field(min_length=1)
    validation: LightGBMValidationMetrics
    model_fingerprint: Hash64
    status: Literal["CHALLENGER_MODEL_ONLY"] = "CHALLENGER_MODEL_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator(
        "training_as_of",
        "latest_label_observed_at",
        "train_start",
        "train_end",
        "validation_start",
        "validation_end",
    )
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("training timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_artifact(self) -> LightGBMModelArtifact:
        if len(set(self.feature_names)) != len(self.feature_names):
            raise ValueError("feature_names cannot contain duplicates")
        if self.train_end >= self.validation_start:
            raise ValueError("training inputs must end before validation starts")
        if self.validation_start > self.validation_end:
            raise ValueError("validation_start cannot be after validation_end")
        if self.validation_end > self.training_as_of:
            raise ValueError("validation decisions cannot exceed training_as_of")
        if self.latest_label_observed_at > self.training_as_of:
            raise ValueError("artifact cannot include labels observed after training_as_of")
        if len(self.training_example_fingerprints) != self.train_rows:
            raise ValueError("training fingerprint count must match train_rows")
        if len(self.validation_example_fingerprints) != self.validation_rows:
            raise ValueError("validation fingerprint count must match validation_rows")
        if len(set(self.training_example_fingerprints)) != self.train_rows:
            raise ValueError("training examples must be unique")
        if len(set(self.validation_example_fingerprints)) != self.validation_rows:
            raise ValueError("validation examples must be unique")
        return self


def _feature_vector(example: LabeledExample, feature_names: Sequence[str]) -> list[float]:
    values = [float(example.features.get(name, 0.0)) for name in feature_names]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("LightGBM feature values must be finite")
    return values


def train_lightgbm_challenger(
    examples: Sequence[LabeledExample],
    *,
    training_as_of: datetime | None = None,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
    config: LightGBMTrainingConfig | None = None,
) -> LightGBMModelArtifact:
    """Train deterministically from labels available no later than ``training_as_of``."""

    config = config or LightGBMTrainingConfig()
    if not examples:
        raise ValueError("labeled examples are required")
    if training_as_of is None:
        training_as_of = max(example.target_observed_at for example in examples)
    if training_as_of.tzinfo is None or training_as_of.utcoffset() is None:
        raise ValueError("training_as_of must be timezone-aware")
    if not feature_names or len(set(feature_names)) != len(feature_names):
        raise ValueError("feature_names must be non-empty and unique")

    eligible = [
        example
        for example in examples
        if example.as_of < training_as_of
        and example.target_observed_at <= training_as_of
    ]
    if len(eligible) < 40:
        raise ValueError("at least 40 causal labeled examples are required")
    ordered = sorted(
        eligible,
        key=lambda example: (example.as_of, example.symbol, example.example_fingerprint),
    )
    if len({example.example_fingerprint for example in ordered}) != len(ordered):
        raise ValueError("labeled examples must be unique")
    target_fingerprints = {example.target_spec_fingerprint for example in ordered}
    if len(target_fingerprints) != 1:
        raise ValueError("all examples must use the same target specification")

    split_index = int(len(ordered) * config.train_fraction)
    split_index = min(max(split_index, 20), len(ordered) - 10)
    validation_examples = ordered[split_index:]
    validation_start = validation_examples[0].as_of

    # Purge any training label whose future target was not observable strictly before the
    # validation decision boundary. Validation labels are all known by training_as_of.
    training_examples = [
        example
        for example in ordered[:split_index]
        if example.target_observed_at < validation_start
    ]
    if len(training_examples) < 20:
        raise ValueError("purged chronological training partition has fewer than 20 rows")

    train_labels = [example.label for example in training_examples]
    validation_labels = [example.label for example in validation_examples]
    if len(set(train_labels)) != 2 or len(set(validation_labels)) != 2:
        raise ValueError("training and validation partitions must contain both classes")

    train_vectors = [_feature_vector(example, feature_names) for example in training_examples]
    validation_vectors = [
        _feature_vector(example, feature_names) for example in validation_examples
    ]

    model = lgb.LGBMClassifier(
        objective="binary",
        n_estimators=config.n_estimators,
        learning_rate=config.learning_rate,
        num_leaves=config.num_leaves,
        max_depth=config.max_depth,
        min_child_samples=config.min_child_samples,
        reg_lambda=config.reg_lambda,
        random_state=config.seed,
        n_jobs=1,
        subsample=1.0,
        colsample_bytree=1.0,
        deterministic=True,
        force_col_wise=True,
        verbosity=-1,
    )
    model.fit(train_vectors, train_labels, feature_name=list(feature_names))
    validation_probabilities = [
        float(probabilities[1]) for probabilities in model.predict_proba(validation_vectors)
    ]
    if any(
        not math.isfinite(value) or value < 0.0 or value > 1.0
        for value in validation_probabilities
    ):
        raise ValueError("LightGBM validation probabilities must be finite values in [0, 1]")

    metrics = LightGBMValidationMetrics(
        roc_auc=float(roc_auc_score(validation_labels, validation_probabilities)),
        brier_score=float(brier_score_loss(validation_labels, validation_probabilities)),
        log_loss=float(log_loss(validation_labels, validation_probabilities, labels=[0, 1])),
        rows=len(validation_examples),
        positive_rows=sum(validation_labels),
        negative_rows=len(validation_labels) - sum(validation_labels),
    )
    booster_model = model.booster_.model_to_string()
    rounded_probabilities = [round(value, 12) for value in validation_probabilities]
    probability_fingerprint = canonical_fingerprint(rounded_probabilities)
    training_fingerprints = [example.example_fingerprint for example in training_examples]
    validation_fingerprints = [example.example_fingerprint for example in validation_examples]
    latest_label_observed_at = max(example.target_observed_at for example in ordered)
    payload = {
        "model_version": LIGHTGBM_MODEL_VERSION,
        "lightgbm_version": lgb.__version__,
        "feature_names": list(feature_names),
        "target_spec_fingerprint": next(iter(target_fingerprints)),
        "training_config_fingerprint": config.fingerprint,
        "training_as_of": training_as_of.isoformat(),
        "latest_label_observed_at": latest_label_observed_at.isoformat(),
        "train_start": training_examples[0].as_of.isoformat(),
        "train_end": training_examples[-1].as_of.isoformat(),
        "validation_start": validation_examples[0].as_of.isoformat(),
        "validation_end": validation_examples[-1].as_of.isoformat(),
        "train_rows": len(training_examples),
        "validation_rows": len(validation_examples),
        "training_example_fingerprints": training_fingerprints,
        "validation_example_fingerprints": validation_fingerprints,
        "validation_probabilities_fingerprint": probability_fingerprint,
        "booster_model": booster_model,
        "validation": metrics.model_dump(mode="json"),
        "status": "CHALLENGER_MODEL_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return LightGBMModelArtifact(
        **payload,
        model_fingerprint=canonical_fingerprint(payload),
    )
