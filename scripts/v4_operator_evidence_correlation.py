#!/usr/bin/env python3
"""V87: fail-closed joining of two independently collected PAPER AWS reports.

Requires successful manually dispatched GitHub Actions provenance for each
sanitized artifact. This is NOT a cryptographically independent attestation;
the original GitHub runs must be reviewed and AWS IAM accounts authorized.
No AWS access, token values, image release, model research or capital authority.
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO = "operationReact/AiTradingSystem"
ACCOUNT = "816344830716"
REGION = "us-east-1"
BACKEND_PATH = ".github/workflows/v4-paper-runtime-inspect.yml"
NETWORK_PATH = ".github/workflows/v4-worker-private-tls-readonly-inspect.yml"
SHA40 = re.compile(r"[a-f0-9]{40}\Z")
VERSION = "v4-paper-private-tls-operator-correlation.v1"
MAX_ARTIFACT_BYTES = 64 * 1024
MAX_RUN_BYTES = 128 * 1024
MAX_AGE = timedelta(minutes=90)
MAX_PAIR_SKEW = timedelta(minutes=25)
MAX_CLOCK_SKEW = timedelta(minutes=2)


def _stamp(value: str) -> datetime:
    if not isinstance(value, str) or len(value) > 60:
        raise ValueError("invalid evidence timestamp")
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None or instant.utcoffset() is None:
        raise ValueError("timestamp lacks UTC timezone")
    return instant.astimezone(timezone.utc)


def _run_origin(record: dict, workflow: str, sha: str) -> bool:
    if not isinstance(record, dict):
        return False
    repo = record.get("repository", {})
    return bool(
        repo.get("full_name") == REPO
        and record.get("head_sha") == sha
        and record.get("head_branch") == "dev"
        and record.get("event") == "workflow_dispatch"
        and record.get("status") == "completed"
        and record.get("conclusion") == "success"
        and isinstance(record.get("id"), int) and not isinstance(record["id"], bool)
        and record["id"] > 0
        and isinstance(record.get("path"), str)
        and record["path"].split("@", 1)[0] == workflow
    )


def _report_time_valid(report: dict, run: dict, now: datetime) -> bool:
    try:
        observed = _stamp(report["checked_at_utc"])
        began = _stamp(run["created_at"])
        finished = _stamp(run["updated_at"])
    except (TypeError, ValueError, KeyError, OverflowError):
        return False
    return (
        now - MAX_AGE <= observed <= now + MAX_CLOCK_SKEW
        and began - MAX_CLOCK_SKEW <= observed <= finished + MAX_CLOCK_SKEW
        and began <= finished
        and finished <= now + MAX_CLOCK_SKEW
    )


def evaluate(
    backend: dict, network: dict, backend_run: dict, network_run: dict,
    sha: str, now: datetime | None = None,
) -> dict:
    now = now or datetime.now(timezone.utc)
    blockers: set[str] = set()

    def require(ok: bool, code: str) -> bool:
        if not ok:
            blockers.add(code)
        return bool(ok)

    commit_ok = require(bool(SHA40.fullmatch(sha)), "EXPECTED_COMMIT_INVALID")
    github_ok = require(
        commit_ok and _run_origin(backend_run, BACKEND_PATH, sha)
        and _run_origin(network_run, NETWORK_PATH, sha)
        and backend_run.get("id") != network_run.get("id"),
        "GITHUB_INSPECTION_RUN_ORIGIN_UNVERIFIED",
    )
    backend_ok = require(
        backend.get("contract_version") == "v4-paper-runtime-posture.v1"
        and backend.get("expected_commit_sha") == sha
        and backend.get("target_region") == REGION
        and backend.get("aws_account_verified") is True
        and backend.get("paper_backend_posture_verified") is True
        and backend.get("research_opt_ins_disabled") is True
        and backend.get("backend_service_stable") is True
        and backend.get("public_paper_health_verified") is True
        and backend.get("worker_model_identity_attested") is False
        and backend.get("live_execution_enabled") is False
        and backend.get("can_authorize_capital") is False
        and backend.get("verdict") == "PAPER_BACKEND_POSTURE_ONLY_RESEARCH_UNVERIFIED"
        and backend.get("blocker_codes") == [],
        "PAPER_BACKEND_EVIDENCE_UNVERIFIED",
    )
    private_ok = require(
        network.get("contract_version") == "v4-worker-private-tls-topology.v1"
        and network.get("expected_commit_sha") == sha
        and network.get("region") == REGION
        and network.get("aws_account_verified") is True
        and network.get("private_worker_tls_metadata_verified") is True
        and network.get("backend_frontend_worker_ecs_separation_verified") is True
        and network.get("backend_only_sg_identity_verified") is True
        and network.get("private_dns_alias_verified") is True
        and network.get("private_tls_handshake_from_java_verified") is False
        and network.get("worker_running") is False
        and network.get("live_execution_enabled") is False
        and network.get("can_authorize_capital") is False
        and network.get("verdict") == "PRIVATE_TLS_TOPOLOGY_CONFIGURED_WORKER_DORMANT_ONLY"
        and network.get("blocker_codes") == [],
        "DORMANT_PRIVATE_WORKER_TLS_EVIDENCE_UNVERIFIED",
    )
    timestamps_ok = require(
        github_ok
        and _report_time_valid(backend, backend_run, now)
        and _report_time_valid(network, network_run, now),
        "INSPECTION_EVIDENCE_STALE_OR_UNGROUNDED",
    )
    close_enough = False
    if timestamps_ok:
        a = _stamp(backend["checked_at_utc"])
        b = _stamp(network["checked_at_utc"])
        close_enough = abs(a - b) <= MAX_PAIR_SKEW
    pair_ok = require(close_enough, "BACKEND_AND_TLS_EVIDENCE_TIME_SKEW")

    correlation = (
        commit_ok and github_ok and backend_ok and private_ok
        and timestamps_ok and pair_ok
    )
    return {
        "contract_version": VERSION,
        "checked_at_utc": now.isoformat(),
        "expected_commit_sha": sha if commit_ok else "invalid",
        "region": REGION,
        "aws_account": ACCOUNT,
        "github_manual_inspection_origins_verified": github_ok,
        "exact_commit_match_verified": commit_ok and backend_ok and private_ok,
        "recent_time_bounded_pair_verified": timestamps_ok and pair_ok,
        "paper_backend_posture_verified": backend_ok,
        "private_tls_dormant_posture_verified": private_ok,
        "paper_backend_and_dormant_worker_metadata_correlated": correlation,
        "actual_java_tls_handshake_verified": False,
        "worker_model_or_secret_identity_verified": False,
        "independent_source_rights_verified": False,
        "paper_shadow_soak_accepted": False,
        "broker_reconciliation_accepted": False,
        "live_execution_enabled": False,
        "can_authorize_capital": False,
        "verdict": "PAPER_BACKEND_DORMANT_PRIVATE_TLS_ONLY" if correlation else "BLOCKED",
        "blocker_codes": sorted(blockers),
    }


def _load(path: Path, max_size: int) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > max_size:
        raise ValueError("invalid evidence input")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("invalid evidence schema")
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description="V87 offline PAPER inspection correlation only")
    for arg in ("backend", "network", "backend_run", "network_run"):
        parser.add_argument("--" + arg.replace("_", "-"), required=True, type=Path)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = evaluate(
            backend=_load(args.backend, MAX_ARTIFACT_BYTES),
            network=_load(args.network, MAX_ARTIFACT_BYTES),
            backend_run=_load(args.backend_run, MAX_RUN_BYTES),
            network_run=_load(args.network_run, MAX_RUN_BYTES),
            sha=args.expected_sha,
        )
    except (OSError, ValueError, TypeError, KeyError, AttributeError, OverflowError):
        report = {
            "contract_version": VERSION,
            "verdict": "BLOCKED",
            "paper_backend_and_dormant_worker_metadata_correlated": False,
            "actual_java_tls_handshake_verified": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "blocker_codes": ["INVALID_EVIDENCE_OR_GITHUB_RUN_INPUT"],
        }
    args.output.write_text(
        json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print("V87 correlated PAPER operator evidence:", report["verdict"])
    return 0 if report["paper_backend_and_dormant_worker_metadata_correlated"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
