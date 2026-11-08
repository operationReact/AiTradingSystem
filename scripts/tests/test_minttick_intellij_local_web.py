"""Deterministic checks for MintTick IntelliJ Chrome + AWS API dev CORS."""
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / ".run" / "MintTick Local Web (AWS Backend).run.xml"
TEMPLATE = ROOT / "infra" / "minttick-domain" / "bootstrap-account-api.yml"
WORKFLOW = ROOT / ".github" / "workflows" / "minttick-intellij-local-cors.yml"


class LocalWebContractTests(unittest.TestCase):
    def test_shared_intellij_flutter_run_is_exact_aws_dev_client(self):
        node = ET.parse(RUN).getroot().find("configuration")
        self.assertEqual(node.attrib["type"], "FlutterRunConfigurationType")
        self.assertEqual(node.attrib["factoryName"], "Flutter")
        options = {x.attrib["name"]: x.attrib["value"] for x in node.findall("option")}
        self.assertEqual(options["filePath"], "$PROJECT_DIR$/apps/trading_client/lib/main.dart")
        args = options["additionalArgs"]
        for expected in (
            "--web-port=5173",
            "--dart-define=API_BASE_URL=https://api.minttick.com",
            "--dart-define=MINTTICK_COGNITO_REGION=ap-south-1",
            "--dart-define=MINTTICK_COGNITO_USER_POOL_ID=ap-south-1_jz2T8qbiY",
            "--dart-define=MINTTICK_COGNITO_CLIENT_ID=6jm18v2mdpcfraip92hrjhqjnd",
            "--dart-define=MINTTICK_AUTH_ONLY_TEST=false",
        ):
            self.assertIn(expected, args)
        for unsafe in ("--disable-web-security", "CONNECTED_API_KEY",
                       "TOTP", "ANGELONE_", "SSM_", "PIN="):
            self.assertNotIn(unsafe, args)

    def test_api_gateway_cors_preserves_production_origins_and_auth_headers(self):
        config = TEMPLATE.read_text()
        cors = config.split("      CorsConfiguration:", 1)[1].split("\n\n", 1)[0]
        self.assertIn("- http://localhost:5173", cors)
        self.assertIn("- https://www.minttick.com", cors)
        self.assertIn("- https://minttick.com", cors)
        self.assertIn("AllowMethods: [GET, POST, OPTIONS]", cors)
        for header in ("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"):
            self.assertIn("- " + header, cors)
        self.assertNotIn("AllowCredentials: true", cors)
        self.assertNotIn("- '*'", cors)

    def test_workflow_limits_deployment_to_exact_one_file_request(self):
        code = WORKFLOW.read_text()
        self.assertIn("git diff-tree --no-commit-id --name-only -r HEAD", code)
        self.assertIn("apigatewayv2 update-api", code)
        self.assertIn("http://localhost:5173", code)
        self.assertIn("https://api.minttick.com", code)
        self.assertNotIn("DisableAuthorization", code)


if __name__ == "__main__":
    unittest.main()
