from __future__ import annotations

from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_financial_nlp_corpus import (
    IndiaFinancialNlpCorpusConfig,
    IndiaFinancialNlpCorpusReport,
    IndiaNlpCausalCase,
)
from aitrading_intelligence.india_gemini_sentiment_benchmark import (
    GeminiAdvisoryBenchmarkConfig,
    GeminiHeldoutBenchmarkReport,
    benchmark_heldout_gemini_advisory,
)
from aitrading_intelligence.india_nlp_event_corpus import (
    build_india_financial_event_sample,
    freeze_india_financial_event_corpus,
)

IST = ZoneInfo("Asia/Kolkata")
HASH = "a" * 64


def _sample(day: int, split: str, sentiment: str, bps: float):
    decision = datetime(2026, 10, day, 10, 0, tzinfo=IST)
    source_id = f"synthetic-gemini-news-{day}"
    return build_india_financial_event_sample(
        event_id=source_id,
        split=split,
        source_kind="SYNTHETIC",
        source_url=f"https://example.org/synthetic-news/{source_id}",
        rights_basis="SYNTHETIC_ORIGINAL",
        headline=f"Original synthetic benchmark research event on day {day}",
        published_at=decision - timedelta(minutes=15),
        captured_at=decision - timedelta(minutes=5),
        decision_at=decision,
        market_label_from=decision + timedelta(minutes=5),
        market_label_to=decision + timedelta(minutes=10),
        market_label_available_at=decision + timedelta(minutes=15),
        sentiment_label=sentiment,
        forward_return_bps=bps,
    )


def _case(sample, *, score: float | None = None,
          change_source: bool = False,
          change_return: bool = False,
          change_label_time: bool = False) -> IndiaNlpCausalCase:
    ready = score is not None
    payload = {
        "source_id": sample.event_id,
        "publisher": "Original Synthetic Fixture",
        "source_url": (
            "https://example.org/changed-source"
            if change_source else sample.source_url
        ),
        "content_hash": HASH,
        "declared_research_rights_reference": "synthetic-fixture-only",
        "news_available_at": sample.decision_at - timedelta(minutes=10),
        "licensed_text_captured_at": sample.captured_at,
        "decision_at": sample.decision_at,
        "label_available_at": (
            sample.market_label_available_at + timedelta(minutes=5)
            if change_label_time else sample.market_label_available_at
        ),
        "target_symbol": "RELIANCE-EQ",
        "observed_forward_return": (
            sample.forward_return_bps / 10_000 + (0.001 if change_return else 0.0)
        ),
        "hypothetical_long_after_assumed_cost_return": (
            sample.forward_return_bps / 10_000 - 0.0001
        ),
        "advisory_analysis_id": f"synthetic-analysis-{sample.event_id}" if ready else None,
        "advisory_evidence_available_at": (
            sample.decision_at - timedelta(minutes=1) if ready else None
        ),
        "advisory_direction_score": score,
        "advisory_confidence": 0.75 if ready else None,
        "advisory_status": "CAUSALLY_AVAILABLE" if ready else "MISSING_OR_LATE",
        "text_licence_independently_verified": False,
        "original_market_source_independently_verified": False,
        "trading_order_authorized": False,
    }
    temporary = IndiaNlpCausalCase.model_construct(**payload, case_fingerprint=HASH)
    return IndiaNlpCausalCase(
        **payload,
        case_fingerprint=canonical_fingerprint(
            temporary.model_dump(mode="json", exclude={"case_fingerprint"})
        ),
    )


def _causal_report(cases: list[IndiaNlpCausalCase],
                   *, status: str = "CAUSAL_RESEARCH_CORPUS_ONLY"):
    ordered = sorted(cases, key=lambda case: case.source_id)
    payload = {
        "contract_version": "india-financial-nlp-causal-corpus.v1",
        "config_fingerprint": IndiaFinancialNlpCorpusConfig().fingerprint,
        "market_export_fingerprint": HASH,
        "news_export_fingerprint": HASH,
        "ai_export_fingerprint": HASH,
        "target_spec_fingerprint": HASH,
        "licensed_text_manifest_fingerprint": HASH,
        "cases": [case.model_dump(mode="json") for case in ordered],
        "excluded": {},
        "status": status,
        "reason_codes": [] if status != "INCONCLUSIVE" else ["INSUFFICIENT_CASES"],
        "independent_news_licensing_verified": False,
        "independent_market_source_verified": False,
        "prospectively_frozen_holdout_verified": False,
        "financial_nlp_model_benchmark_completed": False,
        "order_execution_allowed": False,
        "can_authorize_capital": False,
    }
    return IndiaFinancialNlpCorpusReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )


def _fixture(*, late: bool = False, source: bool = False,
             returns: bool = False, labels: bool = False):
    train = _sample(20, "TRAIN", "NEGATIVE", -9)
    calibration = _sample(21, "CALIBRATION", "NEUTRAL", 0)
    heldout = [
        _sample(23, "INDEPENDENT_HOLDOUT", "NEGATIVE", -12),
        _sample(24, "INDEPENDENT_HOLDOUT", "NEUTRAL", 0),
        _sample(25, "INDEPENDENT_HOLDOUT", "POSITIVE", 16),
    ]
    corpus = freeze_india_financial_event_corpus(
        [train, calibration, *heldout],
        research_created_at=datetime(2026, 10, 30, 10, tzinfo=UTC),
    )
    scores = [-0.75, 0.05, 0.8]
    cases = [
        _case(
            sample, score=None if late and i == 2 else scores[i],
            change_source=source and i == 0,
            change_return=returns and i == 0,
            change_label_time=labels and i == 0,
        )
        for i, sample in enumerate(heldout)
    ]
    return corpus, _causal_report(cases)


def _policy():
    return GeminiAdvisoryBenchmarkConfig(
        minimum_evaluable_predictions=3, minimum_per_actual_label=1
    )


def test_gemini_holdout_benchmark_is_replayable_and_never_proves_real_performance():
    events, causal = _fixture()
    report = benchmark_heldout_gemini_advisory(events, causal, config=_policy())
    assert report == benchmark_heldout_gemini_advisory(events, causal, config=_policy())
    assert report.status == "DESCRIPTIVE_RESEARCH_ONLY"
    assert report.labeled_holdout_events == 3
    assert report.measured_coverage == 1
    assert report.sentiment_accuracy == 1
    assert report.sentiment_macro_f1 == 1
    assert report.ordered_confusion_counts == [1, 0, 0, 0, 1, 0, 0, 0, 1]
    assert report.source_advisory_config.advisory_provider == "GEMINI"
    assert (
        report.source_advisory_config_fingerprint
        == IndiaFinancialNlpCorpusConfig().fingerprint
    )
    assert report.independent_gemini_api_execution_verified is False
    assert report.sentiment_labels_independently_adjudicated is False
    assert report.independent_news_and_content_rights_verified is False
    assert report.independently_verified_market_label_custody is False
    assert report.independently_verified_incremental_trading_value is False
    assert report.llm_order_authority_allowed is False
    assert report.can_authorize_capital is False
    assert GeminiHeldoutBenchmarkReport.model_validate_json(
        report.model_dump_json()
    ) == report


def test_missing_or_late_gemini_is_excluded_from_accuracy_and_becomes_inconclusive():
    events, causal = _fixture(late=True)
    report = benchmark_heldout_gemini_advisory(events, causal, config=_policy())
    assert report.causally_available_advisories == 2
    assert report.missing_or_late_advisories == 1
    assert report.measured_coverage == pytest.approx(2 / 3)
    assert report.rows[-1].advisory_status == "MISSING_OR_LATE"
    assert report.rows[-1].advisory_direction_score is None
    assert report.rows[-1].predicted_sentiment is None
    assert report.status == "INCONCLUSIVE"
    assert report.sentiment_accuracy is None
    assert report.sentiment_macro_f1 is None
    assert "INSUFFICIENT_CAUSAL_GEMINI_PREDICTIONS" in report.reason_codes
    assert "INSUFFICIENT_PER_CLASS_HOLDOUT_SUPPORT" in report.reason_codes


@pytest.mark.parametrize(
    ("option", "error"),
    [
        ({"source": True}, "human label timing or source differs"),
        ({"returns": True}, "human market label differs"),
        ({"labels": True}, "human label timing or source differs"),
    ],
)
def test_corpus_join_rejects_changed_source_or_future_market_labels(option, error):
    events, causal = _fixture(**option)
    with pytest.raises(ValueError, match=error):
        benchmark_heldout_gemini_advisory(events, causal, config=_policy())


def test_missing_causal_case_or_nonholdout_matching_is_rejected():
    events, causal = _fixture()
    with pytest.raises(ValueError, match="holdout annotations"):
        benchmark_heldout_gemini_advisory(
            events, _causal_report(causal.cases[:-1]), config=_policy()
        )


def test_inconclusive_source_corpus_cannot_be_promoted_to_benchmark():
    events, causal = _fixture()
    with pytest.raises(ValueError, match="completed causal news corpus"):
        benchmark_heldout_gemini_advisory(
            events, _causal_report(causal.cases, status="INCONCLUSIVE"),
            config=_policy(),
        )


def test_report_metrics_labels_and_capital_claims_are_fail_closed():
    events, causal = _fixture()
    original = benchmark_heldout_gemini_advisory(events, causal, config=_policy())
    tampered = original.model_dump(mode="json")
    tampered["sentiment_accuracy"] = 0.5
    with pytest.raises(ValidationError, match="sentiment metrics"):
        GeminiHeldoutBenchmarkReport.model_validate({
            **tampered,
            "report_fingerprint": canonical_fingerprint({
                k: v for k, v in tampered.items() if k != "report_fingerprint"
            }),
        })
    tampered = original.model_dump(mode="json")
    tampered["rows"][0]["predicted_sentiment"] = "POSITIVE"
    with pytest.raises(ValidationError):
        GeminiHeldoutBenchmarkReport.model_validate(tampered)
    tampered = original.model_dump(mode="json")
    tampered["llm_order_authority_allowed"] = True
    with pytest.raises(ValidationError):
        GeminiHeldoutBenchmarkReport.model_validate(tampered)
    tampered = original.model_dump(mode="json")
    tampered["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        GeminiHeldoutBenchmarkReport.model_validate(tampered)


def test_insufficient_default_holdout_support_never_publishes_quality():
    events, causal = _fixture()
    report = benchmark_heldout_gemini_advisory(events, causal)
    assert report.status == "INCONCLUSIVE"
    assert report.sentiment_accuracy is None
    assert report.sentiment_macro_f1 is None
    assert report.reason_codes == [
        "INSUFFICIENT_CAUSAL_GEMINI_PREDICTIONS",
        "INSUFFICIENT_PER_CLASS_HOLDOUT_SUPPORT",
    ]


def test_no_future_sentiment_or_market_labels_are_model_inputs():
    events, causal = _fixture()
    model_fields = set(events.inputs_for("INDEPENDENT_HOLDOUT")[0].model_dump())
    assert "sentiment_label" not in model_fields
    assert "forward_return_bps" not in model_fields
    assert "market_label_available_at" not in model_fields
    result = benchmark_heldout_gemini_advisory(events, causal, config=_policy())
    assert result.status == "DESCRIPTIVE_RESEARCH_ONLY"


def test_wrong_upstream_provider_or_advisory_model_cannot_be_called_gemini():
    events, causal = _fixture()
    with pytest.raises(ValueError, match="requires GEMINI"):
        benchmark_heldout_gemini_advisory(
            events, causal, config=_policy(),
            source_config=IndiaFinancialNlpCorpusConfig(advisory_provider="OPENAI"),
        )
    with pytest.raises(ValueError, match="configuration differs"):
        benchmark_heldout_gemini_advisory(
            events, causal, config=_policy(),
            source_config=IndiaFinancialNlpCorpusConfig(advisory_model="gemini-other"),
        )


def test_provider_identity_in_immutable_report_cannot_be_rewritten():
    events, causal = _fixture()
    original = benchmark_heldout_gemini_advisory(events, causal, config=_policy())
    payload = original.model_dump(mode="json")
    payload["source_advisory_config"]["advisory_provider"] = "OPENAI"
    payload["source_advisory_config_fingerprint"] = canonical_fingerprint(
        payload["source_advisory_config"]
    )
    payload["report_fingerprint"] = canonical_fingerprint({
        k: v for k, v in payload.items() if k != "report_fingerprint"
    })
    with pytest.raises(ValidationError, match="cannot attribute another provider"):
        GeminiHeldoutBenchmarkReport.model_validate(payload)
