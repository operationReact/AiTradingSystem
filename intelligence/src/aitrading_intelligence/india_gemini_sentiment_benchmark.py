from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .fingerprints import canonical_fingerprint
from .india_financial_nlp_corpus import IndiaFinancialNlpCorpusReport
from .india_nlp_event_corpus import IndiaFinancialEventCorpus

VERSION = "india-gemini-advisory-heldout-benchmark.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Sentiment = Literal["NEGATIVE", "NEUTRAL", "POSITIVE"]


class GeminiAdvisoryBenchmarkConfig(BaseModel):
    """Declared neutral band, never fitted to independent holdout labels."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-gemini-advisory-heldout-benchmark.v1"] = VERSION
    neutral_abs_direction_score: Annotated[
        float, Field(ge=0.0, le=0.5)
    ] = 0.20
    minimum_evaluable_predictions: Annotated[int, Field(ge=3, le=500)] = 20
    minimum_per_actual_label: Annotated[int, Field(ge=1, le=100)] = 2

    @property
    def fingerprint(self) -> str:
        return canonical_fingerprint(self.model_dump(mode="json"))


def _sentiment(score: float, neutral_band: float) -> Sentiment:
    if not math.isfinite(score) or not -1.0 <= score <= 1.0:
        raise ValueError("Gemini advisory direction score must be bounded and finite")
    if score > neutral_band:
        return "POSITIVE"
    if score < -neutral_band:
        return "NEGATIVE"
    return "NEUTRAL"


class GeminiHeldoutEventRow(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source_id: str
    annotated_sample_fingerprint: Hash64
    causal_case_fingerprint: Hash64
    observed_sentiment: Sentiment
    observed_forward_return_bps: float
    advisory_status: Literal["CAUSALLY_AVAILABLE", "MISSING_OR_LATE"]
    advisory_direction_score: float | None
    predicted_sentiment: Sentiment | None
    row_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_row(self) -> GeminiHeldoutEventRow:
        eligible = self.advisory_status == "CAUSALLY_AVAILABLE"
        if eligible != (self.advisory_direction_score is not None) or (
            eligible != (self.predicted_sentiment is not None)
        ):
            raise ValueError("late or missing advisory must never count as a prediction")
        if not math.isfinite(self.observed_forward_return_bps):
            raise ValueError("Gemini benchmark market-return label must be finite")
        if self.row_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"row_fingerprint"})
        ):
            raise ValueError("Gemini heldout row fingerprint mismatch")
        return self


class GeminiHeldoutBenchmarkReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["india-gemini-advisory-heldout-benchmark.v1"] = VERSION
    annotated_corpus_fingerprint: Hash64
    causal_market_corpus_fingerprint: Hash64
    config: GeminiAdvisoryBenchmarkConfig
    config_fingerprint: Hash64
    imported_provider_category: Literal["GEMINI_ADVISORY"] = "GEMINI_ADVISORY"
    total_eligible_market_cases: Annotated[int, Field(ge=0)]
    excluded_market_cases: Annotated[int, Field(ge=0)]
    labeled_holdout_events: Annotated[int, Field(ge=0)]
    causally_available_advisories: Annotated[int, Field(ge=0)]
    missing_or_late_advisories: Annotated[int, Field(ge=0)]
    measured_coverage: Annotated[float, Field(ge=0, le=1)]
    sentiment_accuracy: float | None
    sentiment_macro_f1: float | None
    ordered_confusion_counts: list[int] = Field(min_length=9, max_length=9)
    rows: list[GeminiHeldoutEventRow] = Field(max_length=2000)
    status: Literal["DESCRIPTIVE_RESEARCH_ONLY", "INCONCLUSIVE"]
    reason_codes: list[str]
    independent_gemini_api_execution_verified: Literal[False] = False
    sentiment_labels_independently_adjudicated: Literal[False] = False
    independent_news_and_content_rights_verified: Literal[False] = False
    independently_verified_market_label_custody: Literal[False] = False
    independently_verified_incremental_trading_value: Literal[False] = False
    llm_order_authority_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False
    report_fingerprint: Hash64

    @model_validator(mode="after")
    def verify_report(self) -> GeminiHeldoutBenchmarkReport:
        if self.config_fingerprint != self.config.fingerprint:
            raise ValueError("Gemini benchmark configuration fingerprint mismatch")
        if [r.source_id for r in self.rows] != sorted(r.source_id for r in self.rows):
            raise ValueError("Gemini event results must be sorted by source ID")
        if len({r.source_id for r in self.rows}) != len(self.rows):
            raise ValueError("Gemini event results contain duplicate source IDs")
        if self.labeled_holdout_events != self.total_eligible_market_cases or (
            self.labeled_holdout_events != len(self.rows)
        ):
            raise ValueError("all eligible causal cases need a holdout annotation")
        ready = [r for r in self.rows if r.predicted_sentiment is not None]
        if any(
            r.predicted_sentiment != _sentiment(
                r.advisory_direction_score, self.config.neutral_abs_direction_score
            )
            for r in ready if r.advisory_direction_score is not None
        ):
            raise ValueError("Gemini predicted label differs from frozen neutral band")
        if (
            len(ready) != self.causally_available_advisories
            or len(self.rows) - len(ready) != self.missing_or_late_advisories
        ):
            raise ValueError("advisory availability counts differ from event rows")
        expected_coverage = len(ready) / len(self.rows) if self.rows else 0.0
        if not math.isclose(self.measured_coverage, expected_coverage, abs_tol=1e-12):
            raise ValueError("Gemini advisory coverage differs from causal evidence")
        labels: tuple[Sentiment, ...] = ("NEGATIVE", "NEUTRAL", "POSITIVE")
        expected_confusion = [
            sum(r.observed_sentiment == actual and r.predicted_sentiment == predicted
                for r in ready)
            for actual in labels for predicted in labels
        ]
        if expected_confusion != self.ordered_confusion_counts:
            raise ValueError("confusion matrix differs from heldout predictions")
        expected_reasons = []
        if len(ready) < self.config.minimum_evaluable_predictions:
            expected_reasons.append("INSUFFICIENT_CAUSAL_GEMINI_PREDICTIONS")
        if any(
            sum(r.observed_sentiment == sentiment for r in ready)
            < self.config.minimum_per_actual_label
            for sentiment in labels
        ):
            expected_reasons.append("INSUFFICIENT_PER_CLASS_HOLDOUT_SUPPORT")
        if self.reason_codes != sorted(expected_reasons):
            raise ValueError("Gemini benchmark reasons differ from heldout support")
        if self.status == "INCONCLUSIVE":
            if not self.reason_codes or self.sentiment_accuracy is not None or (
                self.sentiment_macro_f1 is not None
            ):
                raise ValueError("insufficient holdout evidence cannot claim quality")
        elif self.reason_codes or self.sentiment_accuracy is None or (
            self.sentiment_macro_f1 is None
        ):
            raise ValueError("research metrics require all support thresholds")
        if self.sentiment_accuracy is not None:
            accuracy = sum(
                r.observed_sentiment == r.predicted_sentiment for r in ready
            ) / len(ready)
            f1_by_class = []
            for label in labels:
                tp = sum(
                    r.observed_sentiment == label and r.predicted_sentiment == label
                    for r in ready
                )
                fp = sum(
                    r.observed_sentiment != label and r.predicted_sentiment == label
                    for r in ready
                )
                fn = sum(
                    r.observed_sentiment == label and r.predicted_sentiment != label
                    for r in ready
                )
                f1_by_class.append(
                    2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else 0.0
                )
            if not math.isclose(
                self.sentiment_accuracy, accuracy, abs_tol=1e-12
            ) or not math.isclose(
                self.sentiment_macro_f1, sum(f1_by_class) / 3, abs_tol=1e-12
            ):
                raise ValueError("sentiment metrics differ from raw confusion evidence")
        if self.report_fingerprint != canonical_fingerprint(
            self.model_dump(mode="json", exclude={"report_fingerprint"})
        ):
            raise ValueError("Gemini benchmark fingerprint mismatch")
        return self


def benchmark_heldout_gemini_advisory(
    labeled_events: IndiaFinancialEventCorpus,
    imported_causal_news: IndiaFinancialNlpCorpusReport,
    *,
    config: GeminiAdvisoryBenchmarkConfig | None = None,
) -> GeminiHeldoutBenchmarkReport:
    """Join only future-label heldout annotations and already-causal Gemini evidence.

    This function never calls Gemini, tunes a score using holdout results,
    estimates executable order P&L, or certifies source/news licences.
    """
    policy = config or GeminiAdvisoryBenchmarkConfig()
    labeled = IndiaFinancialEventCorpus.model_validate(
        labeled_events.model_dump(mode="python")
    )
    causal = IndiaFinancialNlpCorpusReport.model_validate(
        imported_causal_news.model_dump(mode="python")
    )
    if causal.status != "CAUSAL_RESEARCH_CORPUS_ONLY":
        raise ValueError("Gemini benchmark requires completed causal news corpus")
    samples = [
        item for item in labeled.samples if item.split == "INDEPENDENT_HOLDOUT"
    ]
    by_id = {item.source_id: item for item in causal.cases}
    if set(by_id) != {item.event_id for item in samples}:
        raise ValueError("all eligible causal cases need labeled holdout annotations")
    rows: list[GeminiHeldoutEventRow] = []
    for sample in samples:
        case = by_id[sample.event_id]
        if (
            sample.source_url != case.source_url
            or sample.decision_at != case.decision_at
            or sample.market_label_available_at != case.label_available_at
            or sample.captured_at < case.licensed_text_captured_at
            or sample.published_at > case.news_available_at
        ):
            raise ValueError("human label timing or source differs from causal news case")
        if not math.isclose(
            sample.forward_return_bps / 10000.0,
            case.observed_forward_return,
            rel_tol=1e-8, abs_tol=1e-10,
        ):
            raise ValueError("human market label differs from causal market return")
        score = (
            case.advisory_direction_score
            if case.advisory_status == "CAUSALLY_AVAILABLE" else None
        )
        prediction = (
            _sentiment(score, policy.neutral_abs_direction_score)
            if score is not None else None
        )
        payload = {
            "source_id": sample.event_id,
            "annotated_sample_fingerprint": sample.sample_fingerprint,
            "causal_case_fingerprint": case.case_fingerprint,
            "observed_sentiment": sample.sentiment_label,
            "observed_forward_return_bps": sample.forward_return_bps,
            "advisory_status": case.advisory_status,
            "advisory_direction_score": score,
            "predicted_sentiment": prediction,
        }
        rows.append(GeminiHeldoutEventRow(
            **payload, row_fingerprint=canonical_fingerprint(payload)
        ))
    rows.sort(key=lambda row: row.source_id)
    ready = [r for r in rows if r.predicted_sentiment is not None]
    class_labels: tuple[Sentiment, ...] = ("NEGATIVE", "NEUTRAL", "POSITIVE")
    counts = [
        sum(row.observed_sentiment == truth and row.predicted_sentiment == guess
            for row in ready)
        for truth in class_labels for guess in class_labels
    ]
    reasons = []
    if len(ready) < policy.minimum_evaluable_predictions:
        reasons.append("INSUFFICIENT_CAUSAL_GEMINI_PREDICTIONS")
    if any(
        sum(row.observed_sentiment == sentiment for row in ready)
        < policy.minimum_per_actual_label
        for sentiment in class_labels
    ):
        reasons.append("INSUFFICIENT_PER_CLASS_HOLDOUT_SUPPORT")
    accuracy = macro = None
    if not reasons:
        accuracy = sum(
            row.observed_sentiment == row.predicted_sentiment for row in ready
        ) / len(ready)
        scores = []
        for sentiment in class_labels:
            tp = sum(r.observed_sentiment == sentiment and
                     r.predicted_sentiment == sentiment for r in ready)
            fp = sum(r.observed_sentiment != sentiment and
                     r.predicted_sentiment == sentiment for r in ready)
            fn = sum(r.observed_sentiment == sentiment and
                     r.predicted_sentiment != sentiment for r in ready)
            scores.append(
                2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else 0.0
            )
        macro = sum(scores) / 3.0
    payload = {
        "contract_version": VERSION,
        "annotated_corpus_fingerprint": labeled.corpus_fingerprint,
        "causal_market_corpus_fingerprint": causal.report_fingerprint,
        "config": policy.model_dump(mode="json"),
        "config_fingerprint": policy.fingerprint,
        "imported_provider_category": "GEMINI_ADVISORY",
        "total_eligible_market_cases": len(causal.cases),
        "excluded_market_cases": len(causal.excluded),
        "labeled_holdout_events": len(rows),
        "causally_available_advisories": len(ready),
        "missing_or_late_advisories": len(rows) - len(ready),
        "measured_coverage": len(ready) / len(rows) if rows else 0.0,
        "sentiment_accuracy": accuracy,
        "sentiment_macro_f1": macro,
        "ordered_confusion_counts": counts,
        "rows": [row.model_dump(mode="json") for row in rows],
        "status": "INCONCLUSIVE" if reasons else "DESCRIPTIVE_RESEARCH_ONLY",
        "reason_codes": sorted(reasons),
        "independent_gemini_api_execution_verified": False,
        "sentiment_labels_independently_adjudicated": False,
        "independent_news_and_content_rights_verified": False,
        "independently_verified_market_label_custody": False,
        "independently_verified_incremental_trading_value": False,
        "llm_order_authority_allowed": False,
        "can_authorize_capital": False,
    }
    return GeminiHeldoutBenchmarkReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Offline research-only Gemini advisory sentiment evaluation"
    )
    parser.add_argument("human_labeled_event_corpus", type=Path)
    parser.add_argument("causal_news_ai_market_corpus", type=Path)
    parser.add_argument("--minimum-evaluable", type=int, default=20)
    args = parser.parse_args()
    report = benchmark_heldout_gemini_advisory(
        IndiaFinancialEventCorpus.model_validate_json(
            args.human_labeled_event_corpus.read_text(encoding="utf-8")
        ),
        IndiaFinancialNlpCorpusReport.model_validate_json(
            args.causal_news_ai_market_corpus.read_text(encoding="utf-8")
        ),
        config=GeminiAdvisoryBenchmarkConfig(
            minimum_evaluable_predictions=args.minimum_evaluable,
        ),
    )
    print(json.dumps(report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
