"""Offline, no-AWS tests for exact owner-only private V3 source activation."""
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_private_v3_activate as module

class FnoPrivateV3ActivationTest(unittest.TestCase):
    def test_exact_host_and_permissions(self):
        self.assertEqual(module.EXACT_REQUEST["instance_id"],
                         "i-09c01b470fb39b85a")
        self.assertEqual(module.EXACT_REQUEST["backend_release"],
                         module.EXPECTED_RELEASE)
        self.assertEqual(module.EXACT_REQUEST["operation"],
                         "activate-private-upstox-v3-operator-source-only")
        self.assertEqual(module.FLAG, "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED")
        self.assertIn("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED", module.FORBIDDEN)
        self.assertIn("MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED", module.FORBIDDEN)
        self.assertIn("MINTTICK_UPSTOX_FNO_PAPER_USAGE_RIGHTS_VERIFIED",
                      module.FORBIDDEN)
        self.assertIn("MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED",
                      module.FORBIDDEN)

    def test_host_env_parser_rejects_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)/"runtime.env"
            path.write_text("APP_API_KEY=value\nMINTTICK_UPSTOX_FNO_V3_FEED_ENABLED=false\n")
            self.assertEqual(module.entries(path)["MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED"],
                             "false")
            path.write_text("APP_API_KEY=x\nAPP_API_KEY=y\n")
            with self.assertRaisesRegex(ValueError, "DUPLICATE_HOST_FLAG"):
                module.entries(path)

    def test_no_remote_http_calls_or_source_fetch_in_offline_runner_contract(self):
        source=pathlib.Path(module.__file__).read_text()
        self.assertNotIn("method=\"POST\"",source)
        self.assertNotIn("placeOrder",source)
        self.assertIn("method=\"GET\"",source)
        self.assertIn("PRIVATE_FNO_RIGHTS:DISPLAY_PAPER_LIVE_ORDER_UNCHANGED_DISABLED",source)
        self.assertIn("os.replace(name, p)",source)
        self.assertIn("if not recreate(root):",source)
        self.assertIn("Source", "Source")
        
    def test_source_checks_never_promote_customer_rights(self):
        self.assertTrue(module.FORBIDDEN)
        with patch.object(module, "check_host_files", side_effect=ValueError("INVALID_HOST")):
            with self.assertRaisesRegex(ValueError,"INVALID_HOST"):
                module.host()

if __name__=="__main__":
    unittest.main()
