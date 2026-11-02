from __future__ import annotations

from collections.abc import Sequence
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import (
    accuracy_score,
    brier_score_loss,
    log_loss,
    precision_score,
    recall_score,
    roc_auc_score,
)

from .baseline import (
    DEFAULT_BASELINE_FEATURES,
    score_logistic_baseline,
    train_logistic_baseline,
)
from .baseline_evaluation import TREE_VERSION
from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .pytorch_model import (
    MODEL_VERSION,
    TorchTrainingConfig,
    score_torch_challenger,
    train_torch_challenger,
)
from .pytorch_target import LabeledExample

EVALUATION_VERSION = "pytorch-evaluation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class CalibrationBucket(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    lower_bound: Annotated[float, Field(ge=0.0, le=1.0)]
    upper_bound: Annotated[float, Field(ge=0.0, le=1.0)]
    rows: Annotated[int, Field(ge=1)]
    mean_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    observed_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    absolute_gap: Annotated[float, Field(ge=0.0, le=1.0)]


class EvaluationMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: str
    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    accuracy: Annotated[float, Field(ge=0.0, le=1.0)]
    precision: Annotated[float, Field(ge=0.0, le=1.0)]
    recall: Annotated[float, Field(ge=0.0, le=1.0)]
    expected_calibration_error: Annotated[float, Field(ge=0.0, le=1.0)]
    calibration_buckets: list[CalibrationBucket]
    test_rows: Annotated[int, Field(ge=10)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]


class PyTorchEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evaluation_version: Literal["pytorch-evaluation.v1"] = EVALUATION_VERSION
    feature_names: list[str]
    target_spec_fingerprint: Hash64
    pytorch_model_fingerprint: Hash64
    train_rows: Annotated[int, Field(ge=20)]
    test_rows: Annotated[int, Field(ge=10)]
    logistic: EvaluationMetrics
    tree: EvaluationMetrics
    pytorch: EvaluationMetrics
    comparison: Literal[
        "PYTORCH_ADVANCES_TO_REPLAY",
        "BASELINES_HOLD",
        "INCONCLUSIVE",
    ]
    replay_candidate: bool
    required_next_gate: Literal["V2_08_REPLAY_WALK_FORWARD"] = "V2_08_REPLAY_WALK_FORWARD"
    evaluation_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def _vector(row: FeatureRow, feature_names: Sequence[str]) -> list[float]:
    return [float(row.features.get(name, 0.0)) for name in feature_names]


def _calibration(
    labels: list[int], probabilities: list[float]
) -> tuple[list[CalibrationBucket], float]:
    buckets: list[CalibrationBucket] = []
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
        gap = abs(mean_probability - observed_rate)
        weighted_gap += gap * len(members)
        buckets.append(
            CalibrationBucket(
                lower_bound=lower,
                upper_bound=upper,
                rows=len(members),
                mean_probability=mean_probability,
                observed_rate=observed_rate,
                absolute_gap=gap,
            )
        )
    return buckets, weighted_gap / total


def _metrics(
    version: str,
    labels: list[int],
    probabilities: list[float],
) -> EvaluationMetrics:
    predictions = [1 if probability >= 0.5 else 0 for probability in probabilities]
    calibration_buckets, expected_calibration_error = _calibration(labels, probabilities)
    return EvaluationMetrics(
        model_version=version,
        roc_auc=float(roc_auc_score(labels, probabilities)),
        brier_score=float(brier_score_loss(labels, probabilities)),
        log_loss=float(log_loss(labels, probabilities, labels=[0, 1])),
        accuracy=float(accuracy_score(labels, predictions)),
        precision=float(precision_score(labels, predictions, zero_division=0)),
        recall=float(recall_score(labels, predictions, zero_division=0)),
        expected_calibration_error=expected_calibration_error,
        calibration_buckets=calibration_buckets,
        test_rows=len(labels),
        positive_rows=sum(labels),
        negative_rows=len(labels) - sum(labels),
    )


def _partition(
    examples: Sequence[LabeledExample], train_fraction: float
) -> tuple[list[LabeledExample], list[LabeledExample]]:
    ordered = sorted(examples, key=lambda example: example.as_of)
    split_index = int(len(ordered) * train_fraction)
    split_index = min(max(split_index, 20), len(ordered) - 10)
    validation = ordered[split_index:]
    validation_start = validation[0].as_of
    training = [
        example
        for example in ordered[:split_index]
        if example.target_observed_at < validation_start
    ]
    return training, validation


def _comparison(
    logistic: EvaluationMetrics,
    tree: EvaluationMetrics,
    pytorch: EvaluationMetrics,
) -> str:
    best_auc = max(logistic.roc_auc, tree.roc_auc)
    best_brier = min(logistic.brier_score, tree.brier_score)
    best_log_loss = min(logistic.log_loss, tree.log_loss)
    best_ece = min(logistic.expected_calibration_error, tree.expected_calibration_error)

    if (
        pytorch.roc_auc >= best_auc + 0.02
        and pytorch.brier_score <= best_brier
        and pytorch.log_loss <= best_log_loss
        and pytorch.expected_calibration_error <= best_ece + 0.01
    ):
        return "PYTORCH_ADVANCES_TO_REPLAY"
    if (
        pytorch.roc_auc < best_auc - 0.01
        or pytorch.brier_score > best_brier + 0.01
        or pytorch.log_loss > best_log_loss + 0.02
    ):
        return "BASELINES_HOLD"
    return "INCONCLUSIVE"


def evaluate_pytorch_challenger(
    rows: Sequence[FeatureRow],
    examples: Sequence[LabeledExample],
    *,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
    config: TorchTrainingConfig | None = None,
) -> PyTorchEvaluation:
    config = config or TorchTrainingConfig()
    if len(examples) < 40:
        raise ValueError("at least 40 labeled examples are required")
    if len({example.target_spec_fingerprint for example in examples}) != 1:
        raise ValueError("all examples must use the same target specification")

    row_by_fingerprint = {row.fingerprint: row for row in rows}
    if len(row_by_fingerprint) != len(rows):
        raise ValueError("feature row fingerprints must be unique")
    missing = [
        example.feature_row_fingerprint
        for example in examples
        if example.feature_row_fingerprint not in row_by_fingerprint
    ]
    if missing:
        raise ValueError("every labeled example must resolve to its frozen feature row")

    training_examples, test_examples = _partition(examples, config.train_fraction)
    if len(training_examples) < 20 or len(test_examples) < 10:
        raise ValueError("chronological purged partitions are too small")
    train_labels = [example.label for example in training_examples]
    test_labels = [example.label for example in test_examples]
    if len(set(train_labels)) != 2 or len(set(test_labels)) != 2:
        raise ValueError("training and test partitions must contain both classes")

    train_rows = [
        row_by_fingerprint[example.feature_row_fingerprint]
        for example in training_examples
    ]
    test_rows = [
        row_by_fingerprint[example.feature_row_fingerprint] for example in test_examples
    ]

    logistic_artifact = train_logistic_baseline(
        train_rows,
        train_labels,
        feature_names=feature_names,
    )
    logistic_probabilities = [
        score_logistic_baseline(row, logistic_artifact).probability_up for row in test_rows
    ]

    tree = HistGradientBoostingClassifier(
        learning_rate=0.05,
        max_iter=120,
        max_leaf_nodes=15,
        l2_regularization=1.0,
        random_state=42,
    )
    tree.fit([_vector(row, feature_names) for row in train_rows], train_labels)
    tree_probabilities = [
        float(probability[1])
        for probability in tree.predict_proba([_vector(row, feature_names) for row in test_rows])
    ]

    pytorch_artifact = train_torch_challenger(
        examples,
        feature_names=feature_names,
        config=config,
    )
    if pytorch_artifact.validation_start != test_examples[0].as_of:
        raise ValueError("PyTorch validation partition does not match evaluation holdout")
    pytorch_probabilities = [
        score_torch_challenger(row, pytorch_artifact).probability_up for row in test_rows
    ]

    logistic_metrics = _metrics(
        logistic_artifact.model_version,
        test_labels,
        logistic_probabilities,
    )
    tree_metrics = _metrics(TREE_VERSION, test_labels, tree_probabilities)
    pytorch_metrics = _metrics(MODEL_VERSION, test_labels, pytorch_probabilities)
    comparison = _comparison(logistic_metrics, tree_metrics, pytorch_metrics)
    replay_candidate = comparison == "PYTORCH_ADVANCES_TO_REPLAY"

    payload = {
        "evaluation_version": EVALUATION_VERSION,
        "feature_names": list(feature_names),
        "target_spec_fingerprint": test_examples[0].target_spec_fingerprint,
        "training_examples": [example.example_fingerprint for example in training_examples],
        "test_examples": [example.example_fingerprint for example in test_examples],
        "logistic_artifact": logistic_artifact.artifact_fingerprint,
        "pytorch_model": pytorch_artifact.model_fingerprint,
        "tree_config": {
            "version": TREE_VERSION,
            "learning_rate": 0.05,
            "max_iter": 120,
            "max_leaf_nodes": 15,
            "l2_regularization": 1.0,
            "random_state": 42,
        },
        "logistic": logistic_metrics.model_dump(mode="json"),
        "tree": tree_metrics.model_dump(mode="json"),
        "pytorch": pytorch_metrics.model_dump(mode="json"),
        "comparison": comparison,
        "replay_candidate": replay_candidate,
        "required_next_gate": "V2_08_REPLAY_WALK_FORWARD",
    }
    return PyTorchEvaluation(
        feature_names=list(feature_names),
        target_spec_fingerprint=test_examples[0].target_spec_fingerprint,
        pytorch_model_fingerprint=pytorch_artifact.model_fingerprint,
        train_rows=len(training_examples),
        test_rows=len(test_examples),
        logistic=logistic_metrics,
        tree=tree_metrics,
        pytorch=pytorch_metrics,
        comparison=comparison,
        replay_candidate=replay_candidate,
        evaluation_fingerprint=canonical_fingerprint(payload),
    )
