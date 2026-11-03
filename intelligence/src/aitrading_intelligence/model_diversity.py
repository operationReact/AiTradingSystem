from __future__ import annotations

import math
from itertools import combinations
from typing import Annotated, Literal, Sequence

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

DIVERSITY_VERSION = "model-diversity-report.v1"
PREDICTION_SERIES_VERSION = "model-prediction-series.v1"
PAIR_VERSION = "model-diversity-pair.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Probability = Annotated[float, Field(ge=0.0, le=1.0)]


class ModelDiversityConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    diversity_version: Literal["model-diversity-report.v1"] = DIVERSITY_VERSION
    decision_threshold: Annotated[float, Field(gt=0.0, lt=1.0)] = 0.5
    redundancy_correlation_threshold: Annotated[float, Field(ge=0.0, le=1.0)] = 0.9
    redundancy_disagreement_threshold: Annotated[float, Field(ge=0.0, le=1.0)] = 0.1

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class ModelPredictionSeries(BaseModel):
    """Immutable aligned model outputs used only for research comparison evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-prediction-series.v1"] = PREDICTION_SERIES_VERSION
    candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    model_version_ref: Annotated[str, Field(min_length=1, max_length=128)]
    source_result_fingerprint: Hash64
    observation_fingerprints: list[Hash64] = Field(min_length=2, max_length=100_000)
    probabilities: list[Probability] = Field(min_length=2, max_length=100_000)
    prediction_fingerprint: Hash64
    series_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @field_validator("probabilities")
    @classmethod
    def probabilities_must_be_finite(cls, values: list[float]) -> list[float]:
        if any(not math.isfinite(value) for value in values):
            raise ValueError("prediction probabilities must be finite")
        return values

    @model_validator(mode="after")
    def validate_series(self) -> ModelPredictionSeries:
        if len(self.observation_fingerprints) != len(self.probabilities):
            raise ValueError("observation and probability counts must match")
        if len(set(self.observation_fingerprints)) != len(self.observation_fingerprints):
            raise ValueError("prediction series cannot contain duplicate observations")
        expected_prediction = _prediction_fingerprint(
            self.observation_fingerprints,
            self.probabilities,
        )
        if self.prediction_fingerprint != expected_prediction:
            raise ValueError("prediction_fingerprint does not match aligned probabilities")
        expected_series = _series_fingerprint(
            candidate_id=self.candidate_id,
            model_version_ref=self.model_version_ref,
            source_result_fingerprint=self.source_result_fingerprint,
            prediction_fingerprint=self.prediction_fingerprint,
        )
        if self.series_fingerprint != expected_series:
            raise ValueError("series_fingerprint does not match prediction lineage")
        return self


class CandidateDiversityEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    candidate_id: str
    model_version_ref: str
    source_result_fingerprint: Hash64
    prediction_fingerprint: Hash64
    series_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False


class PairwiseDiversityMetric(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-diversity-pair.v1"] = PAIR_VERSION
    left_candidate_id: str
    right_candidate_id: str
    observation_count: Annotated[int, Field(ge=2)]
    pearson_correlation: Annotated[float | None, Field(ge=-1.0, le=1.0)]
    correlation_defined: bool
    signal_disagreement_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    mean_absolute_probability_difference: Annotated[float, Field(ge=0.0, le=1.0)]
    co_signal_rate: Annotated[float, Field(ge=0.0, le=1.0)]
    redundancy_risk: bool
    pair_fingerprint: Hash64
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_correlation_state(self) -> PairwiseDiversityMetric:
        if self.correlation_defined != (self.pearson_correlation is not None):
            raise ValueError("correlation_defined must match pearson_correlation availability")
        return self


class ModelDiversityReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-diversity-report.v1"] = DIVERSITY_VERSION
    config_fingerprint: Hash64
    observation_count: Annotated[int, Field(ge=2)]
    observation_membership_fingerprint: Hash64
    candidates: list[CandidateDiversityEvidence] = Field(min_length=2, max_length=20)
    pairs: list[PairwiseDiversityMetric] = Field(min_length=1, max_length=190)
    redundant_pair_count: Annotated[int, Field(ge=0)]
    undefined_correlation_pair_count: Annotated[int, Field(ge=0)]
    status: Literal["DIVERSITY_MEASURED", "REDUNDANCY_RISK", "PARTIAL_UNDEFINED"]
    report_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_report(self) -> ModelDiversityReport:
        candidate_ids = [candidate.candidate_id for candidate in self.candidates]
        if candidate_ids != sorted(candidate_ids):
            raise ValueError("diversity candidates must use canonical candidate order")
        expected_pair_count = len(self.candidates) * (len(self.candidates) - 1) // 2
        if len(self.pairs) != expected_pair_count:
            raise ValueError("diversity report must contain every pair exactly once")
        if self.redundant_pair_count != sum(pair.redundancy_risk for pair in self.pairs):
            raise ValueError("redundant_pair_count does not match pair evidence")
        if self.undefined_correlation_pair_count != sum(
            not pair.correlation_defined for pair in self.pairs
        ):
            raise ValueError("undefined correlation count does not match pair evidence")
        return self


def build_prediction_series(
    *,
    candidate_id: str,
    model_version_ref: str,
    source_result_fingerprint: str,
    observation_fingerprints: Sequence[str],
    probabilities: Sequence[float],
) -> ModelPredictionSeries:
    normalized_candidate = candidate_id.strip()
    normalized_version = model_version_ref.strip()
    normalized_result = source_result_fingerprint.strip().lower()
    observations = [value.strip().lower() for value in observation_fingerprints]
    probability_values = [float(value) for value in probabilities]
    prediction_fingerprint = _prediction_fingerprint(observations, probability_values)
    return ModelPredictionSeries(
        candidate_id=normalized_candidate,
        model_version_ref=normalized_version,
        source_result_fingerprint=normalized_result,
        observation_fingerprints=observations,
        probabilities=probability_values,
        prediction_fingerprint=prediction_fingerprint,
        series_fingerprint=_series_fingerprint(
            candidate_id=normalized_candidate,
            model_version_ref=normalized_version,
            source_result_fingerprint=normalized_result,
            prediction_fingerprint=prediction_fingerprint,
        ),
    )


def build_model_diversity_report(
    series: Sequence[ModelPredictionSeries],
    *,
    config: ModelDiversityConfig | None = None,
) -> ModelDiversityReport:
    resolved_config = config or ModelDiversityConfig()
    candidates = list(series)
    if len(candidates) < 2 or len(candidates) > 20:
        raise ValueError("model diversity report requires between 2 and 20 candidates")

    candidate_ids = [candidate.candidate_id for candidate in candidates]
    if len(set(candidate_ids)) != len(candidate_ids):
        raise ValueError("model diversity report requires unique candidate_id values")
    source_results = [candidate.source_result_fingerprint for candidate in candidates]
    if len(set(source_results)) != len(source_results):
        raise ValueError("model diversity report cannot duplicate the same experiment result")

    ordered = sorted(candidates, key=lambda candidate: candidate.candidate_id)
    baseline_observations = ordered[0].observation_fingerprints
    for candidate in ordered[1:]:
        if candidate.observation_fingerprints != baseline_observations:
            raise ValueError(
                "model diversity candidates must use identical ordered observation fingerprints"
            )

    observation_fingerprint = canonical_fingerprint(
        {"observation_fingerprints": baseline_observations}
    )
    evidence = [
        CandidateDiversityEvidence(
            candidate_id=candidate.candidate_id,
            model_version_ref=candidate.model_version_ref,
            source_result_fingerprint=candidate.source_result_fingerprint,
            prediction_fingerprint=candidate.prediction_fingerprint,
            series_fingerprint=candidate.series_fingerprint,
        )
        for candidate in ordered
    ]

    pairs = [
        _pairwise_metric(left, right, resolved_config)
        for left, right in combinations(ordered, 2)
    ]
    redundant_pairs = sum(pair.redundancy_risk for pair in pairs)
    undefined_pairs = sum(not pair.correlation_defined for pair in pairs)
    status: Literal["DIVERSITY_MEASURED", "REDUNDANCY_RISK", "PARTIAL_UNDEFINED"]
    if undefined_pairs > 0:
        status = "PARTIAL_UNDEFINED"
    elif redundant_pairs > 0:
        status = "REDUNDANCY_RISK"
    else:
        status = "DIVERSITY_MEASURED"

    payload = {
        "contract_version": DIVERSITY_VERSION,
        "config_fingerprint": resolved_config.fingerprint,
        "observation_count": len(baseline_observations),
        "observation_membership_fingerprint": observation_fingerprint,
        "candidates": [candidate.model_dump(mode="json") for candidate in evidence],
        "pairs": [pair.model_dump(mode="json") for pair in pairs],
        "redundant_pair_count": redundant_pairs,
        "undefined_correlation_pair_count": undefined_pairs,
        "status": status,
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelDiversityReport(
        **payload,
        report_fingerprint=canonical_fingerprint(payload),
    )


def _pairwise_metric(
    left: ModelPredictionSeries,
    right: ModelPredictionSeries,
    config: ModelDiversityConfig,
) -> PairwiseDiversityMetric:
    correlation = _pearson(left.probabilities, right.probabilities)
    threshold = config.decision_threshold
    disagreements = sum(
        (left_probability >= threshold) != (right_probability >= threshold)
        for left_probability, right_probability in zip(
            left.probabilities,
            right.probabilities,
            strict=True,
        )
    )
    co_signals = sum(
        left_probability >= threshold and right_probability >= threshold
        for left_probability, right_probability in zip(
            left.probabilities,
            right.probabilities,
            strict=True,
        )
    )
    absolute_difference = sum(
        abs(left_probability - right_probability)
        for left_probability, right_probability in zip(
            left.probabilities,
            right.probabilities,
            strict=True,
        )
    )
    count = len(left.probabilities)
    disagreement_rate = _metric(disagreements / count)
    mean_difference = _metric(absolute_difference / count)
    co_signal_rate = _metric(co_signals / count)
    redundancy = (
        correlation is not None
        and correlation >= config.redundancy_correlation_threshold
        and disagreement_rate <= config.redundancy_disagreement_threshold
    )
    payload = {
        "contract_version": PAIR_VERSION,
        "left_candidate_id": left.candidate_id,
        "right_candidate_id": right.candidate_id,
        "observation_count": count,
        "pearson_correlation": correlation,
        "correlation_defined": correlation is not None,
        "signal_disagreement_rate": disagreement_rate,
        "mean_absolute_probability_difference": mean_difference,
        "co_signal_rate": co_signal_rate,
        "redundancy_risk": redundancy,
        "can_authorize_capital": False,
    }
    return PairwiseDiversityMetric(
        **payload,
        pair_fingerprint=canonical_fingerprint(payload),
    )


def _pearson(left: Sequence[float], right: Sequence[float]) -> float | None:
    if len(left) != len(right) or len(left) < 2:
        raise ValueError("Pearson correlation requires equal vectors with at least two observations")
    left_mean = sum(left) / len(left)
    right_mean = sum(right) / len(right)
    left_centered = [value - left_mean for value in left]
    right_centered = [value - right_mean for value in right]
    left_sum_squares = sum(value * value for value in left_centered)
    right_sum_squares = sum(value * value for value in right_centered)
    denominator = math.sqrt(left_sum_squares * right_sum_squares)
    if denominator <= 1e-15:
        return None
    covariance = sum(
        left_value * right_value
        for left_value, right_value in zip(left_centered, right_centered, strict=True)
    )
    correlation = max(-1.0, min(1.0, covariance / denominator))
    return _metric(correlation)


def _prediction_fingerprint(
    observation_fingerprints: Sequence[str],
    probabilities: Sequence[float],
) -> str:
    return canonical_fingerprint(
        {
            "contract_version": PREDICTION_SERIES_VERSION,
            "observation_fingerprints": list(observation_fingerprints),
            "probabilities": [_probability_text(value) for value in probabilities],
        }
    )


def _series_fingerprint(
    *,
    candidate_id: str,
    model_version_ref: str,
    source_result_fingerprint: str,
    prediction_fingerprint: str,
) -> str:
    return canonical_fingerprint(
        {
            "contract_version": PREDICTION_SERIES_VERSION,
            "candidate_id": candidate_id,
            "model_version_ref": model_version_ref,
            "source_result_fingerprint": source_result_fingerprint,
            "prediction_fingerprint": prediction_fingerprint,
            "can_authorize_capital": False,
        }
    )


def _metric(value: float) -> float:
    if not math.isfinite(value):
        raise ValueError("diversity metric must be finite")
    rounded = round(value, 12)
    return 0.0 if rounded == 0.0 else rounded


def _probability_text(value: float) -> str:
    probability = float(value)
    if not math.isfinite(probability) or probability < 0.0 or probability > 1.0:
        raise ValueError("prediction probabilities must be finite and within [0, 1]")
    return format(probability, ".17g")
