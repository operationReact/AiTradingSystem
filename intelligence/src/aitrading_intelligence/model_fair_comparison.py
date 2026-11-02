from __future__ import annotations

import importlib.util
import math
from collections.abc import Callable, Sequence
from datetime import datetime
from typing import Annotated, Literal

import catboost as cb
import lightgbm as lgb
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

from .baseline import DEFAULT_BASELINE_FEATURES
from .catboost_model import CatBoostTrainingConfig
from .fingerprints import canonical_fingerprint
from .lightgbm_model import LightGBMTrainingConfig
from .pytorch_target import ForwardReturnTargetSpec, LabeledExample

COMPARISON_VERSION = "model-fair-comparison.v1"
TREE_VERSION = "hist-gradient-boosting.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelFamily = Literal["LOGISTIC", "HIST_TREE", "LIGHTGBM", "CATBOOST", "PYTORCH"]
Predictor = Callable[
    [Sequence[LabeledExample], Sequence[LabeledExample], Sequence[str], int],
    list[float],
]


class FairComparisonConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    comparison_version: Literal["model-fair-comparison.v1"] = COMPARISON_VERSION
    fold_count: Annotated[int, Field(ge=2, le=10)] = 3
    test_rows_per_fold: Annotated[int, Field(ge=10, le=500)] = 10
    minimum_train_rows: Annotated[int, Field(ge=20, le=100_000)] = 20
    probability_threshold: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.5
    calibration_bins: Annotated[int, Field(ge=5, le=50)] = 10
    seed: Annotated[int, Field(ge=0, le=2_147_483_647)] = 42

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class PurgedFold(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fold_index: Annotated[int, Field(ge=0)]
    train_start: datetime
    train_end: datetime
    test_start: datetime
    test_end: datetime
    train_rows: Annotated[int, Field(ge=20)]
    test_rows: Annotated[int, Field(ge=10)]
    train_example_fingerprints: list[Hash64]
    test_example_fingerprints: list[Hash64]
    fold_fingerprint: Hash64

    @field_validator("train_start", "train_end", "test_start", "test_end")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("fold timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_fold(self) -> PurgedFold:
        if self.train_end >= self.test_start:
            raise ValueError("purged training inputs must end before test starts")
        if len(self.train_example_fingerprints) != self.train_rows:
            raise ValueError("train fingerprint count must equal train_rows")
        if len(self.test_example_fingerprints) != self.test_rows:
            raise ValueError("test fingerprint count must equal test_rows")
        return self


class ModelAggregateMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    model_family: ModelFamily
    model_version: str
    evaluated_folds: Annotated[int, Field(ge=2)]
    evaluated_rows: Annotated[int, Field(ge=20)]
    positive_rows: Annotated[int, Field(ge=1)]
    negative_rows: Annotated[int, Field(ge=1)]
    roc_auc: Annotated[float, Field(ge=0.0, le=1.0)]
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)]
    log_loss: Annotated[float, Field(ge=0.0)]
    expected_calibration_error: Annotated[float, Field(ge=0.0, le=1.0)]
    trade_count: Annotated[int, Field(ge=0)]
    trade_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    win_rate_after_costs: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    total_net_return: float
    mean_net_return_per_trade: float | None
    max_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    probability_fingerprint: Hash64
    fold_membership_fingerprint: Hash64
    metrics_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("total_net_return", "mean_net_return_per_trade")
    @classmethod
    def returns_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("economic metrics must be finite")
        return value


class ModelFairComparisonReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    comparison_version: Literal["model-fair-comparison.v1"] = COMPARISON_VERSION
    evaluation_as_of: datetime
    feature_names: list[str] = Field(min_length=1, max_length=128)
    target_spec_fingerprint: Hash64
    comparison_config_fingerprint: Hash64
    eligible_example_count: Annotated[int, Field(ge=40)]
    folds: list[PurgedFold] = Field(min_length=2, max_length=10)
    models: list[ModelAggregateMetrics] = Field(min_length=4, max_length=5)
    included_pytorch: bool
    economic_ranking: list[ModelFamily]
    report_fingerprint: Hash64
    status: Literal["RESEARCH_COMPARISON_ONLY"] = "RESEARCH_COMPARISON_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("evaluation_as_of")
    @classmethod
    def evaluation_timestamp_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluation_as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_report(self) -> ModelFairComparisonReport:
        expected_families = {"LOGISTIC", "HIST_TREE", "LIGHTGBM", "CATBOOST"}
        if self.included_pytorch:
            expected_families.add("PYTORCH")
        actual_families = {model.model_family for model in self.models}
        if actual_families != expected_families:
            raise ValueError("comparison models do not match the required model set")
        if set(self.economic_ranking) != actual_families:
            raise ValueError("economic ranking must contain each evaluated model exactly once")
        membership = {model.fold_membership_fingerprint for model in self.models}
        if len(membership) != 1:
            raise ValueError("all models must use identical fold membership")
        return self


def _vector(example: LabeledExample, feature_names: Sequence[str]) -> list[float]:
    values = [float(example.features.get(name, 0.0)) for name in feature_names]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("comparison feature values must be finite")
    return values


def _expected_calibration_error(
    labels: Sequence[int],
    probabilities: Sequence[float],
    *,
    bins: int,
) -> float:
    total = len(labels)
    error = 0.0
    for bin_index in range(bins):
        lower = bin_index / bins
        upper = (bin_index + 1) / bins
        members = [
            index
            for index, probability in enumerate(probabilities)
            if probability >= lower
            and (probability < upper or (bin_index == bins - 1 and probability <= upper))
        ]
        if not members:
            continue
        mean_probability = sum(probabilities[index] for index in members) / len(members)
        observed_rate = sum(labels[index] for index in members) / len(members)
        error += (len(members) / total) * abs(mean_probability - observed_rate)
    return float(error)


def _max_drawdown(net_returns: Sequence[float]) -> float:
    equity = 1.0
    peak = 1.0
    max_drawdown = 0.0
    for net_return in net_returns:
        if net_return <= -1.0:
            return 1.0
        equity *= 1.0 + net_return
        peak = max(peak, equity)
        if peak > 0.0:
            max_drawdown = max(max_drawdown, (peak - equity) / peak)
    return min(1.0, max(0.0, max_drawdown))


def _build_folds(
    examples: Sequence[LabeledExample],
    *,
    config: FairComparisonConfig,
) -> list[tuple[PurgedFold, list[LabeledExample], list[LabeledExample]]]:
    required = config.minimum_train_rows + (config.fold_count * config.test_rows_per_fold)
    if len(examples) < required:
        raise ValueError(
            "insufficient causal examples for configured purged comparison folds"
        )
    first_test_index = len(examples) - (config.fold_count * config.test_rows_per_fold)
    folds: list[tuple[PurgedFold, list[LabeledExample], list[LabeledExample]]] = []
    for fold_index in range(config.fold_count):
        test_start_index = first_test_index + (fold_index * config.test_rows_per_fold)
        test_end_index = test_start_index + config.test_rows_per_fold
        test_examples = list(examples[test_start_index:test_end_index])
        test_start = test_examples[0].as_of
        train_examples = [
            example
            for example in examples[:test_start_index]
            if example.target_observed_at < test_start
        ]
        if len(train_examples) < config.minimum_train_rows:
            raise ValueError("purged fold has fewer than the minimum training rows")
        train_labels = {example.label for example in train_examples}
        test_labels = {example.label for example in test_examples}
        if train_labels != {0, 1} or test_labels != {0, 1}:
            raise ValueError("every comparison fold must contain both classes")

        payload = {
            "fold_index": fold_index,
            "train_example_fingerprints": [
                example.example_fingerprint for example in train_examples
            ],
            "test_example_fingerprints": [
                example.example_fingerprint for example in test_examples
            ],
        }
        fold = PurgedFold(
            fold_index=fold_index,
            train_start=train_examples[0].as_of,
            train_end=train_examples[-1].as_of,
            test_start=test_examples[0].as_of,
            test_end=test_examples[-1].as_of,
            train_rows=len(train_examples),
            test_rows=len(test_examples),
            train_example_fingerprints=payload["train_example_fingerprints"],
            test_example_fingerprints=payload["test_example_fingerprints"],
            fold_fingerprint=canonical_fingerprint(payload),
        )
        folds.append((fold, train_examples, test_examples))
    return folds


def _predict_logistic(
    training: Sequence[LabeledExample],
    test: Sequence[LabeledExample],
    feature_names: Sequence[str],
    seed: int,
) -> list[float]:
    model = LogisticRegression(
        solver="liblinear",
        random_state=seed,
        max_iter=1000,
        C=1.0,
    )
    model.fit(
        [_vector(example, feature_names) for example in training],
        [example.label for example in training],
    )
    return [
        float(probabilities[1])
        for probabilities in model.predict_proba(
            [_vector(example, feature_names) for example in test]
        )
    ]


def _predict_hist_tree(
    training: Sequence[LabeledExample],
    test: Sequence[LabeledExample],
    feature_names: Sequence[str],
    seed: int,
) -> list[float]:
    model = HistGradientBoostingClassifier(
        learning_rate=0.05,
        max_iter=120,
        max_leaf_nodes=15,
        l2_regularization=1.0,
        random_state=seed,
    )
    model.fit(
        [_vector(example, feature_names) for example in training],
        [example.label for example in training],
    )
    return [
        float(probabilities[1])
        for probabilities in model.predict_proba(
            [_vector(example, feature_names) for example in test]
        )
    ]


def _predict_lightgbm(
    training: Sequence[LabeledExample],
    test: Sequence[LabeledExample],
    feature_names: Sequence[str],
    seed: int,
) -> list[float]:
    config = LightGBMTrainingConfig(seed=seed)
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
    model.fit(
        [_vector(example, feature_names) for example in training],
        [example.label for example in training],
        feature_name=list(feature_names),
    )
    return [
        float(probabilities[1])
        for probabilities in model.predict_proba(
            [_vector(example, feature_names) for example in test]
        )
    ]


def _predict_catboost(
    training: Sequence[LabeledExample],
    test: Sequence[LabeledExample],
    feature_names: Sequence[str],
    seed: int,
) -> list[float]:
    config = CatBoostTrainingConfig(seed=seed)
    model = cb.CatBoostClassifier(
        iterations=config.iterations,
        learning_rate=config.learning_rate,
        depth=config.depth,
        l2_leaf_reg=config.l2_leaf_reg,
        random_seed=config.seed,
        loss_function="Logloss",
        eval_metric="Logloss",
        bootstrap_type="No",
        random_strength=0.0,
        thread_count=1,
        verbose=False,
        allow_writing_files=False,
    )
    train_pool = cb.Pool(
        [_vector(example, feature_names) for example in training],
        [example.label for example in training],
        feature_names=list(feature_names),
    )
    test_pool = cb.Pool(
        [_vector(example, feature_names) for example in test],
        feature_names=list(feature_names),
    )
    model.fit(train_pool)
    return [float(probabilities[1]) for probabilities in model.predict_proba(test_pool)]


def _predict_pytorch(
    training: Sequence[LabeledExample],
    test: Sequence[LabeledExample],
    feature_names: Sequence[str],
    seed: int,
) -> list[float]:
    if importlib.util.find_spec("torch") is None:
        raise RuntimeError(
            "PyTorch comparison requested but torch is not installed; install the CPU requirement"
        )

    import torch

    from .pytorch_model import (
        TorchTrainingConfig,
        _BinaryMLP,
        _configure_determinism,
        _mean_and_scale,
        _standardize,
    )

    config = TorchTrainingConfig(seed=seed)
    train_vectors = [_vector(example, feature_names) for example in training]
    test_vectors = [_vector(example, feature_names) for example in test]
    means, scales = _mean_and_scale(train_vectors)
    x_train = _standardize(train_vectors, means, scales)
    y_train = torch.tensor([example.label for example in training], dtype=torch.float32)
    x_test = _standardize(test_vectors, means, scales)

    _configure_determinism(config.seed)
    model = _BinaryMLP(len(feature_names), config.hidden_dim)
    optimizer = torch.optim.SGD(model.parameters(), lr=config.learning_rate)
    loss_function = torch.nn.BCEWithLogitsLoss()
    model.train()
    for _ in range(config.epochs):
        optimizer.zero_grad(set_to_none=True)
        logits = model(x_train)
        loss = loss_function(logits, y_train)
        loss.backward()
        optimizer.step()

    model.eval()
    with torch.no_grad():
        return [float(value) for value in torch.sigmoid(model(x_test)).tolist()]


def _aggregate_metrics(
    *,
    model_family: ModelFamily,
    model_version: str,
    probabilities: Sequence[float],
    test_examples: Sequence[LabeledExample],
    folds: Sequence[PurgedFold],
    target_spec: ForwardReturnTargetSpec,
    config: FairComparisonConfig,
) -> ModelAggregateMetrics:
    if len(probabilities) != len(test_examples):
        raise ValueError("probability count must match evaluated examples")
    if any(not math.isfinite(value) or not 0.0 <= value <= 1.0 for value in probabilities):
        raise ValueError("model probabilities must be finite values in [0, 1]")

    labels = [example.label for example in test_examples]
    if set(labels) != {0, 1}:
        raise ValueError("aggregate comparison rows must contain both classes")
    cost = target_spec.round_trip_cost_bps / 10_000.0
    trade_net_returns = [
        example.forward_return - cost
        for example, probability in zip(test_examples, probabilities, strict=True)
        if probability >= config.probability_threshold
    ]
    trade_count = len(trade_net_returns)
    win_rate = None
    mean_net_return = None
    if trade_net_returns:
        win_rate = sum(value > 0.0 for value in trade_net_returns) / trade_count
        mean_net_return = sum(trade_net_returns) / trade_count

    probability_payload = [round(float(value), 12) for value in probabilities]
    membership_fingerprint = canonical_fingerprint(
        [fold.fold_fingerprint for fold in folds]
    )
    metrics_payload = {
        "model_family": model_family,
        "model_version": model_version,
        "labels": labels,
        "probabilities": probability_payload,
        "fold_membership_fingerprint": membership_fingerprint,
        "round_trip_cost_bps": target_spec.round_trip_cost_bps,
        "probability_threshold": config.probability_threshold,
    }
    return ModelAggregateMetrics(
        model_family=model_family,
        model_version=model_version,
        evaluated_folds=len(folds),
        evaluated_rows=len(test_examples),
        positive_rows=sum(labels),
        negative_rows=len(labels) - sum(labels),
        roc_auc=float(roc_auc_score(labels, probabilities)),
        brier_score=float(brier_score_loss(labels, probabilities)),
        log_loss=float(log_loss(labels, probabilities, labels=[0, 1])),
        expected_calibration_error=_expected_calibration_error(
            labels,
            probabilities,
            bins=config.calibration_bins,
        ),
        trade_count=trade_count,
        trade_rate=trade_count / len(test_examples),
        win_rate_after_costs=win_rate,
        total_net_return=float(sum(trade_net_returns)),
        mean_net_return_per_trade=(
            float(mean_net_return) if mean_net_return is not None else None
        ),
        max_drawdown=_max_drawdown(trade_net_returns),
        probability_fingerprint=canonical_fingerprint(probability_payload),
        fold_membership_fingerprint=membership_fingerprint,
        metrics_fingerprint=canonical_fingerprint(metrics_payload),
    )


def evaluate_model_families(
    examples: Sequence[LabeledExample],
    target_spec: ForwardReturnTargetSpec,
    *,
    evaluation_as_of: datetime,
    feature_names: Sequence[str] = DEFAULT_BASELINE_FEATURES,
    config: FairComparisonConfig | None = None,
    include_pytorch: bool = True,
) -> ModelFairComparisonReport:
    config = config or FairComparisonConfig()
    if evaluation_as_of.tzinfo is None or evaluation_as_of.utcoffset() is None:
        raise ValueError("evaluation_as_of must be timezone-aware")
    if not feature_names or len(set(feature_names)) != len(feature_names):
        raise ValueError("feature_names must be non-empty and unique")

    eligible = sorted(
        (
            example
            for example in examples
            if example.as_of < evaluation_as_of
            and example.target_observed_at <= evaluation_as_of
        ),
        key=lambda example: (example.as_of, example.symbol, example.example_fingerprint),
    )
    if len({example.example_fingerprint for example in eligible}) != len(eligible):
        raise ValueError("comparison examples must be unique")
    target_fingerprints = {example.target_spec_fingerprint for example in eligible}
    if target_fingerprints != {target_spec.fingerprint}:
        raise ValueError("all eligible examples must match the requested target specification")

    fold_entries = _build_folds(eligible, config=config)
    folds = [entry[0] for entry in fold_entries]
    all_test_examples = [example for _, _, test in fold_entries for example in test]

    predictors: list[tuple[ModelFamily, str, Predictor]] = [
        ("LOGISTIC", "logistic-baseline.v1", _predict_logistic),
        ("HIST_TREE", TREE_VERSION, _predict_hist_tree),
        ("LIGHTGBM", "lightgbm-binary.v1", _predict_lightgbm),
        ("CATBOOST", "catboost-binary.v1", _predict_catboost),
    ]
    if include_pytorch:
        predictors.append(("PYTORCH", "pytorch-mlp.v1", _predict_pytorch))

    models: list[ModelAggregateMetrics] = []
    for model_family, model_version, predictor in predictors:
        probabilities: list[float] = []
        for _, training, test in fold_entries:
            probabilities.extend(
                predictor(training, test, feature_names, config.seed)
            )
        models.append(
            _aggregate_metrics(
                model_family=model_family,
                model_version=model_version,
                probabilities=probabilities,
                test_examples=all_test_examples,
                folds=folds,
                target_spec=target_spec,
                config=config,
            )
        )

    ranking = [
        model.model_family
        for model in sorted(
            models,
            key=lambda model: (
                -model.total_net_return,
                model.expected_calibration_error,
                model.brier_score,
                model.model_family,
            ),
        )
    ]
    payload = {
        "comparison_version": COMPARISON_VERSION,
        "evaluation_as_of": evaluation_as_of.isoformat(),
        "feature_names": list(feature_names),
        "target_spec_fingerprint": target_spec.fingerprint,
        "comparison_config_fingerprint": config.fingerprint,
        "eligible_example_fingerprints": [
            example.example_fingerprint for example in eligible
        ],
        "folds": [fold.model_dump(mode="json") for fold in folds],
        "models": [model.model_dump(mode="json") for model in models],
        "included_pytorch": include_pytorch,
        "economic_ranking": ranking,
        "status": "RESEARCH_COMPARISON_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelFairComparisonReport(
        evaluation_as_of=evaluation_as_of,
        feature_names=list(feature_names),
        target_spec_fingerprint=target_spec.fingerprint,
        comparison_config_fingerprint=config.fingerprint,
        eligible_example_count=len(eligible),
        folds=folds,
        models=models,
        included_pytorch=include_pytorch,
        economic_ranking=ranking,
        report_fingerprint=canonical_fingerprint(payload),
    )
