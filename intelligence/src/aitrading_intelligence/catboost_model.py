from __future__ import annotations

import json
import math
from collections.abc import Sequence
from datetime import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Annotated, Literal

import catboost as cb
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

from .baseline import DEFAULT_BASELINE_FEATURES
from .fingerprints import canonical_fingerprint
from .pytorch_target import LabeledExample

CATBOOST_MODEL_VERSION = "catboost-binary.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CatBoostTrainingConfig(BaseModel):
    """Deterministic research configuration for the CatBoost binary challenger."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["catboost-binary.v1"] = CATBOOST_MODEL_VERSION
    iterations: Annotated[int, Field(ge=20, le=2_000)] = 120
    learning_rate: Annotated[float, Field(gt=0.0, le=0.5)] = 0.05
    depth: Annotated[int, Field(ge=2, le=12)] = 5
    l2_leaf_reg: Annotated[float, Field(ge=0.0, le=1_000.0)] = 3.0
    train_fraction: Annotated[float, Field(ge=0.5, le=0.8)] = 0.75
    seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CatBoostValidationMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    rows: Annotated[int, Field(ge=10)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]


class CatBoostModelArtifact(BaseModel):
    """Serializable CatBoost challenger evidence with stable model metadata."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["catboost-binary.v1"] = CATBOOST_MODEL_VERSION
    catboost_version: str
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
    model_json: str = Field(min_length=1)
    validation: CatBoostValidationMetrics
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
    def validate_artifact(self) -> CatBoostModelArtifact:
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
        raise ValueError("CatBoost feature values must be finite")
    return values


def _stable_model_json(model: cb.CatBoostClassifier) -> str:
    """Remove CatBoost run-specific metadata while retaining a reloadable model JSON."""

    with TemporaryDirectory() as directory:
        path = Path(directory) / "model.json"
        model.save_model(str(path), format="json")
        payload = json.loads(path.read_text(encoding="utf-8"))

    model_info = payload.get("model_info")
    if isinstance(model_info, dict):
        # CatBoost writes these per fit even when model parameters and inputs are identical.
        # Replacing them with fixed values makes the stored research artifact reproducible.
        if "model_guid" in model_info:
            model_info["model_guid"] = "00000000-0000-0000-0000-000000000000"
        if "train_finish_time" in model_info:
            model_info["train_finish_time"] = "0"
    return json.dumps(payload, sort_keys=True, separators=(",", ":"))


def train_catboost_challenger(
    examples: Sequence[LabeledExample],
    *,
    training_as_of: datetime | None = None,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
    config: CatBoostTrainingConfig | None = None,
) -> CatBoostModelArtifact:
    """Train deterministically from labels available no later than ``training_as_of``."""

    config = config or CatBoostTrainingConfig()
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
    train_pool = cb.Pool(
        train_vectors,
        label=train_labels,
        feature_names=list(feature_names),
    )
    validation_pool = cb.Pool(
        validation_vectors,
        label=validation_labels,
        feature_names=list(feature_names),
    )
    model = cb.CatBoostClassifier(
        loss_function="Logloss",
        eval_metric="Logloss",
        iterations=config.iterations,
        learning_rate=config.learning_rate,
        depth=config.depth,
        l2_leaf_reg=config.l2_leaf_reg,
        random_seed=config.seed,
        thread_count=1,
        task_type="CPU",
        bootstrap_type="No",
        random_strength=0.0,
        rsm=1.0,
        allow_writing_files=False,
        verbose=False,
    )
    model.fit(train_pool, verbose=False)
    validation_probabilities = [
        float(probabilities[1]) for probabilities in model.predict_proba(validation_pool)
    ]
    if any(
        not math.isfinite(value) or value < 0.0 or value > 1.0
        for value in validation_probabilities
    ):
        raise ValueError("CatBoost validation probabilities must be finite values in [0, 1]")

    metrics = CatBoostValidationMetrics(
        roc_auc=float(roc_auc_score(validation_labels, validation_probabilities)),
        brier_score=float(brier_score_loss(validation_labels, validation_probabilities)),
        log_loss=float(log_loss(validation_labels, validation_probabilities, labels=[0, 1])),
        rows=len(validation_examples),
        positive_rows=sum(validation_labels),
        negative_rows=len(validation_labels) - sum(validation_labels),
    )
    model_json = _stable_model_json(model)
    rounded_probabilities = [round(value, 12) for value in validation_probabilities]
    probability_fingerprint = canonical_fingerprint(rounded_probabilities)
    training_fingerprints = [example.example_fingerprint for example in training_examples]
    validation_fingerprints = [example.example_fingerprint for example in validation_examples]
    latest_label_observed_at = max(example.target_observed_at for example in ordered)
    payload = {
        "model_version": CATBOOST_MODEL_VERSION,
        "catboost_version": cb.__version__,
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
        "model_json": model_json,
        "validation": metrics.model_dump(mode="json"),
        "status": "CHALLENGER_MODEL_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return CatBoostModelArtifact(
        **payload,
        model_fingerprint=canonical_fingerprint(payload),
    )
