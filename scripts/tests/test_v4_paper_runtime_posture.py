"""Contract tests for read-only V73 ECS posture; synthetic, no AWS credentials."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v4_paper_runtime_posture import evaluate  # noqa: E402

SHA = "e" * 40
ACCOUNT = "816344830716"
REGION = "us-east-1"
FAMILY = "ai-trading-system-backend"
TASK_ARN = (
    f"arn:aws:ecs:{REGION}:{ACCOUNT}:task-definition/{FAMILY}:13"
)


def fixtures() -> dict:
    return {
        "identity": {"Account": ACCOUNT, "Arn": "PRIVATE-NEVER-IN-REPORT"},
        "clusters": {"clusters": [{"clusterName": "ai-trading-system",
                                    "status": "ACTIVE"}], "failures": []},
        "services": {"services": [{
            "serviceName": FAMILY, "status": "ACTIVE",
            "desiredCount": 1, "runningCount": 1, "pendingCount": 0,
            "taskDefinition": TASK_ARN,
            "deployments": [{
                "status": "PRIMARY", "rolloutState": "COMPLETED",
                "desiredCount": 1, "runningCount": 1,
            }],
        }], "failures": []},
        "task": {"taskDefinition": {
            "family": FAMILY, "status": "ACTIVE", "taskDefinitionArn": TASK_ARN,
            "containerDefinitions": [{
                "name": "backend",
                "image": (f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com/"
                          f"{FAMILY}:{SHA}"),
                "environment": [
                    {"name": "TRADING_MODE", "value": "PAPER"},
                    {"name": "AI_RESEARCH_WORKER_CLIENT_ENABLED", "value": "false"},
                ],
                "secrets": [{
                    "name": "SPRING_DATASOURCE_PASSWORD",
                    "valueFrom": "PRIVATE-PROVIDER-REFERENCE-NEVER-IN-REPORT",
                }],
            }],
        }},
        "targets": {"TargetHealthDescriptions": [
            {"TargetHealth": {"State": "healthy"}, "Target": {"Id": "PRIVATE-IP"}},
        ]},
        "public_health": {"status": "UP", "mode": "PAPER",
                          "service": "ai-trading-system",
                          "timestamp": "2026-11-04T00:00:00Z"},
        "worker_services": {"services": [], "failures": [{
            "arn": "PRIVATE-NEVER-IN-REPORT", "reason": "MISSING",
        }]},
        "sha": SHA,
    }


class RuntimePostureTest(unittest.TestCase):
    def assert_blocked(self, data: dict, expected_code: str) -> dict:
        report = evaluate(**data)
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertIn(expected_code, report["blocker_codes"])
        self.assertFalse(report["can_authorize_capital"])
        self.assertFalse(report["live_execution_enabled"])
        return report

    def test_canonical_backend_posture_is_not_worker_or_live_attestation(self):
        f = fixtures()
        report = evaluate(**f)
        self.assertEqual(report["verdict"],
                         "PAPER_BACKEND_POSTURE_ONLY_RESEARCH_UNVERIFIED")
        self.assertTrue(report["paper_backend_posture_verified"])
        self.assertEqual(report["blocker_codes"], [])
        self.assertFalse(report["worker_model_identity_attested"])
        self.assertFalse(report["independent_source_rights_verified"])
        self.assertFalse(report["sustained_paper_shadow_soak_accepted"])
        self.assertFalse(report["broker_reconciliation_accepted"])
        self.assertFalse(report["can_authorize_capital"])
        import json
        output = json.dumps(report)
        for private in ("PRIVATE", "valueFrom", "ANGELONE_PASSWORD",
                        "SPRING_DATASOURCE_PASSWORD", "arn:aws"):
            self.assertNotIn(private, output)

    def test_unknown_worker_service_does_not_claim_verified_deployment(self):
        f = fixtures()
        f["worker_services"]["services"] = [
            {"status": "ACTIVE", "runningCount": 1,
             "serviceName": "ai-trading-system-research-worker"},
        ]
        report = evaluate(**f)
        self.assertTrue(report["research_worker_service_candidate_seen"])
        self.assertFalse(report["worker_model_identity_attested"])
        self.assertFalse(report["worker_secret_iam_policy_verified"])

    def test_other_account_or_old_image_is_blocked(self):
        f = fixtures()
        f["identity"]["Account"] = "000000000000"
        self.assert_blocked(f, "AWS_ACCOUNT_MISMATCH")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0]["image"] = (
            f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com/{FAMILY}:latest"
        )
        self.assert_blocked(f, "ECS_IMAGE_NOT_PINNED_TO_EXPECTED_COMMIT")

    def test_live_or_research_worker_enabled_is_blocked(self):
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0]["environment"][0][
            "value"] = "LIVE"
        self.assert_blocked(f, "ECS_NOT_PAPER_MODE")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0]["environment"].append(
            {"name": "AI_RESEARCH_WORKER_READINESS_ENABLED", "value": "true"}
        )
        self.assert_blocked(f, "RESEARCH_OPT_IN_ON_EXISTING_BACKEND")

    def test_plaintext_secrets_and_duplicate_env_rejected(self):
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0]["environment"].append(
            {"name": "AI_RESEARCH_WORKER_TOKEN", "value": "PRIVATE-SECRET"}
        )
        report = self.assert_blocked(
            f, "PLAINTEXT_CREDENTIAL_NAME_IN_ECS_ENVIRONMENT"
        )
        self.assertNotIn("PRIVATE-SECRET", str(report))
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0]["environment"].append(
            {"name": "TRADING_MODE", "value": "LIVE"}
        )
        self.assert_blocked(f, "ECS_ENVIRONMENT_AMBIGUOUS")

    def test_uninspected_spring_overrides_and_secret_flags_are_blocked(self):
        for name in ("SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS",
                     "JDK_JAVA_OPTIONS", "SPRING_CONFIG_ADDITIONAL_LOCATION",
                     "SPRING_PROFILES_ACTIVE"):
            f = fixtures()
            f["task"]["taskDefinition"]["containerDefinitions"][0][
                "environment"].append({"name": name, "value": "PRIVATE"})
            report = self.assert_blocked(
                f, "UNVERIFIED_SPRING_CONFIGURATION_OVERRIDE"
            )
            self.assertNotIn("PRIVATE", str(report))
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0][
            "environmentFiles"] = [{"type": "s3", "value": "PRIVATE-S3-URI"}]
        self.assert_blocked(f, "UNVERIFIED_SPRING_CONFIGURATION_OVERRIDE")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0][
            "command"] = ["--app.research.roster-dispatch.enabled=true"]
        self.assert_blocked(f, "UNVERIFIED_SPRING_CONFIGURATION_OVERRIDE")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0][
            "secrets"].append({
                "name": "APP_RESEARCH_ROSTER_DISPATCH_ENABLED",
                "valueFrom": "PRIVATE",
            })
        self.assert_blocked(f, "RESEARCH_OPT_IN_ON_EXISTING_BACKEND")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0][
            "environment"].append({
                "name": "APP_RESEARCH_ROSTER_DISPATCH_ENABLED", "value": "true",
            })
        self.assert_blocked(f, "RESEARCH_OPT_IN_ON_EXISTING_BACKEND")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0][
            "environment"].append({
                "name": "APP_TRADING_MODE", "value": "LIVE",
            })
        self.assert_blocked(f, "UNVERIFIED_SPRING_CONFIGURATION_OVERRIDE")
        f = fixtures()
        f["task"]["taskDefinition"]["containerDefinitions"][0][
            "secrets"].append({
                "name": "TRADING_MODE", "valueFrom": "PRIVATE",
            })
        self.assert_blocked(f, "ECS_SECRET_REFERENCE_AMBIGUOUS")

    def test_partial_rollout_and_unhealthy_alb_and_http_health_are_blocked(self):
        f = fixtures()
        f["services"]["services"][0]["pendingCount"] = 1
        self.assert_blocked(f, "ECS_BACKEND_NOT_STABLE")
        f = fixtures()
        f["targets"]["TargetHealthDescriptions"][0]["TargetHealth"][
            "State"] = "unhealthy"
        self.assert_blocked(f, "ALB_TARGET_HEALTH_UNVERIFIED")
        f = fixtures()
        f["public_health"]["mode"] = "LIVE"
        self.assert_blocked(f, "PUBLIC_PAPER_HEALTH_UNVERIFIED")

    def test_wrong_task_definition_or_wrong_region_rejected(self):
        f = fixtures()
        f["task"]["taskDefinition"]["taskDefinitionArn"] = (
            f"arn:aws:ecs:ap-south-1:{ACCOUNT}:task-definition/{FAMILY}:13"
        )
        self.assert_blocked(f, "ECS_TASK_DEFINITION_UNVERIFIED")
        f = fixtures()
        f["services"]["services"][0]["taskDefinition"] = (
            "arn:aws:ecs:us-east-1:000000000000:task-definition/other:4"
        )
        self.assert_blocked(f, "ECS_TASK_DEFINITION_UNVERIFIED")

    def test_inspection_action_is_manual_and_not_deployment_capable(self):
        workflow = (Path(__file__).resolve().parents[2] /
                    ".github/workflows/v4-paper-runtime-inspect.yml"
                    ).read_text(encoding="utf-8")
        self.assertIn("on:\n  workflow_dispatch:", workflow)
        self.assertNotIn("\n  push:", workflow)
        self.assertNotIn("\n  pull_request:", workflow)
        self.assertIn("vars.AWS_READONLY_ROLE_ARN", workflow)
        self.assertNotIn("vars.AWS_ROLE_ARN", workflow)
        self.assertIn("allowed-account-ids: '816344830716'", workflow)
        for mutation in (
            "aws ecs update-service",
            "aws ecs register-task-definition",
            "aws ecs run-task",
            "aws ecr put-image",
            "aws ecr create-repository",
            "aws ssm send-command",
            "aws secretsmanager get-secret-value",
            "aws iam",
            "aws ecs execute-command",
        ):
            self.assertNotIn(mutation, workflow.lower())

    def test_invalid_sha_and_missing_inputs_fail_without_inventing_metrics(self):
        f = fixtures()
        f["sha"] = "garbage"
        report = self.assert_blocked(f, "INVALID_EXPECTED_COMMIT")
        self.assertEqual(report["expected_commit_sha"], "unknown")
        f = fixtures()
        f["worker_services"] = {}
        f["services"] = {"services": []}
        self.assert_blocked(f, "ECS_BACKEND_NOT_STABLE")


if __name__ == "__main__":
    unittest.main()
