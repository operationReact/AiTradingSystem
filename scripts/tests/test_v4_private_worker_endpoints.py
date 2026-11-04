"""Source-only V77 safeguards: optional private AWS endpoint plumbing, never task start."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
NETWORK = ROOT / "infra/terraform/research-worker-private-endpoints.tf"
WORKER = ROOT / "infra/terraform/research-worker-scaffold.tf"


class PrivateEndpointsContractTest(unittest.TestCase):
    def setUp(self):
        self.net = NETWORK.read_text(encoding="utf-8")
        self.worker = WORKER.read_text(encoding="utf-8")

    def test_explicit_cost_opt_in_with_dormant_worker_precondition(self):
        self.assertIn('variable "research_worker_private_endpoints_enabled"', self.net)
        self.assertRegex(self.net, r"default\s*=\s*false")
        self.assertIn("!var.research_worker_private_endpoints_enabled || var.research_worker_scaffold_enabled", self.net)
        self.assertIn('worker_endpoint_count = var.research_worker_private_endpoints_enabled ? 1 : 0', self.net)
        self.assertEqual(self.net.count("count               = local.worker_endpoint_count"), 4)

    def test_private_services_required_for_fargate(self):
        for suffix in ("ecr.api", "ecr.dkr", "logs", "secretsmanager"):
            self.assertIn('service_name        = "com.amazonaws.${var.aws_region}.' + suffix + '"', self.net)
        self.assertIn('service_name      = "com.amazonaws.${var.aws_region}.s3"', self.net)
        self.assertIn('vpc_endpoint_type = "Gateway"', self.net)
        self.assertIn('route_table_ids   = [aws_vpc.main.main_route_table_id]', self.net)
        self.assertEqual(self.net.count('private_dns_enabled = true'), 4)
        self.assertEqual(self.net.count('subnet_ids          = aws_subnet.private[*].id'), 4)

    def test_only_endpoint_security_group_and_s3_prefix_list_egress(self):
        self.assertIn('resource "aws_vpc_security_group_egress_rule" "worker_to_endpoints"', self.net)
        self.assertIn('resource "aws_vpc_security_group_egress_rule" "worker_to_s3"', self.net)
        self.assertIn('referenced_security_group_id = aws_security_group.research_worker_endpoints[0].id', self.net)
        self.assertIn('prefix_list_id    = data.aws_prefix_list.worker_s3[0].id', self.net)
        self.assertIn('referenced_security_group_id = aws_security_group.research_worker[0].id', self.net)
        # Do not combine inline SG rules and standalone rule resources:
        # Terraform AWS provider documents that this causes perpetual diffs.
        self.assertNotRegex(self.worker, r"(?m)^\s*(?:ingress|egress)\s*(?:=|\{)")
        self.assertNotRegex(self.net, r"(?m)^\s*(?:ingress|egress)\s*(?:=|\{)")
        self.assertNotIn('resource "aws_vpc_security_group_ingress_rule" "worker_from_java"', self.net)
        tls = (ROOT / "infra/terraform/research-worker-private-tls.tf").read_text(encoding="utf-8")
        self.assertIn('resource "aws_vpc_security_group_ingress_rule" "worker_from_tls_alb"', tls)
        self.assertIn("referenced_security_group_id = aws_security_group.tasks.id", tls)
        self.assertNotIn('cidr_blocks = ["0.0.0.0/0"]', self.worker)
        self.assertNotIn('cidr_blocks = ["0.0.0.0/0"]', self.net)

    def test_endpoints_never_scale_or_grant_live_trading(self):
        self.assertRegex(self.worker, r"desired_count\s*=\s*0")
        self.assertIn('assign_public_ip = false', self.worker)
        self.assertRegex(self.worker, r"enable_execute_command\s*=\s*false")
        self.assertNotIn("aws_ecs_service", self.net)
        self.assertNotIn('aws_route" "internet"', self.net)
        self.assertIn('data.aws_caller_identity.current.account_id == "816344830716"', self.net)
        self.assertIn('var.aws_region == "us-east-1"', self.net)


if __name__ == "__main__":
    unittest.main()
