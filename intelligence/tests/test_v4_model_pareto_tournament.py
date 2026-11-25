"""V4-08.1 Pareto research frontier and frozen declared-cohort regression tests."""

from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.v4_model_pareto_tournament import (
    MultiobjectiveTournamentConfig,
    TournamentCandidateV1,
    TournamentMetricsV1,
    TournamentRosterV1,
    evaluate_research_pareto_tournament,
)

REGISTERED = datetime(2026, 10, 28, 8, 0, tzinfo=UTC)
EVALUATED = REGISTERED + timedelta(days=3)
CUTOFF = EVALUATED + timedelta(hours=1)
HASHES = {
    "source_experiment_spec_fingerprint": "a" * 64,
    "dataset_fingerprint": "b" * 64,
    "target_fingerprint": "c" * 64,
    "fold_fingerprint": "d" * 64,
    "economic_cost_fingerprint": "e" * 64,
    "holdout_cohort_fingerprint": "f" * 64,
}
POLICY = MultiobjectiveTournamentConfig(
    minimum_oos_observations=25, minimum_oos_trades=8
)


def _roster(ids=("alpha", "beta", "gamma")) -> TournamentRosterV1:
    payload = {
        "contract_version": "v4-tournament-roster.v1",
        "registered_at": REGISTERED.isoformat(),
        "baseline_candidate_id": "alpha",
        "declared_candidate_ids": list(ids),
        **HASHES,
    }
    return TournamentRosterV1(
        **payload, registration_fingerprint=canonical_fingerprint(payload)
    )


def _metrics(
    *,
    net=12.0,
    drawdown=0.20,
    brier=0.18,
    latency=30.0,
    cost=15.0,
    observations=80,
    trades=20,
) -> TournamentMetricsV1:
    return TournamentMetricsV1(
        oos_observation_count=observations, oos_trade_count=trades,
        mean_net_return_bps=net, maximum_drawdown=drawdown,
        brier_score=brier, p95_cpu_inference_ms=latency,
        cost_per_1000_inferences_inr=cost,
    )


def _candidate(
    candidate_id: str,
    roster: TournamentRosterV1,
    metrics: TournamentMetricsV1,
    *,
    evaluated_at=EVALUATED,
) -> TournamentCandidateV1:
    payload = {
        "candidate_id": candidate_id,
        "model_version_fingerprint": canonical_fingerprint(
            {"model_version": candidate_id}
        ),
        "source_result_fingerprint": canonical_fingerprint(
            {"result": candidate_id}
        ),
        "registration_fingerprint": roster.registration_fingerprint,
        "dataset_fingerprint": roster.dataset_fingerprint,
        "target_fingerprint": roster.target_fingerprint,
        "fold_fingerprint": roster.fold_fingerprint,
        "economic_cost_fingerprint": roster.economic_cost_fingerprint,
        "holdout_cohort_fingerprint": roster.holdout_cohort_fingerprint,
        "evaluated_at": evaluated_at.isoformat(),
        "metrics": metrics.model_dump(mode="json"),
        "evidence_status": "SELF_DECLARED_RESEARCH_ONLY",
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return TournamentCandidateV1(
        **payload, evidence_fingerprint=canonical_fingerprint(payload)
    )


def _candidates(roster):
    return [
        _candidate("alpha", roster, _metrics()),
        _candidate("beta", roster, _metrics(
            net=20.0, drawdown=0.12, brier=0.15, latency=40.0, cost=18.0,
        )),
        _candidate("gamma", roster, _metrics(
            net=8.0, drawdown=0.30, brier=0.25, latency=50.0, cost=30.0,
        )),
    ]


def _evaluate(roster, candidates):
    return evaluate_research_pareto_tournament(
        roster, candidates, evaluated_as_of=CUTOFF, config=POLICY
    )


def test_multiple_objectives_produce_pareto_front_not_scalar_winner():
    roster = _roster()
    report = _evaluate(roster, _candidates(roster))
    assert report.contract_version == "v4-multiobjective-research-tournament.v1"
    assert report.status == "RESEARCH_PARETO_FRONT_ONLY"
    assert report.pareto_front_candidate_ids == ["alpha", "beta"]
    assert report.decisions[0].decision == "NONDOMINATED_RESEARCH_ONLY"
    assert report.decisions[1].decision == "NONDOMINATED_RESEARCH_ONLY"
    assert report.decisions[2].decision == "DOMINATED_RESEARCH_ONLY"
    assert report.decisions[2].dominated_by_ids == ["alpha", "beta"]
    assert report.valid_candidate_count == 3
    assert report.baseline_candidate_id == "alpha"
    assert report.independent_market_source_verified is False
    assert report.external_trial_provenance_verified is False
    assert report.experiment_worker_execution_verified is False
    assert report.automatic_promotion_allowed is False
    assert report.can_authorize_capital is False


def test_permutation_and_future_only_results_do_not_rewrite_historical_evidence():
    roster = _roster()
    candidates = _candidates(roster)
    original = _evaluate(roster, candidates)
    reverse = _evaluate(roster, list(reversed(candidates)))
    future = _candidate(
        "alpha", roster,
        _metrics(net=900.0),
        evaluated_at=CUTOFF + timedelta(days=1),
    )
    mutated = _evaluate(roster, [*candidates, future])
    assert original.report_fingerprint == reverse.report_fingerprint
    assert original.report_fingerprint == mutated.report_fingerprint


def test_incomplete_metric_axis_fails_entire_declared_roster_closed():
    roster = _roster()
    candidates = _candidates(roster)
    insufficient = _candidate(
        "gamma", roster, _metrics(trades=3, observations=10, brier=None)
    )
    report = _evaluate(roster, [*candidates[:2], insufficient])
    assert report.status == "INCONCLUSIVE"
    assert report.pareto_front_candidate_ids == []
    assert all(row.decision == "INCONCLUSIVE" for row in report.decisions)
    assert "INSUFFICIENT_OOS_OBSERVATIONS" in report.decisions[2].reason_codes
    assert "INSUFFICIENT_OOS_TRADES" in report.decisions[2].reason_codes
    assert "MISSING_MULTI_OBJECTIVE_EVIDENCE" in report.decisions[2].reason_codes
    assert report.automatic_promotion_allowed is False


def test_missing_declared_candidate_keeps_tournament_inconclusive():
    roster = _roster()
    report = _evaluate(roster, _candidates(roster)[:2])
    assert report.status == "INCONCLUSIVE"
    assert report.valid_candidate_count == 2
    assert report.decisions[-1].source_evidence_fingerprint is None
    assert report.decisions[-1].reason_codes == [
        "REGISTERED_CANDIDATE_RESULT_MISSING"
    ]


def test_tampered_fingerprints_or_cohort_mismatch_are_rejected():
    roster = _roster()
    candidates = _candidates(roster)
    forged = candidates[0].model_copy(update={"evidence_fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="candidate evidence fingerprint mismatch"):
        _evaluate(roster, [forged, *candidates[1:]])

    mismatched = candidates[0].model_copy(
        update={"target_fingerprint": "f" * 64}
    )
    payload = mismatched.model_dump(mode="python", exclude={"evidence_fingerprint"})
    payload["evaluated_at"] = mismatched.evaluated_at.isoformat()
    payload["metrics"] = mismatched.metrics.model_dump(mode="json")
    mismatched = mismatched.model_copy(
        update={"evidence_fingerprint": canonical_fingerprint(payload)}
    )
    with pytest.raises(ValueError, match="target_fingerprint"):
        _evaluate(roster, [mismatched, *candidates[1:]])


def test_undeclared_duplicate_and_preregistration_results_are_rejected():
    roster = _roster()
    candidates = _candidates(roster)
    with pytest.raises(ValueError, match="duplicate candidate"):
        _evaluate(roster, [*candidates, candidates[0]])
    outsider = _candidate("unknown", roster, _metrics())
    with pytest.raises(ValueError, match="undeclared"):
        _evaluate(roster, [*candidates, outsider])
    prereg = _candidate("alpha", roster, _metrics(), evaluated_at=REGISTERED)
    with pytest.raises(ValueError, match="follow frozen registration"):
        _evaluate(roster, [prereg, *candidates[1:]])


def test_noncanonical_roster_and_invalid_source_metrics_fail_closed():
    with pytest.raises(ValidationError, match="unique and in sorted order"):
        _roster(("beta", "alpha"))
    with pytest.raises(ValidationError, match="finite"):
        _metrics(net=float("nan"))
    with pytest.raises(ValidationError, match="trades cannot exceed"):
        _metrics(observations=2, trades=20)


def test_naive_or_predeclaration_cutoff_is_rejected():
    roster = _roster()
    candidates = _candidates(roster)
    with pytest.raises(ValueError, match="timezone-aware"):
        evaluate_research_pareto_tournament(
            roster, candidates,
            evaluated_as_of=datetime(2026, 10, 31), config=POLICY,
        )
    with pytest.raises(ValueError, match="before its registration"):
        evaluate_research_pareto_tournament(
            roster, candidates,
            evaluated_as_of=REGISTERED - timedelta(days=1), config=POLICY,
        )
