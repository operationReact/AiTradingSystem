"""V81 offline contracts: private TLS identity, no backend-to-worker plaintext bypass."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
TLS = ROOT / "infra/terraform/research-worker-private-tls.tf"
WORKER = ROOT / "infra/terraform/research-worker-scaffold.tf"
NETWORK = ROOT / "infra/terraform/research-worker-private-endpoints.tf"
BACKEND = ROOT / "infra/terraform/main.tf"


class PrivateWorkerHttpsContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tls = TLS.read_text(encoding="utf-8")
        cls.worker = WORKER.read_text(encoding="utf-8")
        cls.network = NETWORK.read_text(encoding="utf-8")
        cls.backend = BACKEND.read_text(encoding="utf-8")

    def test_cost_bearing_tls_is_zero_resource_by_default(self):
        self.assertIn('variable "research_worker_tls_enabled"', self.tls)
        self.assertRegex(self.tls, r"default\s*=\s*false")
        self.assertIn("worker_tls_count = var.research_worker_tls_enabled ? 1 : 0", self.tls)
        for requirement in (
            "var.research_worker_scaffold_enabled",
            "var.research_worker_private_endpoints_enabled",
            "var.enable_https",
            'var.aws_region == "us-east-1"',
            'data.aws_caller_identity.current.account_id == "816344830716"',
        ):
            self.assertIn(requirement, self.tls)
        self.assertIn("count = local.worker_tls_count", self.tls)
        self.assertNotIn('cidr_blocks = ["0.0.0.0/0"]', self.tls)

    def test_only_private_tls_alb_and_exact_backend_to_tls_ingress(self):
        self.assertIn('resource "aws_lb" "research_worker_tls"', self.tls)
        self.assertRegex(self.tls, r"(?m)^\s*internal\s*=\s*true$")
        self.assertRegex(self.tls, r"subnets\s*=\s*aws_subnet\.private\[\*\]\.id")
        self.assertRegex(self.tls, r'load_balancer_type\s*=\s*"application"')
        self.assertIn('resource "aws_vpc_security_group_ingress_rule" "backend_to_worker_tls"',
                      self.tls)
        self.assertNotIn('referenced_security_group_id = aws_security_group.tasks.id', self.tls)
        self.assertIn('resource "aws_security_group" "research_worker_java_identity"', self.tls)
        self.assertIn(
            'referenced_security_group_id = aws_security_group.research_worker_java_identity[0].id',
            self.tls,
        )
        self.assertIn("aws_security_group.research_worker_java_identity[*].id", self.backend)
        # A separate SG must not be accidentally shared with frontend ECS tasks.
        frontend = self.backend.split('resource "aws_ecs_service" "frontend"')[1]
        self.assertNotIn("aws_security_group.research_worker_java_identity[*].id", frontend)
        self.assertIn('resource "aws_vpc_security_group_egress_rule" "worker_tls_to_target"',
                      self.tls)
        self.assertIn('resource "aws_vpc_security_group_ingress_rule" "worker_from_tls_alb"',
                      self.tls)
        self.assertIn('referenced_security_group_id = aws_security_group.research_worker_tls_alb[0].id',
                      self.tls)
        self.assertNotIn('resource "aws_vpc_security_group_ingress_rule" "worker_from_java"',
                         self.network)
        # The former direct Java -> worker ingress was not merely renamed.
        self.assertNotIn("Internal Java backend to research worker HTTP", self.network)
        for sg in (self.tls, self.worker, self.network):
            self.assertNotIn('cidr_blocks = ["0.0.0.0/0"]', sg)
        self.assertNotIn('assign_public_ip = true', self.worker)

    def test_dns_tls_identity_is_private_and_acm_validation_is_public(self):
        self.assertIn('worker_tls_fqdn  = "research-worker.${var.domain_name}"', self.tls)
        self.assertIn('resource "aws_acm_certificate" "research_worker_tls"', self.tls)
        self.assertIn('validation_method = "DNS"', self.tls)
        self.assertIn("zone_id         = aws_route53_zone.main.zone_id", self.tls)
        self.assertIn('resource "aws_acm_certificate_validation" "research_worker_tls"',
                      self.tls)
        self.assertIn('resource "aws_route53_zone" "research_worker_private"', self.tls)
        self.assertIn("vpc_id = aws_vpc.main.id", self.tls)
        self.assertIn('resource "aws_route53_record" "research_worker_private"', self.tls)
        self.assertIn("zone_id = aws_route53_zone.research_worker_private[0].zone_id",
                      self.tls)
        self.assertIn('type    = "A"', self.tls)
        self.assertIn("evaluate_target_health = false", self.tls)
        self.assertRegex(
            self.tls,
            r"name\s*=\s*aws_lb\.research_worker_tls\[0\]\.dns_name",
        )
        self.assertIn('output "research_worker_private_https_url"', self.tls)
        self.assertIn('"https://${local.worker_tls_fqdn}"', self.tls)
        self.assertNotIn('http://${local.worker_tls_fqdn}', self.tls)

    def test_https_front_does_not_mislabel_private_backend_http_as_tls(self):
        self.assertIn('resource "aws_lb_listener" "research_worker_tls"', self.tls)
        self.assertIn('protocol          = "HTTPS"', self.tls)
        self.assertIn('port              = 443', self.tls)
        self.assertIn('certificate_arn   = aws_acm_certificate_validation.research_worker_tls[0].certificate_arn',
                      self.tls)
        self.assertIn("ELBSecurityPolicy-TLS13-1-2-2021-06", self.tls)
        self.assertIn('resource "aws_lb_target_group" "research_worker_tls"', self.tls)
        self.assertIn('target_type          = "ip"', self.tls)
        self.assertIn('protocol             = "HTTP"', self.tls)
        self.assertIn('path                = "/health"', self.tls)
        self.assertIn('type = "fixed-response"', self.tls)
        self.assertIn('status_code  = "403"', self.tls)
        self.assertIn('resource "aws_lb_listener_rule" "research_worker_private_api_only"', self.tls)
        self.assertIn('"/v1/research/artifact-readiness"', self.tls)
        self.assertIn('"/v1/research/artifact-inference"', self.tls)
        self.assertNotIn("aws_lb_listener_rule\" \"any_path", self.tls)

    def test_python_worker_never_scales_and_java_research_client_stays_off(self):
        self.assertRegex(self.worker, r"desired_count\s*=\s*0")
        self.assertIn('dynamic "load_balancer"', self.worker)
        self.assertIn("for_each = var.research_worker_tls_enabled ? [1] : []", self.worker)
        self.assertIn("target_group_arn = aws_lb_target_group.research_worker_tls[0].arn",
                      self.worker)
        self.assertIn("aws_lb_listener.research_worker_tls", self.worker)
        self.assertIn("enable_execute_command", self.worker)
        self.assertIn('name = "TRADING_MODE", value = "PAPER"', self.worker)
        self.assertIn('name = "AI_RESEARCH_STRICT_BOOT_REQUIRED", value = "true"',
                      self.worker)
        self.assertIn('name = "AI_RESEARCH_WORKER_TOKEN", valueFrom = var.research_worker_token_secret_arn',
                      self.worker)
        # V82 separately prepares the Java URL/token only with an operator
        # flag; presence in a *conditional* must not be mistaken for enabled
        # inference/readiness in the resulting backend task definition.
        prep = (ROOT / "infra/terraform/research-worker-java-secret-prep.tf").read_text(
            encoding="utf-8"
        )
        self.assertIn('variable "research_worker_java_secret_prep_enabled"', prep)
        self.assertRegex(prep, r"default\\s*=\\s*false")
        self.assertIn("var.research_worker_tls_enabled", prep)
        self.assertIn("var.research_worker_java_secret_prep_enabled ? [", self.backend)
        self.assertIn(
            '{ name = "AI_RESEARCH_WORKER_URL", value = "https://${local.worker_tls_fqdn}" }',
            self.backend,
        )
        for flag in ("AI_RESEARCH_WORKER_CLIENT_ENABLED",
                     "AI_RESEARCH_WORKER_READINESS_ENABLED"):
            self.assertIn('{ name = "' + flag + '", value = "false" }', self.backend)
            self.assertNotIn('{ name = "' + flag + '", value = "true" }', self.backend)
        self.assertIn(
            '{ name = "AI_RESEARCH_WORKER_TOKEN", valueFrom = '
            'var.research_worker_token_secret_arn }',
            self.backend,
        )
        self.assertNotIn('name = "AI_RESEARCH_WORKER_TOKEN", value =', self.backend)


if __name__ == "__main__":
    unittest.main()
