"""Offline constraints for exact-host, fail-closed MintTick PAPER recovery."""
import sys
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra/ec2-paper"))
import restore_last_healthy_paper as mod

class RestorePreviousPaperTests(unittest.TestCase):
    def test_only_known_safe_previous_version_and_paper_target(self):
        req=mod.expected_request()
        self.assertEqual(req["target_release"],mod.HEALTHY)
        self.assertNotEqual(req["failed_release"],mod.HEALTHY)
        self.assertEqual(req["postgres"],"KEEP_RUNNING")
        self.assertEqual(req["broker_orders"],"UNCHANGED")
        self.assertEqual(req["environment"],"PAPER")

    def test_receipt_is_sanitized_and_strict(self):
        for state in mod.STATES:
            r=mod.receipt(state)
            self.assertEqual(mod.parse_receipt(r),r)
            self.assertNotIn("APP_API_KEY",r)
            self.assertNotIn("POSTGRES_PASSWORD",r)
        self.assertIsNone(mod.parse_receipt(mod.receipt("ROLLED_BACK")+"\n"+
                                            mod.receipt("ROLLED_BACK")))
        self.assertIsNone(mod.parse_receipt(
            mod.receipt("ROLLED_BACK").replace("UNCHANGED","ENABLED")))

    def test_atomic_replace_changes_only_sha_and_file_is_private(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            env=root/".env"
            env.write_text("SECRET_VALUE=hidden\nRELEASE_SHA="+mod.FAILED+
                           "\nTRADING_MODE=PAPER\n")
            env.chmod(0o600)
            with patch.object(mod,"ROOT",root), patch.object(mod,"ENV",env):
                lines,current=mod.read_env()
                self.assertEqual(current,mod.FAILED)
                mod.replace_release(lines)
                text=env.read_text()
                self.assertIn("SECRET_VALUE=hidden\n",text)
                self.assertIn("RELEASE_SHA="+mod.HEALTHY,text)
                self.assertIn("TRADING_MODE=PAPER",text)
                self.assertEqual(env.stat().st_mode & 0o777,0o600)

    def test_deny_unsafe_release_or_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            p=root/".env"
            p.write_text("RELEASE_SHA=bad\n")
            p.chmod(0o600)
            with patch.object(mod,"ENV",p):
                self.assertEqual(mod.read_env()[1],"bad")
            symlink=root/"alias"
            symlink.symlink_to(p)
            with patch.object(mod,"ENV",symlink):
                self.assertEqual(mod.read_env(),(None,None))

if __name__=="__main__":
    unittest.main()
