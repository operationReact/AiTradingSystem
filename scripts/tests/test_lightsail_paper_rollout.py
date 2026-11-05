"""PAPER Lightsail app rollout is loopback-only, scoped and keyless in CI."""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "ats_paper_ssh", ROOT / "infra/lightsail/prepare_ssh.py")
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def instance():
    return {"instance": {
        "name": gate.INSTANCE,
        "bundleId": gate.BUNDLE,
        "blueprintId": "ubuntu_24_04",
        "location": {"regionName": gate.REGION, "availabilityZone": "us-east-1a"},
        "hardware": {"ramSizeInGb": 4, "cpuCount": 2},
        "tags": [{"key": k, "value": v} for k, v in gate.TAGS.items()],
        "state": {"name": "running"},
        "publicIpAddress": "8.8.8.8",
    }}


def details():
    return {"accessDetails": {
        "instanceName": gate.INSTANCE, "protocol": "ssh",
        "username": "ubuntu", "ipAddress": "8.8.8.8",
        "privateKey": ("-----BEGIN OPENSSH PRIVATE KEY-----\n"
                       + "A" * 160 + "\n-----END OPENSSH PRIVATE KEY-----"),
        "certKey": "ssh-rsa-cert-v01@openssh.com " + "A" * 110,
        "hostKeys": [
            {"algorithm": "ssh-ed25519", "publicKey": "A" * 88},
        ],
    }}


class LightsailPaperRolloutContract(unittest.TestCase):
    def test_exact_named_running_private_paper_host(self):
        self.assertEqual(gate.check_instance(instance()), "8.8.8.8")
        for field, wrong in (
            ("name", "alpha-vidhyarthi"),
            ("bundleId", "large_3_0"),
            ("state", {"name": "stopped"}),
            ("publicIpAddress", "127.0.0.1"),
            ("tags", [{"key": "Environment", "value": "live"}]),
        ):
            bad = instance()
            bad["instance"][field] = wrong
            with self.subTest(field=field), self.assertRaises(ValueError):
                gate.check_instance(bad)

    def test_ephemeral_certificate_and_host_pin_is_exact(self):
        response = gate.select_ssh(details(), "8.8.8.8")
        self.assertIn("PRIVATE KEY", response["private"])
        self.assertTrue(response["certificate"].startswith("ssh-rsa-cert-v01"))
        self.assertEqual(response["known_hosts"],
                         "8.8.8.8 ssh-ed25519 " + "A" * 88 + "\n")

        for field, wrong in (
            ("instanceName", "other"),
            ("protocol", "rdp"),
            ("username", "root"),
            ("ipAddress", "9.9.9.9"),
            ("privateKey", "not-a-key"),
            ("certKey", "ssh-rsa plain-key"),
            ("hostKeys", []),
            ("hostKeys", [{"algorithm": "unknown", "publicKey": "A" * 88}]),
        ):
            bad = details()
            bad["accessDetails"][field] = wrong
            with self.subTest(field=field), self.assertRaises(ValueError):
                gate.select_ssh(bad, "8.8.8.8")

    def test_local_only_compose_resource_and_trading_guards(self):
        c = (ROOT / "infra/lightsail/paper-compose.yml").read_text()
        for expected in (
            'name: aitradingsystem-paper',
            'postgres:16-alpine',
            'mem_limit: 768m', 'mem_limit: 1536m',
            'mem_limit: 256m',
            '127.0.0.1:8080:8080',
            '127.0.0.1:8088:8080',
            'TRADING_MODE: PAPER',
            'AI_RESEARCH_WORKER_CLIENT_ENABLED: "false"',
            'AI_RESEARCH_WORKER_READINESS_ENABLED: "false"',
            'APP_SESSION_COOKIE_SECURE: "true"',
            'APP_DEV_SESSION_BOOTSTRAP_ENABLED: "false"',
            'SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/aitrading',
        ):
            self.assertIn(expected, c)
        for forbidden in (
            '0.0.0.0:', '5432:5432', '8090:8090', 'TRADING_MODE: LIVE',
            'ANGELONE_API_KEY:', 'AI_RESEARCH_MODEL_MANIFEST:',
            'ports:\n      - "8080:8080"',
        ):
            self.assertNotIn(forbidden, c)
        self.assertIn('volumes:\n  postgres_data:', c)

    def test_flutters_nginx_proxies_same_origin_api_without_external_access(self):
        cfg = (ROOT / "apps/trading_client/deploy/nginx.lightsail-paper.conf").read_text()
        dock = (ROOT / "apps/trading_client/Dockerfile.lightsail-paper").read_text()
        self.assertIn('location /api/', cfg)
        self.assertIn('proxy_pass http://backend:8080;', cfg)
        self.assertIn('location = /health', cfg)
        self.assertIn('nginx.lightsail-paper.conf', dock)
        self.assertIn('flutter_service_worker.js', dock)

    def test_deploy_is_manual_exact_head_keyless_and_no_broker(self):
        wf = (ROOT / ".github/workflows/lightsail-paper-app-deploy.yml").read_text()
        run = (ROOT / "infra/lightsail/run_paper.sh").read_text()
        self.assertIn('workflow_dispatch:', wf)
        self.assertIn("  push:", flow)
        self.assertIn("branches: [dev]", flow)
        self.assertIn("infra/lightsail/requests/deploy.request.json", flow)
        self.assertIn("verify_request.py", flow)
        self.assertNotIn('\n  pull_request:', wf)
        self.assertIn('DEPLOY_PAPER_LOCAL_ONLY', wf)
        self.assertIn('id-token: write', wf)
        self.assertIn('get-instance-access-details', wf)
        self.assertIn('StrictHostKeyChecking=yes', wf)
        self.assertIn('CertificateFile=', wf)
        self.assertIn('prepare_ssh.py', wf)
        self.assertIn('get-instance-port-states', wf)
        self.assertIn('put-instance-public-ports', wf)
        self.assertIn('cidrListAliases', wf)
        self.assertIn('flutter build web --release', wf)
        self.assertIn('docker save "aitradingsystem-paper-backend:$GITHUB_SHA"', wf)
        self.assertIn('http://127.0.0.1:8088/api/health', run)
        self.assertIn('pg_dump -U aitrading aitrading', run)
        self.assertIn('docker compose --env-file .env -f paper-compose.yml up -d', run)
        self.assertNotIn('TRADING_MODE=LIVE', wf + run)
        self.assertNotIn('docker login', wf)
        self.assertNotIn('terraform apply', wf)
        self.assertNotIn('aws iam ', wf)
        self.assertNotIn('aws lightsail create-instances', wf)
        self.assertNotIn('aws ecr ', wf)
        self.assertNotIn('secrets.', wf)
        self.assertNotIn('StrictHostKeyChecking=no', wf)
        self.assertNotIn('APP_DEV_SESSION_BOOTSTRAP_ENABLED=true', wf)


if __name__ == "__main__":
    unittest.main()
