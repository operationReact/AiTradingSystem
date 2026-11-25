from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

import aitrading_intelligence.retraining_orchestration as orchestration
from aitrading_intelligence.datasets import DatasetManifest
from aitrading_intelligence.features import FeatureRow
from aitrading_intelligence.fingerprints import canonical_fingerprint
from aitrading_intelligence.learning_dataset import LearningDatasetManifest
from aitrading_intelligence.model_monitoring import ModelMonitoringReport, MonitoringMetrics
from aitrading_intelligence.model_registry import ModelGovernanceCandidate
from aitrading_intelligence.pytorch_target import LabeledExample
from aitrading_intelligence.retraining_orchestration import (
    RetrainingScheduleConfig,
    execute_candidate_retraining,
    plan_candidate_retraining,
)

BASE = datetime(2026, 11, 1, 9, 0, tzinfo=UTC)
REQUESTED_AT = datetime(2026, 11, 2, 9, 0, tzinfo=UTC)
CODE_SHA = "8" * 40
REGISTRY = "1" * 64


def _candidate(*, registry: str = REGISTRY) -> ModelGovernanceCandidate:
    return ModelGovernanceCandidate(
        model_family="PYTORCH",
        model_version="pytorch-champion.v1",
        artifact_fingerprint="2" * 64,
        feature_schema_fingerprint="3" * 64,
        training_dataset_fingerprint="4" * 64,
        training_config_fingerprint="5" * 64,
        code_sha=CODE_SHA,
        evaluation_fingerprint="6" * 64,
        monte_carlo_evidence_fingerprint="7" * 64,
        registered_at=BASE,
        registry_fingerprint=registry,
    )


def _monitoring(
    *,
    registry: str = REGISTRY,
    assessment: str = "RETRAINING_RECOMMENDED",
    report_fingerprint: str = "9" * 64,
) -> ModelMonitoringReport:
    metrics = MonitoringMetrics(
        records=100,
        positive_rate=0.5,
        mean_probability=0.5,
        brier_score=0.20,
        log_loss=0.60,
        ece=0.05,
    )
    retrain = assessment == "RETRAINING_RECOMMENDED"
    return ModelMonitoringReport(
        registry_fingerprint=registry,
        config_fingerprint="a" * 64,
        reference_fingerprint="b" * 64,
        current_fingerprint="c" * 64,
        reference_metrics=metrics,
        current_metrics=metrics,
        probability_psi=0.30 if retrain else 0.01,
        positive_rate_delta=0.0,
        brier_delta=0.0,
        log_loss_delta=0.0,
        ece_delta=0.0,
        assessment=assessment,
        reason_codes=["PREDICTION_PSI_SEVERE"] if retrain else ["STABLE_TEST"],
        required_next_gate=(
            "V2_09_CANDIDATE_RETRAINING"
            if retrain
            else "V2_09_CONTINUE_PAPER_MONITORING"
        ),
        candidate_training_allowed=retrain,
        report_fingerprint=report_fingerprint,
    )


def _rows(count: int = 50) -> list[FeatureRow]:
    rows = []
    for index in range(count):
        as_of = BASE + timedelta(minutes=index * 5)
        payload = {
            "feature_schema_version": "features.market.v1",
            "symbol": "RELIANCE",
            "as_of": as_of.isoformat(),
            "data_cutoff": as_of.isoformat(),
            "features": {
                "market.close": 100.0 + index,
                "market.return_1": 0.001 * ((index % 5) - 2),
                "market.volume": 1000.0 + index,
            },
            "provenance": [f"test:{index}"],
        }
        rows.append(FeatureRow(**payload, fingerprint=canonical_fingerprint(payload)))
    return rows


def _research_dataset(rows: list[FeatureRow]) -> DatasetManifest:
    ordered = sorted(rows, key=lambda row: (row.symbol, row.as_of, row.fingerprint))
    fingerprint = canonical_fingerprint(
        [row.model_dump(mode="json") for row in ordered]
    )
    return DatasetManifest(
        dataset_version="paper-retraining-2026-11-02",
        created_at=REQUESTED_AT - timedelta(hours=1),
        data_cutoff=max(row.data_cutoff for row in rows),
        code_sha=CODE_SHA,
        source_versions={"market": "test.v1"},
        row_count=len(rows),
        fingerprint=fingerprint,
    )


def _learning_dataset() -> LearningDatasetManifest:
    return LearningDatasetManifest(
        dataset_version="learning-2026-11-02",
        created_at=REQUESTED_AT - timedelta(hours=1),
        data_cutoff=REQUESTED_AT - timedelta(hours=2),
        code_sha=CODE_SHA,
        row_count=100,
        dataset_fingerprint="d" * 64,
        source_versions={"learning": "trade-learning-record.v1"},
    )


def _examples(rows: list[FeatureRow], count: int = 40) -> list[LabeledExample]:
    examples = []
    for index, row in enumerate(rows[:count]):
        examples.append(
            LabeledExample(
                symbol=row.symbol,
                as_of=row.as_of,
                target_observed_at=row.as_of + timedelta(minutes=1),
                feature_row_fingerprint=row.fingerprint,
                target_spec_fingerprint="e" * 64,
                label=index % 2,
                forward_return=0.01 if index % 2 else -0.01,
                threshold_return=0.0025,
                features=dict(row.features),
                provenance=list(row.provenance),
                example_fingerprint=f"{2000 + index:064x}",
            )
        )
    return examples


def _evaluation(*, replay_candidate: bool = True):
    comparison = "PYTORCH_ADVANCES_TO_REPLAY" if replay_candidate else "INCONCLUSIVE"
    return SimpleNamespace(
        pytorch_model_fingerprint="f" * 64,
        comparison=comparison,
        replay_candidate=replay_candidate,
        evaluation_fingerprint="0" * 64,
    )


def _plan(
    *,
    monitoring: ModelMonitoringReport | None = None,
    candidate: ModelGovernanceCandidate | None = None,
    rows: list[FeatureRow] | None = None,
    requested_at: datetime = REQUESTED_AT,
    previous_request=None,
):
    materialized = rows or _rows()
    return plan_candidate_retraining(
        monitoring=monitoring or _monitoring(),
        source_candidate=candidate or _candidate(),
        learning_dataset=_learning_dataset(),
        research_dataset=_research_dataset(materialized),
        code_sha=CODE_SHA,
        requested_at=requested_at,
        previous_request=previous_request,
    )


def test_plan_binds_both_datasets_and_never_grants_activation() -> None:
    rows = _rows()
    request = _plan(rows=rows)

    assert request.source_registry_fingerprint == REGISTRY
    assert request.learning_dataset_fingerprint == _learning_dataset().dataset_fingerprint
    assert request.research_dataset_fingerprint == _research_dataset(rows).fingerprint
    assert request.training_config.fingerprint == request.training_config_fingerprint
    assert request.required_next_gate == "V2_09_RUN_CANDIDATE_RETRAINING"
    assert request.activation_allowed is False
    assert request.promotion_allowed is False
    assert request.can_authorize_capital is False


def test_non_retraining_monitoring_cannot_schedule_training() -> None:
    with pytest.raises(ValueError, match="RETRAINING_RECOMMENDED"):
        _plan(monitoring=_monitoring(assessment="STABLE"))


def test_monitoring_must_match_exact_source_registry() -> None:
    with pytest.raises(ValueError, match="exact source model"):
        _plan(monitoring=_monitoring(registry="2" * 64))


def test_same_trigger_is_idempotent_even_inside_cooldown() -> None:
    first = _plan()
    second = _plan(
        requested_at=REQUESTED_AT + timedelta(hours=1),
        previous_request=first,
    )

    assert second == first


def test_different_trigger_is_blocked_until_cooldown_elapses() -> None:
    first = _plan()
    changed = _monitoring(report_fingerprint="8" * 64)
    config = RetrainingScheduleConfig(minimum_cooldown_hours=24)

    with pytest.raises(ValueError, match="cooldown"):
        plan_candidate_retraining(
            monitoring=changed,
            source_candidate=_candidate(),
            learning_dataset=_learning_dataset(),
            research_dataset=_research_dataset(_rows()),
            code_sha=CODE_SHA,
            requested_at=REQUESTED_AT + timedelta(hours=2),
            scheduler_config=config,
            previous_request=first,
        )


def test_future_dataset_cutoff_is_rejected() -> None:
    rows = _rows()
    research = _research_dataset(rows).model_copy(
        update={"data_cutoff": REQUESTED_AT + timedelta(minutes=1)}
    )
    with pytest.raises(ValueError, match="cutoff"):
        plan_candidate_retraining(
            monitoring=_monitoring(),
            source_candidate=_candidate(),
            learning_dataset=_learning_dataset(),
            research_dataset=research,
            code_sha=CODE_SHA,
            requested_at=REQUESTED_AT,
        )


def test_execution_invokes_existing_evaluator_and_stays_research_only(monkeypatch) -> None:
    rows = _rows()
    request = _plan(rows=rows)
    called = {"value": False}

    def fake_evaluator(rows_arg, examples_arg, scheduled_config):
        called["value"] = True
        assert len(rows_arg) == 50
        assert len(examples_arg) == 40
        assert scheduled_config.fingerprint == request.training_config_fingerprint
        return _evaluation(replay_candidate=True)

    monkeypatch.setattr(orchestration, "_run_existing_pytorch_evaluation", fake_evaluator)
    run = execute_candidate_retraining(
        request,
        rows=rows,
        examples=_examples(rows),
        research_dataset=_research_dataset(rows),
        started_at=REQUESTED_AT,
        completed_at=REQUESTED_AT + timedelta(minutes=5),
    )

    assert called["value"] is True
    assert run.required_next_gate == "V2_08_REPLAY_WALK_FORWARD"
    assert run.replay_candidate is True
    assert run.activation_allowed is False
    assert run.promotion_allowed is False
    assert run.can_authorize_capital is False


def test_execution_rejects_rows_that_do_not_match_scheduled_manifest(monkeypatch) -> None:
    rows = _rows()
    request = _plan(rows=rows)
    changed = list(rows)
    changed[0] = changed[0].model_copy(
        update={"features": {**changed[0].features, "market.close": 999.0}}
    )
    monkeypatch.setattr(
        orchestration,
        "_run_existing_pytorch_evaluation",
        lambda *args, **kwargs: _evaluation(),
    )

    with pytest.raises(ValueError, match="row fingerprint"):
        execute_candidate_retraining(
            request,
            rows=changed,
            examples=_examples(changed),
            research_dataset=_research_dataset(rows),
            started_at=REQUESTED_AT,
            completed_at=REQUESTED_AT + timedelta(minutes=5),
        )
