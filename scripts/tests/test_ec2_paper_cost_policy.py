"""Offline guardrails for new scheduled-stop PAPER EC2 Terraform design.

No AWS credentials, state, instances, brokers or live trading are accessed.
"""
from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "infra/ec2-paper"
WORKFLOWS = ROOT / ".github/workflows"
ARCHIVED = ROOT / "docs/operations/archived-actions"


class CheapPaperEC2Policy(unittest.TestCase):
    def test_explicit_charge_opt_in_and_single_fixed_account_region(self):
        versions = (TF / "versions.tf").read_text()
        variables = (TF / "variables.tf").read_text()
        main = (TF / "main.tf").read_text()
        self.assertIn('allowed_account_ids = ["098859530205"]', versions)
        self.assertIn('default     = "ap-south-1"', variables)
        self.assertIn('var.aws_region == "ap-south-1"', variables)
        self.assertIn('default     = "t4g.medium"', variables)
        self.assertIn('contains(["t4g.medium", "m6g.medium", "m7g.medium"], var.instance_type)', variables)
        self.assertIn('default     = ""', variables)
        self.assertIn('PROVISION_PAPER_EC2_4GB', variables)
        self.assertIn('enabled   = var.approve_paid_provision == "PROVISION_PAPER_EC2_4GB"', main)
        self.assertGreaterEqual(main.count('count = local.enabled ? 1 : 0'), 5)
        self.assertIn('backend "s3" {}', versions)

    def test_single_graviton_instance_ebs_and_ssm_without_open_ingress(self):
        main = (TF / "main.tf").read_text()
        bootstrap = (TF / "bootstrap.sh").read_text()
        for text in (
            'resource "aws_instance" "paper"',
            'al2023-ami-kernel-6.1-arm64',
            'volume_type           = "gp3"',
            'volume_size           = var.root_volume_gib',
            'encrypted             = true',
            'http_tokens   = "required"',
            'disable_api_termination              = true',
            'prevent_destroy = true',
            'instance_initiated_shutdown_behavior = "stop"',
            'dynamic "credit_specification" {',
            'for_each = var.instance_type == "t4g.medium" ? [1] : []',
            'cpu_credits = "standard"',
            'AmazonSSMManagedInstanceCore',
            'No ingress rules',
            'associate_public_ip_address          = true',
        ):
            with self.subTest(expected=text):
                self.assertIn(text, main)
        for forbidden in (
            'resource "aws_db_instance"',
            'resource "aws_lb"',
            'resource "aws_nat_gateway"',
            'resource "aws_ecs_cluster"',
            'resource "aws_ecr_repository"',
            'resource "aws_vpc_endpoint"',
            'ingress {',
            'key_name                 =',
            'iam:PassRole',
            'TRADING_MODE=LIVE',
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, main)
        self.assertIn('dnf install -y', bootstrap)
        self.assertIn('systemctl enable --now amazon-ssm-agent', bootstrap)
        self.assertIn('docker-compose-linux-aarch64', bootstrap)
        self.assertIn('sha256sum -c', bootstrap)
        self.assertIn('chmod 0600', bootstrap)
        self.assertNotIn('docker compose up', bootstrap)
        self.assertNotIn('TRADING_MODE=LIVE', bootstrap)
        self.assertNotIn('ANGELONE_API_KEY', bootstrap)

    def test_opt_in_stop_2200_ist_no_auto_start_cross_project_permission(self):
        main = (TF / "main.tf").read_text()
        variables = (TF / "variables.tf").read_text()
        self.assertIn('default     = 22', variables)
        self.assertIn('variable "daily_stop_enabled" {', variables)
        self.assertIn('default     = false', variables.split('variable "daily_stop_enabled" {', 1)[1])
        self.assertIn('schedule_expression_timezone = "Asia/Kolkata"', main)
        self.assertIn('schedule_expression          = "cron(0 ${var.daily_stop_hour_ist} * * ? *)"', main)
        self.assertIn('state                        = var.daily_stop_enabled ? "ENABLED" : "DISABLED"', main)
        self.assertIn('arn:aws:scheduler:::aws-sdk:ec2:stopInstances', main)
        self.assertIn('Action   = "ec2:StopInstances"', main)
        self.assertIn('Resource = aws_instance.paper[0].arn', main)
        self.assertIn('ec2:ResourceTag/Application', main)
        self.assertIn('ec2:ResourceTag/Environment', main)
        self.assertIn('aws:SourceAccount', main)
        self.assertIn('aws:SourceArn', main)
        self.assertNotIn('ec2:StartInstances', main)
        self.assertNotIn('ec2:TerminateInstances', main)
        self.assertNotIn('cron(0 9', main)

    def test_legacy_lightsail_can_never_provision_after_plan_change(self):
        for name in ("lightsail-paper-bootstrap.yml", "lightsail-paper-app-deploy.yml"):
            src = (ARCHIVED / name).read_text()
            with self.subTest(name=name):
                self.assertIn('if: ${{ false }}', src)
                self.assertNotIn("if: github.ref == 'refs/heads/dev'", src)
        deploy = (ROOT / "infra/lightsail/paper-compose.yml").read_text()
        self.assertIn('TRADING_MODE: PAPER', deploy)
        self.assertIn('127.0.0.1:8080:8080', deploy)
        self.assertNotIn('TRADING_MODE: LIVE', deploy)

    def test_review_and_deploy_remain_distinct(self):
        wf = (WORKFLOWS / "ec2-paper-cost-policy.yml").read_text()
        self.assertIn("pull_request:", wf)
        self.assertIn("permissions:\n  contents: read", wf)
        self.assertIn("terraform init -backend=false", wf)
        self.assertIn("terraform validate", wf)
        self.assertIn("terraform fmt -diff -check", wf)
        for forbidden in ("configure-aws-credentials", "terraform apply",
                          "aws ec2 run-instances", "id-token: write",
                          "workflow_dispatch:", "sts:AssumeRoleWithWebIdentity"):
            self.assertNotIn(forbidden, wf)


if __name__ == "__main__":
    unittest.main()
