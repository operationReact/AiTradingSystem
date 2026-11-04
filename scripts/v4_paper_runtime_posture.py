#!/usr/bin/env python3
"""V73 read-only PAPER ECS posture evaluator: sanitized evidence, no AWS writes.

Inputs are temporary AWS CLI JSON snapshots. Never include raw ECS container
environment, secret references, task ARNs, public response bodies, or identity
credentials in the resulting uploadable report. No claim of worker attestation.
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

CONTRACT = "v4-paper-runtime-posture.v1"
ACCOUNT_ID = "816344830716"
REGION = "us-east-1"
CLUSTER = "ai-trading-system"
SERVICE = "ai-trading-system-backend"
FAMILY = "ai-trading-system-backend"
BACKEND_CONTAINER = "backend"
RESEARCH_FLAGS = {
    "AI_RESEARCH_WORKER_CLIENT_ENABLED",
    "AI_RESEARCH_WORKER_READINESS_ENABLED",
    "AI_RESEARCH_ROSTER_DISPATCH_ENABLED",
    "APP_RESEARCH_ARTIFACT_WORKER_ENABLED",
    "APP_RESEARCH_WORKER_READINESS_ENABLED",
    "APP_RESEARCH_ROSTER_DISPATCH_ENABLED",
}
# These alternative property sources can override application.yml and bypass
# an otherwise correct explicit false/absent research flag check.
UNVERIFIED_CONFIG_OVERRIDE_NAMES = {
    "SPRING_APPLICATION_JSON",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "SPRING_CONFIG_LOCATION",
    "SPRING_CONFIG_ADDITIONAL_LOCATION",
    "SPRING_PROFILES_ACTIVE",
    "SPRING_CONFIG_IMPORT",
    "SPRING_CONFIG_NAME",
    "SPRING_CLOUD_CONFIG_URI",
    "APP_TRADING_MODE",
}
SENSITIVE_NAMES = {
    "APP_API_KEY",
    "ANGELONE_API_KEY",
    "ANGELONE_CLIENT_CODE",
    "ANGELONE_PASSWORD",
    "ANGELONE_TOTP_SECRET",
    "AI_RESEARCH_WORKER_TOKEN",
    "GEMINI_API_KEY",
    "ANTHROPIC_API_KEY",
    "SPRING_DATASOURCE_PASSWORD",
}


def evaluate(
    identity: dict, clusters: dict, services: dict, task: dict,
    targets: dict, public_health: dict, worker_services: dict, sha: str,
) -> dict:
    problems: set[str] = set()

    def require(ok: bool, code: str) -> bool:
        if not ok:
            problems.add(code)
        return bool(ok)

    if not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{40}", sha):
        problems.add("INVALID_EXPECTED_COMMIT")
        sha = "unknown"
    account_ok = require(
        isinstance(identity, dict) and identity.get("Account") == ACCOUNT_ID,
        "AWS_ACCOUNT_MISMATCH",
    )
    cluster_rows = clusters.get("clusters", []) if isinstance(clusters, dict) else []
    cluster_ok = require(
        len(cluster_rows) == 1
        and cluster_rows[0].get("clusterName") == CLUSTER
        and cluster_rows[0].get("status") == "ACTIVE"
        and not clusters.get("failures", []),
        "ECS_CLUSTER_NOT_ACTIVE",
    )

    rows = services.get("services", []) if isinstance(services, dict) else []
    svc = rows[0] if len(rows) == 1 and isinstance(rows[0], dict) else {}
    deployments = svc.get("deployments", [])
    stable = require(
        svc.get("serviceName") == SERVICE
        and svc.get("status") == "ACTIVE"
        and svc.get("desiredCount") == 1
        and svc.get("runningCount") == 1
        and svc.get("pendingCount") == 0
        and not services.get("failures", [])
        and len(deployments) == 1
        and deployments[0].get("status") == "PRIMARY"
        and deployments[0].get("rolloutState") == "COMPLETED"
        and deployments[0].get("desiredCount") == 1
        and deployments[0].get("runningCount") == 1,
        "ECS_BACKEND_NOT_STABLE",
    )

    definition = task.get("taskDefinition", {}) if isinstance(task, dict) else {}
    container_rows = definition.get("containerDefinitions", [])
    matches = [c for c in container_rows if c.get("name") == BACKEND_CONTAINER]
    container = matches[0] if len(matches) == 1 else {}
    task_ok = require(
        len(matches) == 1 and definition.get("family") == FAMILY
        and definition.get("status") == "ACTIVE"
        and svc.get("taskDefinition") == definition.get("taskDefinitionArn")
        and f":{REGION}:{ACCOUNT_ID}:task-definition/{FAMILY}:" in
            definition.get("taskDefinitionArn", ""),
        "ECS_TASK_DEFINITION_UNVERIFIED",
    )
    expected_image = (
        f"{ACCOUNT_ID}.dkr.ecr.{REGION}.amazonaws.com/{FAMILY}:{sha}"
    )
    image_ok = require(
        container.get("image") == expected_image,
        "ECS_IMAGE_NOT_PINNED_TO_EXPECTED_COMMIT",
    )

    env_rows = container.get("environment", [])
    names = [row.get("name") for row in env_rows if isinstance(row, dict)]
    env = {row.get("name"): row.get("value") for row in env_rows
           if isinstance(row, dict) and isinstance(row.get("name"), str)}
    unique = require(
        len(names) == len(set(names))
        and all(isinstance(name, str) for name in names),
        "ECS_ENVIRONMENT_AMBIGUOUS",
    )
    paper_mode = require(env.get("TRADING_MODE") == "PAPER", "ECS_NOT_PAPER_MODE")
    secrets_safe = require(
        all(name not in SENSITIVE_NAMES and not re.search(
            r"(?:_PASSWORD|_SECRET|_TOKEN|_API_KEY)$", name, re.IGNORECASE
        ) for name in env),
        "PLAINTEXT_CREDENTIAL_NAME_IN_ECS_ENVIRONMENT",
    )
    # Never claim secret values or IAM policies were verified from task metadata.
    secret_names = [row.get("name") for row in container.get("secrets", [])
                    if isinstance(row, dict)]
    require(
        len(secret_names) == len(set(secret_names))
        and all(isinstance(n, str) for n in secret_names)
        and not (set(names) & set(secret_names)),
        "ECS_SECRET_REFERENCE_AMBIGUOUS",
    )
    all_names = set(names) | set(secret_names)
    disabled = require(
        all(str(env.get(flag, "false")).lower() == "false"
            for flag in RESEARCH_FLAGS)
        and not (set(secret_names) & RESEARCH_FLAGS),
        "RESEARCH_OPT_IN_ON_EXISTING_BACKEND",
    )
    config_sources_verified = require(
        not (all_names & UNVERIFIED_CONFIG_OVERRIDE_NAMES)
        and not container.get("environmentFiles")
        and not container.get("command")
        and not container.get("entryPoint"),
        "UNVERIFIED_SPRING_CONFIGURATION_OVERRIDE",
    )

    target_rows = (targets.get("TargetHealthDescriptions", [])
                   if isinstance(targets, dict) else [])
    alb_ok = require(
        len(target_rows) == 1
        and target_rows[0].get("TargetHealth", {}).get("State") == "healthy",
        "ALB_TARGET_HEALTH_UNVERIFIED",
    )
    endpoint_ok = require(
        isinstance(public_health, dict)
        and public_health.get("status") == "UP"
        and public_health.get("mode") == "PAPER"
        and public_health.get("service") == "ai-trading-system",
        "PUBLIC_PAPER_HEALTH_UNVERIFIED",
    )
    worker_rows = (worker_services.get("services", [])
                   if isinstance(worker_services, dict) else [])
    candidate_worker_seen = any(
        row.get("status") == "ACTIVE" and row.get("runningCount", 0) > 0
        for row in worker_rows if isinstance(row, dict)
    )
    # A worker ECS service name or /health alone is NEVER model-artifact
    # identity, authenticated V72 session roster, or license evidence.
    ok = not problems
    return {
        "contract_version": CONTRACT,
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "expected_commit_sha": sha,
        "target_region": REGION,
        "paper_backend_posture_verified": ok,
        "aws_account_verified": account_ok,
        "cluster_active": cluster_ok,
        "backend_service_stable": stable,
        "task_definition_matches_service": task_ok,
        "backend_image_matches_commit": image_ok,
        "environment_unambiguous": unique,
        "paper_mode_verified": paper_mode,
        "plaintext_sensitive_env_absent": secrets_safe,
        "research_opt_ins_disabled": disabled,
        "runtime_configuration_override_absent": config_sources_verified,
        "alb_healthy": alb_ok,
        "public_paper_health_verified": endpoint_ok,
        "research_worker_service_candidate_seen": candidate_worker_seen,
        "worker_model_identity_attested": False,
        "worker_secret_iam_policy_verified": False,
        "independent_source_rights_verified": False,
        "sustained_paper_shadow_soak_accepted": False,
        "broker_reconciliation_accepted": False,
        "live_execution_enabled": False,
        "can_authorize_capital": False,
        "verdict": (
            "PAPER_BACKEND_POSTURE_ONLY_RESEARCH_UNVERIFIED" if ok else "BLOCKED"
        ),
        "blocker_codes": sorted(problems),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    for name in ("identity", "clusters", "services", "task", "targets",
                 "public_health", "worker_services"):
        parser.add_argument("--" + name.replace("_", "-"), type=Path, required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        inputs = {
            name: json.loads(getattr(args, name).read_text(encoding="utf-8"))
            for name in ("identity", "clusters", "services", "task", "targets",
                         "public_health", "worker_services")
        }
        report = evaluate(**inputs, sha=args.expected_sha)
    except (OSError, ValueError, TypeError, AttributeError, KeyError):
        report = {
            "contract_version": CONTRACT,
            "verdict": "BLOCKED",
            "paper_backend_posture_verified": False,
            "worker_model_identity_attested": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "blocker_codes": ["INVALID_INSPECTION_INPUT"],
        }
    args.output.write_text(
        json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print("V73 sanitized inspection verdict:", report["verdict"])
    return 0 if report["paper_backend_posture_verified"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
