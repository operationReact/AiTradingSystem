from __future__ import annotations

import json
from datetime import timedelta

import httpx
import pytest
from test_india_independent_holdout import _run
from test_java_research_registration import JOB_ID, NOW

from aitrading_intelligence.java_research_registration import (
    JavaExperimentJobSnapshot,
    JavaExperimentSnapshot,
    derive_holdout_dataset_fingerprint,
)
from aitrading_intelligence.java_research_result_worker import (
    JavaRegistrationError,
    JavaResearchResultReceipt,
    submit_governed_java_research_result,
)

ORIGIN = "https://java.example.test"
SESSION = "sample-test-admin-session"
CSRF = "sample-test-csrf-token"


@pytest.fixture(scope="module")
def holdout():
    # Synthetic research candles; this test does not access NSE/BSE markets.
    return _run()


@pytest.fixture(scope="module")
def snapshots(holdout):
    experiment_id = "11111111-1111-4111-8111-111111111111"
    spec = JavaExperimentSnapshot.model_validate({
        "experimentId": experiment_id,
        "modelVersionId": "22222222-2222-4222-8222-222222222222",
        "specFingerprint": "a" * 64,
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
        "experimentId": experiment_id,
        "specFingerprint": spec.specFingerprint,
        "runnerType": "PYTHON_INTELLIGENCE",
        "runnerVersion": "india-independent-holdout.v1",
        "orchestrationFingerprint": "b" * 64,
        "status": "RUNNING",
        "claimToken": "44444444-4444-4444-8444-444444444444",
        "leaseExpiresAt": (NOW + timedelta(minutes=5)).isoformat(),
        "canAuthorizeCapital": False,
    })
    return spec, job


def _fake_java(holdout, snapshots, *, mode="created"):
    experiment, job = snapshots
    state = {"posts": 0, "record": None, "requests": []}
    jobs = f"/api/admin/model-governance/experiment-jobs/{JOB_ID}"
    specs = f"/api/admin/model-governance/experiments/{experiment.experimentId}"

    def route(request: httpx.Request) -> httpx.Response:
        assert request.headers.get("Authorization") is None
        assert f"ATS_SESSION={SESSION}" in request.headers.get("Cookie", "")
        if request.method == "POST":
            assert request.headers.get("X-CSRF-Token") == CSRF
        assert request.headers.get("Accept") == "application/json"
        state["requests"].append((request.method, request.url.path))
        path = request.url.path
        if request.method == "GET" and path == jobs:
            payload = job.model_dump(mode="json")
            if state["record"] is not None:
                payload = {**payload, "status": "SUCCEEDED", "claimToken": None}
            return httpx.Response(200, json=payload)
        if request.method == "GET" and path == specs:
            return httpx.Response(200, json=experiment.model_dump(mode="json"))
        if request.method == "POST" and path == jobs + "/result":
            state["posts"] += 1
            if mode == "unauthorized":
                return httpx.Response(403)
            payload = json.loads(request.content)
            assert payload["assessment"] == "INCONCLUSIVE"
            assert payload["claimToken"] == str(job.claimToken)
            assert payload["resultArtifactFingerprint"] == holdout.report_fingerprint
            state["record"] = {
                "contractVersion": "experiment-result.v1",
                "resultId": "12345678-1234-4234-8234-123456789012",
                "jobId": str(job.jobId),
                "experimentId": str(experiment.experimentId),
                "modelVersionId": str(experiment.modelVersionId),
                "specFingerprint": experiment.specFingerprint,
                "orchestrationFingerprint": job.orchestrationFingerprint,
                "assessment": payload["assessment"],
                "sampleCount": payload["sampleCount"],
                "resultArtifactFingerprint": payload["resultArtifactFingerprint"],
                "metricsFingerprint": payload["metricsFingerprint"],
                "evidenceFingerprint": payload["evidenceFingerprint"],
                "resultFingerprint": "d" * 64,
                "canAuthorizeCapital": False,
            }
            if mode == "bad_readback":
                state["record"] = {**state["record"], "resultFingerprint": "e" * 64}
                return httpx.Response(201, json={
                    **state["record"], "resultFingerprint": "d" * 64
                })
            if mode == "conflict":
                return httpx.Response(409)
            if mode == "timeout_after_commit":
                raise httpx.ReadTimeout("server committed before the client received response")
            if mode == "timeout_no_commit":
                state["record"] = None
                raise httpx.ReadTimeout("no server response")
            return httpx.Response(201, json=state["record"])
        if request.method == "GET" and path == jobs + "/result":
            if state["record"] is None:
                return httpx.Response(404)
            return httpx.Response(200, json=state["record"])
        return httpx.Response(404)

    return httpx.MockTransport(route), state


def _submit(holdout, transport, **kwargs):
    return submit_governed_java_research_result(
        backend_url=ORIGIN,
        admin_session_cookie=SESSION,
        admin_csrf_token=CSRF,
        job_id=JOB_ID,
        holdout=holdout,
        now=NOW,
        transport=transport,
        **kwargs,
    )


def test_trusted_worker_posts_once_then_reads_back_java_result_and_job(
    holdout, snapshots
) -> None:
    transport, state = _fake_java(holdout, snapshots)
    receipt = _submit(holdout, transport)

    assert receipt.contract_version == "java-research-result-receipt.v1"
    assert receipt.assessment == "INCONCLUSIVE"
    assert receipt.readback_verified is True
    assert receipt.can_authorize_capital is False
    assert receipt.promotion_allowed is False
    assert receipt.result_fingerprint == "d" * 64
    assert state["posts"] == 1
    assert [method for method, _ in state["requests"]] == [
        "GET", "GET", "POST", "GET", "GET",
    ]
    assert SESSION not in receipt.model_dump_json()
    assert CSRF not in receipt.model_dump_json()
    assert str(snapshots[1].claimToken) not in receipt.model_dump_json()
    assert (
        JavaResearchResultReceipt.model_validate(receipt.model_dump(mode="json"))
        == receipt
    )


@pytest.mark.parametrize("mode", ["conflict", "timeout_after_commit"])
def test_ambiguous_post_is_reconciled_without_duplicate_submission(
    holdout, snapshots, mode
) -> None:
    transport, state = _fake_java(holdout, snapshots, mode=mode)
    receipt = _submit(holdout, transport)

    assert receipt.assessment == "INCONCLUSIVE"
    assert state["posts"] == 1


def test_failed_post_does_not_retry_and_never_exposes_admin_token(
    holdout, snapshots
) -> None:
    transport, state = _fake_java(holdout, snapshots, mode="timeout_no_commit")
    with pytest.raises(JavaRegistrationError, match="outcome is ambiguous") as exc:
        _submit(holdout, transport)
    assert state["posts"] == 1
    assert SESSION not in str(exc.value)
    assert CSRF not in str(exc.value)
    assert str(snapshots[1].claimToken) not in str(exc.value)


def test_unauthorized_result_write_is_not_retried(holdout, snapshots) -> None:
    transport, state = _fake_java(holdout, snapshots, mode="unauthorized")
    with pytest.raises(JavaRegistrationError, match="HTTP 403"):
        _submit(holdout, transport)
    assert state["posts"] == 1


def test_readback_mismatch_is_not_reported_as_success(holdout, snapshots) -> None:
    transport, state = _fake_java(holdout, snapshots, mode="bad_readback")
    with pytest.raises(JavaRegistrationError, match="readback differs"):
        _submit(holdout, transport)
    assert state["posts"] == 1


def test_expired_java_lease_blocks_submission(holdout, snapshots) -> None:
    experiment, job = snapshots
    expired = job.model_dump(mode="json")
    expired["leaseExpiresAt"] = (NOW - timedelta(minutes=1)).isoformat()
    transport, state = _fake_java(holdout, snapshots)
    normal = transport.handler

    def route(request):
        if request.method == "GET" and request.url.path.endswith(f"/{JOB_ID}"):
            return httpx.Response(200, json=expired)
        return normal(request)

    with pytest.raises(ValueError, match="lease has expired"):
        _submit(holdout, httpx.MockTransport(route))
    assert state["posts"] == 0
    assert experiment.experimentId == job.experimentId


def test_java_experiment_binding_mismatch_blocks_write(holdout, snapshots) -> None:
    transport, state = _fake_java(holdout, snapshots)
    normal = transport.handler

    def route(request):
        response = normal(request)
        if (
            request.method == "GET"
            and "/experiments/" in request.url.path
            and response.status_code == 200
        ):
            body = response.json()
            body["dataset"]["datasetFingerprint"] = "a" * 64
            return httpx.Response(200, json=body)
        return response

    with pytest.raises(ValueError, match="dataset fingerprint"):
        _submit(holdout, httpx.MockTransport(route))
    assert state["posts"] == 0


@pytest.mark.parametrize("origin", [
    "http://java.example.test",
    "http://192.168.1.5:8080",
    "ftp://java.example.test",
    "https://name:password@java.example.test",
    "https://java.example.test/api",
    "https://java.example.test?redirect=1",
])
def test_never_send_admin_credentials_to_untrusted_origin(holdout, origin) -> None:
    with pytest.raises(ValueError, match="Java backend|Plaintext"):
        submit_governed_java_research_result(
            backend_url=origin,
            admin_session_cookie=SESSION,
            admin_csrf_token=CSRF,
            job_id=JOB_ID,
            holdout=holdout,
            now=NOW,
        )


def test_empty_admin_session_rejected_before_any_network(holdout) -> None:
    with pytest.raises(ValueError, match="ADMIN session cookie"):
        submit_governed_java_research_result(
            backend_url=ORIGIN,
            admin_session_cookie="",
            admin_csrf_token=CSRF,
            job_id=JOB_ID,
            holdout=holdout,
            now=NOW,
        )


@pytest.mark.parametrize(
    ("session", "csrf", "expected"),
    [
        ("", CSRF, "ADMIN session cookie"),
        ("bad;cookie", CSRF, "ADMIN session cookie"),
        ("bad\nvalue", CSRF, "ADMIN session cookie"),
        (SESSION, "", "ADMIN CSRF token"),
        (SESSION, "bad;csrf", "ADMIN CSRF token"),
        (SESSION, "bad\ncsrf", "ADMIN CSRF token"),
    ],
)
def test_bad_session_or_csrf_never_reaches_network(
    holdout, session, csrf, expected
) -> None:
    with pytest.raises(ValueError, match=expected):
        submit_governed_java_research_result(
            backend_url=ORIGIN,
            admin_session_cookie=session,
            admin_csrf_token=csrf,
            job_id=JOB_ID,
            holdout=holdout,
            now=NOW,
        )
