"""V83 source contract: worker origin pin independent of URL and token."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "backend/src/main/java/in/aitradingsystem/intelligence"
TF = ROOT / "infra/terraform/main.tf"
APP = ROOT / "backend/src/main/resources/application.yml"


class JavaWorkerHostPinContract(unittest.TestCase):
    def test_both_worker_clients_check_origin_before_http_transport(self):
        for file, mark in [
            ("ResearchArtifactInferenceClient.java", "URI endpoint = validatedEndpoint(baseUrl);"),
            ("ResearchWorkerReadinessService.java", "URI url = validatedEndpoint(baseUrl);"),
        ]:
            content = (JAVA / file).read_text()
            self.assertIn("ResearchWorkerTlsOrigin.requirePinned(", content)
            self.assertIn('app.research.artifact-worker.trusted-host:', content)
            self.assertLess(content.index(mark), content.index("ResearchWorkerTlsOrigin.requirePinned("))
            self.assertNotIn("followRedirects(HttpClient.Redirect.ALWAYS)", content)
        policy = (JAVA / "ResearchWorkerTlsOrigin.java").read_text()
        self.assertIn('!"https".equals(endpoint.getScheme())', policy)
        self.assertIn("host.equals(actual.toLowerCase(Locale.ROOT))", policy)
        self.assertIn('endpoint.getPort() != 443', policy)

    def test_pinned_hostname_comes_from_private_terraform_not_untrusted_url(self):
        terraform = TF.read_text()
        app = APP.read_text()
        self.assertIn('name = "AI_RESEARCH_WORKER_TRUSTED_HOST", value = local.worker_tls_fqdn',
                      terraform)
        self.assertIn("var.research_worker_tls_enabled ? [", terraform)
        self.assertIn("trusted-host: ${AI_RESEARCH_WORKER_TRUSTED_HOST:}", app)
        # No config-driven LIVE or scoring opt-in is added by hostname hardening.
        self.assertNotIn('name = "AI_RESEARCH_WORKER_CLIENT_ENABLED", value = "true"',
                         terraform)
        self.assertNotIn('name = "AI_RESEARCH_WORKER_READINESS_ENABLED", value = "true"',
                         terraform)

    def test_no_tls_bypass_or_token_leak_in_code(self):
        source = (JAVA / "ResearchWorkerTlsOrigin.java").read_text()
        for bad in ("TrustAll", "insecureSkipVerify", "setDefaultHostnameVerifier",
                    "NoopHostnameVerifier", "X-Research-Worker-Token"):
            self.assertNotIn(bad, source)
        self.assertIn("Trusted worker HTTPS origin mismatch", source)


if __name__ == "__main__":
    unittest.main()
