from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest
from pydantic import ValidationError
from test_india_independent_holdout import _run

from aitrading_intelligence.java_research_registration import (
    JavaExperimentJobSnapshot,
    JavaExperimentSnapshot,
    JavaResearchRegistrationEnvelope,
    build_java_research_result_registration,
    derive_holdout_dataset_fingerprint,
)

NOW = datetime(2026, 11, 3, 18, tzinfo=UTC)
EXPERIMENT_ID = UUID("11111111-1111-4111-8111-111111111111")
MODEL_VERSION_ID = UUID("22222222-2222-4222-8222-222222222222")
JOB_ID = UUID("33333333-3333-4333-8333-333333333333")
CLAIM_TOKEN = UUID("44444444-4444-4444-8444-444444444444")
SHA = "a" * 64
ORCHESTRATION_SHA = "b" * 64


@pytest.fixture(scope="module")
def holdout():
    # Synthetic fixture bars test the integration contract, not real India-market profits.
    return _run()


@pytest.fixture(scope="module")
def snapshots(holdout):
    experiment = JavaExperimentSnapshot.model_validate({
        "experimentId": str(EXPERIMENT_ID),
        "modelVersionId": str(MODEL_VERSION_ID),
        "specFingerprint": SHA,
        "fromAt": "2026-10-28T00:00:00Z",
        "toAt": "2026-10-31T12:00:00Z",
        "dataset": {
            "datasetFingerprint": derive_holdout_dataset_fingerprint(holdout),
            "pointInTime": True,
            "asOf": "2026-10-30T13:00:00Z",
        },
        "target": {
            "targetName": "forward-return-after-costs.v1",
            "labelDefinitionFingerprint": holdout.target_spec_fingerprint,
            "horizonBars": 1,
        },
        "folds": {"protocol": "CHRONOLOGICAL", "folds": 2},
        "canAuthorizeCapital": False,
    })
    job = JavaExperimentJobSnapshot.model_validate({
        "jobId": str(JOB_ID),
        "experimentId": str(EXPERIMENT_ID),
        "specFingerprint": SHA,
        "runnerType": "PYTHON_INTELLIGENCE",
        "runnerVersion": "india-independent-holdout.v1",
        "orchestrationFingerprint": ORCHESTRATION_SHA,
        "status": "RUNNING",
        "claimToken": str(CLAIM_TOKEN),
        "leaseExpiresAt": (NOW + timedelta(minutes=5)).isoformat(),
        "canAuthorizeCapital": False,
    })
    return experiment, job


def _copy(model, **updates):
    return type(model).model_validate({**model.model_dump(mode="json"), **updates})


def test_immutable_holdout_produces_exact_java_result_body(holdout, snapshots) -> None:
    experiment, job = snapshots
    envelope = build_java_research_result_registration(
        experiment=experiment, job=job, holdout=holdout, now=NOW
    )
    request = envelope.request.model_dump(mode="json")

    assert envelope.contract_version == "python-research-result-bridge.v1"
    assert envelope.job_id == JOB_ID
    assert envelope.experiment_id == EXPERIMENT_ID
    assert envelope.dataset_fingerprint == derive_holdout_dataset_fingerprint(holdout)
    assert request["claimToken"] == str(CLAIM_TOKEN)
    assert request["assessment"] == "INCONCLUSIVE"
    assert request["sampleCount"] == holdout.holdout_rows
    assert request["resultArtifactFingerprint"] == holdout.report_fingerprint
    assert len(request["metricsFingerprint"]) == 64
    assert len(request["evidenceFingerprint"]) == 64
    assert envelope.can_authorize_capital is False
    assert envelope.promotion_allowed is False
    assert envelope.holdout_pre_registration_verified is False
    assert envelope.external_market_source_verified is False


def test_public_evidence_identity_does_not_hash_worker_secret(holdout, snapshots) -> None:
    experiment, job = snapshots
    first = build_java_research_result_registration(
        experiment=experiment, job=job, holdout=holdout, now=NOW
    )
    next_claim = _copy(job, claimToken=str(UUID("55555555-5555-4555-8555-555555555555")))
    second = build_java_research_result_registration(
        experiment=experiment, job=next_claim, holdout=holdout, now=NOW
    )
    assert first.envelope_fingerprint == second.envelope_fingerprint
    assert first.request.evidenceFingerprint == second.request.evidenceFingerprint
    assert first.request.claimToken != second.request.claimToken


def test_wrong_dataset_or_target_fingerprint_fails_closed(holdout, snapshots) -> None:
    experiment, job = snapshots
    bad_dataset = _copy(
        experiment, dataset={**experiment.dataset.model_dump(), "datasetFingerprint": "c" * 64}
    )
    with pytest.raises(ValueError, match="dataset fingerprint"):
        build_java_research_result_registration(
            experiment=bad_dataset, job=job, holdout=holdout, now=NOW
        )
    bad_target = _copy(
        experiment, target={
            **experiment.target.model_dump(), "labelDefinitionFingerprint": "d" * 64
        },
    )
    with pytest.raises(ValueError, match="target label fingerprint"):
        build_java_research_result_registration(
            experiment=bad_target, job=job, holdout=holdout, now=NOW
        )


def test_wrong_registered_job_spec_and_runner_are_rejected(holdout, snapshots) -> None:
    experiment, job = snapshots
    for change, expected in (
        ({"experimentId": str(UUID("66666666-6666-4666-8666-666666666666"))},
         "registered experiment"),
        ({"specFingerprint": "c" * 64}, "job/spec fingerprint"),
        ({"runnerVersion": "conditional-xgboost-benchmark.v1"}, "runner version"),
    ):
        with pytest.raises(ValueError, match=expected):
            build_java_research_result_registration(
                experiment=experiment, job=_copy(job, **change), holdout=holdout, now=NOW
            )


def test_expired_lease_does_not_generate_registerable_request(holdout, snapshots) -> None:
    experiment, job = snapshots
    with pytest.raises(ValueError, match="lease has expired"):
        build_java_research_result_registration(
            experiment=experiment,
            job=_copy(job, leaseExpiresAt=(NOW - timedelta(seconds=1)).isoformat()),
            holdout=holdout,
            now=NOW,
        )


def test_nonrunning_or_non_python_job_is_not_an_eligible_source(snapshots) -> None:
    _, job = snapshots
    for update in ({"status": "CLAIMED"}, {"runnerType": "JAVA_BACKTEST"}):
        with pytest.raises(ValidationError):
            _copy(job, **update)


def test_unverified_research_cannot_be_relabelled_as_pass(holdout, snapshots) -> None:
    experiment, job = snapshots
    envelope = build_java_research_result_registration(
        experiment=experiment, job=job, holdout=holdout, now=NOW
    )
    with pytest.raises(ValidationError):
        JavaResearchRegistrationEnvelope.model_validate({
            **envelope.model_dump(mode="json"),
            "request": {**envelope.request.model_dump(mode="json"), "assessment": "PASS"},
        })


def test_tampering_with_public_report_fingerprint_is_detected(holdout, snapshots) -> None:
    experiment, job = snapshots
    envelope = build_java_research_result_registration(
        experiment=experiment, job=job, holdout=holdout, now=NOW
    )
    with pytest.raises(ValidationError, match="envelope fingerprint mismatch"):
        JavaResearchRegistrationEnvelope.model_validate({
            **envelope.model_dump(mode="json"),
            "dataset_fingerprint": "f" * 64,
        })


def test_forged_python_report_hash_fails_validation(holdout, snapshots) -> None:
    experiment, job = snapshots
    with pytest.raises(ValidationError, match="independent holdout report fingerprint"):
        bad = holdout.model_dump(mode="json")
        bad["report_fingerprint"] = "c" * 64
        from aitrading_intelligence.india_independent_holdout import IndiaHoldoutReport

        build_java_research_result_registration(
            experiment=experiment, job=job,
            holdout=IndiaHoldoutReport.model_validate(bad),
            now=NOW,
        )
