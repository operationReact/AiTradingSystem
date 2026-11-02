from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
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
from .features import FeatureRow
from .fingerprints import canonical_fingerprint

EVALUATION_VERSION = "baseline-evaluation.v1"
TREE_VERSION = "hist-gradient-boosting.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class ModelMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_version: str
    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    accuracy: Annotated[float, Field(ge=0.0, le=1.0)]
    precision: Annotated[float, Field(ge=0.0, le=1.0)]
    recall: Annotated[float, Field(ge=0.0, le=1.0)]
    test_rows: Annotated[int, Field(ge=10)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]


class BaselineEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evaluation_version: Literal["baseline-evaluation.v1"] = EVALUATION_VERSION
    feature_names: list[str]
    train_start: datetime
    train_end: datetime
    test_start: datetime
    test_end: datetime
    train_rows: Annotated[int, Field(ge=20)]
    test_rows: Annotated[int, Field(ge=10)]
    logistic: ModelMetrics
    tree_challenger: ModelMetrics
    comparison: Literal[
        "TREE_OUTPERFORMS_LOGISTIC",
        "LOGISTIC_HOLDS",
        "INCONCLUSIVE",
    ]
    evaluation_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False


def _vector(row: FeatureRow, feature_names: Sequence[str]) -> list[float]:
    return [float(row.features.get(name, 0.0)) for name in feature_names]


def _metrics(version: str, labels: list[int], probabilities: list[float]) -> ModelMetrics:
    predictions = [1 if probability >= 0.5 else 0 for probability in probabilities]
    return ModelMetrics(
        model_version=version,
        roc_auc=float(roc_auc_score(labels, probabilities)),
        brier_score=float(brier_score_loss(labels, probabilities)),
        log_loss=float(log_loss(labels, probabilities, labels=[0, 1])),
        accuracy=float(accuracy_score(labels, predictions)),
        precision=float(precision_score(labels, predictions, zero_division=0)),
        recall=float(recall_score(labels, predictions, zero_division=0)),
        test_rows=len(labels),
        positive_rows=sum(labels),
        negative_rows=len(labels) - sum(labels),
    )


def _comparison(logistic: ModelMetrics, tree: ModelMetrics) -> str:
    auc_gain = tree.roc_auc - logistic.roc_auc
    brier_gain = logistic.brier_score - tree.brier_score
    if auc_gain >= 0.02 and brier_gain >= 0.01:
        return "TREE_OUTPERFORMS_LOGISTIC"
    if logistic.roc_auc >= tree.roc_auc - 0.01 and logistic.brier_score <= tree.brier_score + 0.005:
        return "LOGISTIC_HOLDS"
    return "INCONCLUSIVE"


def evaluate_baselines(
    rows: Sequence[FeatureRow],
    labels: Sequence[int],
    *,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
    train_fraction: float = 0.70,
) -> BaselineEvaluation:
    if len(rows) != len(labels):
        raise ValueError("rows and labels must have the same length")
    if len(rows) < 40:
        raise ValueError("at least 40 labeled rows are required for chronological evaluation")
    if not 0.5 <= train_fraction <= 0.8:
        raise ValueError("train_fraction must be within [0.5, 0.8]")
    if any(label not in (0, 1) for label in labels):
        raise ValueError("labels must contain only 0 or 1")

    ordered = sorted(zip(rows, labels, strict=True), key=lambda item: item[0].as_of)
    split_index = int(len(ordered) * train_fraction)
    split_index = min(max(split_index, 20), len(ordered) - 10)
    train = ordered[:split_index]
    test = ordered[split_index:]
    train_rows = [row for row, _ in train]
    train_labels = [label for _, label in train]
    test_rows = [row for row, _ in test]
    test_labels = [label for _, label in test]
    if len(set(train_labels)) != 2 or len(set(test_labels)) != 2:
        raise ValueError("both train and test partitions must contain positive and negative labels")

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

    logistic_metrics = _metrics(logistic_artifact.model_version, test_labels, logistic_probabilities)
    tree_metrics = _metrics(TREE_VERSION, test_labels, tree_probabilities)
    comparison = _comparison(logistic_metrics, tree_metrics)
    payload = {
        "evaluation_version": EVALUATION_VERSION,
        "feature_names": list(feature_names),
        "train_start": train_rows[0].as_of.isoformat(),
        "train_end": train_rows[-1].as_of.isoformat(),
        "test_start": test_rows[0].as_of.isoformat(),
        "test_end": test_rows[-1].as_of.isoformat(),
        "train_rows": len(train_rows),
        "test_rows": len(test_rows),
        "train_fingerprints": [row.fingerprint for row in train_rows],
        "test_fingerprints": [row.fingerprint for row in test_rows],
        "logistic_artifact": logistic_artifact.artifact_fingerprint,
        "tree_config": {
            "version": TREE_VERSION,
            "learning_rate": 0.05,
            "max_iter": 120,
            "max_leaf_nodes": 15,
            "l2_regularization": 1.0,
            "random_state": 42,
        },
        "logistic": logistic_metrics.model_dump(mode="json"),
        "tree_challenger": tree_metrics.model_dump(mode="json"),
        "comparison": comparison,
    }
    return BaselineEvaluation(
        feature_names=list(feature_names),
        train_start=train_rows[0].as_of,
        train_end=train_rows[-1].as_of,
        test_start=test_rows[0].as_of,
        test_end=test_rows[-1].as_of,
        train_rows=len(train_rows),
        test_rows=len(test_rows),
        logistic=logistic_metrics,
        tree_challenger=tree_metrics,
        comparison=comparison,
        evaluation_fingerprint=canonical_fingerprint(payload),
    )
