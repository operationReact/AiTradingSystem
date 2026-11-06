"""V4-07 research-only causal anomaly guard for the inherited India regime stack.

Scikit-learn Isolation Forest is a *challenger*, not a truth label or signal
generator. Its only permitted output is an advisory reduction of eligibility.
This in-memory model is not a portable deployment artifact.
"""
from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Annotated, Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

from .fingerprints import canonical_fingerprint
from .india_context import IndiaMarketContextSnapshot
from .regime_hmm import FEATURE_ORDER

CONTRACT_VERSION = "india-regime-anomaly-isolation.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class IsolationAnomalyConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    minimum_training_samples: Annotated[int, Field(ge=32, le=5_000)] = 50
    maximum_training_samples: Annotated[int, Field(ge=50, le=5_000)] = 500
    trees: Annotated[int, Field(ge=16, le=200)] = 64
    max_tree_samples: Annotated[int, Field(ge=16, le=512)] = 128
    upper_anomaly_quantile: Annotated[float, Field(ge=0.90, le=0.999)] = 0.95
    max_feature_age_minutes: Annotated[int, Field(ge=1, le=1_440)] = 180
    random_seed: Annotated[int, Field(ge=0, le=2**31 - 1)] = 31

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class IsolationAnomalyModelEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-regime-anomaly-isolation.v1"] = CONTRACT_VERSION
    training_as_of: datetime
    latest_training_observation: datetime
    training_data_cutoff: datetime
    training_samples: Annotated[int, Field(ge=1)]
    feature_order: list[str] = Field(min_length=4, max_length=4)
    source_snapshot_fingerprints: list[Hash64] = Field(min_length=1, max_length=5_000)
    config_fingerprint: Hash64
    fitted_tree_fingerprint: Hash64
    fitted_scaler_fingerprint: Hash64
    anomaly_score_threshold: float
    model_fingerprint: Hash64
    status: Literal["RESEARCH_ISOLATION_FOREST_CHALLENGER"] = (
        "RESEARCH_ISOLATION_FOREST_CHALLENGER"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("training_as_of", "latest_training_observation", "training_data_cutoff")
    @classmethod
    def aware_times(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("training timestamps must be timezone-aware")
        return value

    @field_validator("anomaly_score_threshold")
    @classmethod
    def finite_threshold(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("anomaly threshold must be finite")
        return value


@dataclass(frozen=True)
class FittedIsolationAnomaly:
    """Non-serializable, local research runtime paired to immutable evidence."""

    evidence: IsolationAnomalyModelEvidence
    config: IsolationAnomalyConfig
    scaler: StandardScaler
    estimator: IsolationForest


class IsolationAnomalyObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-regime-anomaly-observation.v1"] = (
        "india-regime-anomaly-observation.v1"
    )
    decision_at: datetime
    data_cutoff: datetime
    source_snapshot_fingerprint: Hash64
    model_fingerprint: Hash64
    training_data_cutoff: datetime
    anomaly_score: float
    threshold: float
    elevated_anomaly: bool
    action: Literal["REDUCE_SIGNAL_ELIGIBILITY", "NO_ANOMALY_REDUCTION"]
    evidence_fingerprint: Hash64
    status: Literal["RESEARCH_ANOMALY_ADVISORY_ONLY"] = (
        "RESEARCH_ANOMALY_ADVISORY_ONLY"
    )
    model_promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_at", "data_cutoff", "training_data_cutoff")
    @classmethod
    def aware_times(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("anomaly timestamps must be timezone-aware")
        return value


def _context_vector(
    snapshot: IndiaMarketContextSnapshot, *, max_age_minutes: int
) -> list[float]:
    checked = IndiaMarketContextSnapshot.model_validate(snapshot.model_dump(mode="python"))
    if checked.as_of - checked.data_cutoff > timedelta(minutes=max_age_minutes):
        raise ValueError("stale India context cannot enter anomaly research")
    if any(checked.quality_flags.get(key) for key in FEATURE_ORDER):
        raise ValueError("flagged India context cannot enter anomaly research")
    missing = [key for key in FEATURE_ORDER if key not in checked.metrics]
    if missing:
        raise ValueError("missing required India regime features")
    values = [checked.metrics[key] for key in FEATURE_ORDER]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("non-finite India context feature")
    if not -1.0 <= values[1] <= 1.0 or values[2] < 0.0 or values[3] <= 0.0:
        raise ValueError("invalid breadth, dispersion or VIX")
    return values


def _scaler_fingerprint(scaler: StandardScaler) -> str:
    """Keep the exact fitted preprocessing identity with the fitted trees."""
    return canonical_fingerprint({
        "mean": [round(float(value), 12) for value in scaler.mean_],
        "scale": [round(float(value), 12) for value in scaler.scale_],
        "n_features": int(scaler.n_features_in_),
    })


def _tree_fingerprint(estimator: IsolationForest) -> str:
    """Commit to exact fitted tree structure without unsafe pickle exchange."""
    return canonical_fingerprint([
        {
            "features": tree.tree_.feature.tolist(),
            "left": tree.tree_.children_left.tolist(),
            "right": tree.tree_.children_right.tolist(),
            "thresholds": [round(float(value), 12) for value in tree.tree_.threshold],
        }
        for tree in estimator.estimators_
    ])


def train_isolation_anomaly_challenger(
    contexts: Sequence[IndiaMarketContextSnapshot],
    *,
    training_as_of: datetime,
    config: IsolationAnomalyConfig | None = None,
) -> FittedIsolationAnomaly:
    cfg = config or IsolationAnomalyConfig()
    if training_as_of.tzinfo is None or training_as_of.utcoffset() is None:
        raise ValueError("training_as_of must be timezone-aware")
    eligible = sorted(
        (
            item for item in contexts
            if item.as_of <= training_as_of and item.data_cutoff <= training_as_of
        ),
        key=lambda item: (item.as_of, item.snapshot_fingerprint),
    )
    if len(eligible) > cfg.maximum_training_samples:
        eligible = eligible[-cfg.maximum_training_samples:]
    if len(eligible) < cfg.minimum_training_samples:
        raise ValueError("insufficient causal India context training samples")
    times = [item.as_of for item in eligible]
    if len(set(times)) != len(times):
        raise ValueError("training observations must have unique timestamps")
    input_rows = np.asarray([
        _context_vector(item, max_age_minutes=cfg.max_feature_age_minutes)
        for item in eligible
    ], dtype=np.float64)
    scaler = StandardScaler()
    transformed = scaler.fit_transform(input_rows)
    estimator = IsolationForest(
        n_estimators=cfg.trees,
        max_samples=min(cfg.max_tree_samples, len(eligible)),
        contamination="auto",
        bootstrap=False,
        random_state=cfg.random_seed,
        n_jobs=1,
    ).fit(transformed)
    scores = -estimator.score_samples(transformed)
    threshold = float(np.quantile(scores, cfg.upper_anomaly_quantile))
    tree_fingerprint = _tree_fingerprint(estimator)
    payload = {
        "contract_version": CONTRACT_VERSION,
        "training_as_of": training_as_of.isoformat(),
        "latest_training_observation": eligible[-1].as_of.isoformat(),
        "training_data_cutoff": max(item.data_cutoff for item in eligible).isoformat(),
        "training_samples": len(eligible),
        "feature_order": list(FEATURE_ORDER),
        "source_snapshot_fingerprints": [
            item.snapshot_fingerprint for item in eligible
        ],
        "config_fingerprint": cfg.fingerprint,
        "fitted_tree_fingerprint": tree_fingerprint,
        "fitted_scaler_fingerprint": _scaler_fingerprint(scaler),
        "anomaly_score_threshold": threshold,
        "status": "RESEARCH_ISOLATION_FOREST_CHALLENGER",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    evidence = IsolationAnomalyModelEvidence(
        **payload, model_fingerprint=canonical_fingerprint(payload)
    )
    return FittedIsolationAnomaly(
        evidence=evidence, config=cfg, scaler=scaler, estimator=estimator
    )


def score_isolation_anomaly(
    fitted: FittedIsolationAnomaly,
    snapshot: IndiaMarketContextSnapshot,
) -> IsolationAnomalyObservation:
    evidence = fitted.evidence
    if snapshot.as_of <= evidence.training_as_of:
        raise ValueError("anomaly scoring must be strictly after training cutoff")
    if snapshot.data_cutoff > snapshot.as_of:
        raise ValueError("anomaly evidence cannot include future information")
    if _tree_fingerprint(fitted.estimator) != evidence.fitted_tree_fingerprint:
        raise ValueError("fitted model tree identity changed since freeze")
    if _scaler_fingerprint(fitted.scaler) != evidence.fitted_scaler_fingerprint:
        raise ValueError("fitted model scaler identity changed since freeze")
    vector = _context_vector(
        snapshot, max_age_minutes=fitted.config.max_feature_age_minutes
    )
    transformed = fitted.scaler.transform(
        np.asarray([vector], dtype=np.float64)
    )
    score = float(-fitted.estimator.score_samples(transformed)[0])
    if not math.isfinite(score):
        raise ValueError("anomaly score must be finite")
    elevated = score > evidence.anomaly_score_threshold
    payload = {
        "contract_version": "india-regime-anomaly-observation.v1",
        "decision_at": snapshot.as_of.isoformat(),
        "data_cutoff": snapshot.data_cutoff.isoformat(),
        "source_snapshot_fingerprint": snapshot.snapshot_fingerprint,
        "model_fingerprint": evidence.model_fingerprint,
        "training_data_cutoff": evidence.training_data_cutoff.isoformat(),
        "anomaly_score": score,
        "threshold": evidence.anomaly_score_threshold,
        "elevated_anomaly": elevated,
        "action": (
            "REDUCE_SIGNAL_ELIGIBILITY" if elevated else "NO_ANOMALY_REDUCTION"
        ),
        "status": "RESEARCH_ANOMALY_ADVISORY_ONLY",
        "model_promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return IsolationAnomalyObservation(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )
