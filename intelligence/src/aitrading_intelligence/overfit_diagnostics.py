from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import datetime
from itertools import combinations
from statistics import NormalDist, mean, stdev
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint

OVERFIT_DIAGNOSTICS_VERSION = "overfit-diagnostics.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
DiagnosticState = Literal["EVALUATED", "INCONCLUSIVE"]


class OverfitDiagnosticsConfigV1(BaseModel):
    """Fail-closed assumptions for PBO and Deflated Sharpe diagnostics."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    diagnostics_version: Literal["overfit-diagnostics.v1"] = OVERFIT_DIAGNOSTICS_VERSION
    pbo_partitions: Annotated[int, Field(ge=4, le=16)] = 6
    minimum_candidates: Annotated[int, Field(ge=2, le=1_000)] = 3
    minimum_observations: Annotated[int, Field(ge=20, le=1_000_000)] = 60
    minimum_partition_observations: Annotated[int, Field(ge=2, le=100_000)] = 5
    independent_trial_count: Annotated[int | None, Field(ge=2, le=1_000_000)] = None
    maximum_pbo_combinations: Annotated[int, Field(ge=2, le=100_000)] = 5_000

    @model_validator(mode="after")
    def validate_pbo_partitioning(self) -> OverfitDiagnosticsConfigV1:
        if self.pbo_partitions % 2:
            raise ValueError("pbo_partitions must be even for symmetric CSCV")
        combination_count = math.comb(self.pbo_partitions, self.pbo_partitions // 2)
        if combination_count > self.maximum_pbo_combinations:
            raise ValueError("configured PBO combination count exceeds maximum_pbo_combinations")
        return self

    @property
    def pbo_combination_count(self) -> int:
        return math.comb(self.pbo_partitions, self.pbo_partitions // 2)

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


class CandidateReturnSeriesV1(BaseModel):
    """One aligned after-cost research return series for a tried strategy configuration."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    diagnostics_version: Literal["overfit-diagnostics.v1"] = OVERFIT_DIAGNOSTICS_VERSION
    candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    source_fingerprint: Hash64
    decision_times: list[datetime] = Field(min_length=2)
    net_returns: list[float] = Field(min_length=2)
    after_cost: Literal[True] = True
    series_fingerprint: Hash64
    status: Literal["RESEARCH_RETURN_SERIES_ONLY"] = "RESEARCH_RETURN_SERIES_ONLY"
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("decision_times")
    @classmethod
    def decision_times_must_be_aware(cls, values: list[datetime]) -> list[datetime]:
        if any(value.tzinfo is None or value.utcoffset() is None for value in values):
            raise ValueError("candidate return timestamps must be timezone-aware")
        return values

    @field_validator("net_returns")
    @classmethod
    def returns_must_be_valid(cls, values: list[float]) -> list[float]:
        if any(not math.isfinite(value) or value <= -1.0 for value in values):
            raise ValueError("candidate net returns must be finite and greater than -100%")
        return values

    @model_validator(mode="after")
    def validate_series(self) -> CandidateReturnSeriesV1:
        if self.decision_times != sorted(set(self.decision_times)):
            raise ValueError("candidate return timestamps must be unique and chronological")
        if len(self.decision_times) != len(self.net_returns):
            raise ValueError("candidate return timestamps and net returns must align")
        if self.series_fingerprint != canonical_fingerprint(_candidate_payload(self)):
            raise ValueError("candidate return series fingerprint mismatch")
        return self


class PboSplitEvidenceV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    split_index: Annotated[int, Field(ge=0)]
    training_partition_indices: list[Annotated[int, Field(ge=0)]] = Field(min_length=1)
    test_partition_indices: list[Annotated[int, Field(ge=0)]] = Field(min_length=1)
    selected_candidate_id: Annotated[str, Field(min_length=1, max_length=128)]
    in_sample_sharpe: float
    out_of_sample_sharpe: float
    out_of_sample_relative_rank: Annotated[float, Field(gt=0.0, lt=1.0)]
    rank_logit: float
    split_fingerprint: Hash64

    @field_validator("in_sample_sharpe", "out_of_sample_sharpe", "rank_logit")
    @classmethod
    def metrics_must_be_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("PBO split metrics must be finite")
        return value


class ProbabilityBacktestOverfitDiagnosticV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    state: DiagnosticState
    reason_codes: list[str]
    partition_count: Annotated[int, Field(ge=4)]
    combination_count: Annotated[int, Field(ge=0)]
    evaluated_split_count: Annotated[int, Field(ge=0)]
    pbo: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    splits: list[PboSplitEvidenceV1]
    diagnostic_fingerprint: Hash64

    @model_validator(mode="after")
    def validate_state(self) -> ProbabilityBacktestOverfitDiagnosticV1:
        if self.state == "EVALUATED":
            if self.pbo is None or self.evaluated_split_count != self.combination_count:
                raise ValueError("evaluated PBO must contain every symmetric CSCV split")
        elif self.pbo is not None or self.splits:
            raise ValueError("inconclusive PBO cannot expose partial significance")
        if self.evaluated_split_count != len(self.splits):
            raise ValueError("PBO evaluated_split_count mismatch")
        return self


class DeflatedSharpeDiagnosticV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    state: DiagnosticState
    reason_codes: list[str]
    independent_trial_count: Annotated[int | None, Field(ge=2)] = None
    selected_candidate_id: str | None = None
    selected_sharpe: float | None = None
    expected_max_sharpe: float | None = None
    trial_sharpe_standard_deviation: float | None = None
    selected_return_skewness: float | None = None
    selected_return_kurtosis: float | None = None
    dsr_probability: Annotated[float | None, Field(ge=0.0, le=1.0)] = None
    diagnostic_fingerprint: Hash64

    @field_validator(
        "selected_sharpe",
        "expected_max_sharpe",
        "trial_sharpe_standard_deviation",
        "selected_return_skewness",
        "selected_return_kurtosis",
    )
    @classmethod
    def optional_metrics_must_be_finite(cls, value: float | None) -> float | None:
        if value is not None and not math.isfinite(value):
            raise ValueError("Deflated Sharpe metrics must be finite")
        return value

    @model_validator(mode="after")
    def validate_state(self) -> DeflatedSharpeDiagnosticV1:
        metrics = (
            self.selected_candidate_id,
            self.selected_sharpe,
            self.expected_max_sharpe,
            self.trial_sharpe_standard_deviation,
            self.selected_return_skewness,
            self.selected_return_kurtosis,
            self.dsr_probability,
        )
        if self.state == "EVALUATED" and any(value is None for value in metrics):
            raise ValueError("evaluated Deflated Sharpe must contain complete evidence")
        if self.state == "INCONCLUSIVE" and any(value is not None for value in metrics):
            raise ValueError("inconclusive Deflated Sharpe cannot expose partial significance")
        return self


class OverfitDiagnosticsReportV1(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    diagnostics_version: Literal["overfit-diagnostics.v1"] = OVERFIT_DIAGNOSTICS_VERSION
    state: DiagnosticState
    reason_codes: list[str]
    config_fingerprint: Hash64
    candidate_count: Annotated[int, Field(ge=1)]
    observation_count: Annotated[int, Field(ge=2)]
    source_series_fingerprints: list[Hash64] = Field(min_length=1)
    pbo: ProbabilityBacktestOverfitDiagnosticV1
    deflated_sharpe: DeflatedSharpeDiagnosticV1
    report_fingerprint: Hash64
    status: Literal["RESEARCH_OVERFIT_DIAGNOSTICS_ONLY"] = (
        "RESEARCH_OVERFIT_DIAGNOSTICS_ONLY"
    )
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @model_validator(mode="after")
    def validate_report(self) -> OverfitDiagnosticsReportV1:
        expected_state: DiagnosticState = (
            "EVALUATED"
            if self.pbo.state == "EVALUATED" and self.deflated_sharpe.state == "EVALUATED"
            else "INCONCLUSIVE"
        )
        if self.state != expected_state:
            raise ValueError("overall overfit diagnostic state must reflect both component diagnostics")
        if self.report_fingerprint != canonical_fingerprint(_report_payload(self)):
            raise ValueError("overfit diagnostic report fingerprint mismatch")
        return self


def _candidate_payload(series: CandidateReturnSeriesV1) -> dict[str, object]:
    return {
        "diagnostics_version": OVERFIT_DIAGNOSTICS_VERSION,
        "candidate_id": series.candidate_id,
        "source_fingerprint": series.source_fingerprint,
        "decision_times": [value.isoformat() for value in series.decision_times],
        "net_returns": [round(float(value), 12) for value in series.net_returns],
        "after_cost": True,
    }


def build_candidate_return_series(
    candidate_id: str,
    source_fingerprint: str,
    decision_times: Sequence[datetime],
    net_returns: Sequence[float],
) -> CandidateReturnSeriesV1:
    times = list(decision_times)
    returns = [float(value) for value in net_returns]
    payload = {
        "diagnostics_version": OVERFIT_DIAGNOSTICS_VERSION,
        "candidate_id": candidate_id,
        "source_fingerprint": source_fingerprint,
        "decision_times": [value.isoformat() for value in times],
        "net_returns": [round(value, 12) for value in returns],
        "after_cost": True,
    }
    return CandidateReturnSeriesV1(
        candidate_id=candidate_id,
        source_fingerprint=source_fingerprint,
        decision_times=times,
        net_returns=returns,
        series_fingerprint=canonical_fingerprint(payload),
    )


def _sample_sharpe(values: Sequence[float]) -> float | None:
    if len(values) < 2:
        return None
    volatility = stdev(values)
    if volatility <= 1e-15:
        return None
    return float(mean(values) / volatility)


def _sample_skewness_and_kurtosis(values: Sequence[float]) -> tuple[float, float] | None:
    count = len(values)
    if count < 4:
        return None
    location = mean(values)
    volatility = stdev(values)
    if volatility <= 1e-15:
        return None
    standardized = [(value - location) / volatility for value in values]
    skewness = (
        count
        / ((count - 1) * (count - 2))
        * sum(value**3 for value in standardized)
    )
    excess_kurtosis = (
        count
        * (count + 1)
        / ((count - 1) * (count - 2) * (count - 3))
        * sum(value**4 for value in standardized)
        - (3 * (count - 1) ** 2) / ((count - 2) * (count - 3))
    )
    return float(skewness), float(excess_kurtosis + 3.0)


def _average_rank_ascending(values: Sequence[float], target_index: int) -> float:
    target = values[target_index]
    lower = sum(value < target for value in values)
    equal = sum(math.isclose(value, target, rel_tol=1e-12, abs_tol=1e-12) for value in values)
    return lower + ((equal + 1.0) / 2.0)


def _inconclusive_pbo(
    config: OverfitDiagnosticsConfigV1,
    reason: str,
) -> ProbabilityBacktestOverfitDiagnosticV1:
    payload = {
        "state": "INCONCLUSIVE",
        "reason_codes": [reason],
        "partition_count": config.pbo_partitions,
        "combination_count": config.pbo_combination_count,
        "evaluated_split_count": 0,
        "pbo": None,
        "splits": [],
    }
    return ProbabilityBacktestOverfitDiagnosticV1(
        **payload,
        diagnostic_fingerprint=canonical_fingerprint(payload),
    )


def _evaluate_pbo(
    candidates: Sequence[CandidateReturnSeriesV1],
    config: OverfitDiagnosticsConfigV1,
) -> ProbabilityBacktestOverfitDiagnosticV1:
    observation_count = len(candidates[0].net_returns)
    if observation_count % config.pbo_partitions:
        return _inconclusive_pbo(config, "OBSERVATIONS_NOT_DIVISIBLE_BY_PBO_PARTITIONS")
    partition_size = observation_count // config.pbo_partitions
    if partition_size < config.minimum_partition_observations:
        return _inconclusive_pbo(config, "INSUFFICIENT_PBO_PARTITION_OBSERVATIONS")

    partition_indices = [
        list(range(index * partition_size, (index + 1) * partition_size))
        for index in range(config.pbo_partitions)
    ]
    split_evidence: list[PboSplitEvidenceV1] = []
    split_choices = list(
        combinations(range(config.pbo_partitions), config.pbo_partitions // 2)
    )
    all_partitions = set(range(config.pbo_partitions))
    for split_index, training_tuple in enumerate(split_choices):
        training_partitions = list(training_tuple)
        test_partitions = sorted(all_partitions - set(training_partitions))
        training_indices = [
            item for partition in training_partitions for item in partition_indices[partition]
        ]
        test_indices = [
            item for partition in test_partitions for item in partition_indices[partition]
        ]
        in_sample_sharpes: list[float] = []
        out_of_sample_sharpes: list[float] = []
        for candidate in candidates:
            in_sample = _sample_sharpe([candidate.net_returns[index] for index in training_indices])
            out_of_sample = _sample_sharpe(
                [candidate.net_returns[index] for index in test_indices]
            )
            if in_sample is None or out_of_sample is None:
                return _inconclusive_pbo(config, "ZERO_VARIANCE_CSCV_SPLIT")
            in_sample_sharpes.append(in_sample)
            out_of_sample_sharpes.append(out_of_sample)

        best_sharpe = max(in_sample_sharpes)
        winners = [
            index
            for index, value in enumerate(in_sample_sharpes)
            if math.isclose(value, best_sharpe, rel_tol=1e-12, abs_tol=1e-12)
        ]
        if len(winners) != 1:
            return _inconclusive_pbo(config, "AMBIGUOUS_IN_SAMPLE_WINNER")
        selected_index = winners[0]
        rank = _average_rank_ascending(out_of_sample_sharpes, selected_index)
        relative_rank = rank / (len(candidates) + 1.0)
        rank_logit = math.log(relative_rank / (1.0 - relative_rank))
        split_payload = {
            "split_index": split_index,
            "training_partition_indices": training_partitions,
            "test_partition_indices": test_partitions,
            "selected_candidate_id": candidates[selected_index].candidate_id,
            "in_sample_sharpe": in_sample_sharpes[selected_index],
            "out_of_sample_sharpe": out_of_sample_sharpes[selected_index],
            "out_of_sample_relative_rank": relative_rank,
            "rank_logit": rank_logit,
        }
        split_evidence.append(
            PboSplitEvidenceV1(
                **split_payload,
                split_fingerprint=canonical_fingerprint(split_payload),
            )
        )

    pbo = sum(item.rank_logit <= 0.0 for item in split_evidence) / len(split_evidence)
    payload = {
        "state": "EVALUATED",
        "reason_codes": [],
        "partition_count": config.pbo_partitions,
        "combination_count": len(split_choices),
        "evaluated_split_count": len(split_evidence),
        "pbo": pbo,
        "splits": [item.model_dump(mode="json") for item in split_evidence],
    }
    return ProbabilityBacktestOverfitDiagnosticV1(
        state="EVALUATED",
        reason_codes=[],
        partition_count=config.pbo_partitions,
        combination_count=len(split_choices),
        evaluated_split_count=len(split_evidence),
        pbo=pbo,
        splits=split_evidence,
        diagnostic_fingerprint=canonical_fingerprint(payload),
    )


def _inconclusive_dsr(
    config: OverfitDiagnosticsConfigV1,
    reason: str,
) -> DeflatedSharpeDiagnosticV1:
    payload = {
        "state": "INCONCLUSIVE",
        "reason_codes": [reason],
        "independent_trial_count": config.independent_trial_count,
        "selected_candidate_id": None,
        "selected_sharpe": None,
        "expected_max_sharpe": None,
        "trial_sharpe_standard_deviation": None,
        "selected_return_skewness": None,
        "selected_return_kurtosis": None,
        "dsr_probability": None,
    }
    return DeflatedSharpeDiagnosticV1(
        **payload,
        diagnostic_fingerprint=canonical_fingerprint(payload),
    )


def _evaluate_deflated_sharpe(
    candidates: Sequence[CandidateReturnSeriesV1],
    config: OverfitDiagnosticsConfigV1,
) -> DeflatedSharpeDiagnosticV1:
    independent_trials = config.independent_trial_count
    if independent_trials is None:
        return _inconclusive_dsr(config, "INDEPENDENT_TRIAL_COUNT_REQUIRED")
    if independent_trials > len(candidates):
        return _inconclusive_dsr(config, "INDEPENDENT_TRIAL_COUNT_EXCEEDS_CANDIDATES")

    candidate_sharpes = [_sample_sharpe(candidate.net_returns) for candidate in candidates]
    if any(value is None for value in candidate_sharpes):
        return _inconclusive_dsr(config, "ZERO_VARIANCE_CANDIDATE_RETURNS")
    sharpes = [float(value) for value in candidate_sharpes if value is not None]
    best_sharpe = max(sharpes)
    winners = [
        index
        for index, value in enumerate(sharpes)
        if math.isclose(value, best_sharpe, rel_tol=1e-12, abs_tol=1e-12)
    ]
    if len(winners) != 1:
        return _inconclusive_dsr(config, "AMBIGUOUS_FULL_SAMPLE_WINNER")

    trial_sharpe_std = stdev(sharpes)
    if trial_sharpe_std <= 1e-15:
        return _inconclusive_dsr(config, "INSUFFICIENT_TRIAL_SHARPE_DISPERSION")

    selected_index = winners[0]
    selected_returns = candidates[selected_index].net_returns
    moments = _sample_skewness_and_kurtosis(selected_returns)
    if moments is None:
        return _inconclusive_dsr(config, "INSUFFICIENT_RETURN_MOMENTS")
    skewness, kurtosis = moments

    normal = NormalDist()
    euler_mascheroni = 0.5772156649015329
    max_standard_normal = (
        (1.0 - euler_mascheroni)
        * normal.inv_cdf(1.0 - (1.0 / independent_trials))
        + euler_mascheroni
        * normal.inv_cdf(1.0 - (1.0 / (independent_trials * math.e)))
    )
    expected_max_sharpe = trial_sharpe_std * max_standard_normal
    selected_sharpe = sharpes[selected_index]
    variance_term = (
        1.0
        - (skewness * selected_sharpe)
        + (((kurtosis - 1.0) / 4.0) * selected_sharpe**2)
    )
    if not math.isfinite(variance_term) or variance_term <= 0.0:
        return _inconclusive_dsr(config, "INVALID_DSR_MOMENT_ADJUSTMENT")

    statistic = (
        (selected_sharpe - expected_max_sharpe)
        * math.sqrt(len(selected_returns) - 1)
        / math.sqrt(variance_term)
    )
    probability = normal.cdf(statistic)
    payload = {
        "state": "EVALUATED",
        "reason_codes": [],
        "independent_trial_count": independent_trials,
        "selected_candidate_id": candidates[selected_index].candidate_id,
        "selected_sharpe": selected_sharpe,
        "expected_max_sharpe": expected_max_sharpe,
        "trial_sharpe_standard_deviation": trial_sharpe_std,
        "selected_return_skewness": skewness,
        "selected_return_kurtosis": kurtosis,
        "dsr_probability": probability,
    }
    return DeflatedSharpeDiagnosticV1(
        **payload,
        diagnostic_fingerprint=canonical_fingerprint(payload),
    )


def _report_payload(report: OverfitDiagnosticsReportV1) -> dict[str, object]:
    return {
        "diagnostics_version": OVERFIT_DIAGNOSTICS_VERSION,
        "state": report.state,
        "reason_codes": report.reason_codes,
        "config_fingerprint": report.config_fingerprint,
        "candidate_count": report.candidate_count,
        "observation_count": report.observation_count,
        "source_series_fingerprints": report.source_series_fingerprints,
        "pbo": report.pbo.model_dump(mode="json"),
        "deflated_sharpe": report.deflated_sharpe.model_dump(mode="json"),
        "status": "RESEARCH_OVERFIT_DIAGNOSTICS_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }


def _inconclusive_report(
    candidates: Sequence[CandidateReturnSeriesV1],
    config: OverfitDiagnosticsConfigV1,
    reason: str,
) -> OverfitDiagnosticsReportV1:
    pbo = _inconclusive_pbo(config, reason)
    dsr = _inconclusive_dsr(config, reason)
    source_fingerprints = [item.series_fingerprint for item in candidates]
    observation_count = len(candidates[0].net_returns)
    payload = {
        "diagnostics_version": OVERFIT_DIAGNOSTICS_VERSION,
        "state": "INCONCLUSIVE",
        "reason_codes": [reason],
        "config_fingerprint": config.fingerprint,
        "candidate_count": len(candidates),
        "observation_count": observation_count,
        "source_series_fingerprints": source_fingerprints,
        "pbo": pbo.model_dump(mode="json"),
        "deflated_sharpe": dsr.model_dump(mode="json"),
        "status": "RESEARCH_OVERFIT_DIAGNOSTICS_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return OverfitDiagnosticsReportV1(
        state="INCONCLUSIVE",
        reason_codes=[reason],
        config_fingerprint=config.fingerprint,
        candidate_count=len(candidates),
        observation_count=observation_count,
        source_series_fingerprints=source_fingerprints,
        pbo=pbo,
        deflated_sharpe=dsr,
        report_fingerprint=canonical_fingerprint(payload),
    )


def evaluate_overfit_diagnostics(
    series: Sequence[CandidateReturnSeriesV1],
    *,
    config: OverfitDiagnosticsConfigV1 | None = None,
) -> OverfitDiagnosticsReportV1:
    """Evaluate PBO/DSR only when their evidence assumptions are explicitly satisfied."""

    cfg = config or OverfitDiagnosticsConfigV1()
    candidates = sorted(
        (
            CandidateReturnSeriesV1.model_validate(item.model_dump(mode="python"))
            for item in series
        ),
        key=lambda item: (item.candidate_id, item.series_fingerprint),
    )
    if not candidates:
        raise ValueError("candidate return series are required for overfit diagnostics")
    if len({item.candidate_id for item in candidates}) != len(candidates):
        raise ValueError("overfit diagnostic candidate IDs must be unique")
    if len({item.series_fingerprint for item in candidates}) != len(candidates):
        raise ValueError("overfit diagnostic series fingerprints must be unique")

    if len(candidates) < cfg.minimum_candidates:
        return _inconclusive_report(candidates, cfg, "INSUFFICIENT_CANDIDATE_TRIALS")
    observation_count = len(candidates[0].net_returns)
    if observation_count < cfg.minimum_observations:
        return _inconclusive_report(candidates, cfg, "INSUFFICIENT_RETURN_HISTORY")
    if any(item.decision_times != candidates[0].decision_times for item in candidates[1:]):
        return _inconclusive_report(candidates, cfg, "UNALIGNED_CANDIDATE_OBSERVATIONS")
    if any(len(item.net_returns) != observation_count for item in candidates[1:]):
        return _inconclusive_report(candidates, cfg, "UNALIGNED_CANDIDATE_OBSERVATIONS")

    pbo = _evaluate_pbo(candidates, cfg)
    dsr = _evaluate_deflated_sharpe(candidates, cfg)
    state: DiagnosticState = (
        "EVALUATED" if pbo.state == "EVALUATED" and dsr.state == "EVALUATED" else "INCONCLUSIVE"
    )
    reason_codes = sorted(set([*pbo.reason_codes, *dsr.reason_codes]))
    source_fingerprints = [item.series_fingerprint for item in candidates]
    payload = {
        "diagnostics_version": OVERFIT_DIAGNOSTICS_VERSION,
        "state": state,
        "reason_codes": reason_codes,
        "config_fingerprint": cfg.fingerprint,
        "candidate_count": len(candidates),
        "observation_count": observation_count,
        "source_series_fingerprints": source_fingerprints,
        "pbo": pbo.model_dump(mode="json"),
        "deflated_sharpe": dsr.model_dump(mode="json"),
        "status": "RESEARCH_OVERFIT_DIAGNOSTICS_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return OverfitDiagnosticsReportV1(
        state=state,
        reason_codes=reason_codes,
        config_fingerprint=cfg.fingerprint,
        candidate_count=len(candidates),
        observation_count=observation_count,
        source_series_fingerprints=source_fingerprints,
        pbo=pbo,
        deflated_sharpe=dsr,
        report_fingerprint=canonical_fingerprint(payload),
    )
