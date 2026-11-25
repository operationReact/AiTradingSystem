from __future__ import annotations

from datetime import UTC, datetime
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError
from test_india_gemini_sentiment_benchmark import _case, _causal_report, _fixture, _policy

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.india_financial_nlp_corpus import IndiaFinancialNlpCorpusReport
from aitrading_intelligence.india_gemini_sentiment_benchmark import (
    benchmark_heldout_gemini_advisory,
)
from aitrading_intelligence.india_nlp_event_corpus import (
    IndiaFinancialEventSample,
    freeze_india_financial_event_corpus,
)
from aitrading_intelligence.india_nlp_partition_bridge import (
    IndiaNlpPartitionBridgeReport,
    IndiaNlpProspectivePartitionPlan,
    NlpPartitionWindow,
    bridge_nlp_prospective_partitions,
    freeze_nlp_partition_plan,
)

IST = ZoneInfo("Asia/Kolkata")


def _date(day: int) -> datetime:
    return datetime(2026, 10, day, tzinfo=IST)


def _windows() -> list[NlpPartitionWindow]:
    return [
        NlpPartitionWindow(
            split="TRAIN", from_inclusive=_date(20), to_exclusive=_date(21)
        ),
        NlpPartitionWindow(
            split="CALIBRATION", from_inclusive=_date(21), to_exclusive=_date(22)
        ),
        NlpPartitionWindow(
            split="INDEPENDENT_HOLDOUT",
            from_inclusive=_date(23), to_exclusive=_date(26),
        ),
    ]


def _setup(*, cases_missing: bool = False, min_cases: int = 1):
    events, heldout = _fixture()
    train, calibration = events.samples[:2]
    cases = [
        _case(train, score=-0.75), _case(calibration, score=0.05),
        *heldout.cases,
    ]
    if cases_missing:
        cases.pop(0)
    full = _causal_report(cases)
    plan = freeze_nlp_partition_plan(
        registered_at=_date(19),
        windows=_windows(),
        target_symbol="RELIANCE-EQ",
        source_config_fingerprint=full.config_fingerprint,
        minimum_cases_per_split=min_cases,
    )
    return plan, events, full


def _replace_sample(events, index: int, **updates):
    samples = list(events.samples)
    payload = samples[index].model_dump(mode="json", exclude={"sample_fingerprint"})
    payload.update(updates)
    # Schema normalizes numerical forward returns to float before fingerprinting.
    payload["forward_return_bps"] = float(payload["forward_return_bps"])
    payload["sample_fingerprint"] = canonical_fingerprint(payload)
    samples[index] = IndiaFinancialEventSample.model_validate(payload)
    return freeze_india_financial_event_corpus(
        samples, research_created_at=events.research_created_at
    )


def test_freeze_is_deterministic_and_never_external_timestamp_attested():
    plan, events, full = _setup()
    report = bridge_nlp_prospective_partitions(plan, events, full)
    assert report == bridge_nlp_prospective_partitions(plan, events, full)
    assert report.status == "SELF_DECLARED_CAUSAL_PARTITION_ONLY"
    assert report.counts_by_split == {
        "TRAIN": 1, "CALIBRATION": 1, "INDEPENDENT_HOLDOUT": 3
    }
    assert len(report.joined_rows) == 5
    assert len(report.holdout_causal_slice.cases) == 3
    assert len(report.holdout_causal_slice.excluded) == 0
    assert {c.source_id for c in report.holdout_causal_slice.cases} == {
        sample.event_id for sample in events.samples
        if sample.split == "INDEPENDENT_HOLDOUT"
    }
    assert report.source_causal_corpus_fingerprint == full.report_fingerprint
    assert report.annotated_corpus_fingerprint == events.corpus_fingerprint
    assert report.plan.independently_witnessed_registration is False
    assert report.external_timestamp_witness_verified is False
    assert report.independent_news_rights_verified is False
    assert report.market_data_custody_verified is False
    assert report.independent_human_annotation_verified is False
    assert report.actual_gemini_provider_call_verified is False
    assert report.paper_shadow_live_approval is False
    assert report.can_authorize_capital is False
    assert IndiaNlpPartitionBridgeReport.model_validate_json(
        report.model_dump_json()
    ) == report


def test_heldout_slice_runs_existing_gemini_benchmark_without_train_leakage():
    plan, events, full = _setup()
    bridge = bridge_nlp_prospective_partitions(plan, events, full)
    scored = benchmark_heldout_gemini_advisory(
        events, bridge.holdout_causal_slice, config=_policy()
    )
    assert scored.status == "DESCRIPTIVE_RESEARCH_ONLY"
    assert scored.labeled_holdout_events == 3
    assert scored.sentiment_accuracy == 1
    assert scored.can_authorize_capital is False
    input_fields = set(events.inputs_for("INDEPENDENT_HOLDOUT")[0].model_dump())
    assert "forward_return_bps" not in input_fields
    assert "sentiment_label" not in input_fields
    assert "market_label_available_at" not in input_fields


def test_plan_cannot_be_registered_after_first_training_capture():
    _, _, full = _setup()
    with pytest.raises(ValueError, match="must predate first TRAIN"):
        freeze_nlp_partition_plan(
            registered_at=_date(20),
            windows=_windows(),
            target_symbol="RELIANCE-EQ",
            source_config_fingerprint=full.config_fingerprint,
        )


def test_plan_rejects_overlapping_or_missing_splits_and_naive_times():
    _, _, full = _setup()
    wrong = _windows()
    wrong[1] = NlpPartitionWindow(
        split="CALIBRATION", from_inclusive=_date(20), to_exclusive=_date(22)
    )
    with pytest.raises(ValueError, match="windows overlap"):
        freeze_nlp_partition_plan(
            registered_at=_date(19), windows=wrong, target_symbol="RELIANCE-EQ",
            source_config_fingerprint=full.config_fingerprint,
        )
    wrong = _windows()
    wrong[1] = NlpPartitionWindow(
        split="TRAIN", from_inclusive=_date(21), to_exclusive=_date(22)
    )
    with pytest.raises(ValueError, match="requires TRAIN"):
        freeze_nlp_partition_plan(
            registered_at=_date(19), windows=wrong, target_symbol="RELIANCE-EQ",
            source_config_fingerprint=full.config_fingerprint,
        )
    with pytest.raises(ValueError, match="timezone"):
        freeze_nlp_partition_plan(
            registered_at=datetime(2026, 10, 19), windows=_windows(),
            target_symbol="RELIANCE-EQ",
            source_config_fingerprint=full.config_fingerprint,
        )


def test_plan_rejects_any_missing_event_or_subset_cherry_picking():
    plan, events, full = _setup(cases_missing=True)
    with pytest.raises(ValueError, match="cover every eligible"):
        bridge_nlp_prospective_partitions(plan, events, full)


def test_holdout_labels_are_not_allowed_to_move_between_frozen_windows():
    plan, events, full = _setup()
    wrong = _replace_sample(events, 2, split="CALIBRATION")
    with pytest.raises(ValueError, match="outside frozen partition window"):
        bridge_nlp_prospective_partitions(plan, wrong, full)


def test_changed_news_source_or_market_outcome_fails_closed():
    plan, events, full = _setup()
    altered = _replace_sample(events, 2, source_url="https://example.org/tampered")
    with pytest.raises(ValueError, match="source or point-in-time"):
        bridge_nlp_prospective_partitions(plan, altered, full)
    altered = _replace_sample(events, 2, forward_return_bps=999)
    with pytest.raises(ValueError, match="returns do not match"):
        bridge_nlp_prospective_partitions(plan, altered, full)


def test_wrong_instrument_and_changed_source_config_are_not_accepted():
    plan, events, full = _setup()
    changed_plan = freeze_nlp_partition_plan(
        registered_at=_date(19), windows=_windows(),
        target_symbol="INFY-EQ",
        source_config_fingerprint=full.config_fingerprint,
    )
    with pytest.raises(ValueError, match="instrument"):
        bridge_nlp_prospective_partitions(changed_plan, events, full)
    changed_plan = freeze_nlp_partition_plan(
        registered_at=_date(19), windows=_windows(),
        target_symbol="RELIANCE-EQ",
        source_config_fingerprint="f" * 64,
    )
    with pytest.raises(ValueError, match="configuration differs"):
        bridge_nlp_prospective_partitions(changed_plan, events, full)


def test_frozen_plan_sample_sufficiency_prevents_optimistic_metrics():
    plan, events, full = _setup(min_cases=2)
    with pytest.raises(ValueError, match="insufficient eligible"):
        bridge_nlp_prospective_partitions(plan, events, full)


def test_parent_exclusions_cannot_be_attributed_to_holdout():
    plan, events, full = _setup()
    payload = full.model_dump(mode="json", exclude={"report_fingerprint"})
    payload["excluded"] = {"outside-cohort": "NO_DECLARED_RESEARCH_TEXT_RIGHTS"}
    full = IndiaFinancialNlpCorpusReport(
        **payload, report_fingerprint=canonical_fingerprint(payload)
    )
    report = bridge_nlp_prospective_partitions(plan, events, full)
    assert report.holdout_causal_slice.excluded == {}
    assert report.source_causal_corpus_fingerprint == full.report_fingerprint


def test_report_cannot_forge_counts_holdout_membership_or_capital():
    plan, events, full = _setup()
    result = bridge_nlp_prospective_partitions(plan, events, full)
    payload = result.model_dump(mode="json")
    payload["counts_by_split"]["TRAIN"] = 100
    with pytest.raises(ValidationError):
        IndiaNlpPartitionBridgeReport.model_validate(payload)
    payload = result.model_dump(mode="json")
    payload["holdout_causal_slice"]["cases"].append(
        full.cases[0].model_dump(mode="json")
    )
    payload["holdout_causal_slice"]["cases"].sort(key=lambda case: case["source_id"])
    payload["holdout_causal_slice"]["report_fingerprint"] = canonical_fingerprint({
        k: v for k, v in payload["holdout_causal_slice"].items()
        if k != "report_fingerprint"
    })
    payload["report_fingerprint"] = canonical_fingerprint({
        k: v for k, v in payload.items() if k != "report_fingerprint"
    })
    with pytest.raises(ValidationError, match="non-holdout"):
        IndiaNlpPartitionBridgeReport.model_validate(payload)
    payload = result.model_dump(mode="json")
    payload["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        IndiaNlpPartitionBridgeReport.model_validate(payload)


def test_plan_and_report_tamper_fail_even_if_self_declared():
    plan, events, full = _setup()
    edited_plan = plan.model_dump(mode="json")
    edited_plan["registered_at"] = datetime(2026, 10, 18, tzinfo=UTC).isoformat()
    with pytest.raises(ValidationError, match="fingerprint mismatch"):
        IndiaNlpProspectivePartitionPlan.model_validate(edited_plan)
    report = bridge_nlp_prospective_partitions(plan, events, full)
    edited_report = report.model_dump(mode="json")
    edited_report["joined_rows"][0]["source_id"] = "different-id"
    with pytest.raises(ValidationError):
        IndiaNlpPartitionBridgeReport.model_validate(edited_report)
