"""No-AWS contracts for the explicitly approved 4 GB PAPER Lightsail bootstrap."""
from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "infra/lightsail/verify_plan.py"
spec = importlib.util.spec_from_file_location("lightsail_paper_verify", SRC)
assert spec and spec.loader
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


def fixture():
    return {
        "bundles": {"bundles": [{
            "bundleId": "medium_3_0", "isActive": True,
            "ramSizeInGb": 4.0, "cpuCount": 2,
            "diskSizeInGb": 80, "price": 24.0,
            "publicIpv4AddressCount": 1, "supportedPlatforms": ["LINUX_UNIX"]
        }]},
        "blueprints": {"blueprints": [
            {"blueprintId": "ubuntu_24_04", "isActive": True}
        ]},
        "regions": {"regions": [
            {"name": "us-east-1", "availabilityZones": [{"zoneName": "us-east-1a"}]}
        ]},
        "instances": {"instances": []},
    }


def existing():
    return {
        "name": plan.INSTANCE, "bundleId": plan.BUNDLE,
        "blueprintId": plan.BLUEPRINT,
        "tags": [{"key": k, "value": v} for k, v in plan.TAGS.items()],
        "location": {"regionName": plan.REGION, "availabilityZone": plan.AZ},
        "hardware": {"cpuCount": 2, "ramSizeInGb": 4.0},
    }


class LightsailPaperBootstrapContract(unittest.TestCase):
    def test_allows_only_expected_4gb_24_dollar_paper_plan(self):
        self.assertEqual(plan.verify(**fixture()), "absent")
        data = fixture()
        data["instances"]["instances"] = [existing()]
        self.assertEqual(plan.verify(**data), "present")

    def test_refuses_bigger_cheaper_or_wrong_platform(self):
        for key, bad in (
            ("bundleId", "large_3_0"),
            ("ramSizeInGb", 8.0), ("cpuCount", 4),
            ("diskSizeInGb", 160), ("price", 44.0),
            ("isActive", False), ("publicIpv4AddressCount", 0),
            ("supportedPlatforms", ["WINDOWS"]),
        ):
            f = fixture()
            f["bundles"]["bundles"][0][key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError):
                plan.verify(**f)

    def test_refuses_adopting_wrong_app_instance(self):
        for field, value in (
            ("bundleId", "large_3_0"),
            ("blueprintId", "other_linux"),
            ("tags", [{"key": "Application", "value": "alpha-vidhyarthi"}]),
            ("hardware", {"cpuCount": 2, "ramSizeInGb": 8.0}),
            ("location", {"regionName": "ap-south-1", "availabilityZone": "ap-south-1a"}),
        ):
            f = fixture()
            entry = existing()
            entry[field] = value
            f["instances"]["instances"] = [entry]
            with self.subTest(field=field), self.assertRaises(ValueError):
                plan.verify(**f)

    def test_rejects_unknown_blueprints_and_availability_zones(self):
        f = fixture()
        f["blueprints"]["blueprints"][0]["isActive"] = False
        with self.assertRaises(ValueError):
            plan.verify(**f)
        f = fixture()
        f["regions"]["regions"][0]["availabilityZones"] = []
        with self.assertRaises(ValueError):
            plan.verify(**f)

    def test_oidc_trust_is_exact_dev_only_and_no_iam_escalation(self):
        trust = json.loads((ROOT / "infra/lightsail/iam-trust-dev.json").read_text())
        policy = json.loads((ROOT / "infra/lightsail/iam-paper-provision-policy.json").read_text())
        statement = trust["Statement"][0]
        eq = statement["Condition"]["StringEquals"]
        self.assertEqual(eq["token.actions.githubusercontent.com:aud"], "sts.amazonaws.com")
        self.assertEqual(eq["token.actions.githubusercontent.com:sub"],
                         "repo:operationReact/AiTradingSystem:ref:refs/heads/dev")
        self.assertEqual(statement["Principal"]["Federated"],
                         "arn:aws:iam::098859530205:oidc-provider/token.actions.githubusercontent.com")
        all_actions = [a for entry in policy["Statement"]
                       for a in (entry["Action"] if isinstance(entry["Action"], list)
                                 else [entry["Action"]])]
        self.assertNotIn("lightsail:DeleteInstance", all_actions)
        self.assertNotIn("lightsail:CreateRelationalDatabase", all_actions)
        self.assertNotIn("iam:*", all_actions)
        self.assertFalse(any(a.startswith(("ec2:", "iam:", "ecs:", "rds:", "ecr:"))
                             for a in all_actions))
        provision = next(s for s in policy["Statement"]
                         if s["Action"] == "lightsail:CreateInstances")
        self.assertEqual(provision["Condition"]["StringEquals"]["aws:RequestTag/Environment"],
                         "paper")
        self.assertEqual(provision["Condition"]["StringEquals"]["aws:RequestedRegion"],
                         "us-east-1")

    def test_active_lightsail_paths_cannot_target_retired_aws_account(self):
        current = "098859530205"
        retired = "816344830716"
        # Legacy ECS/evidence files are intentionally outside this scope:
        # only the new four-gigabyte Lightsail deployment is authorized.
        paths = (
            "infra/lightsail/iam-trust-dev.json",
            "infra/lightsail/iam-paper-provision-policy.json",
            "infra/lightsail/verify_plan.py",
            ".github/workflows/lightsail-paper-bootstrap.yml",
            ".github/workflows/lightsail-paper-app-deploy.yml",
            "scripts/tests/test_lightsail_paper_rollout.py",
            "docs/deployment/lightsail-paper-4gb.md",
        )
        for path in paths:
            source = (ROOT / path).read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertIn(current, source)
                self.assertNotIn(retired, source)
        self.assertIn(
            "arn:aws:iam::098859530205:oidc-provider/token.actions.githubusercontent.com",
            (ROOT / "infra/lightsail/iam-trust-dev.json").read_text(),
        )
        self.assertIn(
            "arn:aws:lightsail:us-east-1:098859530205:Instance/*",
            (ROOT / "infra/lightsail/iam-paper-provision-policy.json").read_text(),
        )

    def test_manual_workflow_does_not_provision_without_confirmation(self):
        flow = (ROOT / ".github/workflows/lightsail-paper-bootstrap.yml").read_text()
        self.assertIn("workflow_dispatch:", flow)
        self.assertIn("  push:", flow)
        self.assertIn("branches: [dev]", flow)
        self.assertIn("infra/lightsail/requests/bootstrap.request.json", flow)
        self.assertIn("verify_request.py", flow)
        self.assertNotIn("\n  pull_request:", flow)
        self.assertIn("CREATE_PAPER_4GB", flow)
        self.assertIn('AWS_LIGHTSAIL_ROLE_ARN: arn:aws:iam::098859530205:role/aitradingsystem-github-lightsail-paper', flow)
        self.assertIn('role-to-assume: arn:aws:iam::098859530205:role/aitradingsystem-github-lightsail-paper', flow)
        self.assertNotIn('vars.AWS_LIGHTSAIL_ROLE_ARN', flow)
        self.assertIn('AWS_ACCOUNT: "098859530205"', flow)
        self.assertIn('LIGHTSAIL_INSTANCE: aitradingsystem-paper-dev', flow)
        self.assertIn("lightsail create-instances", flow)
        self.assertIn("lightsail put-instance-public-ports", flow)
        self.assertIn("cidrListAliases", flow)
        self.assertNotIn("terraform apply", flow)
        self.assertNotIn("lightsail delete-instance", flow)
        self.assertNotIn("id-token: read", flow)
        self.assertNotIn("aws iam create-role", flow)
        boot = (ROOT / "infra/lightsail/user-data.sh").read_text()
        self.assertIn("docker-compose-v2", boot)
        self.assertIn("chmod 0600", boot)
        self.assertNotIn("ANGELONE_", boot)
        self.assertNotIn("TRADING_MODE=LIVE", boot)
        self.assertNotIn("docker compose up", boot)


if __name__ == "__main__":
    unittest.main()
