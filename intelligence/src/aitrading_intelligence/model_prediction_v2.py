from __future__ import annotations

import math
from datetime import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from time import perf_counter_ns
from typing import TYPE_CHECKING, Annotated, Literal

import catboost as cb
import lightgbm as lgb
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .baseline import BaselineScore, LogisticBaselineArtifact, score_logistic_baseline
from .catboost_model import CatBoostModelArtifact
from .features import FeatureRow
from .fingerprints import canonical_fingerprint
from .lightgbm_model import LightGBMModelArtifact

if TYPE_CHECKING:
    from .pytorch_model import TorchModelArtifact

MODEL_PREDICTION_V2 = "model-prediction.v2"
MAX_ATTRIBUTION_FEATURES = 16
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
ModelFamily = Literal["LOGISTIC", "PYTORCH", "LIGHTGBM", "CATBOOST"]
AttributionStatus = Literal["AVAILABLE", "NOT_ATTACHED"]


class ModelPredictionV2(BaseModel):
    """Normalized research-only prediction evidence across accepted model families."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-prediction.v2"] = MODEL_PREDICTION_V2
    symbol: str
    as_of: datetime
    data_cutoff: datetime
    model_family: ModelFamily
    model_name: str
    model_version: str
    model_artifact_fingerprint: Hash64
    target_definition_fingerprint: Hash64
    raw_probability: Annotated[float, Field(ge=0.0, le=1.0)]
    raw_score: Annotated[float, Field(ge=-1.0, le=1.0)]
    raw_confidence: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    feature_row_fingerprint: Hash64
    attribution_summary: dict[str, float] = Field(
        default_factory=dict,
        max_length=MAX_ATTRIBUTION_FEATURES,
    )
    attribution_status: AttributionStatus
    inference_latency_micros: Annotated[int, Field(ge=0)]
    inference_fingerprint: Hash64
    probability_semantics: Literal["P_FORWARD_RETURN_AFTER_COSTS_POSITIVE"] = (
        "P_FORWARD_RETURN_AFTER_COSTS_POSITIVE"
    )
    status: Literal["MODEL_EVIDENCE_ONLY"] = "MODEL_EVIDENCE_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of", "data_cutoff")
    @classmethod
    def timestamps_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("prediction timestamps must be timezone-aware")
        return value

    @field_validator("attribution_summary")
    @classmethod
    def attributions_must_be_finite(cls, value: dict[str, float]) -> dict[str, float]:
        if any(not math.isfinite(item) for item in value.values()):
            raise ValueError("attribution values must be finite")
        return value

    @model_validator(mode="after")
    def validate_prediction(self) -> ModelPredictionV2:
        if self.data_cutoff > self.as_of:
            raise ValueError("prediction data_cutoff cannot exceed as_of")
        expected_score = (2.0 * self.raw_probability) - 1.0
        if not math.isclose(self.raw_score, expected_score, rel_tol=0.0, abs_tol=1e-9):
            raise ValueError("raw_score must equal 2 * raw_probability - 1")
        if self.attribution_status == "AVAILABLE" and not self.attribution_summary:
            raise ValueError("AVAILABLE attribution requires a non-empty summary")
        if self.attribution_status == "NOT_ATTACHED" and self.attribution_summary:
            raise ValueError("NOT_ATTACHED attribution cannot contain values")
        return self


def _feature_vector(row: FeatureRow, feature_names: list[str]) -> list[float]:
    if row.data_cutoff > row.as_of:
        raise ValueError("prediction feature row is not causal")
    # The fitted artifact's ordered feature list is the authoritative schema.
    # A missing event, filing or market feature is not the numeric value 0.0.
    # Do not silently manufacture a prediction from an incomplete golden source.
    if not feature_names or len(set(feature_names)) != len(feature_names):
        raise ValueError("model feature names must be non-empty and unique")
    missing = [name for name in feature_names if name not in row.features]
    if missing:
        raise ValueError(
            "missing trained model features: " + ", ".join(sorted(missing))
        )
    values = [float(row.features[name]) for name in feature_names]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("prediction feature values must be finite")
    return values


def _bounded_attribution(
    values: dict[str, float],
    *,
    limit: int = MAX_ATTRIBUTION_FEATURES,
) -> dict[str, float]:
    ordered = sorted(values.items(), key=lambda item: (-abs(float(item[1])), item[0]))
    return {name: float(value) for name, value in ordered[:limit]}


def _tree_attribution(
    feature_names: list[str],
    contribution_row: object,
    *,
    provider_name: str,
) -> dict[str, float]:
    values = [float(value) for value in contribution_row]
    expected_length = len(feature_names) + 1
    if len(values) != expected_length:
        raise ValueError(
            f"{provider_name} attribution must contain one value per feature plus expected value"
        )
    feature_values = values[:-1]
    if any(not math.isfinite(value) for value in feature_values):
        raise ValueError(f"{provider_name} attribution values must be finite")
    return dict(zip(feature_names, feature_values, strict=True))


def _latency_micros(start_ns: int) -> int:
    return max(0, (perf_counter_ns() - start_ns) // 1_000)


def _build_prediction(
    *,
    row: FeatureRow,
    model_family: ModelFamily,
    model_name: str,
    model_version: str,
    model_artifact_fingerprint: str,
    target_definition_fingerprint: str,
    raw_probability: float,
    raw_score: float,
    raw_confidence: float | None,
    attribution_summary: dict[str, float],
    inference_latency_micros: int,
) -> ModelPredictionV2:
    bounded_attribution = _bounded_attribution(attribution_summary)
    attribution_status: AttributionStatus = (
        "AVAILABLE" if bounded_attribution else "NOT_ATTACHED"
    )
    payload = {
        "contract_version": MODEL_PREDICTION_V2,
        "symbol": row.symbol,
        "as_of": row.as_of.isoformat(),
        "data_cutoff": row.data_cutoff.isoformat(),
        "model_family": model_family,
        "model_name": model_name,
        "model_version": model_version,
        "model_artifact_fingerprint": model_artifact_fingerprint,
        "target_definition_fingerprint": target_definition_fingerprint,
        "raw_probability": raw_probability,
        "raw_score": raw_score,
        "raw_confidence": raw_confidence,
        "feature_row_fingerprint": row.fingerprint,
        "attribution_summary": bounded_attribution,
        "attribution_status": attribution_status,
        "probability_semantics": "P_FORWARD_RETURN_AFTER_COSTS_POSITIVE",
        "status": "MODEL_EVIDENCE_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelPredictionV2(
        **payload,
        inference_latency_micros=inference_latency_micros,
        # Latency is deliberately telemetry, not semantic identity; identical model/input
        # evidence retains one reproducible inference fingerprint across repeated runs.
        inference_fingerprint=canonical_fingerprint(payload),
    )


def adapt_logistic_prediction(
    row: FeatureRow,
    artifact: LogisticBaselineArtifact,
    *,
    target_definition_fingerprint: str,
) -> ModelPredictionV2:
    start_ns = perf_counter_ns()
    score: BaselineScore = score_logistic_baseline(row, artifact)
    latency = _latency_micros(start_ns)
    return _build_prediction(
        row=row,
        model_family="LOGISTIC",
        model_name="LogisticRegression",
        model_version=artifact.model_version,
        model_artifact_fingerprint=artifact.artifact_fingerprint,
        target_definition_fingerprint=target_definition_fingerprint,
        raw_probability=score.probability_up,
        raw_score=score.directional_score,
        raw_confidence=score.confidence,
        attribution_summary=score.feature_contributions,
        inference_latency_micros=latency,
    )


def adapt_lightgbm_prediction(
    row: FeatureRow,
    artifact: LightGBMModelArtifact,
) -> ModelPredictionV2:
    start_ns = perf_counter_ns()
    vector = _feature_vector(row, artifact.feature_names)
    model = lgb.Booster(model_str=artifact.booster_model)
    probability = float(model.predict([vector])[0])
    contribution_row = model.predict([vector], pred_contrib=True)[0]
    attribution = _tree_attribution(
        artifact.feature_names,
        contribution_row,
        provider_name="LightGBM",
    )
    latency = _latency_micros(start_ns)
    score = (2.0 * probability) - 1.0
    return _build_prediction(
        row=row,
        model_family="LIGHTGBM",
        model_name="LGBMClassifier",
        model_version=artifact.model_version,
        model_artifact_fingerprint=artifact.model_fingerprint,
        target_definition_fingerprint=artifact.target_spec_fingerprint,
        raw_probability=probability,
        raw_score=score,
        raw_confidence=None,
        attribution_summary=attribution,
        inference_latency_micros=latency,
    )


def adapt_catboost_prediction(
    row: FeatureRow,
    artifact: CatBoostModelArtifact,
) -> ModelPredictionV2:
    start_ns = perf_counter_ns()
    vector = _feature_vector(row, artifact.feature_names)
    with TemporaryDirectory() as directory:
        path = Path(directory) / "model.json"
        path.write_text(artifact.model_json, encoding="utf-8")
        model = cb.CatBoostClassifier()
        model.load_model(str(path), format="json")
        pool = cb.Pool([vector], feature_names=artifact.feature_names)
        probability = float(model.predict_proba(pool)[0][1])
        contribution_row = model.get_feature_importance(
            pool,
            type=cb.EFstrType.ShapValues,
            thread_count=1,
            verbose=False,
        )[0]
        attribution = _tree_attribution(
            artifact.feature_names,
            contribution_row,
            provider_name="CatBoost",
        )
    latency = _latency_micros(start_ns)
    score = (2.0 * probability) - 1.0
    return _build_prediction(
        row=row,
        model_family="CATBOOST",
        model_name="CatBoostClassifier",
        model_version=artifact.model_version,
        model_artifact_fingerprint=artifact.model_fingerprint,
        target_definition_fingerprint=artifact.target_spec_fingerprint,
        raw_probability=probability,
        raw_score=score,
        raw_confidence=None,
        attribution_summary=attribution,
        inference_latency_micros=latency,
    )


def adapt_pytorch_prediction(
    row: FeatureRow,
    artifact: TorchModelArtifact,
) -> ModelPredictionV2:
    # PyTorch is intentionally an optional heavyweight dependency in this repository.
    from .pytorch_model import score_torch_challenger

    start_ns = perf_counter_ns()
    score = score_torch_challenger(row, artifact)
    latency = _latency_micros(start_ns)
    return _build_prediction(
        row=row,
        model_family="PYTORCH",
        model_name="PyTorchMLP",
        model_version=artifact.model_version,
        model_artifact_fingerprint=artifact.model_fingerprint,
        target_definition_fingerprint=artifact.target_spec_fingerprint,
        raw_probability=score.probability_up,
        raw_score=score.directional_score,
        raw_confidence=score.confidence,
        attribution_summary=score.feature_attribution,
        inference_latency_micros=latency,
    )
