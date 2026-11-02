from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import IndiaMarketContextSnapshot
from .regime import (
    REGIME_LABELS,
    HeuristicRegimeConfig,
    RegimeSnapshot,
    build_heuristic_regime_snapshot,
)
from .regime_hmm import HMMRegimeConfig, build_hmm_regime_snapshot, train_hmm_regime_model

REGIME_EVALUATION_VERSION = "regime-walk-forward-evaluation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Comparison = Literal[
    "HMM_OUTPERFORMS",
    "HMM_COMPLEMENTS_HEURISTIC",
    "HEURISTIC_HOLDS",
    "INCONCLUSIVE",
]


class RegimeEvaluationConfig(BaseModel):
    """Causal evaluation policy; it cannot promote a regime model."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_training_samples: Annotated[int, Field(ge=40, le=5_000)] = 60
    step_periods: Annotated[int, Field(ge=1, le=500)] = 5
    transaction_cost_bps: Annotated[float, Field(ge=0.0, le=100.0)] = 5.0
    outperform_net_return_gain: Annotated[float, Field(ge=0.0, le=0.25)] = 0.002
    complement_drawdown_gain: Annotated[float, Field(ge=0.0, le=0.25)] = 0.002
    complement_max_net_return_loss: Annotated[float, Field(ge=0.0, le=0.25)] = 0.002
    heuristic: HeuristicRegimeConfig = HeuristicRegimeConfig()
    hmm: HMMRegimeConfig = HMMRegimeConfig()

    @model_validator(mode="after")
    def validate_training_window(self) -> RegimeEvaluationConfig:
        if self.hmm.minimum_training_samples > self.minimum_training_samples:
            raise ValueError(
                "evaluation minimum_training_samples must satisfy the HMM minimum"
            )
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class RegimeTransitionDiagnostics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    snapshot_count: Annotated[int, Field(ge=1)]
    transition_count: Annotated[int, Field(ge=0)]
    dominant_regime_changes: Annotated[int, Field(ge=0)]
    dominant_flip_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    mean_probability_turnover: Annotated[float, Field(ge=0.0, le=1.0)]
    mean_confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    mean_normalized_entropy: Annotated[float, Field(ge=0.0, le=1.0)]


class RegimeEconomicMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    observation_count: Annotated[int, Field(ge=1)]
    mean_absolute_exposure: Annotated[float, Field(ge=0.0, le=1.0)]
    gross_compounded_return: float
    net_compounded_return: float
    worst_drawdown: Annotated[float, Field(ge=0.0, le=1.0)]
    total_turnover: Annotated[float, Field(ge=0.0)]
    total_friction_cost: Annotated[float, Field(ge=0.0)]
    positive_net_period_rate: Annotated[float, Field(ge=0.0, le=1.0)]


class RegimeEvaluationObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    decision_at: datetime
    realized_at: datetime
    realized_next_return: float
    heuristic_snapshot_fingerprint: Hash64
    hmm_snapshot_fingerprint: Hash64
    hmm_model_fingerprint: Hash64
    heuristic_signed_exposure: Annotated[float, Field(ge=-1.0, le=1.0)]
    hmm_signed_exposure: Annotated[float, Field(ge=-1.0, le=1.0)]
    observation_fingerprint: Hash64

    @field_validator("decision_at", "realized_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluation timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_order(self) -> RegimeEvaluationObservation:
        if self.realized_at <= self.decision_at:
            raise ValueError("realized_at must be after decision_at")
        if not math.isfinite(self.realized_next_return):
            raise ValueError("realized_next_return must be finite")
        return self


class RegimeWalkForwardEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evaluation_version: Literal["regime-walk-forward-evaluation.v1"] = (
        REGIME_EVALUATION_VERSION
    )
    config_fingerprint: Hash64
    start_at: datetime
    end_at: datetime
    observations: list[RegimeEvaluationObservation]
    heuristic_transition: RegimeTransitionDiagnostics
    hmm_transition: RegimeTransitionDiagnostics
    heuristic_economic: RegimeEconomicMetrics
    hmm_economic: RegimeEconomicMetrics
    comparison: Comparison
    evaluation_fingerprint: Hash64
    truth_assumption: Literal["NO_DETERMINISTIC_REGIME_GROUND_TRUTH"] = (
        "NO_DETERMINISTIC_REGIME_GROUND_TRUTH"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("start_at", "end_at")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("evaluation timestamps must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_evaluation(self) -> RegimeWalkForwardEvaluation:
        if not self.observations:
            raise ValueError("walk-forward evaluation requires observations")
        if self.end_at < self.start_at:
            raise ValueError("evaluation end cannot precede start")
        return self


def _validate_contexts(
    contexts: Sequence[IndiaMarketContextSnapshot],
) -> list[IndiaMarketContextSnapshot]:
    if not contexts:
        raise ValueError("market contexts are required")
    ordered = sorted(contexts, key=lambda context: context.as_of)
    timestamps = [context.as_of for context in ordered]
    if len(set(timestamps)) != len(timestamps):
        raise ValueError("market contexts must have unique decision timestamps")
    for context in ordered:
        if context.as_of.tzinfo is None or context.as_of.utcoffset() is None:
            raise ValueError("market context timestamps must be timezone-aware")
        if context.data_cutoff > context.as_of:
            raise ValueError("market context is not causal")
        value = context.metrics.get("benchmark.NIFTY50.return_1d")
        if value is None or not math.isfinite(value):
            raise ValueError("finite benchmark.NIFTY50.return_1d is required")
    return ordered


def _signed_exposure(snapshot: RegimeSnapshot) -> float:
    return snapshot.probabilities["TREND_UP"] - snapshot.probabilities["TREND_DOWN"]


def _normalized_entropy(snapshot: RegimeSnapshot) -> float:
    entropy = -sum(
        probability * math.log(probability)
        for probability in snapshot.probabilities.values()
        if probability > 0.0
    )
    return entropy / math.log(len(REGIME_LABELS))


def _transition_diagnostics(
    snapshots: Sequence[RegimeSnapshot],
) -> RegimeTransitionDiagnostics:
    transitions = max(len(snapshots) - 1, 0)
    changes = sum(
        current.dominant_regime != previous.dominant_regime
        for previous, current in zip(snapshots, snapshots[1:], strict=False)
    )
    turnovers = [
        0.5
        * sum(
            abs(current.probabilities[label] - previous.probabilities[label])
            for label in REGIME_LABELS
        )
        for previous, current in zip(snapshots, snapshots[1:], strict=False)
    ]
    return RegimeTransitionDiagnostics(
        snapshot_count=len(snapshots),
        transition_count=transitions,
        dominant_regime_changes=changes,
        dominant_flip_rate=(changes / transitions) if transitions else 0.0,
        mean_probability_turnover=(sum(turnovers) / len(turnovers)) if turnovers else 0.0,
        mean_confidence=sum(snapshot.confidence for snapshot in snapshots) / len(snapshots),
        mean_normalized_entropy=(
            sum(_normalized_entropy(snapshot) for snapshot in snapshots) / len(snapshots)
        ),
    )


def _economic_metrics(
    exposures: Sequence[float],
    realized_returns: Sequence[float],
    *,
    transaction_cost_bps: float,
) -> RegimeEconomicMetrics:
    if len(exposures) != len(realized_returns) or not exposures:
        raise ValueError("exposures and realized returns must be equally populated")
    gross_capital = 1.0
    net_capital = 1.0
    peak = 1.0
    worst_drawdown = 0.0
    previous_exposure = 0.0
    turnover_total = 0.0
    friction_total = 0.0
    positive_periods = 0
    for exposure, realized_return in zip(exposures, realized_returns, strict=True):
        turnover = abs(exposure - previous_exposure)
        friction = turnover * transaction_cost_bps / 10_000.0
        gross_return = exposure * realized_return
        net_return = gross_return - friction
        if gross_return <= -1.0 or net_return <= -1.0:
            raise ValueError("evaluation return would make compounded capital non-positive")
        gross_capital *= 1.0 + gross_return
        net_capital *= 1.0 + net_return
        peak = max(peak, net_capital)
        worst_drawdown = max(worst_drawdown, (peak - net_capital) / peak)
        turnover_total += turnover
        friction_total += friction
        positive_periods += net_return > 0.0
        previous_exposure = exposure
    return RegimeEconomicMetrics(
        observation_count=len(exposures),
        mean_absolute_exposure=sum(abs(value) for value in exposures) / len(exposures),
        gross_compounded_return=gross_capital - 1.0,
        net_compounded_return=net_capital - 1.0,
        worst_drawdown=worst_drawdown,
        total_turnover=turnover_total,
        total_friction_cost=friction_total,
        positive_net_period_rate=positive_periods / len(exposures),
    )


def _comparison(
    *,
    heuristic: RegimeEconomicMetrics,
    hmm: RegimeEconomicMetrics,
    config: RegimeEvaluationConfig,
) -> Comparison:
    net_gain = hmm.net_compounded_return - heuristic.net_compounded_return
    drawdown_gain = heuristic.worst_drawdown - hmm.worst_drawdown
    if (
        net_gain >= config.outperform_net_return_gain
        and hmm.worst_drawdown <= heuristic.worst_drawdown
    ):
        return "HMM_OUTPERFORMS"
    if (
        drawdown_gain >= config.complement_drawdown_gain
        and net_gain >= -config.complement_max_net_return_loss
    ):
        return "HMM_COMPLEMENTS_HEURISTIC"
    if net_gain <= 0.0 and drawdown_gain <= 0.0:
        return "HEURISTIC_HOLDS"
    return "INCONCLUSIVE"


def evaluate_regime_models_walk_forward(
    contexts: Sequence[IndiaMarketContextSnapshot],
    *,
    config: RegimeEvaluationConfig | None = None,
) -> RegimeWalkForwardEvaluation:
    """Compare heuristic and HMM evidence without inventing deterministic regime labels."""

    config = config or RegimeEvaluationConfig()
    ordered = _validate_contexts(contexts)
    if len(ordered) < config.minimum_training_samples + 2:
        raise ValueError("insufficient contexts for regime walk-forward evaluation")

    heuristic_snapshots: list[RegimeSnapshot] = []
    hmm_snapshots: list[RegimeSnapshot] = []
    observations: list[RegimeEvaluationObservation] = []
    heuristic_exposures: list[float] = []
    hmm_exposures: list[float] = []
    realized_returns: list[float] = []

    last_target_index = len(ordered) - 2
    for target_index in range(
        config.minimum_training_samples,
        last_target_index + 1,
        config.step_periods,
    ):
        training = ordered[:target_index]
        target = ordered[target_index]
        realized = ordered[target_index + 1]
        model = train_hmm_regime_model(
            training_contexts=training,
            training_as_of=training[-1].as_of,
            config=config.hmm,
        )
        heuristic_snapshot = build_heuristic_regime_snapshot(
            context=target,
            config=config.heuristic,
        )
        hmm_snapshot = build_hmm_regime_snapshot(model=model, context=target)
        realized_return = float(realized.metrics["benchmark.NIFTY50.return_1d"])
        heuristic_exposure = _signed_exposure(heuristic_snapshot)
        hmm_exposure = _signed_exposure(hmm_snapshot)
        observation_payload = {
            "decision_at": target.as_of.isoformat(),
            "realized_at": realized.as_of.isoformat(),
            "realized_next_return": realized_return,
            "heuristic_snapshot_fingerprint": heuristic_snapshot.snapshot_fingerprint,
            "hmm_snapshot_fingerprint": hmm_snapshot.snapshot_fingerprint,
            "hmm_model_fingerprint": model.model_fingerprint,
            "heuristic_signed_exposure": heuristic_exposure,
            "hmm_signed_exposure": hmm_exposure,
        }
        observations.append(
            RegimeEvaluationObservation(
                **observation_payload,
                observation_fingerprint=canonical_fingerprint(observation_payload),
            )
        )
        heuristic_snapshots.append(heuristic_snapshot)
        hmm_snapshots.append(hmm_snapshot)
        heuristic_exposures.append(heuristic_exposure)
        hmm_exposures.append(hmm_exposure)
        realized_returns.append(realized_return)

    if not observations:
        raise ValueError("evaluation cadence produced no walk-forward observations")

    heuristic_transition = _transition_diagnostics(heuristic_snapshots)
    hmm_transition = _transition_diagnostics(hmm_snapshots)
    heuristic_economic = _economic_metrics(
        heuristic_exposures,
        realized_returns,
        transaction_cost_bps=config.transaction_cost_bps,
    )
    hmm_economic = _economic_metrics(
        hmm_exposures,
        realized_returns,
        transaction_cost_bps=config.transaction_cost_bps,
    )
    comparison = _comparison(heuristic=heuristic_economic, hmm=hmm_economic, config=config)
    payload = {
        "evaluation_version": REGIME_EVALUATION_VERSION,
        "config_fingerprint": config.fingerprint,
        "start_at": observations[0].decision_at.isoformat(),
        "end_at": observations[-1].realized_at.isoformat(),
        "observations": [item.model_dump(mode="json") for item in observations],
        "heuristic_transition": heuristic_transition.model_dump(mode="json"),
        "hmm_transition": hmm_transition.model_dump(mode="json"),
        "heuristic_economic": heuristic_economic.model_dump(mode="json"),
        "hmm_economic": hmm_economic.model_dump(mode="json"),
        "comparison": comparison,
        "truth_assumption": "NO_DETERMINISTIC_REGIME_GROUND_TRUTH",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return RegimeWalkForwardEvaluation(
        **payload,
        evaluation_fingerprint=canonical_fingerprint(payload),
    )
