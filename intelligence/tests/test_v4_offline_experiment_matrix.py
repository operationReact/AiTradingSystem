"""V4-08.2 integration-style tests for bounded offline V4-02 matrix invocation.

The fake local runner is explicitly not an AWS worker or independent trial proof.
"""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.v4_model_pareto_tournament import (
    MultiobjectiveTournamentConfig,
    TournamentMetricsV1,
    TournamentRosterV1,
)
from aitrading_intelligence.v4_offline_experiment_matrix import (
    JavaDatasetBinding,
    JavaExperimentSpecView,
    MatrixSlot,
    OfflineRunnerReceipt,
    freeze_offline_experiment_matrix,
    run_offline_experiment_matrix,
)

FROM_AT = datetime(2026, 1, 1, tzinfo=UTC)
TO_AT = datetime(2026, 1, 30, tzinfo=UTC)
CREATED = datetime(2026, 2, 2, tzinfo=UTC)
REGISTERED = datetime(2026, 2, 3, tzinfo=UTC)
COMPLETED = datetime(2026, 2, 4, tzinfo=UTC)
CUTOFF = datetime(2026, 2, 5, tzinfo=UTC)
HASHES = {
    "dataset_binding_fingerprint": "a" * 64,
    "target_binding_fingerprint": "b" * 64,
    "fold_binding_fingerprint": "c" * 64,
    "economic_cost_binding_fingerprint": "d" * 64,
}


def _spec(*, suffix: int, **overrides) -> JavaExperimentSpecView:
    payload = {
        "contract_version": "experiment-spec.v1",
        "experiment_id": uuid4(),
        "model_version_id": uuid4(),
        "from_at": FROM_AT,
        "to_at": TO_AT,
        "seed": 42,
        "dataset": JavaDatasetBinding(point_in_time=True),
        **HASHES,
        "spec_fingerprint": canonical_fingerprint({"java_spec": suffix}),
        "created_at": CREATED,
        "can_authorize_capital": False,
        **overrides,
    }
    return JavaExperimentSpecView(**payload)


def _roster(spec: JavaExperimentSpecView) -> TournamentRosterV1:
    payload = {
        "contract_version": "v4-tournament-roster.v1",
        "registered_at": REGISTERED.isoformat(),
        "baseline_candidate_id": "alpha",
        "declared_candidate_ids": ["alpha", "beta"],
        "source_experiment_spec_fingerprint": spec.spec_fingerprint,
        "dataset_fingerprint": HASHES["dataset_binding_fingerprint"],
        "target_fingerprint": HASHES["target_binding_fingerprint"],
        "fold_fingerprint": HASHES["fold_binding_fingerprint"],
        "economic_cost_fingerprint": HASHES["economic_cost_binding_fingerprint"],
        "holdout_cohort_fingerprint": "e" * 64,
    }
    return TournamentRosterV1(
        **payload, registration_fingerprint=canonical_fingerprint(payload)
    )


def _matrix():
    baseline = _spec(suffix=1)
    challenger = _spec(suffix=2)
    roster = _roster(baseline)
    return freeze_offline_experiment_matrix(roster, [
        MatrixSlot(candidate_id="beta", experiment=challenger),
        MatrixSlot(candidate_id="alpha", experiment=baseline),
    ])


class LocalFakeRunner:
    """Only a deterministic local test double; no external model workers."""

    def __init__(self, *, fail="", wrong_spec="", completed_at=COMPLETED, sparse=""):
        self.calls: list[str] = []
        self.fail = fail
        self.wrong_spec = wrong_spec
        self.completed_at = completed_at
        self.sparse = sparse

    def run(self, *, candidate_id: str, experiment: JavaExperimentSpecView):
        self.calls.append(candidate_id)
        if candidate_id == self.fail:
            raise RuntimeError("private local-test secret that must never be logged")
        metrics = TournamentMetricsV1(
            oos_observation_count=40,
            oos_trade_count=1 if candidate_id == self.sparse else 10,
            mean_net_return_bps=10.0 if candidate_id == "alpha" else 14.0,
            maximum_drawdown=0.15,
            brier_score=0.2 if candidate_id == "alpha" else 0.17,
            p95_cpu_inference_ms=5.0 if candidate_id == "alpha" else 15.0,
            cost_per_1000_inferences_inr=4.0 if candidate_id == "alpha" else 10.0,
        )
        payload = {
            "experiment_id": str(experiment.experiment_id),
            "model_version_id": str(experiment.model_version_id),
            "spec_fingerprint": (
                "f" * 64 if candidate_id == self.wrong_spec
                else experiment.spec_fingerprint
            ),
            "source_result_fingerprint": canonical_fingerprint({
                "test-only-research-output": candidate_id
            }),
            "completed_at": self.completed_at.isoformat(),
            "metrics": metrics.model_dump(mode="json"),
            "execution_mode": "LOCAL_OFFLINE_RESEARCH",
        }
        return OfflineRunnerReceipt(
            **payload, receipt_fingerprint=canonical_fingerprint(payload)
        )


CFG = MultiobjectiveTournamentConfig(
    minimum_oos_observations=20, minimum_oos_trades=5
)


def _run(matrix, runner):
    return run_offline_experiment_matrix(
        matrix, runner=runner, evaluated_as_of=CUTOFF, tournament_config=CFG
    )


def test_registered_matrix_invokes_local_runner_once_per_candidate():
    matrix = _matrix()
    runner = LocalFakeRunner()
    report = _run(matrix, runner)
    assert runner.calls == ["alpha", "beta"]
    assert report.contract_version == "v4-offline-experiment-matrix-run.v1"
    assert report.status == "OFFLINE_RESEARCH_COMPARISON"
    assert report.pareto_report is not None
    assert report.pareto_report.status == "RESEARCH_PARETO_FRONT_ONLY"
    assert report.pareto_report.pareto_front_candidate_ids == ["alpha", "beta"]
    assert [row.status for row in report.slot_results] == [
        "LOCAL_RECEIPT_ACCEPTED", "LOCAL_RECEIPT_ACCEPTED"
    ]
    assert report.execution_mode == "LOCAL_OFFLINE_RESEARCH"
    assert report.external_worker_verified is False
    assert report.experiment_result_db_verified is False
    assert report.independent_market_custody_verified is False
    assert report.automatic_promotion_allowed is False
    assert report.can_authorize_capital is False


def test_failed_candidate_is_not_retried_or_silently_omitted():
    matrix = _matrix()
    runner = LocalFakeRunner(fail="beta")
    report = _run(matrix, runner)
    assert runner.calls == ["alpha", "beta"]
    assert report.status == "INCONCLUSIVE"
    assert report.pareto_report is not None
    assert report.pareto_report.status == "INCONCLUSIVE"
    assert report.pareto_report.pareto_front_candidate_ids == []
    assert report.slot_results[1].status == "RESEARCH_RUN_FAILED"
    assert report.slot_results[1].receipt_fingerprint is None
    assert report.slot_results[1].failure_reason == "LOCAL_RUNNER_FAILED_OR_INVALID_RECEIPT"
    assert "private local-test secret" not in report.model_dump_json()


def test_wrong_spec_or_future_result_fails_only_affected_slot_closed():
    matrix = _matrix()
    for runner in (
        LocalFakeRunner(wrong_spec="alpha"),
        LocalFakeRunner(completed_at=CUTOFF + timedelta(hours=1)),
    ):
        report = _run(matrix, runner)
        assert report.status == "INCONCLUSIVE"
        assert report.pareto_report is not None
        assert report.pareto_report.pareto_front_candidate_ids == []
        assert report.can_authorize_capital is False


def test_one_insufficient_result_keeps_every_declared_candidate_inconclusive():
    matrix = _matrix()
    report = _run(matrix, LocalFakeRunner(sparse="beta"))
    assert report.status == "INCONCLUSIVE"
    assert all(slot.status == "LOCAL_RECEIPT_ACCEPTED" for slot in report.slot_results)
    assert report.pareto_report is not None
    assert report.pareto_report.valid_candidate_count == 1
    assert all(row.decision == "INCONCLUSIVE" for row in report.pareto_report.decisions)


def test_plan_is_order_invariant_and_bound_to_exact_experiment_cohort():
    matrix = _matrix()
    reversed_plan = freeze_offline_experiment_matrix(
        matrix.roster, list(reversed(matrix.slots))
    )
    assert matrix.matrix_fingerprint == reversed_plan.matrix_fingerprint

    altered = matrix.slots[1].experiment.model_copy(
        update={"economic_cost_binding_fingerprint": "f" * 64}
    )
    with pytest.raises(ValueError, match="economic_cost_binding_fingerprint"):
        freeze_offline_experiment_matrix(matrix.roster, [
            matrix.slots[0], MatrixSlot(candidate_id="beta", experiment=altered)
        ])


def test_plan_rejects_duplicate_model_and_experiment_registration():
    matrix = _matrix()
    baseline = matrix.slots[0].experiment
    challenger = matrix.slots[1].experiment
    with pytest.raises(ValueError, match="model versions"):
        freeze_offline_experiment_matrix(matrix.roster, [
            matrix.slots[0],
            MatrixSlot(candidate_id="beta", experiment=challenger.model_copy(
                update={"model_version_id": baseline.model_version_id}
            )),
        ])
    with pytest.raises(ValueError, match="reuse an experiment spec"):
        freeze_offline_experiment_matrix(matrix.roster, [
            matrix.slots[0],
            MatrixSlot(candidate_id="beta", experiment=challenger.model_copy(
                update={"experiment_id": baseline.experiment_id}
            )),
        ])


def test_source_specs_cannot_postdate_registration_or_change_seed():
    matrix = _matrix()
    late = matrix.slots[1].experiment.model_copy(
        update={"created_at": REGISTERED + timedelta(seconds=1)}
    )
    with pytest.raises(ValueError, match="after roster freeze"):
        freeze_offline_experiment_matrix(matrix.roster, [
            matrix.slots[0], MatrixSlot(candidate_id="beta", experiment=late)
        ])
    changed_seed = matrix.slots[1].experiment.model_copy(update={"seed": 99})
    with pytest.raises(ValueError, match="window or seed"):
        freeze_offline_experiment_matrix(matrix.roster, [
            matrix.slots[0], MatrixSlot(candidate_id="beta", experiment=changed_seed)
        ])


def test_tampered_matrix_fingerprint_and_non_point_in_time_specs_rejected():
    matrix = _matrix()
    forged = matrix.model_copy(update={"matrix_fingerprint": "0" * 64})
    with pytest.raises(ValueError, match="frozen matrix fingerprint"):
        _run(forged, LocalFakeRunner())

    with pytest.raises(ValidationError, match="point_in_time"):
        JavaDatasetBinding(point_in_time=False)


def test_runner_receipts_reject_modified_economic_metrics():
    matrix = _matrix()
    sample = LocalFakeRunner().run(
        candidate_id="alpha", experiment=matrix.slots[0].experiment
    )
    forged = sample.model_copy(
        update={"metrics": sample.metrics.model_copy(
            update={"mean_net_return_bps": 999.0}
        )}
    )
    with pytest.raises(ValidationError, match="runner receipt fingerprint"):
        OfflineRunnerReceipt.model_validate(forged.model_dump(mode="python"))


def test_replay_determinism_and_naive_cutoffs_fail_closed():
    matrix = _matrix()
    first = _run(matrix, LocalFakeRunner())
    second = _run(matrix, LocalFakeRunner())
    assert first.report_fingerprint == second.report_fingerprint

    with pytest.raises(ValueError, match="timezone-aware"):
        run_offline_experiment_matrix(
            matrix, runner=LocalFakeRunner(),
            evaluated_as_of=datetime(2026, 2, 5), tournament_config=CFG,
        )
    with pytest.raises(ValueError, match="precedes frozen roster"):
        run_offline_experiment_matrix(
            matrix, runner=LocalFakeRunner(),
            evaluated_as_of=REGISTERED - timedelta(seconds=1),
            tournament_config=CFG,
        )
