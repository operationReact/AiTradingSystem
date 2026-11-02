from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .india_context import IndiaMarketContextSnapshot

REGIME_SNAPSHOT_CONTRACT_VERSION = "regime-snapshot.v1"
REGIME_LABELS = (
    "TREND_UP",
    "TREND_DOWN",
    "RANGE_LOW_VOL",
    "STRESS_HIGH_VOL",
)
REQUIRED_CONTEXT_KEYS = (
    "benchmark.NIFTY50.return_1d",
    "breadth.NSE.net_breadth",
    "breadth.NSE.return_dispersion",
    "volatility.INDIA_VIX.close",
)

RegimeLabel = Literal[
    "TREND_UP",
    "TREND_DOWN",
    "RANGE_LOW_VOL",
    "STRESS_HIGH_VOL",
]
RegimeMethod = Literal[
    "HEURISTIC_BASELINE",
    "HMM_CHALLENGER",
    "CHANGE_POINT_CHALLENGER",
]
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value


def _finite(value: float, *, name: str) -> float:
    if not math.isfinite(value):
        raise ValueError(f"{name} must be finite")
    return value


def _clip(value: float, lower: float = -3.0, upper: float = 3.0) -> float:
    return max(lower, min(upper, value))


class HeuristicRegimeConfig(BaseModel):
    """Deterministic V3 baseline config for causal India market-regime evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    return_scale: Annotated[float, Field(gt=0.0)] = 0.015
    breadth_scale: Annotated[float, Field(gt=0.0)] = 0.35
    vix_center: Annotated[float, Field(gt=0.0)] = 18.0
    vix_scale: Annotated[float, Field(gt=0.0)] = 8.0
    dispersion_center: Annotated[float, Field(ge=0.0)] = 0.012
    dispersion_scale: Annotated[float, Field(gt=0.0)] = 0.012
    softmax_temperature: Annotated[float, Field(gt=0.0, le=10.0)] = 1.0

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class RegimeSnapshot(BaseModel):
    """Versioned probabilistic regime evidence. It never authorizes capital."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["regime-snapshot.v1"] = REGIME_SNAPSHOT_CONTRACT_VERSION
    as_of: datetime
    data_cutoff: datetime
    method: RegimeMethod
    dominant_regime: RegimeLabel
    probabilities: dict[str, float]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    evidence: dict[str, float]
    input_fingerprint: Hash64
    config_fingerprint: Hash64
    model_fingerprint: Hash64 | None = None
    training_data_cutoff: datetime | None = None
    snapshot_fingerprint: Hash64
    status: Literal["RESEARCH_REGIME_EVIDENCE_ONLY"] = "RESEARCH_REGIME_EVIDENCE_ONLY"
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff", "training_data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime | None) -> datetime | None:
        return _require_aware(value) if value is not None else None

    @field_validator("probabilities")
    @classmethod
    def probabilities_must_be_valid(cls, value: dict[str, float]) -> dict[str, float]:
        if tuple(value) != REGIME_LABELS:
            raise ValueError("regime probabilities must contain the canonical ordered label set")
        invalid = any(
            not math.isfinite(probability) or probability < 0.0 or probability > 1.0
            for probability in value.values()
        )
        if invalid:
            raise ValueError("regime probabilities must be finite values in [0, 1]")
        if not math.isclose(sum(value.values()), 1.0, rel_tol=0.0, abs_tol=1e-12):
            raise ValueError("regime probabilities must sum to 1")
        return value

    @field_validator("evidence")
    @classmethod
    def evidence_must_be_valid(cls, value: dict[str, float]) -> dict[str, float]:
        if not value:
            raise ValueError("regime evidence cannot be empty")
        if list(value) != sorted(value):
            raise ValueError("regime evidence must be deterministically ordered")
        if any(not math.isfinite(metric) for metric in value.values()):
            raise ValueError("regime evidence must be finite")
        return value

    @model_validator(mode="after")
    def validate_snapshot(self) -> RegimeSnapshot:
        if self.data_cutoff > self.as_of:
            raise ValueError("data_cutoff cannot exceed as_of")
        if self.training_data_cutoff is not None and self.training_data_cutoff > self.as_of:
            raise ValueError("training_data_cutoff cannot exceed as_of")
        if self.method == "HEURISTIC_BASELINE":
            if self.model_fingerprint is not None or self.training_data_cutoff is not None:
                raise ValueError("heuristic baseline cannot declare trained-model lineage")
        elif self.model_fingerprint is None or self.training_data_cutoff is None:
            raise ValueError("challenger regime snapshots require trained-model lineage")
        maximum = max(self.probabilities.values())
        expected_dominant = next(
            label for label in REGIME_LABELS if self.probabilities[label] == maximum
        )
        if self.dominant_regime != expected_dominant:
            raise ValueError("dominant_regime must match the deterministic probability argmax")
        if not math.isclose(self.confidence, maximum, rel_tol=0.0, abs_tol=1e-12):
            raise ValueError("confidence must equal the dominant regime probability")
        return self


def _softmax(scores: dict[str, float], *, temperature: float) -> dict[str, float]:
    scaled = {label: scores[label] / temperature for label in REGIME_LABELS}
    pivot = max(scaled.values())
    weights = {label: math.exp(scaled[label] - pivot) for label in REGIME_LABELS}
    denominator = sum(weights.values())
    probabilities = {label: weights[label] / denominator for label in REGIME_LABELS}
    residual = 1.0 - sum(probabilities.values())
    probabilities[REGIME_LABELS[-1]] += residual
    return probabilities


def build_heuristic_regime_snapshot(
    *,
    context: IndiaMarketContextSnapshot,
    config: HeuristicRegimeConfig | None = None,
) -> RegimeSnapshot:
    """Build a causal soft heuristic baseline from the frozen V3 India context contract."""

    config = config or HeuristicRegimeConfig()
    missing = [key for key in REQUIRED_CONTEXT_KEYS if key not in context.metrics]
    if missing:
        raise ValueError(f"missing required regime context metrics: {', '.join(missing)}")
    if context.data_cutoff > context.as_of:
        raise ValueError("market context is not causal")

    return_1d = _finite(
        context.metrics["benchmark.NIFTY50.return_1d"],
        name="benchmark.NIFTY50.return_1d",
    )
    net_breadth = _finite(
        context.metrics["breadth.NSE.net_breadth"],
        name="breadth.NSE.net_breadth",
    )
    dispersion = _finite(
        context.metrics["breadth.NSE.return_dispersion"],
        name="breadth.NSE.return_dispersion",
    )
    india_vix = _finite(
        context.metrics["volatility.INDIA_VIX.close"],
        name="volatility.INDIA_VIX.close",
    )
    if dispersion < 0.0:
        raise ValueError("breadth.NSE.return_dispersion cannot be negative")
    if india_vix <= 0.0:
        raise ValueError("volatility.INDIA_VIX.close must be positive")
    if not -1.0 <= net_breadth <= 1.0:
        raise ValueError("breadth.NSE.net_breadth must be in [-1, 1]")

    return_state = _clip(return_1d / config.return_scale)
    breadth_state = _clip(net_breadth / config.breadth_scale)
    volatility_state = _clip((india_vix - config.vix_center) / config.vix_scale)
    dispersion_state = _clip(
        (dispersion - config.dispersion_center) / config.dispersion_scale
    )
    calm_state = -volatility_state
    positive_dispersion = max(dispersion_state, 0.0)
    quiet_return = 1.0 - min(abs(return_state), 1.5)
    balanced_breadth = 1.0 - min(abs(breadth_state), 1.5)

    scores = {
        "TREND_UP": (
            1.20 * return_state
            + 0.90 * breadth_state
            + 0.25 * calm_state
            - 0.15 * positive_dispersion
        ),
        "TREND_DOWN": (
            -1.20 * return_state
            - 0.90 * breadth_state
            + 0.25 * volatility_state
            + 0.15 * positive_dispersion
        ),
        "RANGE_LOW_VOL": (
            0.80 * quiet_return
            + 0.80 * balanced_breadth
            + 0.45 * calm_state
            - 0.25 * positive_dispersion
        ),
        "STRESS_HIGH_VOL": (
            1.20 * volatility_state
            + 1.00 * dispersion_state
            + 0.25 * abs(return_state)
            + 0.15 * abs(breadth_state)
        ),
    }
    probabilities = _softmax(scores, temperature=config.softmax_temperature)
    maximum = max(probabilities.values())
    dominant = next(label for label in REGIME_LABELS if probabilities[label] == maximum)
    evidence = dict(
        sorted(
            {
                "benchmark.NIFTY50.return_1d": return_1d,
                "breadth.NSE.net_breadth": net_breadth,
                "breadth.NSE.return_dispersion": dispersion,
                "normalized.breadth": breadth_state,
                "normalized.dispersion": dispersion_state,
                "normalized.return_1d": return_state,
                "normalized.volatility": volatility_state,
                "volatility.INDIA_VIX.close": india_vix,
            }.items()
        )
    )
    payload = {
        "contract_version": REGIME_SNAPSHOT_CONTRACT_VERSION,
        "as_of": context.as_of.isoformat(),
        "data_cutoff": context.data_cutoff.isoformat(),
        "method": "HEURISTIC_BASELINE",
        "dominant_regime": dominant,
        "probabilities": probabilities,
        "confidence": maximum,
        "evidence": evidence,
        "input_fingerprint": context.snapshot_fingerprint,
        "config_fingerprint": config.fingerprint,
        "model_fingerprint": None,
        "training_data_cutoff": None,
        "status": "RESEARCH_REGIME_EVIDENCE_ONLY",
        "can_authorize_capital": False,
    }
    return RegimeSnapshot(
        **payload,
        snapshot_fingerprint=canonical_fingerprint(payload),
    )
