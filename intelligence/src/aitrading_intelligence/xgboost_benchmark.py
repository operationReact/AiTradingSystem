from __future__ import annotations

import math
from collections.abc import Sequence
from time import perf_counter
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from sklearn.metrics import brier_score_loss

from .fingerprints import canonical_fingerprint
from .incremental_economic_value import (
    IncrementalValueConfig,
    create_net_return_series,
)
from .india_fee_tax import IndiaFeeTaxBookV1
from .india_independent_holdout import (
    IndiaHoldoutConfig,
    _build_examples,
    evaluate_india_independent_holdout,
)
from .joint_diversity_economic_gate import evaluate_joint_diversity_economic_gate
from .market_source import MarketResearchExport
from .model_diversity import build_prediction_series
from .model_fair_comparison import _max_drawdown, _predict_catboost, _vector
from .pytorch_target import ForwardReturnTargetSpec, LabeledExample

VERSION = "conditional-xgboost-benchmark.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class XGBoostResearchConfig(BaseModel):
    """Small deterministic CPU challenger; not a deployable trading strategy."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    n_estimators: Annotated[int, Field(ge=20, le=120)] = 50
    max_depth: Annotated[int, Field(ge=2, le=5)] = 3
    learning_rate: Annotated[float, Field(gt=0, le=0.2)] = 0.05
    minimum_child_weight: Annotated[int, Field(ge=1, le=20)] = 5

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class XGBoostScreenPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_net_uplift_per_decision: Annotated[float, Field(ge=0, le=0.1)] = 0.0001
    maximum_brier_regression: Annotated[float, Field(ge=0, le=0.1)] = 0.01
    maximum_drawdown_regression: Annotated[float, Field(ge=0, le=0.1)] = 0.02
    max_inference_ms_per_1000_rows: Annotated[float, Field(gt=0, le=100_000)] = 1_000

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class XGBoostConditionalBenchmarkReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["conditional-xgboost-benchmark.v1"] = VERSION
    india_holdout_report_fingerprint: Hash64
    training_config_fingerprint: Hash64
    screen_policy_fingerprint: Hash64
    economic_policy_fingerprint: Hash64
    joint_screen_report_fingerprint: Hash64 | None
    xgboost_runtime_version: str | None
    evaluated_holdout_rows: int
    catboost_net_return_after_costs: float | None
    xgboost_net_return_after_costs: float | None
    total_net_uplift_after_costs: float | None
    catboost_brier_score: float | None
    xgboost_brier_score: float | None
    catboost_max_drawdown: float | None
    xgboost_max_drawdown: float | None
    xgboost_fit_ms: float | None
    xgboost_inference_ms_per_1000_rows: float | None
    status: Literal["RESEARCH_CANDIDATE_ONLY", "NOT_ADDITIVE", "INCONCLUSIVE"]
    reason_codes: list[str]
    external_market_source_verified: Literal[False] = False
    holdout_pre_registration_verified: Literal[False] = False
    multiple_trial_selection_adjusted: Literal[False] = False
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_evidence(self) -> XGBoostConditionalBenchmarkReport:
        if self.status == "RESEARCH_CANDIDATE_ONLY" and self.reason_codes:
            raise ValueError("research candidate cannot have failed gate reasons")
        if self.status != "RESEARCH_CANDIDATE_ONLY" and not self.reason_codes:
            raise ValueError("non-promising XGBoost requires explicit reasons")
        if self.status == "RESEARCH_CANDIDATE_ONLY" and (
            self.joint_screen_report_fingerprint is None
            or self.xgboost_net_return_after_costs is None
        ):
            raise ValueError("candidate requires actual paired research evidence")
        for name in (
            "catboost_net_return_after_costs", "xgboost_net_return_after_costs",
            "total_net_uplift_after_costs", "catboost_brier_score", "xgboost_brier_score",
            "catboost_max_drawdown", "xgboost_max_drawdown", "xgboost_fit_ms",
            "xgboost_inference_ms_per_1000_rows",
        ):
            value = getattr(self, name)
            if value is not None and not math.isfinite(value):
                raise ValueError("XGBoost metrics must be finite")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("XGBoost benchmark fingerprint mismatch")
        return self


def _predict_xgboost(
    training: Sequence[LabeledExample],
    holdout: Sequence[LabeledExample],
    features: Sequence[str],
    seed: int,
    config: XGBoostResearchConfig,
) -> tuple[list[float], str, float, float]:
    try:
        import xgboost as xgb
    except ImportError as exc:
        raise RuntimeError(
            "XGBoost research is optional: install requirements-xgboost-cpu.txt"
        ) from exc

    model = xgb.XGBClassifier(
        objective="binary:logistic",
        tree_method="hist",
        device="cpu",
        n_jobs=1,
        random_state=seed,
        n_estimators=config.n_estimators,
        max_depth=config.max_depth,
        learning_rate=config.learning_rate,
        min_child_weight=config.minimum_child_weight,
        subsample=1.0,
        colsample_bytree=1.0,
        eval_metric="logloss",
        verbosity=0,
    )
    start = perf_counter()
    model.fit(
        [_vector(row, features) for row in training],
        [row.label for row in training],
    )
    fit_ms = (perf_counter() - start) * 1000
    start = perf_counter()
    probs = [
        float(values[1])
        for values in model.predict_proba([_vector(row, features) for row in holdout])
    ]
    inference_ms = (perf_counter() - start) * 1000
    return probs, xgb.__version__, fit_ms, inference_ms


def _series_returns(
    examples: Sequence[LabeledExample],
    probabilities: Sequence[float],
    *,
    threshold: float,
    cost: float,
) -> list[float]:
    return [
        example.forward_return - cost if probability >= threshold else 0.0
        for example, probability in zip(examples, probabilities, strict=True)
    ]


def evaluate_conditional_xgboost_benchmark(
    training_export: MarketResearchExport,
    holdout_export: MarketResearchExport,
    fee_book: IndiaFeeTaxBookV1,
    target: ForwardReturnTargetSpec,
    *,
    holdout_config: IndiaHoldoutConfig | None = None,
    xgboost_config: XGBoostResearchConfig | None = None,
    screen_policy: XGBoostScreenPolicy | None = None,
    economic_policy: IncrementalValueConfig | None = None,
) -> XGBoostConditionalBenchmarkReport:
    """Run a single registered XGBoost-vs-CatBoost research-only comparison.

    Uses the authoritative independent holdout preparation, fee gates and labels.
    The paired block bootstrap is only a *screen*; multiple researcher trials,
    liquidity, real fill realism, and independently audited market sources remain open.
    """
    holdout_settings = holdout_config or IndiaHoldoutConfig()
    model_settings = xgboost_config or XGBoostResearchConfig()
    policy = screen_policy or XGBoostScreenPolicy()
    economics = economic_policy or IncrementalValueConfig(
        minimum_mean_return_uplift=policy.minimum_net_uplift_per_decision
    )

    baseline_report = evaluate_india_independent_holdout(
        training_export, holdout_export, fee_book, target,
        config=holdout_settings, include_pytorch=False,
    )
    result: dict[str, object] = {
        "contract_version": VERSION,
        "india_holdout_report_fingerprint": baseline_report.report_fingerprint,
        "training_config_fingerprint": model_settings.fingerprint,
        "screen_policy_fingerprint": policy.fingerprint,
        "economic_policy_fingerprint": economics.fingerprint,
        "joint_screen_report_fingerprint": None,
        "xgboost_runtime_version": None,
        "evaluated_holdout_rows": baseline_report.holdout_rows,
        "catboost_net_return_after_costs": None,
        "xgboost_net_return_after_costs": None,
        "total_net_uplift_after_costs": None,
        "catboost_brier_score": None,
        "xgboost_brier_score": None,
        "catboost_max_drawdown": None,
        "xgboost_max_drawdown": None,
        "xgboost_fit_ms": None,
        "xgboost_inference_ms_per_1000_rows": None,
        "status": "INCONCLUSIVE",
        "reason_codes": [],
        "external_market_source_verified": False,
        "holdout_pre_registration_verified": False,
        "multiple_trial_selection_adjusted": False,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    if baseline_report.status == "INCONCLUSIVE":
        result["reason_codes"] = [
            f"INDEPENDENT_HOLDOUT_{reason}" for reason in baseline_report.reason_codes
        ]
        return XGBoostConditionalBenchmarkReport(
            **result, report_fingerprint=canonical_fingerprint(result)
        )

    train = _build_examples(training_export, target)
    holdout = _build_examples(holdout_export, target)
    baseline_probabilities = _predict_catboost(
        train, holdout, holdout_settings.feature_names, holdout_settings.seed
    )
    challenger, version, fit_ms, predict_ms = _predict_xgboost(
        train, holdout, holdout_settings.feature_names, holdout_settings.seed, model_settings
    )
    for probabilities in (baseline_probabilities, challenger):
        if len(probabilities) != len(holdout) or any(
            not math.isfinite(p) or not 0.0 <= p <= 1.0 for p in probabilities
        ):
            raise ValueError("XGBoost benchmark probabilities must be finite in [0, 1]")
    if not all(math.isfinite(v) and v >= 0 for v in (fit_ms, predict_ms)):
        raise ValueError("XGBoost runtime measurements must be finite and nonnegative")

    per_row_cost = target.round_trip_cost_bps / 10_000
    base_net = _series_returns(
        holdout, baseline_probabilities,
        threshold=holdout_settings.probability_threshold, cost=per_row_cost,
    )
    challenger_net = _series_returns(
        holdout, challenger,
        threshold=holdout_settings.probability_threshold, cost=per_row_cost,
    )
    base_result = next(
        model for model in baseline_report.results if model.model_family == "CATBOOST"
    )
    if abs(sum(base_net) - base_result.net_return_after_assumed_costs) > 1e-9:
        raise ValueError("CatBoost baseline replay differs from accepted holdout evidence")

    observations = [example.example_fingerprint for example in holdout]
    times = [example.as_of for example in holdout]
    cost_binding = canonical_fingerprint({
        "india_fee_book": fee_book.book_fingerprint,
        "round_trip_cost_bps": target.round_trip_cost_bps,
        "assumed_slippage_bps": holdout_settings.assumed_round_trip_spread_slippage_bps,
    })
    for_training = baseline_report.training_example_membership_fingerprint
    fold_binding = canonical_fingerprint({
        "training_membership": for_training,
        "holdout_membership": baseline_report.holdout_example_membership_fingerprint,
    })
    source_ids = {
        family: canonical_fingerprint({
            "holdout": baseline_report.report_fingerprint,
            "model_family": family,
            "model_version": model_version,
            "training_configuration": fingerprint,
        })
        for family, model_version, fingerprint in (
            ("catboost", "catboost-binary.v1", holdout_settings.fingerprint),
            ("xgboost", f"xgboost-binary.v1+{version}", model_settings.fingerprint),
        )
    }
    model_versions = {
        "catboost": "catboost-binary.v1",
        "xgboost": f"xgboost-binary.v1+{version}",
    }

    def aligned_series(
        candidate: str, probabilities: list[float], returns: list[float]
    ):
        lineage = {
            "candidate_id": candidate,
            "model_version_ref": model_versions[candidate],
            "source_result_fingerprint": source_ids[candidate],
            "observation_fingerprints": observations,
        }
        predictions = build_prediction_series(**lineage, probabilities=probabilities)
        outcomes = create_net_return_series(
            **lineage,
            target_binding_fingerprint=target.fingerprint,
            fold_binding_fingerprint=fold_binding,
            economic_cost_binding_fingerprint=cost_binding,
            decision_times=times,
            net_returns_after_costs=returns,
        )
        return predictions, outcomes

    baseline_prediction, baseline_returns = aligned_series(
        "catboost", baseline_probabilities, base_net
    )
    xgboost_prediction, xgboost_returns = aligned_series(
        "xgboost", challenger, challenger_net
    )
    joint = evaluate_joint_diversity_economic_gate(
        baseline_prediction, xgboost_prediction,
        baseline_returns, xgboost_returns, economic_config=economics,
    )
    base_brier = float(brier_score_loss([row.label for row in holdout], baseline_probabilities))
    challenger_brier = float(brier_score_loss([row.label for row in holdout], challenger))
    base_drawdown = _max_drawdown(base_net)
    challenger_drawdown = _max_drawdown(challenger_net)
    latency_per_1000 = predict_ms * 1000 / len(holdout)
    reasons = [f"JOINT_{reason}" for reason in joint.reason_codes]
    if challenger_brier > base_brier + policy.maximum_brier_regression:
        reasons.append("CALIBRATION_REGRESSION")
    if challenger_drawdown > base_drawdown + policy.maximum_drawdown_regression:
        reasons.append("DRAWDOWN_REGRESSION")
    if latency_per_1000 > policy.max_inference_ms_per_1000_rows:
        reasons.append("INFERENCE_LATENCY_BUDGET_EXCEEDED")
    if joint.status == "INCONCLUSIVE":
        status = "INCONCLUSIVE"
    elif reasons:
        status = "NOT_ADDITIVE"
    else:
        status = "RESEARCH_CANDIDATE_ONLY"
    result.update({
        "joint_screen_report_fingerprint": joint.report_fingerprint,
        "xgboost_runtime_version": version,
        "catboost_net_return_after_costs": sum(base_net),
        "xgboost_net_return_after_costs": sum(challenger_net),
        "total_net_uplift_after_costs": sum(challenger_net) - sum(base_net),
        "catboost_brier_score": base_brier,
        "xgboost_brier_score": challenger_brier,
        "catboost_max_drawdown": base_drawdown,
        "xgboost_max_drawdown": challenger_drawdown,
        "xgboost_fit_ms": fit_ms,
        "xgboost_inference_ms_per_1000_rows": latency_per_1000,
        "status": status,
        "reason_codes": sorted(set(reasons)),
    })
    return XGBoostConditionalBenchmarkReport(
        **result, report_fingerprint=canonical_fingerprint(result)
    )
