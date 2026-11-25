"""Offline regression tests for the single EC2 PostgreSQL + PAPER app runtime.

No AWS, provider credentials, broker accounts or paid EC2 required.
"""
from __future__ import annotations

import os
import json
from pathlib import Path
import shutil
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
            '"10.87.0.10:8081:8080"',
            "https://www.minttick.com",
            'NEWS_INGESTION_ENABLED: ${NEWS_INGESTION_ENABLED:-false}',
            'NEWS_EVENT_LEDGER_ENABLED: ${NEWS_EVENT_LEDGER_ENABLED:-false}',
            'NEWS_SUMMARIZATION_ENABLED: ${NEWS_SUMMARIZATION_ENABLED:-false}',
            'NEWS_ANALYZER_BRIDGE_ENABLED: ${NEWS_ANALYZER_BRIDGE_ENABLED:-false}',
            'NEWS_ANALYSIS_SCHEDULER_ENABLED: ${NEWS_ANALYSIS_SCHEDULER_ENABLED:-false}',
            'NEWS_MIND_CONTEXT_ENABLED: ${NEWS_MIND_CONTEXT_ENABLED:-false}',
            'OFFICIAL_SOURCE_SCHEDULER_ENABLED: "false"',
            'AI_RESEARCH_WORKER_CLIENT_ENABLED: "false"',
            'MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_ENABLED: ${MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_ENABLED:-false}',
            'MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_DIRECTORY: /run/minttick-fno176-intake',
            'MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_REVIEWER_PUBLIC_KEY: ${MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_REVIEWER_PUBLIC_KEY:-}',
            'source: /opt/aitradingsystem-paper/fno176-attested-intake',
            'target: /run/minttick-fno176-intake',
            'read_only: true',
            'create_host_path: false',
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
            "ANTHROPIC_API_KEY:",
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
            "10.87.0.10:8081/api/health",
            'ports("backend")=={("127.0.0.1",8080,8080)}',
            'ports("web")=={("127.0.0.1",8088,8080),("10.87.0.10",8081,8080)}',
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

    def test_str176_intake_mount_is_isolated_default_off_and_fail_closed(self):
        compose=COMPOSE.read_text(encoding="utf-8")
        script=RUN.read_text(encoding="utf-8")
        intake=compose.split("source: /opt/aitradingsystem-paper/fno176-attested-intake",1)[1].split("ports:",1)[0]
        self.assertIn("read_only: true",intake)
        self.assertIn("create_host_path: false",intake)
        self.assertIn("MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_ENABLED:-false",compose)
        self.assertIn("MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_REVIEWER_PUBLIC_KEY:-",compose)
        self.assertIn('install -d -o 10001 -g 10001 -m 0700',script)
        self.assertIn("test ! -L \"$intake\"",script)
        self.assertIn("10001:10001:700",script)
        self.assertIn('MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED: ${MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED:-false}',compose)
        self.assertNotIn("MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_ENABLED=true",script)

    def test_fail_closed_invalid_release_before_host_operations(self):
        result=subprocess.run(["bash",str(RUN),"invalid-sha"],
                              capture_output=True,text=True)
        self.assertEqual(result.returncode,2)
        self.assertIn("Bad release SHA",result.stderr)

    def test_compose_resolves_required_env_without_cloud(self):
        if not shutil.which("docker"):
            self.skipTest("Docker CLI not available in this environment")
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

    def test_gemini_binding_is_backend_only_and_does_not_enable_analysis_or_trading(self):
        if not shutil.which("docker"):
            self.skipTest("Docker CLI not available in this environment")
        if subprocess.run(["docker", "compose", "version"], capture_output=True).returncode:
            self.skipTest("Docker Compose not available in this environment")
        env = {k: v for k, v in os.environ.items()
               if k not in {"NEWS_ANALYSIS_PROVIDER", "GEMINI_API_KEY", "GEMINI_MODEL"}}
        env.update(POSTGRES_PASSWORD="fixture-unused", APP_API_KEY="fixture-unused", RELEASE_SHA="0" * 40)
        for configured in (False, True):
            if configured:
                env.update(NEWS_INGESTION_ENABLED="true",
                           NEWS_EVENT_LEDGER_ENABLED="true",
                           NEWS_SUMMARIZATION_ENABLED="true",
                           NEWS_ANALYZER_BRIDGE_ENABLED="true",
                           NEWS_ANALYSIS_SCHEDULER_ENABLED="true",
                           NEWS_MIND_CONTEXT_ENABLED="true",
                           NEWS_ANALYZER_APPROVED_SOURCES="moneycontrol",
                           NEWS_ANALYSIS_PROVIDER="GEMINI", GEMINI_MODEL="fixture-model",
                           GEMINI_API_KEY="fixture-gemini-key")
            p = subprocess.run(["docker", "compose", "--env-file", "/dev/null", "-f", str(COMPOSE),
                                "config", "--format", "json"], env=env, capture_output=True, text=True)
            self.assertEqual(0, p.returncode)
            services = json.loads(p.stdout)["services"]
            backend = services["backend"]["environment"]
            self.assertEqual("GEMINI" if configured else "NONE", backend["NEWS_ANALYSIS_PROVIDER"])
            self.assertEqual("fixture-gemini-key" if configured else "", backend["GEMINI_API_KEY"])
            self.assertEqual("fixture-model" if configured else "gemini-3.5-flash-lite", backend["GEMINI_MODEL"])
            expected_flag = "true" if configured else "false"
            for flag in (
                "NEWS_INGESTION_ENABLED", "NEWS_EVENT_LEDGER_ENABLED",
                "NEWS_SUMMARIZATION_ENABLED", "NEWS_ANALYZER_BRIDGE_ENABLED",
                "NEWS_ANALYSIS_SCHEDULER_ENABLED", "NEWS_MIND_CONTEXT_ENABLED",
            ):
                self.assertEqual(expected_flag, backend[flag], flag)
            self.assertEqual("moneycontrol" if configured else "", backend["NEWS_ANALYZER_APPROVED_SOURCES"])
            self.assertEqual("PAPER", backend["TRADING_MODE"])
            for name, service in services.items():
                if name != "backend":
                    self.assertNotIn("GEMINI_API_KEY", service.get("environment", {}))
            self.assertNotIn("fixture-gemini-key", json.dumps(services["web"]))


if __name__=="__main__":
    unittest.main()
