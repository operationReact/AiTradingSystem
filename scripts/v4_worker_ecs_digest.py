#!/usr/bin/env python3
"""V75: fail-closed read-only AWS ECS worker image digest evidence.

All raw inputs are runner-local and may contain secrets. Output contains only
bounded booleans/codes; never report ECS environment, secret references or ARNs.
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

ACCOUNT = "816344830716"
REGION = "us-east-1"
CLUSTER = "ai-trading-system"
SERVICE = "ai-trading-system-research-worker"
CONTAINER = "research-worker"
REPO = "ai-trading-system-research-worker"
VERSION = "v4-worker-ecs-digest-evidence.v2"
TASK_FAMILY = SERVICE
CLUSTER_ARN = f"arn:aws:ecs:{REGION}:{ACCOUNT}:cluster/{CLUSTER}"
TASK_PREFIX = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task/{CLUSTER}/"
DEFINITION_PREFIX = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task-definition/{TASK_FAMILY}:"


def evaluate(identity: dict, clusters: dict, services: dict, task_ids: dict,
             tasks: dict, definition: dict, images: dict, sha: str) -> dict:
    blockers: set[str] = set()

    def check(condition: bool, code: str) -> bool:
        if not condition:
            blockers.add(code)
        return bool(condition)

    if not isinstance(sha, str) or re.fullmatch(r"[a-f0-9]{40}", sha) is None:
        blockers.add("INVALID_COMMIT")
        sha = "unknown"
    account_ok = check(identity.get("Account") == ACCOUNT, "ACCOUNT_UNVERIFIED")
    cs = clusters.get("clusters", [])
    cluster_ok = check(
        len(cs) == 1 and cs[0].get("clusterName") == CLUSTER
        and cs[0].get("clusterArn") == CLUSTER_ARN
        and cs[0].get("status") == "ACTIVE"
        and not clusters.get("failures"),
        "CLUSTER_UNVERIFIED",
    )
    ss = services.get("services", [])
    service = ss[0] if len(ss) == 1 else {}
    ds = service.get("deployments", [])
    svc_ok = check(
        service.get("serviceName") == SERVICE
        and service.get("clusterArn") == CLUSTER_ARN
        and service.get("status") == "ACTIVE"
        and service.get("desiredCount") == 1
        and service.get("runningCount") == 1
        and service.get("pendingCount") == 0
        and service.get("enableExecuteCommand") is False
        and service.get("launchType") == "FARGATE"
        and not service.get("capacityProviderStrategy")
        and len(ds) == 1 and ds[0].get("status") == "PRIMARY"
        and ds[0].get("rolloutState") == "COMPLETED"
        and ds[0].get("runningCount") == 1
        and ds[0].get("desiredCount") == 1
        and not services.get("failures"),
        "WORKER_SERVICE_UNVERIFIED",
    )
    ids = task_ids.get("taskArns", [])
    ids_ok = check(
        len(ids) == 1 and isinstance(ids[0], str)
        and ids[0].startswith(TASK_PREFIX) and not task_ids.get("nextToken"),
        "TASK_INVENTORY_UNVERIFIED",
    )
    td = definition.get("taskDefinition", {})
    def_ok = check(
        td.get("family") == TASK_FAMILY and td.get("status") == "ACTIVE"
        and isinstance(td.get("taskDefinitionArn"), str)
        and td["taskDefinitionArn"].startswith(DEFINITION_PREFIX)
        and td.get("taskDefinitionArn") == service.get("taskDefinition"),
        "WORKER_TASK_DEFINITION_UNVERIFIED",
    )
    # V94: a digest-matched task must still use the bounded, isolated
    # Terraform runtime. Only metadata is inspected: role *permissions*,
    # network reachability and the actual container filesystem are unverified.
    task_runtime_ok = check(
        td.get("networkMode") == "awsvpc"
        and td.get("requiresCompatibilities") == ["FARGATE"]
        and td.get("cpu") == "512"
        and td.get("memory") == "1024"
        and td.get("taskRoleArn")
        == f"arn:aws:iam::{ACCOUNT}:role/{SERVICE}-task"
        and td.get("executionRoleArn")
        == f"arn:aws:iam::{ACCOUNT}:role/{SERVICE}-execution"
        and not td.get("volumes")
        and not td.get("pidMode")
        and not td.get("ipcMode")
        and not td.get("proxyConfiguration")
        and not td.get("inferenceAccelerators")
        and td.get("enableFaultInjection") in (None, False),
        "WORKER_TASK_RUNTIME_UNVERIFIED",
    )
    containers = td.get("containerDefinitions", [])
    container = containers[0] if len(containers) == 1 else {}
    expected_image = f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com/{REPO}:{sha}"
    tag_ok = check(
        len(containers) == 1 and container.get("name") == CONTAINER
        and container.get("essential") is True
        and container.get("image") == expected_image,
        "WORKER_IMAGE_TAG_UNVERIFIED",
    )
    rows = images.get("imageDetails", [])
    image = rows[0] if len(rows) == 1 else {}
    digest = image.get("imageDigest", "")
    image_ok = check(
        re.fullmatch(r"sha256:[a-f0-9]{64}", digest) is not None
        and sha in image.get("imageTags", [])
        and image.get("repositoryName") in (None, REPO),
        "ECR_IMAGE_DIGEST_UNVERIFIED",
    )
    ts = tasks.get("tasks", [])
    task = ts[0] if len(ts) == 1 else {}
    tc = task.get("containers", [])
    task_container = tc[0] if len(tc) == 1 else {}
    running_ok = check(
        ids_ok and len(ts) == 1
        and task.get("taskArn") == ids[0]
        and task.get("clusterArn") == CLUSTER_ARN
        and task.get("group") == f"service:{SERVICE}"
        and task.get("taskDefinitionArn") == td.get("taskDefinitionArn")
        and task.get("lastStatus") == "RUNNING"
        and task.get("desiredStatus") == "RUNNING"
        and task.get("enableExecuteCommand") is False
        and len(tc) == 1 and task_container.get("name") == CONTAINER
        and task_container.get("lastStatus") == "RUNNING"
        and task_container.get("healthStatus") == "HEALTHY"
        and task_container.get("image") == expected_image
        and not tasks.get("failures"),
        "RUNNING_WORKER_UNVERIFIED",
    )
    digest_ok = check(
        image_ok and running_ok and task_container.get("imageDigest") == digest,
        "RUNNING_IMAGE_DIGEST_MISMATCH",
    )
    envrows = container.get("environment", [])
    envnames = [row.get("name") for row in envrows]
    secretrows = container.get("secrets", [])
    secret_names = [row.get("name") for row in secretrows]
    env = {row.get("name"): row.get("value") for row in envrows}
    unique_ok = check(
        all(isinstance(n, str) and bool(n) for n in envnames + secret_names)
        and len(envnames) == len(set(envnames))
        and len(secret_names) == len(set(secret_names))
        and not set(envnames).intersection(secret_names)
        and all(isinstance(row, dict) for row in envrows + secretrows)
        and all(isinstance(row.get("valueFrom"), str) and row["valueFrom"]
                for row in secretrows),
        "WORKER_CONFIG_AMBIGUOUS",
    )
    # V95: allow only the three documented PAPER startup configuration
    # variables and the single Secrets Manager token reference. Unreviewed
    # flags or extra injected secrets must not inherit a healthy-image verdict.
    allowlisted_env = check(
        unique_ok
        and set(envnames) == {
            "TRADING_MODE", "AI_RESEARCH_STRICT_BOOT_REQUIRED",
            "AI_RESEARCH_MODEL_MANIFEST",
        }
        and set(secret_names) == {"AI_RESEARCH_WORKER_TOKEN"}
        and all(isinstance(row.get("value"), str) for row in envrows),
        "WORKER_STARTUP_CONFIG_NOT_ALLOWLISTED",
    )
    # Private worker exposes only its reviewed container-local TCP 8090.
    # This is not evidence that VPC/Security Groups or ALB paths are correct.
    mappings = container.get("portMappings", [])
    mapping = mappings[0] if isinstance(mappings, list) and len(mappings) == 1 else {}
    port_surface_ok = check(
        isinstance(mappings, list) and len(mappings) == 1
        and isinstance(mapping, dict)
        and mapping.get("containerPort") == 8090
        and mapping.get("hostPort") == 8090
        and mapping.get("protocol") == "tcp"
        and not mapping.get("containerPortRange")
        and not mapping.get("appProtocol")
        and not mapping.get("name"),
        "WORKER_PORT_SURFACE_UNVERIFIED",
    )
    # V93: metadata-only privilege guard. Explicit non-root UID:GID is
    # required in the task definition, rather than inferred from an image tag.
    # No task-defined root, privileged process, interactive terminal, injected
    # Linux capability/security options, or runtime command/entrypoint overrides.
    # Image contents are NOT attested by this metadata-only read.
    privilege_ok = check(
        container.get("user") == "65532:65532"
        and container.get("privileged") in (None, False)
        and container.get("interactive") in (None, False)
        and container.get("pseudoTerminal") in (None, False)
        and not container.get("linuxParameters")
        and not container.get("dockerSecurityOptions")
        and not container.get("extraHosts"),
        "WORKER_PRIVILEGE_OVERRIDE_UNVERIFIED",
    )
    mode_ok = check(
        env.get("TRADING_MODE") == "PAPER"
        and "APP_TRADING_MODE" not in env
        and not container.get("environmentFiles")
        and not container.get("entryPoint")
        and not container.get("command"),
        "WORKER_PAPER_DECLARATION_UNVERIFIED",
    )
    # V79: a healthy ECS image is not acceptable if the deployed task can
    # bypass V78's local hash/token validation during container startup.
    # A secret-sourced/missing/false flag is not an attested strict runtime.
    strict_boot_ok = check(
        env.get("AI_RESEARCH_STRICT_BOOT_REQUIRED") == "true"
        and "AI_RESEARCH_STRICT_BOOT_REQUIRED" not in secret_names,
        "WORKER_STRICT_BOOT_NOT_REQUIRED",
    )
    refs = [row.get("valueFrom", "") for row in secretrows
            if row.get("name") == "AI_RESEARCH_WORKER_TOKEN"]
    token_ref_ok = check(
        "AI_RESEARCH_WORKER_TOKEN" not in env
        and len(refs) == 1
        and isinstance(refs[0], str)
        and refs[0].startswith(f"arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:")
        and "AI_RESEARCH_MODEL_MANIFEST" in env
        and isinstance(env.get("AI_RESEARCH_MODEL_MANIFEST"), str)
        and env["AI_RESEARCH_MODEL_MANIFEST"].startswith("/"),
        "WORKER_SECRET_OR_MANIFEST_DECLARATION_UNVERIFIED",
    )
    sensitive_plaintext = any(
        str(name) in {
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "DATABASE_URL", "REDIS_URL", "DATABASE_DSN", "SPRING_DATASOURCE_URL",
        } or re.search(r"(?:_TOKEN|_PASSWORD|_SECRET|_API_KEY|_PRIVATE_KEY|_ACCESS_KEY)$",
                       str(name))
        for name in envnames
    )
    secret_safe = check(not sensitive_plaintext, "PLAINTEXT_WORKER_CREDENTIAL_NAME")
    overrides_safe = check(
        not set(envnames + secret_names).intersection({
            "SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
            "SPRING_PROFILES_ACTIVE", "SPRING_CONFIG_IMPORT",
        }),
        "UNVERIFIED_WORKER_CONFIG_OVERRIDE",
    )
    ok = not blockers
    return {
        "contract_version": VERSION,
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "expected_commit_sha": sha,
        "target_region": REGION,
        "aws_account_verified": account_ok,
        "cluster_verified": cluster_ok,
        "worker_service_stable": svc_ok,
        "task_inventory_verified": ids_ok,
        "task_definition_verified": def_ok,
        "bounded_fargate_runtime_declared": task_runtime_ok,
        "expected_image_tag_verified": tag_ok,
        "ecr_image_digest_present": image_ok,
        "one_running_healthy_task": running_ok,
        "running_image_digest_matches_ecr": digest_ok,
        "environment_unambiguous": unique_ok,
        "startup_config_names_allowlisted": allowlisted_env,
        "single_private_tcp_port_declared": port_surface_ok,
        "paper_declaration_verified": mode_ok,
        "worker_privilege_overrides_absent": privilege_ok,
        "strict_worker_boot_declared": strict_boot_ok,
        "worker_secret_reference_and_manifest_declared": token_ref_ok,
        "no_plaintext_credential_env_names": secret_safe,
        "config_override_absent": overrides_safe,
        "ecs_worker_image_posture_verified": ok,
        "worker_model_artifacts_verified": False,
        "worker_secret_value_or_iam_access_verified": False,
        "worker_network_and_v72_roster_verified": False,
        "independent_market_data_rights_verified": False,
        "paper_shadow_soak_accepted": False,
        "broker_reconciliation_accepted": False,
        "live_execution_enabled": False,
        "can_authorize_capital": False,
        "verdict": "PAPER_WORKER_IMAGE_POSTURE_ONLY_MODEL_UNVERIFIED" if ok else "BLOCKED",
        "blocker_codes": sorted(blockers),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    inputs = ("identity", "clusters", "services", "task_ids", "tasks",
              "definition", "images")
    for name in inputs:
        parser.add_argument("--" + name.replace("_", "-"), type=Path, required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        snapshots = {name: json.loads(getattr(args, name).read_text(encoding="utf-8"))
                     for name in inputs}
        if not all(isinstance(value, dict) for value in snapshots.values()):
            raise ValueError("invalid top level JSON")
        report = evaluate(**snapshots, sha=args.expected_sha)
    except (OSError, ValueError, TypeError, AttributeError, KeyError, IndexError):
        report = {
            "contract_version": VERSION,
            "verdict": "BLOCKED",
            "ecs_worker_image_posture_verified": False,
            "worker_model_artifacts_verified": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "blocker_codes": ["INVALID_INSPECTION_INPUT"],
        }
    args.output.write_text(
        json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print("V75 worker image inspection:", report["verdict"])
    return 0 if report["ecs_worker_image_posture_verified"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
