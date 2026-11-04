"""V81 source-only safety contract for optional Java -> private ALB -> Python TLS."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TERRAFORM = ROOT / "infra/terraform"
TLS = TERRAFORM / "research-worker-private-tls.tf"
MAIN = TERRAFORM / "main.tf"
WORKER = TERRAFORM / "research-worker-scaffold.tf"
ENDPOINTS = TERRAFORM / "research-worker-private-endpoints.tf"
CONFIG = ROOT / "backend/src/main/resources/application.yml"


def resource_body(raw: str, resource: str, kind: str = "resource") -> str:
    # A conservative text contract; full syntax is checked by terraform validate.
    marker = f'{kind} "'
    start = raw.find(f'{kind} "{resource}"')
    if start >= 0:
        return raw[start:raw.find("\nresource \"", start + len(marker)) if raw.find(
            "\nresource \"", start + len(marker)) >= 0 else len(raw)]
    raise AssertionError(f"required resource section missing: {resource}")


class PrivateWorkerTlsBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tls = TLS.read_text()
        cls.main = MAIN.read_text()
        cls.worker = WORKER.read_text()
        cls.endpoints = ENDPOINTS.read_text()
        cls.config = CONFIG.read_text()

    def test_explicit_cost_opt_in_and_region_account_lock(self):
        s = self.tls
        self.assertIn('variable "research_worker_private_tls_enabled"', s)
        self.assertRegex(s, r"(?s)variable \"research_worker_private_tls_enabled\".*?default\s*=\s*false")
        self.assertIn("var.research_worker_scaffold_enabled", s)
        self.assertIn("var.research_worker_private_endpoints_enabled", s)
        self.assertIn("var.aws_region == \"us-east-1\"", s)
        self.assertIn('data.aws_caller_identity.current.account_id == "816344830716"', s)
        self.assertIn("local.worker_tls_count", s)

    def test_certificate_identity_and_public_validation_but_private_service_dns(self):
        s = self.tls
        self.assertIn('worker_tls_zone_domain = "internal.${var.domain_name}"', s)
        self.assertIn('worker_tls_fqdn        = "research.${local.worker_tls_zone_domain}"', s)
        self.assertIn('resource "aws_acm_certificate" "research_worker_private_tls"', s)
        self.assertIn("domain_name       = local.worker_tls_fqdn", s)
        self.assertIn('validation_method = "DNS"', s)
        self.assertIn('zone_id         = aws_route53_zone.main.zone_id', s)
        self.assertIn('resource "aws_acm_certificate_validation" "research_worker_private_tls"', s)
        self.assertIn('resource "aws_route53_zone" "research_worker_private"', s)
        self.assertIn('vpc { vpc_id = aws_vpc.main.id }', s)
        self.assertIn("zone_id = aws_route53_zone.research_worker_private[0].zone_id", s)
        self.assertIn("name    = local.worker_tls_fqdn", s)
        self.assertIn('type    = "A"', s)
        self.assertIn("evaluate_target_health = false", s)
        self.assertNotIn('zone_id = aws_route53_zone.main.zone_id\n  name    = local.worker_tls_fqdn', s)

    def test_backend_only_identity_and_no_direct_java_plaintext(self):
        s = self.tls
        self.assertIn('resource "aws_security_group" "research_worker_java_identity"', s)
        self.assertIn('resource "aws_security_group" "research_worker_tls_alb"', s)
        self.assertIn("aws_security_group.research_worker_java_identity[0].id", s)
        self.assertNotIn("referenced_security_group_id = aws_security_group.tasks.id", s)
        self.assertIn("aws_security_group.research_worker_java_identity[*].id", self.main)
        self.assertIn('resource "aws_vpc_security_group_ingress_rule" "research_worker_from_tls_alb"', s)
        self.assertIn('referenced_security_group_id = aws_security_group.research_worker_tls_alb[0].id', s)
        self.assertNotIn('worker_from_java"', self.endpoints)
        self.assertNotRegex(self.worker, r"(?m)^\s*ingress\s*\{")
        self.assertIn('internal           = true', s)
        self.assertIn('subnets            = aws_subnet.private[*].id', s)
        self.assertNotIn("0.0.0.0/0", s)
        self.assertNotIn('protocol          = "HTTP"', s)
        self.assertNotIn("assign_public_ip = true", s)

    def test_only_two_authorized_backend_routes_and_tls_termination(self):
        s = self.tls
        self.assertIn('port              = 443', s)
        self.assertIn('protocol          = "HTTPS"', s)
        self.assertIn('ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"', s)
        self.assertIn('type = "fixed-response"', s)
        self.assertIn('status_code  = "403"', s)
        self.assertIn('"/v1/research/artifact-readiness"', s)
        self.assertIn('"/v1/research/artifact-inference"', s)
        self.assertIn('protocol             = "HTTP"', s)
        self.assertIn('port                 = 8090', s)
        self.assertIn('path                = "/health"', s)
        self.assertNotIn('"/api/orders"', s)
        self.assertNotIn('"/actuator"', s)

    def test_no_worker_scaling_no_java_research_or_live_approval(self):
        self.assertRegex(self.worker, r"desired_count\s*=\s*0")
        self.assertIn('enable_execute_command = false', self.worker)
        self.assertIn('dynamic "load_balancer"', self.worker)
        self.assertIn("var.research_worker_private_tls_enabled ? [1] : []", self.worker)
        self.assertIn("aws_lb_listener.research_worker_private_tls", self.worker)
        self.assertIn('assign_public_ip = false', self.worker)
        self.assertIn("AI_RESEARCH_WORKER_CLIENT_ENABLED:false", self.config)
        self.assertIn("AI_RESEARCH_WORKER_READINESS_ENABLED:false", self.config)
        self.assertNotIn("AI_RESEARCH_WORKER_CLIENT_ENABLED", self.tls)
        self.assertNotIn("AI_RESEARCH_WORKER_TOKEN", self.tls)
        self.assertNotIn("aws ecs update-service", self.tls)
        self.assertNotIn("aws ecr", self.tls)

    def test_no_inline_rules_mixed_with_standalone_rules(self):
        # Terraform provider must not own both inline and standalone rule sets.
        for raw in (self.tls, self.worker, self.endpoints):
            self.assertNotRegex(raw, r"(?m)^\s*(?:ingress|egress)\s*(?:=|\{)")


if __name__ == "__main__":
    unittest.main()
