"""No AWS or broker calls. Tests for reversible owner-approved manual LIVE gate."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SOURCE=Path(__file__).resolve().parents[2] / "infra/ec2-paper/activate_upstox_fno_live_execution.py"
spec=importlib.util.spec_from_file_location("activate_upstox_fno_live_execution",SOURCE)
activation=importlib.util.module_from_spec(spec)
spec.loader.exec_module(activation)


class UpstoxFnoManualLiveActivationTests(unittest.TestCase):
    def test_exact_request_requires_explicit_owner_and_exact_release(self):
        req=activation.EXACT_REQUEST
        self.assertEqual(req["operation"],
            "activate-confirmed-one-lot-live-upstox-order-policy")
        self.assertEqual(req["authorization"],
            "OWNER_EXPLICITLY_REQUESTED_LIVE_EXECUTION_2026_11_20")
        self.assertEqual(req["expected_backend_release"],activation.EXPECTED_RELEASE)
        self.assertEqual(req["instance_id"],"i-09c01b470fb39b85a")

    def test_display_rights_can_imply_discovery_but_never_imply_feed_or_usage(self):
        flags={
            "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED":"true",
            "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED":"true",
            "MINTTICK_UPSTOX_FNO_CONTRACT_DISCOVERY_VERIFIED":"false",
        }
        self.assertTrue(activation.source_flags_ready(flags))
        for key in ("MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED",
                    "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED"):
            failed=dict(flags,**{key:"false"})
            self.assertFalse(activation.source_flags_ready(failed))
        self.assertFalse(activation.source_flags_ready(None))
        self.assertFalse(activation.source_flags_ready({}))
        self.assertEqual(activation.EXPECTED_RELEASE,
            "0a1302f264f37ac71f167d0ace4947dbe4f30577")

    def test_only_live_flag_is_added_and_other_broker_secrets_remain_byte_identical(self):
        content=b"UPSTOX_CLIENT_SECRET=do-not-log-me\nMINTTICK_UPSTOX_FNO_PAPER_ENABLED=false\n"
        result=activation.replace_flag(content)
        self.assertIn(b"UPSTOX_CLIENT_SECRET=do-not-log-me\n",result)
        self.assertIn(b"MINTTICK_UPSTOX_FNO_PAPER_ENABLED=false\n",result)
        self.assertIn(b"MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED=true\n",result)
        self.assertEqual(result.count(b"MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED="),1)

    def test_repeated_activation_remains_idempotent(self):
        initial=b"MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED=false\nFOO=bar\n"
        once=activation.replace_flag(initial)
        twice=activation.replace_flag(once)
        self.assertEqual(once,twice)
        self.assertEqual(activation.entries(twice)["FOO"],"bar")

    def test_duplicate_flags_are_rejected_before_mutation(self):
        with self.assertRaisesRegex(ValueError,"DUPLICATE_HOST_FLAG"):
            activation.entries(b"MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED=false\n"
                               b"MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED=true\n")

    def test_unsafe_carriage_returns_fail_closed_error(self):
        with self.assertRaisesRegex(ValueError,"INVALID_HOST_FILES"):
            activation.replace_flag(b"FOO=bar\r\n")

    def test_runtime_files_symlink_and_permissions_fail_preflight(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"runtime.env").write_text("FOO=bar\n")
            (root/".env").write_text("RELEASE_SHA="+activation.EXPECTED_RELEASE+"\n")
            (root/"paper-compose.yml").write_text("TRADING_MODE: PAPER\n"+
                activation.FLAG+": default\n")
            for item in ("runtime.env",".env"):
                (root/item).chmod(0o600)
            with mock.patch.object(activation.os,"geteuid",return_value=0), \
                 mock.patch.object(activation.os,"uname") as uname:
                uname.return_value.machine="aarch64"
                activation.check_files(root)
                (root/".env").chmod(0o644)
                with self.assertRaisesRegex(ValueError,"INSECURE_HOST_ENV"):
                    activation.check_files(root)
                (root/".env").chmod(0o600)
                (root/"paper-compose.yml").unlink()
                (root/"paper-compose.yml").symlink_to(root/"runtime.env")
                with self.assertRaisesRegex(ValueError,"INVALID_HOST_FILES"):
                    activation.check_files(root)

    def test_activation_contains_no_credential_stdout_or_broker_order_path(self):
        text=SOURCE.read_text()
        for prohibited in ("submitVerifiedFnoOnce","placeOrder","/orders",
                           "orderHistoryByTag","--with-decryption","APP_API_KEY="):
            self.assertNotIn(prohibited,text)
        self.assertIn("UPSTOX_FNO_LIVE_ORDERS:NOT_SUBMITTED",text)
        self.assertIn("UPSTOX_FNO_LIVE_PER_ORDER_CONFIRMATION:REQUIRED",text)
        self.assertIn("ACTIVATION_FAILED_ROLLED_BACK",text)
        self.assertIn("ROLLBACK_UNVERIFIED",text)


if __name__=="__main__":
    unittest.main()
