from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.matched_paper_shadow_counterfactual import (
    MatchedMarketObservation,
    MatchedPaperShadowCounterfactualReport,
    PaperShadowEnrollment,
    RegisteredPaperShadowModel,
    build_matched_model_decision,
    evaluate_matched_paper_shadow,
    freeze_paper_shadow_enrollment,
)

START = datetime(2026, 11, 4, 9, 15, tzinfo=UTC)
A = "a" * 64
B = "b" * 64
C = "c" * 64
D = "d" * 64

MODELS = [
    RegisteredPaperShadowModel(
        model_id="alpha-paper", registry_fingerprint=A, role="PAPER_CHAMPION"
    ),
    RegisteredPaperShadowModel(
        model_id="beta-shadow", registry_fingerprint=B, role="SHADOW_CHALLENGER"
    ),
]


def _enrollment(*, minimum=3, enters=2, cost=10):
    return freeze_paper_shadow_enrollment(
        registered_at=START - timedelta(days=1),
        target_spec_fingerprint=C,
        models=MODELS,
        minimum_cases=minimum,
        minimum_enter_decisions=enters,
        assumed_round_trip_cost_bps=cost,
    )


def _case(
    i: int,
    champion: str,
    challenger: str,
    *,
    return_bps: float = 20.0,
    late_model: bool = False,
    changed_input: bool = False,
    outcome_time: datetime | None = None,
):
    when = START + timedelta(minutes=i)
    shared = f"{i:064x}"
    signals = [
        build_matched_model_decision(
            model_id="alpha-paper",
            registry_fingerprint=A,
            predicted_at=when,
            action=champion,
            input_fingerprint=shared,
        ),
        build_matched_model_decision(
            model_id="beta-shadow",
            registry_fingerprint=B,
            predicted_at=when + timedelta(seconds=1) if late_model else when,
            action=challenger,
            input_fingerprint=D if changed_input else shared,
        ),
    ]
    return MatchedMarketObservation(
        case_id=f"market-event-{i}",
        symbol="RELIANCE-EQ",
        decision_at=when,
        market_available_at=when - timedelta(minutes=1),
        news_available_at=when - timedelta(seconds=30),
        future_outcome_available_at=outcome_time or when + timedelta(minutes=5),
        market_source_fingerprint=C,
        news_source_fingerprint=D,
        input_fingerprint=shared,
        observed_forward_return_bps=return_bps,
        decisions=signals,
    )


def _cases():
    return [
        _case(1, "ENTER", "WAIT", return_bps=40),
        _case(2, "WAIT", "ENTER", return_bps=-20),
        _case(3, "ENTER", "ENTER", return_bps=15),
    ]


def _run(*, cases=None, origin="CI_SYNTHETIC", plan=None):
    return evaluate_matched_paper_shadow(
        plan or _enrollment(),
        _cases() if cases is None else cases,
        evaluated_at=START + timedelta(hours=1),
        capture_origin=origin,
    )


def test_matched_counterfactual_replays_without_broker_or_capital_authority():
    first = _run()
    assert first == _run()
    assert first.assessment == "INCONCLUSIVE"
    assert first.reason_codes == ["CI_FIXTURE_NOT_REAL_RUNTIME"]
    assert first.matched_case_count == 3
    assert first.cases_with_any_shadow_disagreement == 2
    assert first.case_rows[0].champion_action == "ENTER"
    champion, challenger = first.model_metrics
    assert champion.enter_count == 2
    assert champion.wait_count == 1
    assert champion.action_coverage == pytest.approx(2 / 3)
    assert champion.hypothetical_mean_net_bps == pytest.approx(17.5)
    assert champion.hypothetical_total_net_bps == pytest.approx(35)
    assert champion.hypothetical_max_drawdown_bps == 0
    assert challenger.role == "SHADOW_CHALLENGER"
    assert challenger.hypothetical_total_net_bps == pytest.approx(-25)
    assert challenger.hypothetical_max_drawdown_bps == pytest.approx(30)
    assert first.actual_paper_orders_submitted is False
    assert first.actual_shadow_orders_submitted is False
    assert first.actual_paper_fills_verified is False
    assert first.original_market_data_custody_verified is False
    assert first.genuine_live_runtime_soak_verified is False
    assert first.model_promotion_allowed is False
    assert first.can_authorize_capital is False
    assert MatchedPaperShadowCounterfactualReport.model_validate_json(
        first.model_dump_json()
    ) == first


def test_operator_runtime_label_is_still_not_runtime_soak_acceptance():
    output = _run(origin="RUNTIME_EXPORT")
    assert output.assessment == "COUNTERFACTUAL_RESEARCH_ONLY"
    assert output.reason_codes == []
    assert not output.genuine_live_runtime_soak_verified
    assert not output.actual_paper_fills_verified
    assert not output.can_authorize_capital


def test_unsupportive_cohort_does_not_claim_comparative_quality():
    result = _run(plan=_enrollment(minimum=4, enters=3), origin="RUNTIME_EXPORT")
    assert result.assessment == "INCONCLUSIVE"
    assert result.reason_codes == [
        "INSUFFICIENT_ENTER_DECISIONS", "INSUFFICIENT_MATCHED_CASES"
    ]


def test_holdout_market_labels_are_unavailable_during_model_decision():
    with pytest.raises(ValidationError, match="future label"):
        _case(1, "ENTER", "WAIT", outcome_time=START + timedelta(minutes=1))
    with pytest.raises(ValueError, match="unavailable future"):
        evaluate_matched_paper_shadow(
            _enrollment(), [
                _case(1, "ENTER", "WAIT"),
                _case(
                    2, "ENTER", "WAIT",
                    outcome_time=START + timedelta(hours=2),
                ),
            ],
            evaluated_at=START + timedelta(hours=1),
            capture_origin="CI_SYNTHETIC",
        )


def test_prediction_cannot_arrive_after_market_decision():
    with pytest.raises(ValidationError, match="unavailable market or news"):
        _case(1, "ENTER", "WAIT", late_model=True)


def test_same_cohort_must_use_identical_predecision_input_hash():
    with pytest.raises(ValidationError, match="same frozen input identity"):
        _case(1, "ENTER", "WAIT", changed_input=True)


def test_no_subset_or_missing_candidate_is_silently_scored():
    wrong = _cases()
    payload = wrong[0].model_dump(mode="json")
    payload["decisions"] = payload["decisions"][:1]
    with pytest.raises(ValidationError):
        MatchedMarketObservation.model_validate(payload)
    payload["decisions"].append(
        build_matched_model_decision(
            model_id="gamma-unregistered",
            registry_fingerprint=A,
            predicted_at=START + timedelta(minutes=1),
            action="ENTER",
            input_fingerprint=f"{1:064x}",
        ).model_dump(mode="json")
    )
    with pytest.raises(ValueError, match="roster differs"):
        _run(cases=[
            MatchedMarketObservation.model_validate(payload),
            *wrong[1:],
        ])


def test_duplicate_event_id_cannot_be_double_counted():
    with pytest.raises(ValueError, match="duplicate source case"):
        _run(cases=[_cases()[0], _cases()[0], _cases()[1]])


def test_enrollment_must_precede_all_candidate_predictions():
    invalid = freeze_paper_shadow_enrollment(
        registered_at=START + timedelta(minutes=1),
        target_spec_fingerprint=C,
        models=MODELS,
        minimum_cases=3,
        minimum_enter_decisions=2,
    )
    with pytest.raises(ValueError, match="must predate"):
        _run(plan=invalid)


def test_registry_and_candidate_roster_cannot_be_changed_after_freeze():
    cases = _cases()
    payload = cases[0].model_dump(mode="json")
    payload["decisions"][0]["registry_fingerprint"] = D
    payload["decisions"][0]["decision_fingerprint"] = canonical_fingerprint({
        key: value for key, value in payload["decisions"][0].items()
        if key != "decision_fingerprint"
    })
    with pytest.raises(ValueError, match="registry lineage"):
        _run(cases=[MatchedMarketObservation.model_validate(payload), *cases[1:]])


def test_different_champion_or_unsorted_model_registry_fails():
    with pytest.raises(ValidationError, match="exactly one PAPER champion"):
        freeze_paper_shadow_enrollment(
            registered_at=START - timedelta(days=1),
            target_spec_fingerprint=C,
            models=[
                RegisteredPaperShadowModel(
                    model_id="alpha-paper", registry_fingerprint=A,
                    role="SHADOW_CHALLENGER",
                ),
                MODELS[1],
            ],
        )
    with pytest.raises(ValidationError, match="sorted by model ID"):
        PaperShadowEnrollment.model_validate({
            **_enrollment().model_dump(mode="json"),
            "models": list(reversed(
                [m.model_dump(mode="json") for m in MODELS]
            )),
        })


def test_no_enter_case_has_null_mean_but_no_fabricated_executable_alpha():
    cases = [
        _case(1, "WAIT", "ENTER"),
        _case(2, "ABSTAIN", "ENTER"),
    ]
    result = _run(
        cases=cases,
        plan=_enrollment(minimum=2, enters=1),
    )
    champion = result.model_metrics[0]
    assert champion.enter_count == 0
    assert champion.hypothetical_mean_net_bps is None
    assert champion.hypothetical_total_net_bps == 0
    assert "INSUFFICIENT_ENTER_DECISIONS" in result.reason_codes


def test_low_sample_results_and_metrics_are_never_live_gate():
    original = _run()
    tampered = original.model_dump(mode="json")
    tampered["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        MatchedPaperShadowCounterfactualReport.model_validate(tampered)
    tampered = original.model_dump(mode="json")
    tampered["model_metrics"][0]["enter_count"] = 99
    tampered["report_fingerprint"] = canonical_fingerprint({
        key: value for key, value in tampered.items()
        if key != "report_fingerprint"
    })
    with pytest.raises(ValidationError, match="counts do not reconcile"):
        MatchedPaperShadowCounterfactualReport.model_validate(tampered)
    tampered = original.model_dump(mode="json")
    tampered["cases_with_any_shadow_disagreement"] = 0
    tampered["report_fingerprint"] = canonical_fingerprint({
        key: value for key, value in tampered.items()
        if key != "report_fingerprint"
    })
    with pytest.raises(ValidationError, match="disagreement totals"):
        MatchedPaperShadowCounterfactualReport.model_validate(tampered)
