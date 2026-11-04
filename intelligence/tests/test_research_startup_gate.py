"""V78 fail-closed worker bootstrap: no model/secret disclosure or trading authority."""
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

import aitrading_intelligence.research_startup_gate as gate
from aitrading_intelligence.research_artifact_inference import InferenceUnavailable


@pytest.fixture(autouse=True)
def clear_worker_env(monkeypatch):
    for name in ("TRADING_MODE", "APP_TRADING_MODE",
                 "AI_RESEARCH_STRICT_BOOT_REQUIRED", "AI_RESEARCH_WORKER_TOKEN",
                 "AI_RESEARCH_MODEL_MANIFEST"):
        monkeypatch.delenv(name, raising=False)


def strict_env(monkeypatch, manifest: str = "/private/verified.json"):
    monkeypatch.setenv("TRADING_MODE", "PAPER")
    monkeypatch.setenv("AI_RESEARCH_STRICT_BOOT_REQUIRED", "true")
    monkeypatch.setenv("AI_RESEARCH_WORKER_TOKEN", "test-only-private-worker-token-" + "x" * 32)
    monkeypatch.setenv("AI_RESEARCH_MODEL_MANIFEST", manifest)


def test_local_development_default_does_not_claim_worker_identity():
    assert gate.verify_strict_worker_bootstrap() is False


@pytest.mark.parametrize("setting", ["TRUE", "1", "off", "", "False"])
def test_unknown_strict_setting_fails_closed(monkeypatch, setting):
    monkeypatch.setenv("AI_RESEARCH_STRICT_BOOT_REQUIRED", setting)
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()


def test_missing_paper_or_override_fails(monkeypatch):
    strict_env(monkeypatch)
    monkeypatch.setenv("TRADING_MODE", "LIVE")
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()
    monkeypatch.setenv("TRADING_MODE", "PAPER")
    monkeypatch.setenv("APP_TRADING_MODE", "LIVE")
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()


def test_token_strength_required_and_no_plaintext_feedback(monkeypatch, capsys):
    strict_env(monkeypatch)
    monkeypatch.setenv("AI_RESEARCH_WORKER_TOKEN", "SECRET123")
    assert gate.main() == 2
    assert "SECRET123" not in capsys.readouterr().err


def test_manifest_path_must_be_absolute_and_real(monkeypatch, tmp_path):
    strict_env(monkeypatch, manifest="relative.json")
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()
    strict_env(monkeypatch, manifest=str(tmp_path / "missing.json"))
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()


def test_verified_roster_checked_but_never_grants_capital(monkeypatch, tmp_path):
    path = tmp_path / "verified.json"
    path.write_text("placeholder", encoding="utf-8")
    strict_env(monkeypatch, manifest=str(path))
    observed = []
    monkeypatch.setattr(gate, "inspect_worker_roster", lambda p: (
        observed.append(p) or SimpleNamespace(
            local_artifacts_validated=True, can_authorize_capital=False)
    ))
    assert gate.verify_strict_worker_bootstrap() is True
    assert observed == [path]


def test_rejected_roster_does_not_start_worker(monkeypatch):
    strict_env(monkeypatch)
    monkeypatch.setattr(gate, "inspect_worker_roster", lambda _: SimpleNamespace(
        local_artifacts_validated=False, can_authorize_capital=False
    ))
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()
    monkeypatch.setattr(gate, "inspect_worker_roster", lambda _: SimpleNamespace(
        local_artifacts_validated=True, can_authorize_capital=True
    ))
    with pytest.raises(InferenceUnavailable):
        gate.verify_strict_worker_bootstrap()


def test_docker_command_rejects_bad_bootstrap_before_binding_port():
    docker = (Path(__file__).resolve().parents[1] / "Dockerfile").read_text()
    assert "python -m aitrading_intelligence.research_startup_gate && exec uvicorn" in docker
    assert "USER 65532:65532" in docker
