"""V75 synthetic-only contracts: deployed ECS worker digest cannot imply model acceptance."""
from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v4_worker_ecs_digest import evaluate  # noqa: E402

ACCOUNT = "816344830716"
REGION = "us-east-1"
CLUSTER = "ai-trading-system"
NAME = "ai-trading-system-research-worker"
SHA = "a" * 40
DIGEST = "sha256:" + "b" * 64
CLUSTER_ARN = f"arn:aws:ecs:{REGION}:{ACCOUNT}:cluster/{CLUSTER}"
TASK_ARN = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task/{CLUSTER}/123abc"
DEFINITION_ARN = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task-definition/{NAME}:17"
IMAGE = f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com/{NAME}:{SHA}"


def fixture() -> dict:
    return {
        "sha": SHA,
        "identity": {"Account": ACCOUNT, "Arn": "SECRET-DO-NOT-REPORT"},
        "clusters": {"clusters": [{"clusterName": CLUSTER, "clusterArn": CLUSTER_ARN,
                                   "status": "ACTIVE"}], "failures": []},
        "services": {"services": [{"serviceName": NAME, "clusterArn": CLUSTER_ARN,
                      "status": "ACTIVE", "desiredCount": 1, "runningCount": 1,
                      "pendingCount": 0, "enableExecuteCommand": False,
                      "launchType": "FARGATE",
                      "taskDefinition": DEFINITION_ARN,
                      "deployments": [{"status": "PRIMARY", "rolloutState": "COMPLETED",
                                       "desiredCount": 1, "runningCount": 1}]}],
                     "failures": []},
        "task_ids": {"taskArns": [TASK_ARN]},
        "tasks": {"tasks": [{"taskArn": TASK_ARN, "clusterArn": CLUSTER_ARN,
                  "group": f"service:{NAME}", "lastStatus": "RUNNING",
                  "desiredStatus": "RUNNING", "enableExecuteCommand": False,
                  "taskDefinitionArn": DEFINITION_ARN,
                  "containers": [{"name": "research-worker", "image": IMAGE,
                                  "imageDigest": DIGEST, "lastStatus": "RUNNING",
                                  "healthStatus": "HEALTHY"}]}], "failures": []},
        "definition": {"taskDefinition": {
            "family": NAME, "taskDefinitionArn": DEFINITION_ARN, "status": "ACTIVE",
            "networkMode": "awsvpc", "requiresCompatibilities": ["FARGATE"],
            "cpu": "512", "memory": "1024",
            "taskRoleArn": f"arn:aws:iam::{ACCOUNT}:role/{NAME}-task",
            "executionRoleArn": f"arn:aws:iam::{ACCOUNT}:role/{NAME}-execution",
            "containerDefinitions": [{
                "name": "research-worker", "essential": True, "image": IMAGE,
                "user": "65532:65532",
                "environment": [
                    {"name": "TRADING_MODE", "value": "PAPER"},
                    {"name": "AI_RESEARCH_STRICT_BOOT_REQUIRED", "value": "true"},
                    {"name": "AI_RESEARCH_MODEL_MANIFEST", "value": "/private/models.json"},
                ],
                "secrets": [{"name": "AI_RESEARCH_WORKER_TOKEN",
                             "valueFrom": f"arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:private"}],
            }],
        }},
        "images": {"imageDetails": [{"imageDigest": DIGEST, "imageTags": [SHA]}]},
    }


class WorkerImageEvidenceTest(unittest.TestCase):
    def blocked(self, data: dict, code: str) -> None:
        report = evaluate(**data)
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertIn(code, report["blocker_codes"])
        self.assertFalse(report["can_authorize_capital"])

    def test_matching_snapshot_is_image_posture_only(self):
        result = evaluate(**fixture())
        self.assertTrue(result["ecs_worker_image_posture_verified"])
        self.assertTrue(result["running_image_digest_matches_ecr"])
        self.assertTrue(result["strict_worker_boot_declared"])
        self.assertTrue(result["worker_privilege_overrides_absent"])
        self.assertTrue(result["bounded_fargate_runtime_declared"])
        self.assertEqual(result["verdict"], "PAPER_WORKER_IMAGE_POSTURE_ONLY_MODEL_UNVERIFIED")
        for field in ("worker_model_artifacts_verified",
                      "worker_secret_value_or_iam_access_verified",
                      "worker_network_and_v72_roster_verified",
                      "independent_market_data_rights_verified",
                      "paper_shadow_soak_accepted", "broker_reconciliation_accepted",
                      "live_execution_enabled", "can_authorize_capital"):
            self.assertFalse(result[field])
        raw = json.dumps(result)
        for sensitive in ("SECRET-DO-NOT-REPORT", "/private/models.json",
                          "secret:private", "arn:aws", DIGEST):
            self.assertNotIn(sensitive, raw)

    def test_missing_worker_or_wrong_account_never_passes(self):
        f = fixture()
        f["services"]["services"] = []
        self.blocked(f, "WORKER_SERVICE_UNVERIFIED")
        f = fixture()
        f["identity"]["Account"] = "000000000000"
        self.blocked(f, "ACCOUNT_UNVERIFIED")

    def test_v78_strict_boot_is_mandatory_for_worker_posture(self):
        f = fixture()
        rows = f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "environment"]
        rows[:] = [row for row in rows
                   if row["name"] != "AI_RESEARCH_STRICT_BOOT_REQUIRED"]
        self.blocked(f, "WORKER_STRICT_BOOT_NOT_REQUIRED")

        for wrong in ("false", "TRUE", "", "1"):
            f = fixture()
            rows = f["definition"]["taskDefinition"]["containerDefinitions"][0][
                "environment"]
            next(row for row in rows
                 if row["name"] == "AI_RESEARCH_STRICT_BOOT_REQUIRED")["value"] = wrong
            self.blocked(f, "WORKER_STRICT_BOOT_NOT_REQUIRED")

        f = fixture()
        rows = f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "environment"]
        rows[:] = [row for row in rows
                   if row["name"] != "AI_RESEARCH_STRICT_BOOT_REQUIRED"]
        f["definition"]["taskDefinition"]["containerDefinitions"][0]["secrets"].append({
            "name": "AI_RESEARCH_STRICT_BOOT_REQUIRED",
            "valueFrom": f"arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:strict",
        })
        self.blocked(f, "WORKER_STRICT_BOOT_NOT_REQUIRED")

    def test_v93_explicit_nonroot_and_no_ecs_privilege_overrides(self):
        root = Path(__file__).resolve().parents[2]
        tf = (root / "infra/terraform/research-worker-scaffold.tf").read_text()
        docker = (root / "intelligence/Dockerfile.research-release").read_text()
        self.assertIn('user = "65532:65532"', tf)
        self.assertIn("desired_count                     = 0", tf)
        self.assertIn("USER 65532:65532", docker)
        for field, value in (
            ("user", "0"), ("user", "0:0"), ("user", "65532"),
            ("user", None), ("privileged", True),
            ("interactive", True), ("pseudoTerminal", True),
            ("linuxParameters", {"capabilities": {"add": ["SYS_ADMIN"]}}),
            ("dockerSecurityOptions", ["no-new-privileges:false"]),
            ("extraHosts", [{"hostname": "bad", "ipAddress": "127.0.0.1"}]),
        ):
            f = fixture()
            f["definition"]["taskDefinition"]["containerDefinitions"][0][field] = value
            self.blocked(f, "WORKER_PRIVILEGE_OVERRIDE_UNVERIFIED")

        valid = fixture()
        valid["definition"]["taskDefinition"]["containerDefinitions"][0]["privileged"] = False
        self.assertTrue(evaluate(**valid)["worker_privilege_overrides_absent"])

    def test_v94_bounded_fargate_runtime_and_role_separation(self):
        root = Path(__file__).resolve().parents[2]
        tf = (root / "infra/terraform/research-worker-scaffold.tf").read_text()
        for required in (
            'requires_compatibilities = ["FARGATE"]',
            'network_mode             = "awsvpc"',
            "cpu                      = 512",
            "memory                   = 1024",
            "task_role_arn            = aws_iam_role.research_worker_task[0].arn",
            "execution_role_arn       = aws_iam_role.research_worker_execution[0].arn",
            'launch_type                       = "FARGATE"',
            "desired_count                     = 0",
            "enable_execute_command            = false",
        ):
            self.assertIn(required, tf)

        for field, value in (
            ("networkMode", "bridge"),
            ("networkMode", "host"),
            ("networkMode", None),
            ("requiresCompatibilities", ["EC2"]),
            ("requiresCompatibilities", ["FARGATE", "EC2"]),
            ("requiresCompatibilities", []),
            ("cpu", "1024"),
            ("memory", "2048"),
            ("taskRoleArn", f"arn:aws:iam::{ACCOUNT}:role/{NAME}-execution"),
            ("executionRoleArn", f"arn:aws:iam::{ACCOUNT}:role/{NAME}-task"),
            ("taskRoleArn", "arn:aws:iam::000000000000:role/other"),
            ("volumes", [{"name": "sneaky-mount"}]),
            ("pidMode", "task"),
            ("ipcMode", "task"),
            ("proxyConfiguration", {"type": "APPMESH"}),
            ("inferenceAccelerators", [{"deviceName": "a", "deviceType": "eia1.medium"}]),
            ("enableFaultInjection", True),
        ):
            f = fixture()
            f["definition"]["taskDefinition"][field] = value
            self.blocked(f, "WORKER_TASK_RUNTIME_UNVERIFIED")

        for field, value in (
            ("launchType", "EC2"),
            ("launchType", None),
            ("capacityProviderStrategy", [{"capacityProvider": "FARGATE_SPOT",
                                          "weight": 1}]),
        ):
            f = fixture()
            f["services"]["services"][0][field] = value
            self.blocked(f, "WORKER_SERVICE_UNVERIFIED")

        f = fixture()
        f["definition"]["taskDefinition"]["volumes"] = []
        self.assertTrue(evaluate(**f)["bounded_fargate_runtime_declared"])

    def test_running_image_must_match_ecr_digest_and_tag(self):
        f = fixture()
        f["tasks"]["tasks"][0]["containers"][0]["imageDigest"] = "sha256:" + "c" * 64
        self.blocked(f, "RUNNING_IMAGE_DIGEST_MISMATCH")
        f = fixture()
        f["images"]["imageDetails"][0]["imageTags"] = ["latest"]
        self.blocked(f, "ECR_IMAGE_DIGEST_UNVERIFIED")
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0]["image"] = IMAGE[:-40] + "latest"
        self.blocked(f, "WORKER_IMAGE_TAG_UNVERIFIED")

    def test_worker_rollout_and_task_identity_must_match(self):
        f = fixture()
        f["services"]["services"][0]["deployments"][0]["rolloutState"] = "IN_PROGRESS"
        self.blocked(f, "WORKER_SERVICE_UNVERIFIED")
        f = fixture()
        f["task_ids"]["taskArns"] = []
        self.blocked(f, "TASK_INVENTORY_UNVERIFIED")
        f = fixture()
        f["task_ids"]["nextToken"] = "MORE"
        self.blocked(f, "TASK_INVENTORY_UNVERIFIED")
        f = fixture()
        f["tasks"]["tasks"][0]["enableExecuteCommand"] = True
        self.blocked(f, "RUNNING_WORKER_UNVERIFIED")
        f = fixture()
        f["tasks"]["tasks"][0]["containers"][0]["healthStatus"] = "UNKNOWN"
        self.blocked(f, "RUNNING_WORKER_UNVERIFIED")

    def test_no_plaintext_worker_credentials_or_live_mode(self):
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "environment"].append({"name": "AI_RESEARCH_WORKER_TOKEN", "value": "PRIVATE"})
        self.blocked(f, "WORKER_SECRET_OR_MANIFEST_DECLARATION_UNVERIFIED")
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "secrets"] = []
        self.blocked(f, "WORKER_SECRET_OR_MANIFEST_DECLARATION_UNVERIFIED")
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "environment"][0]["value"] = "LIVE"
        self.blocked(f, "WORKER_PAPER_DECLARATION_UNVERIFIED")
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "environmentFiles"] = [{"type": "s3", "value": "PRIVATE-URI"}]
        self.blocked(f, "WORKER_PAPER_DECLARATION_UNVERIFIED")
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "environment"].append({"name": "AWS_ACCESS_KEY_ID", "value": "PRIVATE"})
        self.blocked(f, "PLAINTEXT_WORKER_CREDENTIAL_NAME")
        f = fixture()
        f["definition"]["taskDefinition"]["containerDefinitions"][0][
            "secrets"].append({"name": "", "valueFrom": ""})
        self.blocked(f, "WORKER_CONFIG_AMBIGUOUS")

    def test_read_only_manual_workflow(self):
        workflow = (Path(__file__).resolve().parents[2] /
                    ".github/workflows/v4-worker-ecs-image-inspect.yml").read_text()
        self.assertIn("on:\n  workflow_dispatch:", workflow)
        self.assertIn("vars.AWS_WORKER_READONLY_ROLE_ARN", workflow)
        for forbidden in ("aws ecs run-task", "aws ecs update-service",
                          "aws ecr put-image", "aws secretsmanager get-secret-value",
                          "aws ecs execute-command", "aws iam", "aws ssm send-command"):
            self.assertNotIn(forbidden, workflow.lower())

    def test_iam_policy_cannot_mutate(self):
        policy = json.loads((Path(__file__).resolve().parents[2] /
                            "infra/aws/readonly-v75-worker-inspection-policy.json").read_text())
        actions = {action for stmt in policy["Statement"]
                   for action in stmt["Action"]}
        self.assertEqual(actions, {"sts:GetCallerIdentity", "ecs:DescribeClusters",
                                   "ecs:DescribeServices", "ecs:ListTasks",
                                   "ecs:DescribeTasks", "ecs:DescribeTaskDefinition",
                                   "ecr:DescribeImages"})
        self.assertTrue(all(s["Effect"] == "Allow" for s in policy["Statement"]))
        self.assertTrue(all("*" not in a for a in actions))
        ecr = next(s for s in policy["Statement"] if "ecr:DescribeImages" in s["Action"])
        self.assertEqual(ecr["Resource"],
                         f"arn:aws:ecr:{REGION}:{ACCOUNT}:repository/{NAME}")


if __name__ == "__main__":
    unittest.main()
