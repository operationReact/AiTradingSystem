"""Offline contracts for the real disposable Lightsail PAPER Docker smoke."""
from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "infra/lightsail/verify_connected_smoke.py"
spec = importlib.util.spec_from_file_location("paper_connected_smoke", PATH)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def health():
    return {
        "status": "UP", "service": "ai-trading-system",
        "mode": "PAPER", "timestamp": "2026-11-05T10:00:00Z",
    }


def services():
    def state(ports):
        return {"State": {"Running": True}, "NetworkSettings": {"Ports": ports}}
    return {
        "postgres": state({"5432/tcp": None}),
        "backend": state({"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8080"}]}),
        "web": state({"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8088"}]}),
    }


class PaperConnectedRuntimeContract(unittest.TestCase):
    def test_real_database_and_two_connected_backend_health_endpoints(self):
        gate.verify_result(health(), health(), "ok\n", "42\n", services())
        for bad_health in (
            dict(health(), status="DOWN"),
            dict(health(), mode="LIVE"),
            dict(health(), service="different"),
            dict(health(), timestamp=""),
            {"status": "UP"},
        ):
            with self.subTest(data=bad_health), self.assertRaises(ValueError):
                gate.verify_result(bad_health, health(), "ok", "42", services())
            with self.subTest(proxy=bad_health), self.assertRaises(ValueError):
                gate.verify_result(health(), bad_health, "ok", "42", services())

    def test_flyway_and_flutter_are_both_required(self):
        for web, migrations in (
            ("nginx welcome", "5"), ("ok", ""), ("ok", "0"), ("ok", "-1"),
            ("ok", "not verified"), ("ok", "2\n3"),
        ):
            with self.subTest(web=web, migrations=migrations), self.assertRaises(ValueError):
                gate.verify_result(health(), health(), web, migrations, services())

    def test_database_ports_never_public_and_no_extra_mappings(self):
        mutations = [
            ("postgres", {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]}),
            ("backend", {"8080/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8080"}]}),
            ("web", {"8080/tcp": [{"HostIp": "::", "HostPort": "8088"}]}),
            ("backend", {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8090"}]}),
            ("web", {"8080/tcp": [
                {"HostIp": "0.0.0.0", "HostPort": "8088"},
                {"HostIp": "127.0.0.1", "HostPort": "8088"},
            ]}),
        ]
        for svc, ports in mutations:
            candidate = services()
            candidate[svc]["NetworkSettings"]["Ports"] = ports
            with self.subTest(service=svc, ports=ports), self.assertRaises(ValueError):
                gate.verify_network(candidate)
        c = services()
        c["model-worker"] = {"State": {"Running": True}, "NetworkSettings": {"Ports": {}}}
        with self.assertRaises(ValueError):
            gate.verify_network(c)
        c = services()
        c["backend"]["State"]["Running"] = False
        with self.assertRaises(ValueError):
            gate.verify_network(c)

    def test_workflow_is_real_disposable_local_and_has_no_cloud_access(self):
        workflow = (
            ROOT / ".github/workflows/paper-connected-db-e2e.yml"
        ).read_text()
        expected = (
            "pull_request:", "permissions:", "contents: read",
            "flutter build web --release", "docker build --file backend/Dockerfile",
            "Dockerfile.lightsail-paper",
            "docker compose", "postgres_data", "pg_dump",
            "flyway_schema_history", "http://127.0.0.1:8080/api/health",
            "http://127.0.0.1:8088/api/health",
            "http://127.0.0.1:8088/health",
            "verify_connected_smoke.py",
            "docker compose", "down -v",
        )
        for item in expected:
            self.assertIn(item, workflow)
        for forbidden in (
            "id-token: write", "aws-actions/configure-aws-credentials",
            "aws lightsail", "aws ecr", "terraform apply", "docker push",
            "ssh ", "scp ", "TRADING_MODE=LIVE", "actions/upload-artifact",
        ):
            self.assertNotIn(forbidden, workflow)
        self.assertIn("await_health java-api", workflow)
        self.assertIn("for attempt in $(seq 1 45)", workflow)
        self.assertIn("'infra/lightsail/run_paper.sh'", workflow)
        script = (ROOT / "infra/lightsail/run_paper.sh").read_text()
        self.assertIn("wait_local_health java-api", script)
        self.assertIn("wait_local_health flutter-proxy", script)
        self.assertIn("for attempt in $(seq 1 45)", script)
        self.assertIn("'infra/lightsail/paper-compose.yml'", workflow)
        self.assertIn("'apps/trading_client/Dockerfile.lightsail-paper'", workflow)
        # No synthetic claims of auth, licensed market data or broker readiness.
        self.assertNotIn("can_authorize_capital=True", workflow)


if __name__ == "__main__":
    unittest.main()
