from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

import torch
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score
from torch import nn

from .baseline import DEFAULT_BASELINE_FEATURES
from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .pytorch_target import LabeledExample

MODEL_VERSION = "pytorch-mlp.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
StateValue = list[float] | list[list[float]]


class TorchTrainingConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["pytorch-mlp.v1"] = MODEL_VERSION
    hidden_dim: Annotated[int, Field(ge=2, le=128)] = 16
    epochs: Annotated[int, Field(ge=10, le=2000)] = 120
    learning_rate: Annotated[float, Field(gt=0.0, le=1.0)] = 0.03
    train_fraction: Annotated[float, Field(ge=0.5, le=0.8)] = 0.75
    seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class TorchValidationMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    rows: Annotated[int, Field(ge=10)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]


class TorchModelArtifact(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["pytorch-mlp.v1"] = MODEL_VERSION
    torch_version: str
    feature_names: list[str] = Field(min_length=1, max_length=128)
    feature_means: list[float]
    feature_scales: list[float]
    hidden_dim: int
    seed: int
    epochs: int
    learning_rate: float
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
    state_dict: dict[str, StateValue]
    validation: TorchValidationMetrics
    model_fingerprint: Hash64
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
            raise ValueError("PyTorch artifact timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_artifact(self) -> TorchModelArtifact:
        if len(self.feature_names) != len(self.feature_means):
            raise ValueError("feature_names and feature_means must have equal length")
        if len(self.feature_names) != len(self.feature_scales):
            raise ValueError("feature_names and feature_scales must have equal length")
        if any(scale <= 0.0 for scale in self.feature_scales):
            raise ValueError("feature scales must be positive")
        if self.train_end >= self.validation_start:
            raise ValueError("training inputs must end before validation starts")
        if self.validation_start > self.validation_end:
            raise ValueError("validation_start cannot exceed validation_end")
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
        if set(self.training_example_fingerprints) & set(
            self.validation_example_fingerprints
        ):
            raise ValueError("training and validation examples must be disjoint")
        return self


class TorchInferenceEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: Literal["pytorch-mlp.v1"] = MODEL_VERSION
    symbol: str
    as_of: datetime
    data_cutoff: datetime
    probability_up: Annotated[float, Field(ge=0.0, le=1.0)]
    directional_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    feature_attribution: dict[str, float]
    feature_row_fingerprint: Hash64
    model_fingerprint: Hash64
    inference_fingerprint: Hash64
    status: Literal["CHALLENGER_EVIDENCE_ONLY"] = "CHALLENGER_EVIDENCE_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


class _BinaryMLP(nn.Module):
    def __init__(self, input_dim: int, hidden_dim: int) -> None:
        super().__init__()
        self.hidden = nn.Linear(input_dim, hidden_dim)
        self.activation = nn.ReLU()
        self.output = nn.Linear(hidden_dim, 1)

    def forward(self, values: torch.Tensor) -> torch.Tensor:
        return self.output(self.activation(self.hidden(values))).squeeze(-1)


def _configure_determinism(seed: int) -> None:
    torch.manual_seed(seed)
    torch.use_deterministic_algorithms(True)
    torch.set_num_threads(1)


def _feature_vector(features: dict[str, float], feature_names: Sequence[str]) -> list[float]:
    return [float(features.get(name, 0.0)) for name in feature_names]


def _mean_and_scale(vectors: list[list[float]]) -> tuple[list[float], list[float]]:
    tensor = torch.tensor(vectors, dtype=torch.float32)
    means = tensor.mean(dim=0)
    scales = tensor.std(dim=0, unbiased=False)
    scales = torch.where(scales < 1e-8, torch.ones_like(scales), scales)
    return means.tolist(), scales.tolist()


def _standardize(
    vectors: list[list[float]],
    means: list[float],
    scales: list[float],
) -> torch.Tensor:
    values = torch.tensor(vectors, dtype=torch.float32)
    mean_tensor = torch.tensor(means, dtype=torch.float32)
    scale_tensor = torch.tensor(scales, dtype=torch.float32)
    return (values - mean_tensor) / scale_tensor


def _serialize_state(model: nn.Module) -> dict[str, StateValue]:
    return {
        name: tensor.detach().cpu().tolist()
        for name, tensor in sorted(model.state_dict().items())
    }


def _load_model(artifact: TorchModelArtifact) -> _BinaryMLP:
    model = _BinaryMLP(len(artifact.feature_names), artifact.hidden_dim)
    state = {
        name: torch.tensor(value, dtype=torch.float32)
        for name, value in artifact.state_dict.items()
    }
    model.load_state_dict(state)
    model.eval()
    return model


def train_torch_challenger(
    examples: Sequence[LabeledExample],
    *,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
    config: TorchTrainingConfig | None = None,
    training_as_of: datetime | None = None,
) -> TorchModelArtifact:
    config = config or TorchTrainingConfig()
    if not examples:
        raise ValueError("PyTorch training requires labeled examples")
    if not feature_names or len(set(feature_names)) != len(feature_names):
        raise ValueError("feature_names must be non-empty and unique")
    if training_as_of is not None and (
        training_as_of.tzinfo is None or training_as_of.utcoffset() is None
    ):
        raise ValueError("training_as_of must be timezone-aware")

    all_ordered = sorted(examples, key=lambda example: example.as_of)
    cutoff = training_as_of or max(
        example.target_observed_at for example in all_ordered
    )
    ordered = [
        example for example in all_ordered if example.target_observed_at <= cutoff
    ]
    if len(ordered) < 40:
        raise ValueError("at least 40 causally available labeled examples are required")

    target_fingerprints = {example.target_spec_fingerprint for example in ordered}
    if len(target_fingerprints) != 1:
        raise ValueError("all examples must use the same target specification")

    split_index = int(len(ordered) * config.train_fraction)
    split_index = min(max(split_index, 20), len(ordered) - 10)
    validation = ordered[split_index:]
    validation_start = validation[0].as_of

    # Purge training labels whose future outcome is not yet known at validation start.
    training = [
        example
        for example in ordered[:split_index]
        if example.target_observed_at < validation_start
    ]
    if len(training) < 20:
        raise ValueError("purged chronological training partition has fewer than 20 rows")
    train_labels = [example.label for example in training]
    validation_labels = [example.label for example in validation]
    if len(set(train_labels)) != 2 or len(set(validation_labels)) != 2:
        raise ValueError("training and validation partitions must contain both classes")

    train_vectors = [_feature_vector(example.features, feature_names) for example in training]
    validation_vectors = [
        _feature_vector(example.features, feature_names) for example in validation
    ]
    means, scales = _mean_and_scale(train_vectors)
    x_train = _standardize(train_vectors, means, scales)
    y_train = torch.tensor(train_labels, dtype=torch.float32)
    x_validation = _standardize(validation_vectors, means, scales)

    _configure_determinism(config.seed)
    model = _BinaryMLP(len(feature_names), config.hidden_dim)
    optimizer = torch.optim.SGD(model.parameters(), lr=config.learning_rate)
    loss_function = nn.BCEWithLogitsLoss()
    model.train()
    for _ in range(config.epochs):
        optimizer.zero_grad(set_to_none=True)
        logits = model(x_train)
        loss = loss_function(logits, y_train)
        loss.backward()
        optimizer.step()

    model.eval()
    with torch.no_grad():
        validation_probabilities = torch.sigmoid(model(x_validation)).tolist()
    metrics = TorchValidationMetrics(
        roc_auc=float(roc_auc_score(validation_labels, validation_probabilities)),
        brier_score=float(brier_score_loss(validation_labels, validation_probabilities)),
        log_loss=float(log_loss(validation_labels, validation_probabilities, labels=[0, 1])),
        rows=len(validation),
        positive_rows=sum(validation_labels),
        negative_rows=len(validation_labels) - sum(validation_labels),
    )
    state = _serialize_state(model)
    latest_label_observed_at = max(
        example.target_observed_at for example in [*training, *validation]
    )
    training_example_fingerprints = [
        example.example_fingerprint for example in training
    ]
    validation_example_fingerprints = [
        example.example_fingerprint for example in validation
    ]
    payload = {
        "model_version": MODEL_VERSION,
        "torch_version": torch.__version__,
        "feature_names": list(feature_names),
        "feature_means": means,
        "feature_scales": scales,
        "hidden_dim": config.hidden_dim,
        "seed": config.seed,
        "epochs": config.epochs,
        "learning_rate": config.learning_rate,
        "target_spec_fingerprint": next(iter(target_fingerprints)),
        "training_config_fingerprint": config.fingerprint,
        "training_as_of": cutoff.isoformat(),
        "latest_label_observed_at": latest_label_observed_at.isoformat(),
        "train_start": training[0].as_of.isoformat(),
        "train_end": training[-1].as_of.isoformat(),
        "validation_start": validation[0].as_of.isoformat(),
        "validation_end": validation[-1].as_of.isoformat(),
        "training_example_fingerprints": training_example_fingerprints,
        "validation_example_fingerprints": validation_example_fingerprints,
        "state_dict": state,
        "validation": metrics.model_dump(mode="json"),
    }
    return TorchModelArtifact(
        torch_version=torch.__version__,
        feature_names=list(feature_names),
        feature_means=means,
        feature_scales=scales,
        hidden_dim=config.hidden_dim,
        seed=config.seed,
        epochs=config.epochs,
        learning_rate=config.learning_rate,
        target_spec_fingerprint=next(iter(target_fingerprints)),
        training_config_fingerprint=config.fingerprint,
        training_as_of=cutoff,
        latest_label_observed_at=latest_label_observed_at,
        train_start=training[0].as_of,
        train_end=training[-1].as_of,
        validation_start=validation[0].as_of,
        validation_end=validation[-1].as_of,
        train_rows=len(training),
        validation_rows=len(validation),
        training_example_fingerprints=training_example_fingerprints,
        validation_example_fingerprints=validation_example_fingerprints,
        state_dict=state,
        validation=metrics,
        model_fingerprint=canonical_fingerprint(payload),
    )


def score_torch_challenger(
    row: FeatureRow,
    artifact: TorchModelArtifact,
) -> TorchInferenceEvidence:
    _configure_determinism(artifact.seed)
    model = _load_model(artifact)
    vector = _feature_vector(row.features, artifact.feature_names)
    standardized = _standardize([vector], artifact.feature_means, artifact.feature_scales)
    standardized.requires_grad_(True)
    logit = model(standardized)[0]
    probability = torch.sigmoid(logit)
    logit.backward()
    gradients = standardized.grad
    if gradients is None:
        raise RuntimeError("feature attribution gradient was not produced")
    attributions = (gradients[0] * standardized.detach()[0]).tolist()
    probability_value = float(probability.detach())
    directional_score = (2.0 * probability_value) - 1.0
    feature_attribution = dict(zip(artifact.feature_names, attributions, strict=True))
    payload = {
        "model_version": MODEL_VERSION,
        "symbol": row.symbol,
        "as_of": row.as_of.isoformat(),
        "data_cutoff": row.data_cutoff.isoformat(),
        "probability_up": probability_value,
        "directional_score": directional_score,
        "confidence": abs(directional_score),
        "feature_attribution": feature_attribution,
        "feature_row_fingerprint": row.fingerprint,
        "model_fingerprint": artifact.model_fingerprint,
        "status": "CHALLENGER_EVIDENCE_ONLY",
    }
    return TorchInferenceEvidence(
        symbol=row.symbol,
        as_of=row.as_of,
        data_cutoff=row.data_cutoff,
        probability_up=probability_value,
        directional_score=directional_score,
        confidence=abs(directional_score),
        feature_attribution=feature_attribution,
        feature_row_fingerprint=row.fingerprint,
        model_fingerprint=artifact.model_fingerprint,
        inference_fingerprint=canonical_fingerprint(payload),
    )
