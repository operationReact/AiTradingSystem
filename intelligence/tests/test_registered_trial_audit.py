from __future__ import annotations

from datetime import timedelta
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError
from test_india_holdout_freeze import _inputs
from test_structured_alpha_tournament import _baseline, _candidate, _sha

from aitrading_intelligence.incremental_economic_value import create_net_return_series
from aitrading_intelligence.registered_trial_audit import (
    RegisteredTrial,
    RegisteredTrialAuditReport,
    RegisteredTrialBudget,
    TrialOutcome,
    audit_registered_trials,
    register_trial_budget,
)
from aitrading_intelligence.structured_alpha_tournament import TournamentCandidate

IST = ZoneInfo("Asia/Kolkata")


def _aligned(source: TournamentCandidate) -> TournamentCandidate:
    # Use the two frozen NSE-session dates instead of the generic tournament fixture dates.
    from datetime import datetime

    count = len(source.returns.decision_times)
    first = datetime(2026, 10, 29, 9, 20, tzinfo=IST)
    times = [
        first + timedelta(days=index // 40, minutes=5 * (index % 40))
        for index in range(count)
    ]
    returns = source.returns
    return TournamentCandidate(
        prediction=source.prediction,
        returns=create_net_return_series(
            candidate_id=returns.candidate_id,
            model_version_ref=returns.model_version_ref,
            source_result_fingerprint=returns.source_result_fingerprint,
            target_binding_fingerprint=returns.target_binding_fingerprint,
            fold_binding_fingerprint=returns.fold_binding_fingerprint,
            economic_cost_binding_fingerprint=returns.economic_cost_binding_fingerprint,
            observation_fingerprints=returns.observation_fingerprints,
            decision_times=times,
            net_returns_after_costs=returns.net_returns_after_costs,
        ),
    )


def _trial(candidate: TournamentCandidate) -> RegisteredTrial:
    return RegisteredTrial(
        candidate_id=candidate.prediction.candidate_id,
        model_version_ref=candidate.prediction.model_version_ref,
        family="LIGHTGBM",
        parameter_spec_fingerprint=_sha(
            "predeclared-configuration-" + candidate.prediction.candidate_id
        ),
    )


def _case(n: int = 3):
    freeze = _inputs()[0]
    baseline = _aligned(_baseline(80))
    candidates = [
        _aligned(_candidate(f"model-{index}", 0.006 - index * 0.0004, count=80))
        for index in range(n)
    ]
    plan = register_trial_budget(
        freeze,
        [_trial(candidate) for candidate in candidates],
        baseline_candidate_id=baseline.prediction.candidate_id,
        baseline_model_version_ref=baseline.prediction.model_version_ref,
        registered_at=freeze.recorded_at + timedelta(minutes=5),
    )
    now = freeze.planned_holdout_to + timedelta(minutes=5)
    return freeze, plan, baseline, candidates, now


def _outcome(candidate: TournamentCandidate) -> TrialOutcome:
    return TrialOutcome(
        candidate_id=candidate.prediction.candidate_id,
        disposition="EVALUATED",
        model_source_result_fingerprint=candidate.prediction.source_result_fingerprint,
    )


def test_failed_trial_is_counted_in_familywise_economic_threshold() -> None:
    freeze, plan, baseline, candidates, now = _case()
    outcomes = [
        _outcome(candidates[0]),
        _outcome(candidates[1]),
        TrialOutcome(candidate_id=candidates[2].prediction.candidate_id,
                     disposition="TRAINING_FAILED"),
    ]
    result = audit_registered_trials(
        freeze, plan, baseline, outcomes, candidates[:2], evaluation_started_at=now,
    )
    backwards = audit_registered_trials(
        freeze, plan, baseline, list(reversed(outcomes)),
        list(reversed(candidates[:2])), evaluation_started_at=now,
    )
    assert result == backwards
    assert result.total_declared_trial_budget == 3
    assert result.evaluated_trial_count == 2
    assert result.observed_trial_dispositions == 3
    assert result.confidence_after_declared_trials == pytest.approx(1 - 0.05 / 3)
    assert result.declared_trials_familywise_adjusted is True
    assert result.tournament_report_fingerprint is not None
    assert result.undeclared_trials_independently_excluded is False
    assert result.externally_timestamped is False
    assert result.promotion_allowed is False
    assert result.can_authorize_capital is False


def test_missing_attempt_cannot_be_silently_dropped_from_trial_budget() -> None:
    freeze, plan, baseline, candidates, now = _case()
    result = audit_registered_trials(
        freeze, plan, baseline, [_outcome(candidates[0])], [candidates[0]],
        evaluation_started_at=now,
    )
    assert result.status == "INCONCLUSIVE"
    assert "UNACCOUNTED_DECLARED_TRIALS" in result.reason_codes
    assert result.research_shortlisted_ids == []
    assert result.declared_trials_familywise_adjusted is False


def test_six_declared_trials_cannot_use_unsupported_99_percent_confidence() -> None:
    freeze, plan, baseline, candidates, now = _case(6)
    results = [
        _outcome(candidates[0]),
        *[TrialOutcome(candidate_id=item.prediction.candidate_id,
                       disposition="CANCELLED_BEFORE_EVALUATION")
          for item in candidates[1:]],
    ]
    audit = audit_registered_trials(
        freeze, plan, baseline, results, [candidates[0]],
        evaluation_started_at=now,
    )
    assert audit.status == "INCONCLUSIVE"
    assert "TRIAL_BUDGET_EXCEEDS_SUPPORTED_CONFIDENCE" in audit.reason_codes
    assert audit.tournament_report_fingerprint is None
    assert audit.confidence_after_declared_trials is None


def test_unregistered_trial_or_changed_version_fails_closed() -> None:
    freeze, plan, baseline, candidates, now = _case()
    unknown = _aligned(_candidate("not-declared", 0.009, count=80))
    with pytest.raises(ValueError, match="unregistered trial dispositions"):
        audit_registered_trials(
            freeze, plan, baseline, [_outcome(unknown)], [unknown],
            evaluation_started_at=now,
        )
    wrong = candidates[0].model_copy(update={"prediction": unknown.prediction})
    with pytest.raises(ValueError, match="tournament prediction/net-return mismatches"):
        audit_registered_trials(
            freeze, plan, baseline, [_outcome(candidates[0])], [wrong],
            evaluation_started_at=now,
        )


def test_mismatched_result_fingerprint_cannot_be_counted_as_registered() -> None:
    freeze, plan, baseline, candidates, now = _case()
    bogus = TrialOutcome(
        candidate_id=candidates[0].prediction.candidate_id,
        disposition="EVALUATED",
        model_source_result_fingerprint=_sha("forged"),
    )
    with pytest.raises(ValueError, match="result or version differs"):
        audit_registered_trials(
            freeze, plan, baseline, [bogus], [candidates[0]],
            evaluation_started_at=now,
        )


def test_trial_plan_must_precede_data_and_screen_after_holdout_close() -> None:
    freeze, plan, baseline, candidates, now = _case()
    with pytest.raises(ValueError, match="before the holdout opens"):
        register_trial_budget(
            freeze, [_trial(candidates[0])],
            baseline_candidate_id=baseline.prediction.candidate_id,
            baseline_model_version_ref=baseline.prediction.model_version_ref,
            registered_at=freeze.planned_holdout_from + timedelta(minutes=1),
        )
    with pytest.raises(ValueError, match="before holdout window ends"):
        audit_registered_trials(
            freeze, plan, baseline, [_outcome(candidates[0])], [candidates[0]],
            evaluation_started_at=freeze.planned_holdout_from,
        )


def test_duplicate_dispositions_and_mutated_plan_are_rejected() -> None:
    freeze, plan, baseline, candidates, now = _case()
    with pytest.raises(ValueError, match="unique candidate identities"):
        audit_registered_trials(
            freeze, plan, baseline,
            [_outcome(candidates[0]), _outcome(candidates[0])], [candidates[0]],
            evaluation_started_at=now,
        )
    with pytest.raises(ValidationError, match="registered trial budget fingerprint"):
        RegisteredTrialBudget.model_validate({
            **plan.model_dump(mode="json"),
            "baseline_candidate_id": "rewritten-baseline",
        })


def test_report_is_tamper_evident_and_never_capital_eligible() -> None:
    freeze, plan, baseline, candidates, now = _case()
    report = audit_registered_trials(
        freeze, plan, baseline,
        [_outcome(candidates[0]),
         TrialOutcome(candidate_id=candidates[1].prediction.candidate_id,
                      disposition="TRAINING_FAILED"),
         TrialOutcome(candidate_id=candidates[2].prediction.candidate_id,
                      disposition="TRAINING_FAILED")],
        [candidates[0]], evaluation_started_at=now,
    )
    with pytest.raises(ValidationError, match="trial audit report fingerprint"):
        RegisteredTrialAuditReport.model_validate({
            **report.model_dump(mode="json"),
            "observed_trial_dispositions": 2,
        })


def test_wrong_holdout_plan_and_unregistered_family_are_rejected() -> None:
    freeze, plan, baseline, candidates, now = _case()
    bad_family = _trial(candidates[0]).model_copy(update={"family": "PYTORCH"})
    with pytest.raises(ValueError, match="not included in the freeze plan"):
        register_trial_budget(
            freeze, [bad_family],
            baseline_candidate_id=baseline.prediction.candidate_id,
            baseline_model_version_ref=baseline.prediction.model_version_ref,
            registered_at=freeze.recorded_at + timedelta(minutes=5),
        )
    wrong = plan.model_copy(
        update={"holdout_freeze_plan_fingerprint": _sha("wrong-freeze")}
    )
    with pytest.raises(ValidationError, match="budget fingerprint"):
        audit_registered_trials(
            freeze, wrong, baseline,
            [_outcome(candidates[0])], [candidates[0]],
            evaluation_started_at=now,
        )
