"""Offline regression tests for the single EC2 PostgreSQL + PAPER app runtime.

No AWS, provider credentials, broker accounts or paid EC2 required.
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import unittest

ROOT=Path(__file__).resolve().parents[2]
COMPOSE=ROOT/"infra/ec2-paper/paper-compose.yml"
RUN=ROOT/"infra/ec2-paper/run_paper.sh"


class PaperAppRuntimeContract(unittest.TestCase):
    def test_paper_stack_has_persistent_postgres_and_only_loopback_api_web(self):
        source=COMPOSE.read_text(encoding="utf-8")
        for expected in (
            "postgres:16-alpine",
            "postgres_data:/var/lib/postgresql/data",
            "restart: unless-stopped",
            "service_healthy",
            "aitradingsystem-paper-backend-arm64:",
            "aitradingsystem-paper-web-arm64:",
            'TRADING_MODE: PAPER',
            'POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?required}',
            'APP_API_KEY: ${APP_API_KEY:?required}',
            '"127.0.0.1:8080:8080"',
            '"127.0.0.1:8088:8080"',
            'NEWS_INGESTION_ENABLED: "false"',
            'OFFICIAL_SOURCE_SCHEDULER_ENABLED: "false"',
            'AI_RESEARCH_WORKER_CLIENT_ENABLED: "false"',
            'APP_DEV_SESSION_BOOTSTRAP_ENABLED: "false"',
            'APP_SESSION_COOKIE_SECURE: "true"',
            "mem_limit: 1536m",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected,source)
        for forbidden in (
            'TRADING_MODE: LIVE', '"5432:5432"', '"8080:8080"',
            '"8088:8080"', "BROKER_ORDER_EXECUTION_ENABLED: true",
            "ports:\n      - \"0.0.0.0", "ANGELONE_API_KEY:",
            "GEMINI_API_KEY:", "ANTHROPIC_API_KEY:",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden,source)

    def test_idempotent_script_requires_exact_reviewed_arm64_and_host_secrets(self):
        script=RUN.read_text(encoding="utf-8")
        for required in (
            '[[ "$sha" =~ ^[0-9a-f]{40}$ ]]',
            'test "$(uname -m)" = "aarch64"',
            "runtime.env",
            "test ! -L",
            "chmod 0600",
            "docker image inspect",
            '"arm64"',
            '"10001"',
            "docker compose",
            "--no-build --remove-orphans",
            "pg_dump -U aitrading aitrading",
            "wait_health",
            "127.0.0.1:8080/api/health",
            "127.0.0.1:8088/health",
            "127.0.0.1:8088/api/health",
            's["backend"]["environment"]["TRADING_MODE"]=="PAPER"',
        ):
            with self.subTest(required=required):
                self.assertIn(required,script)
        for forbidden in (
            "docker compose down -v", "docker volume rm",
            "terraform apply", "aws ec2 start-instances",
            "aws ec2 run-instances", "AWS_SECRET_ACCESS_KEY",
            "TRADING_MODE=LIVE", "open 8080",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden,script)
        result=subprocess.run(["bash","-n",str(RUN)],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)

    def test_fail_closed_invalid_release_before_host_operations(self):
        result=subprocess.run(["bash",str(RUN),"invalid-sha"],
                              capture_output=True,text=True)
        self.assertEqual(result.returncode,2)
        self.assertIn("Bad release SHA",result.stderr)

    def test_compose_resolves_required_env_without_cloud(self):
        docker=subprocess.run(["docker","compose","version"],
                              capture_output=True,text=True)
        if docker.returncode:
            self.skipTest("Docker Compose not available in this environment")
        env=os.environ.copy()
        env.update({
            "POSTGRES_PASSWORD":"unit-test-postgres-unused",
            "APP_API_KEY":"unit-test-api-unused",
            "RELEASE_SHA":"0"*40,
        })
        p=subprocess.run(["docker","compose","-f",str(COMPOSE),
                         "config","--quiet"],env=env,capture_output=True,text=True)
        self.assertEqual(p.returncode,0,p.stderr)


if __name__=="__main__":
    unittest.main()
