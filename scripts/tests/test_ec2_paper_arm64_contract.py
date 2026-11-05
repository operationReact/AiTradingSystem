"""No-AWS contract for real Graviton ARM64 Java/Flutter CI builds."""
from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ec2-paper-arm64-images.yml"
BACKEND = ROOT / "backend/Dockerfile.ec2-paper-arm64"
COMPOSE = ROOT / "infra/lightsail/paper-compose.yml"


class PaperARM64RuntimeContract(unittest.TestCase):
    def test_cross_compile_maven_native_runtime_image_and_nonroot_java(self):
        src = BACKEND.read_text()
        self.assertIn("FROM --platform=$BUILDPLATFORM maven:", src)
        self.assertIn("FROM eclipse-temurin:21-jre-jammy", src)
        self.assertIn("USER 10001", src)
        self.assertIn("-Xmx1024m", src)
        self.assertIn("COPY --from=build /workspace/target/*.jar", src)
        self.assertIn('ENTRYPOINT ["java","-jar","/app/app.jar"]', src)
        self.assertNotIn("apt-get install python", src)
        self.assertNotIn("TRADING_MODE=LIVE", src)

    def test_github_builds_real_arm64_layers_and_executes_arm_binaries(self):
        wf = WORKFLOW.read_text()
        for part in (
            "pull_request:",
            "branches: [dev]",
            "permissions:\n  contents: read",
            "docker/setup-qemu-action@v3",
            "docker/setup-buildx-action@v3",
            "platforms: arm64",
            "flutter build web --release",
            "--dart-define=API_BASE_URL=http://localhost:8088",
            "docker buildx build --platform linux/arm64 --load",
            "--file backend/Dockerfile.ec2-paper-arm64",
            "--file apps/trading_client/Dockerfile.lightsail-paper",
            'test "$(docker image inspect "$api" --format',
            'test "$(docker image inspect "$web" --format',
            "--entrypoint java",
            "--entrypoint nginx",
            "--network none",
        ):
            with self.subTest(part=part):
                self.assertIn(part, wf)
        self.assertIn("test", wf)
        self.assertIn("= \"arm64\"", wf)
        self.assertIn("= \"10001\"", wf)

    def test_ci_cannot_publish_or_deploy_or_infer_live_authority(self):
        wf = WORKFLOW.read_text()
        for forbidden in (
            "id-token: write",
            "configure-aws-credentials",
            "terraform apply",
            "aws s3api",
            "aws ec2",
            "docker push",
            "--push",
            "docker/login-action",
            "actions/upload-artifact",
            "secrets.",
            "TRADING_MODE=LIVE",
            "BROKER_ORDER_EXECUTION_ENABLED: true",
            "ssh ",
            "scp ",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, wf)
        compose = COMPOSE.read_text()
        self.assertIn('TRADING_MODE: PAPER', compose)
        self.assertIn('AI_RESEARCH_WORKER_CLIENT_ENABLED: "false"', compose)
        self.assertIn('"127.0.0.1:8080:8080"', compose)
        self.assertIn('"127.0.0.1:8088:8080"', compose)
        self.assertNotIn('TRADING_MODE: LIVE', compose)


if __name__ == "__main__":
    unittest.main()
