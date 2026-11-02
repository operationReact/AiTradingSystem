from __future__ import annotations

import math
from collections.abc import Iterable
from datetime import datetime
from itertools import permutations
from typing import Annotated, Literal

import numpy as np
from hmmlearn.hmm import GaussianHMM
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sklearn.preprocessing import StandardScaler

from .fingerprints import canonical_fingerprint
from .india_context import IndiaMarketContextSnapshot
from .regime import (
    REGIME_LABELS,
    REGIME_SNAPSHOT_CONTRACT_VERSION,
    REQUIRED_CONTEXT_KEYS,
    Hash64,
    RegimeLabel,
    RegimeSnapshot,
)

HMM_REGIME_MODEL_CONTRACT_VERSION = "regime-hmm-model.v1"
FEATURE_ORDER = (
    "benchmark.NIFTY50.return_1d",
    "breadth.NSE.net_breadth",
    "breadth.NSE.return_dispersion",
    "volatility.INDIA_VIX.close",
)
SEMANTIC_INITIAL_MEANS = (
    (1.0, 1.0, -0.5, -0.5),
    (-1.0, -1.0, 0.0, 0.5),
    (0.0, 0.0, -0.5, -1.0),
    (0.0, -0.2, 1.0, 1.5),
)


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


def _finite(value: float, *, name: str) -> float:
    if not math.isfinite(value):
        raise ValueError(f"{name} must be finite")
    return value


def _round(value: float) -> float:
    return round(float(value), 12)


def _round_vector(values: Iterable[float]) -> list[float]:
    return [_round(value) for value in values]


def _round_matrix(values: np.ndarray) -> list[list[float]]:
    return [_round_vector(row) for row in values.tolist()]


def _context_vector(context: IndiaMarketContextSnapshot) -> list[float]:
    missing = [key for key in REQUIRED_CONTEXT_KEYS if key not in context.metrics]
    if missing:
        raise ValueError(f"missing required regime context metrics: {', '.join(missing)}")
    values = [_finite(context.metrics[key], name=key) for key in FEATURE_ORDER]
    if not -1.0 <= values[1] <= 1.0:
        raise ValueError("breadth.NSE.net_breadth must be in [-1, 1]")
    if values[2] < 0.0:
        raise ValueError("breadth.NSE.return_dispersion cannot be negative")
    if values[3] <= 0.0:
        raise ValueError("volatility.INDIA_VIX.close must be positive")
    return values


class HMMRegimeConfig(BaseModel):
    """Deterministic research configuration for the four-state Gaussian HMM."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_training_samples: Annotated[int, Field(ge=40, le=5_000)] = 60
    maximum_training_samples: Annotated[int, Field(ge=60, le=5_000)] = 756
    transition_self_probability: Annotated[float, Field(gt=0.25, lt=1.0)] = 0.88
    min_covar: Annotated[float, Field(gt=0.0, le=1.0)] = 1e-3
    n_iter: Annotated[int, Field(ge=10, le=1_000)] = 200
    tolerance: Annotated[float, Field(gt=0.0, le=0.1)] = 1e-4

    @model_validator(mode="after")
    def validate_window(self) -> HMMRegimeConfig:
        if self.maximum_training_samples < self.minimum_training_samples:
            raise ValueError("maximum_training_samples cannot be below the minimum")
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class HMMRegimeModel(BaseModel):
    """Serializable research-only HMM evidence used for causal inference."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["regime-hmm-model.v1"] = HMM_REGIME_MODEL_CONTRACT_VERSION
    training_as_of: datetime
    training_end_as_of: datetime
    training_data_cutoff: datetime
    training_samples: Annotated[int, Field(ge=1)]
    feature_order: list[str]
    scaler_mean: list[float]
    scaler_scale: list[float]
    start_probabilities: list[float]
    transition_matrix: list[list[float]]
    state_means: list[list[float]]
    state_covariances: list[list[float]]
    terminal_state_probabilities: list[float]
    state_labels: list[RegimeLabel]
    training_input_fingerprints: list[Hash64]
    config_fingerprint: Hash64
    converged: bool
    training_iterations: Annotated[int, Field(ge=1)]
    model_fingerprint: Hash64
    status: Literal["RESEARCH_HMM_CHALLENGER_ONLY"] = "RESEARCH_HMM_CHALLENGER_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("training_as_of", "training_end_as_of", "training_data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        return _require_aware(value)

    @field_validator(
        "scaler_mean",
        "scaler_scale",
        "start_probabilities",
        "terminal_state_probabilities",
    )
    @classmethod
    def vectors_must_be_finite(cls, value: list[float]) -> list[float]:
        if len(value) != len(REGIME_LABELS):
            raise ValueError("HMM vectors must have four entries")
        if any(not math.isfinite(item) for item in value):
            raise ValueError("HMM vectors must be finite")
        return value

    @field_validator("transition_matrix", "state_means", "state_covariances")
    @classmethod
    def matrices_must_be_finite(cls, value: list[list[float]]) -> list[list[float]]:
        size = len(REGIME_LABELS)
        if len(value) != size or any(len(row) != size for row in value):
            raise ValueError("HMM matrices must be 4x4")
        if any(not math.isfinite(item) for row in value for item in row):
            raise ValueError("HMM matrices must be finite")
        return value

    @model_validator(mode="after")
    def validate_model(self) -> HMMRegimeModel:
        size = len(REGIME_LABELS)
        if tuple(self.feature_order) != FEATURE_ORDER:
            raise ValueError("feature_order must match the canonical HMM feature order")
        if len(self.state_labels) != size or set(self.state_labels) != set(REGIME_LABELS):
            raise ValueError("state_labels must map each latent state to one canonical regime")
        if self.training_samples != len(self.training_input_fingerprints):
            raise ValueError("training sample count must match input fingerprints")
        if len(set(self.training_input_fingerprints)) != self.training_samples:
            raise ValueError("training context fingerprints must be unique")
        if self.training_end_as_of > self.training_as_of:
            raise ValueError("training sequence cannot exceed training_as_of")
        if self.training_data_cutoff > self.training_as_of:
            raise ValueError("training data cutoff cannot exceed training_as_of")
        if any(scale <= 0.0 for scale in self.scaler_scale):
            raise ValueError("scaler scales must be positive")
        if any(covariance <= 0.0 for row in self.state_covariances for covariance in row):
            raise ValueError("state covariances must be positive")
        for probabilities in (self.start_probabilities, self.terminal_state_probabilities):
            if any(value < 0.0 or value > 1.0 for value in probabilities):
                raise ValueError("HMM probabilities must be in [0, 1]")
            if not math.isclose(sum(probabilities), 1.0, rel_tol=0.0, abs_tol=1e-9):
                raise ValueError("HMM probability vectors must sum to 1")
        for row in self.transition_matrix:
            if any(value < 0.0 or value > 1.0 for value in row):
                raise ValueError("HMM transition probabilities must be in [0, 1]")
            if not math.isclose(sum(row), 1.0, rel_tol=0.0, abs_tol=1e-9):
                raise ValueError("HMM transition rows must sum to 1")
        return self


def _initial_transition_matrix(self_probability: float) -> np.ndarray:
    size = len(REGIME_LABELS)
    other = (1.0 - self_probability) / (size - 1)
    matrix = np.full((size, size), other, dtype=float)
    np.fill_diagonal(matrix, self_probability)
    return matrix


def _diag_covariances(model: GaussianHMM) -> np.ndarray:
    covariances = np.asarray(model.covars_, dtype=float)
    if covariances.ndim == 3:
        return np.stack([np.diag(row) for row in covariances])
    if covariances.ndim != 2:
        raise ValueError("unexpected HMM covariance shape")
    return covariances


def _state_semantic_score(mean: np.ndarray, label: RegimeLabel) -> float:
    return_state, breadth_state, dispersion_state, volatility_state = mean.tolist()
    positive_dispersion = max(dispersion_state, 0.0)
    if label == "TREND_UP":
        return (
            1.20 * return_state
            + 0.90 * breadth_state
            - 0.25 * volatility_state
            - 0.15 * positive_dispersion
        )
    if label == "TREND_DOWN":
        return (
            -1.20 * return_state
            - 0.90 * breadth_state
            + 0.25 * volatility_state
            + 0.15 * positive_dispersion
        )
    if label == "RANGE_LOW_VOL":
        return (
            -0.80 * abs(return_state)
            - 0.80 * abs(breadth_state)
            - 0.45 * volatility_state
            - 0.25 * dispersion_state
        )
    return (
        1.20 * volatility_state
        + 1.00 * dispersion_state
        + 0.25 * abs(return_state)
        + 0.15 * abs(breadth_state)
    )


def _semantic_state_labels(means: np.ndarray) -> list[RegimeLabel]:
    best_labels: tuple[RegimeLabel, ...] | None = None
    best_score = -math.inf
    for candidate in permutations(REGIME_LABELS):
        score = sum(
            _state_semantic_score(means[state], candidate[state])
            for state in range(len(REGIME_LABELS))
        )
        if score > best_score + 1e-12:
            best_labels = candidate
            best_score = score
    if best_labels is None:
        raise ValueError("unable to map HMM latent states to semantic regime labels")
    return list(best_labels)


def train_hmm_regime_model(
    *,
    training_contexts: Iterable[IndiaMarketContextSnapshot],
    training_as_of: datetime,
    config: HMMRegimeConfig | None = None,
) -> HMMRegimeModel:
    """Fit a deterministic HMM from contexts that existed by training_as_of."""

    config = config or HMMRegimeConfig()
    training_as_of = _require_aware(training_as_of)
    contexts = [context for context in training_contexts if context.as_of <= training_as_of]
    contexts.sort(key=lambda context: context.as_of)
    if len(contexts) < config.minimum_training_samples:
        raise ValueError("insufficient causal HMM training contexts")
    as_of_values = [context.as_of for context in contexts]
    if len(set(as_of_values)) != len(as_of_values):
        raise ValueError("HMM training contexts must have unique decision timestamps")
    for context in contexts:
        if context.data_cutoff > context.as_of:
            raise ValueError("HMM training context is not causal")
        if context.data_cutoff > training_as_of:
            raise ValueError("HMM training evidence exceeds training_as_of")

    selected = contexts[-config.maximum_training_samples :]
    raw_matrix = np.asarray([_context_vector(context) for context in selected], dtype=float)
    scaler = StandardScaler()
    matrix = scaler.fit_transform(raw_matrix)

    size = len(REGIME_LABELS)
    model = GaussianHMM(
        n_components=size,
        covariance_type="diag",
        min_covar=config.min_covar,
        n_iter=config.n_iter,
        tol=config.tolerance,
        init_params="",
        params="stmc",
        algorithm="map",
        implementation="log",
    )
    model.startprob_ = np.full(size, 1.0 / size, dtype=float)
    model.transmat_ = _initial_transition_matrix(config.transition_self_probability)
    model.means_ = np.asarray(SEMANTIC_INITIAL_MEANS, dtype=float)
    model.covars_ = np.ones((size, len(FEATURE_ORDER)), dtype=float)
    model.fit(matrix)

    covariances = _diag_covariances(model)
    terminal = np.asarray(model.predict_proba(matrix)[-1], dtype=float)
    terminal = terminal / terminal.sum()
    state_labels = _semantic_state_labels(np.asarray(model.means_, dtype=float))
    training_fingerprints = [context.snapshot_fingerprint for context in selected]
    training_data_cutoff = max(context.data_cutoff for context in selected)
    training_end_as_of = selected[-1].as_of
    iterations = int(model.monitor_.iter)

    payload = {
        "contract_version": HMM_REGIME_MODEL_CONTRACT_VERSION,
        "training_as_of": training_as_of.isoformat(),
        "training_end_as_of": training_end_as_of.isoformat(),
        "training_data_cutoff": training_data_cutoff.isoformat(),
        "training_samples": len(selected),
        "feature_order": list(FEATURE_ORDER),
        "scaler_mean": _round_vector(scaler.mean_),
        "scaler_scale": _round_vector(scaler.scale_),
        "start_probabilities": _round_vector(model.startprob_),
        "transition_matrix": _round_matrix(np.asarray(model.transmat_, dtype=float)),
        "state_means": _round_matrix(np.asarray(model.means_, dtype=float)),
        "state_covariances": _round_matrix(covariances),
        "terminal_state_probabilities": _round_vector(terminal),
        "state_labels": state_labels,
        "training_input_fingerprints": training_fingerprints,
        "config_fingerprint": config.fingerprint,
        "converged": bool(model.monitor_.converged),
        "training_iterations": iterations,
        "status": "RESEARCH_HMM_CHALLENGER_ONLY",
        "can_authorize_capital": False,
    }
    return HMMRegimeModel(
        **payload,
        model_fingerprint=canonical_fingerprint(payload),
    )


def _latent_posterior(model: HMMRegimeModel, context: IndiaMarketContextSnapshot) -> np.ndarray:
    raw = np.asarray(_context_vector(context), dtype=float)
    mean = np.asarray(model.scaler_mean, dtype=float)
    scale = np.asarray(model.scaler_scale, dtype=float)
    observation = (raw - mean) / scale
    transition = np.asarray(model.transition_matrix, dtype=float)
    terminal = np.asarray(model.terminal_state_probabilities, dtype=float)
    prior = terminal @ transition
    state_means = np.asarray(model.state_means, dtype=float)
    covariances = np.asarray(model.state_covariances, dtype=float)

    log_weights: list[float] = []
    for state in range(len(REGIME_LABELS)):
        variance = covariances[state]
        delta = observation - state_means[state]
        log_emission = -0.5 * float(
            np.sum(np.log(2.0 * math.pi * variance) + (delta * delta) / variance)
        )
        log_prior = math.log(max(float(prior[state]), 1e-300))
        log_weights.append(log_prior + log_emission)
    pivot = max(log_weights)
    weights = np.asarray([math.exp(value - pivot) for value in log_weights], dtype=float)
    return weights / weights.sum()


def build_hmm_regime_snapshot(
    *,
    model: HMMRegimeModel,
    context: IndiaMarketContextSnapshot,
) -> RegimeSnapshot:
    """Infer the next causal regime distribution from an already-trained challenger."""

    if context.data_cutoff > context.as_of:
        raise ValueError("market context is not causal")
    if model.training_as_of >= context.as_of:
        raise ValueError("HMM inference must be strictly after training_as_of")
    if model.training_data_cutoff >= context.as_of:
        raise ValueError("HMM training data must precede inference decision time")

    latent = _latent_posterior(model, context)
    probabilities_by_label = {
        label: float(latent[state]) for state, label in enumerate(model.state_labels)
    }
    probabilities = {label: probabilities_by_label[label] for label in REGIME_LABELS}
    probabilities[REGIME_LABELS[-1]] += 1.0 - sum(probabilities.values())
    maximum = max(probabilities.values())
    dominant = next(label for label in REGIME_LABELS if probabilities[label] == maximum)
    raw = _context_vector(context)
    evidence = dict(
        sorted(
            {
                **dict(zip(FEATURE_ORDER, raw, strict=True)),
                "hmm.converged": float(model.converged),
                "hmm.training_iterations": float(model.training_iterations),
                "hmm.training_samples": float(model.training_samples),
            }.items()
        )
    )
    data_cutoff = max(context.data_cutoff, model.training_data_cutoff)
    payload = {
        "contract_version": REGIME_SNAPSHOT_CONTRACT_VERSION,
        "as_of": context.as_of.isoformat(),
        "data_cutoff": data_cutoff.isoformat(),
        "method": "HMM_CHALLENGER",
        "dominant_regime": dominant,
        "probabilities": probabilities,
        "confidence": maximum,
        "evidence": evidence,
        "input_fingerprint": context.snapshot_fingerprint,
        "config_fingerprint": model.config_fingerprint,
        "model_fingerprint": model.model_fingerprint,
        "training_data_cutoff": model.training_data_cutoff.isoformat(),
        "status": "RESEARCH_REGIME_EVIDENCE_ONLY",
        "can_authorize_capital": False,
    }
    return RegimeSnapshot(
        **payload,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
