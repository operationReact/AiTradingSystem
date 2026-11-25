"""Offline source-only EC2 stage regression; never contacts AWS or Upstox."""
import base64
from pathlib import Path
import json
import hashlib
import gzip
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_history_stage as stage


class StageTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        self.source=self.root/"source"
        self.source.mkdir()
        self.src=b"safe_fixture=42\n"*95
        for name in stage.FILES:
            (self.source/name).write_bytes(self.src)

    def tearDown(self):
        self.tmp.cleanup()

    def test_source_stage_is_idempotent_and_never_executes_code(self):
        archive=stage.archive_from_repo(self.source)
        self.assertEqual(stage.install_archive(archive,self.root,False),"STAGED")
        dest=self.root/"infra"/"ec2-paper"
        for name in stage.FILES:
            self.assertEqual((dest/name).read_bytes(),self.src)
            self.assertEqual((dest/name).stat().st_mode & 0o777,0o600)
        self.assertEqual(stage.install_archive(archive,self.root,False),"ALREADY_CURRENT")
        self.assertEqual(len(list(dest.iterdir())),3)
        self.assertFalse((self.root/"fno-history"/"checkpoints.sqlite").exists())
        self.assertFalse((self.root/"fno-history"/"candles.sqlite").exists())

    def test_tampered_archive_rejected_before_writes(self):
        archive=stage.archive_from_repo(self.source)
        obj=json.loads(gzip.decompress(base64.b64decode(archive)))
        obj["files"][stage.FILES[0]]["sha256"]="f"*64
        bad=base64.b64encode(gzip.compress(json.dumps(obj).encode())).decode()
        self.assertEqual(stage.install_archive(bad,self.root,False),"SOURCE_REJECTED")
        self.assertFalse((self.root/"infra").exists())

    def test_symlink_destination_refused_without_touching_target(self):
        archive=stage.archive_from_repo(self.source)
        other=self.root/"outside"
        other.mkdir()
        (self.root/"infra").symlink_to(other,target_is_directory=True)
        self.assertEqual(stage.install_archive(archive,self.root,False),"FILESYSTEM_REJECTED")
        self.assertEqual(list(other.iterdir()),[])

    def test_safe_receipt_rejects_extra_lines_and_unknown_states(self):
        line=stage.make_receipt("STAGED")
        self.assertEqual(stage.parse_receipt(line),"STAGED")
        self.assertEqual(stage.parse_receipt(stage.make_receipt("ALREADY_CURRENT")),
                         "ALREADY_CURRENT")
        self.assertIsNone(stage.parse_receipt(line+"\n"+line))
        self.assertIsNone(stage.parse_receipt(line+" RAW_123"))
        with self.assertRaises(ValueError):
            stage.make_receipt("LICENSE_APPROVED")

    def test_owner_request_zero_broker_traffic_zero_storage(self):
        obj=stage.expected_request()
        self.assertEqual(obj["max_provider_gets"],0)
        self.assertIs(obj["persist_market_data"],False)
        self.assertIs(obj["start_service"],False)
        self.assertIs(obj["trading_rights_change"],False)
        self.assertEqual(obj["account"],"098859530205")
        self.assertEqual(obj["instance_id"],"i-09c01b470fb39b85a")

if __name__=="__main__":
    unittest.main()
