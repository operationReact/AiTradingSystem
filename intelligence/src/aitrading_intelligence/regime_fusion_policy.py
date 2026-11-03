from __future__ import annotations

import math
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .fusion import (
    V2_CHAMPION_FUSION_POLICY,
    FusionFamily,
    FusionRequest,
)
from .regime import REGIME_LABELS

REGIME_MANUAL_FUSION_POLICY_VERSION = "regime-conditioned-manual-fusion.v1"
POLICY_ID = "v3-regime-manual-v1"
FUSION_FAMILIES: tuple[FusionFamily, ...] = (
    "DETERMINISTIC",
    "QUANT",
    "GEMINI",
)


def _default_regime_weight_matrix() -> dict[str, dict[str, float]]:
    """Conservative manual challenger centered on the static 45/35/20 champion."""

    return {
        "TREND_UP": {
            "DETERMINISTIC": 0.40,
            "QUANT": 0.40,
            "GEMINI": 0.20,
        },
        "TREND_DOWN": {
            "DETERMINISTIC": 0.50,
            "QUANT": 0.30,
            "GEMINI": 0.20,
        },
        "RANGE_LOW_VOL": {
            "DETERMINISTIC": 0.35,
            "QUANT": 0.45,
            "GEMINI": 0.20,
        },
        "STRESS_HIGH_VOL": {
            "DETERMINISTIC": 0.55,
            "QUANT": 0.25,
            "GEMINI": 0.20,
        },
    }


class RegimeConditionedManualFusionConfig(BaseModel):
    """Versioned manual regime-to-family weight matrix for research comparison only."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["regime-conditioned-manual-fusion.v1"] = (
        REGIME_MANUAL_FUSION_POLICY_VERSION
    )
    regime_weights: dict[str, dict[str, float]] = Field(
        default_factory=_default_regime_weight_matrix
    )

    @model_validator(mode="after")
    def validate_matrix(self) -> RegimeConditionedManualFusionConfig:
        if tuple(self.regime_weights) != REGIME_LABELS:
            raise ValueError(
                "regime weight matrix must use canonical ordered regime labels"
            )
        expected_families = tuple(FUSION_FAMILIES)
        for label in REGIME_LABELS:
            row = self.regime_weights[label]
            if tuple(row) != expected_families:
                raise ValueError(
                    f"regime weight row {label} must use canonical ordered fusion families"
                )
            if any(
                not math.isfinite(weight) or weight < 0.0 or weight > 1.0
                for weight in row.values()
            ):
                raise ValueError(
                    "regime-conditioned weights must be finite values in [0, 1]"
                )
            if not math.isclose(
                sum(row.values()),
                1.0,
                rel_tol=0.0,
                abs_tol=1e-12,
            ):
                raise ValueError("each regime-conditioned weight row must sum to 1")

        uniform_blend = {
            family: sum(
                self.regime_weights[label][family] for label in REGIME_LABELS
            )
            / len(REGIME_LABELS)
            for family in FUSION_FAMILIES
        }
        champion_anchor = {
            "DETERMINISTIC": 0.45,
            "QUANT": 0.35,
            "GEMINI": 0.20,
        }
        if any(
            not math.isclose(
                uniform_blend[family],
                champion_anchor[family],
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for family in FUSION_FAMILIES
        ):
            raise ValueError(
                "uniform regime uncertainty must blend back to the static 45/35/20 champion"
            )
        return self

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class RegimeFusionWeightExplanation(BaseModel):
    """Reconstructable diagnostics for the manual challenger; never execution authority."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    policy_id: Literal["v3-regime-manual-v1"] = POLICY_ID
    config_fingerprint: str
    source_regime_fingerprint: str | None = None
    source_regime_age_seconds: int | None = Field(default=None, ge=0)
    regime_probabilities: dict[str, float] | None = None
    fallback_to_static: bool
    reason: Literal[
        "NO_REGIME_STATIC_FALLBACK",
        "STALE_REGIME_STATIC_FALLBACK",
        "FRESH_REGIME_PROBABILITY_BLEND",
    ]
    configured_weights: dict[str, float]
    can_authorize_capital: Literal[False] = False


class RegimeConditionedManualFusionPolicy:
    """Manual probabilistic regime challenger; the static V2 policy remains champion."""

    policy_id = POLICY_ID

    def __init__(
        self,
        config: RegimeConditionedManualFusionConfig | None = None,
    ) -> None:
        self.config = config or RegimeConditionedManualFusionConfig()

    def explain(self, request: FusionRequest) -> RegimeFusionWeightExplanation:
        static_weights = dict(V2_CHAMPION_FUSION_POLICY.configured_weights(request))
        regime = request.regime
        if regime is None:
            return RegimeFusionWeightExplanation(
                config_fingerprint=self.config.fingerprint,
                fallback_to_static=True,
                reason="NO_REGIME_STATIC_FALLBACK",
                configured_weights=static_weights,
            )

        age_seconds = max(0, int((request.as_of - regime.as_of).total_seconds()))
        if age_seconds > request.max_age_seconds:
            return RegimeFusionWeightExplanation(
                config_fingerprint=self.config.fingerprint,
                source_regime_fingerprint=regime.snapshot_fingerprint,
                source_regime_age_seconds=age_seconds,
                regime_probabilities=dict(regime.probabilities),
                fallback_to_static=True,
                reason="STALE_REGIME_STATIC_FALLBACK",
                configured_weights=static_weights,
            )

        configured_weights = {
            family: sum(
                regime.probabilities[label]
                * self.config.regime_weights[label][family]
                for label in REGIME_LABELS
            )
            for family in FUSION_FAMILIES
        }
        return RegimeFusionWeightExplanation(
            config_fingerprint=self.config.fingerprint,
            source_regime_fingerprint=regime.snapshot_fingerprint,
            source_regime_age_seconds=age_seconds,
            regime_probabilities=dict(regime.probabilities),
            fallback_to_static=False,
            reason="FRESH_REGIME_PROBABILITY_BLEND",
            configured_weights=configured_weights,
        )

    def configured_weights(
        self,
        request: FusionRequest,
    ) -> dict[FusionFamily, float]:
        explanation = self.explain(request)
        return {
            family: explanation.configured_weights[family]
            for family in FUSION_FAMILIES
        }

    def required_positive_families(
        self,
        request: FusionRequest,
    ) -> tuple[FusionFamily, ...]:
        return V2_CHAMPION_FUSION_POLICY.required_positive_families(request)

    def buy_threshold(self, request: FusionRequest) -> float:
        return V2_CHAMPION_FUSION_POLICY.buy_threshold(request)
