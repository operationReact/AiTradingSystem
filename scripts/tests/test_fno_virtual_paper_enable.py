"""Offline checks: enabling only two virtual F&O PAPER flags; no AWS/broker."""
from __future__ import annotations

import datetime as dt
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/"infra"/"ec2-paper"/"fno_virtual_paper_enable.py"
SPEC=importlib.util.spec_from_file_location("fno_virtual_paper_enable",SRC)
module=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)
IST=ZoneInfo("Asia/Kolkata")


def initial():
    return (
        b"RELEASE_SHA=0123456789abcdef0123456789abcdef01234567\n"
        b"MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED=true\n"
        b"MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED=true\n"
        b"MINTTICK_UPSTOX_FNO_PAPER_ENABLED=false\n"
        b"MINTTICK_UPSTOX_FNO_PAPER_USAGE_RIGHTS_VERIFIED=false\n"
        b"MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED=false\n"
        b"KEEP_PRIVATE_CREDENTIAL=DO_NOT_EXPOSE\n"
    )


class VirtualPaperEnableTests(unittest.TestCase):
    def test_changes_exactly_two_paper_flags_and_preserves_other_values(self):
        source=initial()
        result=module.patch_env(source)
        old=module.env_entries(source)
        new=module.env_entries(result)
        self.assertEqual(set(new.keys()),set(old.keys()))
        for k in old:
            if k in module.FLAGS:
                self.assertEqual(new[k],"true")
            else:
                self.assertEqual(new[k],old[k])
        self.assertEqual(new["MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED"],"false")
        self.assertEqual(module.patch_env(result),result)

    def test_afterhours_source_and_display_requirements(self):
        c=module.env_entries(initial())
        self.assertEqual(module.ready_to_update(c,c,
            dt.datetime(2026,11,23,23,30,tzinfo=IST)),c["RELEASE_SHA"])
        with self.assertRaisesRegex(module.Blocked,"MARKET_HOURS_NO_RESTART"):
            module.ready_to_update(c,c,dt.datetime(2026,11,24,11,0,tzinfo=IST))
        for k in module.REQUIRED_TRUE:
            bad=dict(c);bad[k]="false"
            with self.subTest(k=k),self.assertRaisesRegex(module.Blocked,"SOURCE_OR_DISPLAY"):
                module.ready_to_update(bad,c,dt.datetime(2026,11,23,23,30,tzinfo=IST))

    def test_live_flag_must_be_disabled_pre_and_post_activation(self):
        c=module.env_entries(initial())
        for side in ("runtime","compose"):
            a,b=dict(c),dict(c)
            (a if side=="runtime" else b)["MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED"]="true"
            with self.subTest(side=side), self.assertRaisesRegex(module.Blocked,"LIVE_FLAG_NOT_DISABLED"):
                module.ready_to_update(a,b,dt.datetime(2026,11,23,23,30,tzinfo=IST))

    def test_rejects_bad_or_duplicate_host_keys(self):
        with self.assertRaisesRegex(module.Blocked,"DUPLICATE_HOST_FLAG"):
            module.env_entries(initial()+b"MINTTICK_UPSTOX_FNO_PAPER_ENABLED=true\n")
        with self.assertRaisesRegex(module.Blocked,"INVALID_HOST_ENV_ENCODING"):
            module.env_entries(b"\xff")
        bad=module.env_entries(initial())
        bad["RELEASE_SHA"]="unknown"
        with self.assertRaisesRegex(module.Blocked,"HOST_RELEASE_UNVERIFIED"):
            module.ready_to_update(bad,bad,dt.datetime(2026,11,23,23,30,tzinfo=IST))

    def test_activation_must_not_touch_live_or_broker_transport(self):
        code=SRC.read_text()
        self.assertIn("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED",code)
        self.assertIn("PAPER_FLAGS_ENABLED",code)
        self.assertIn("ROLLBACK_HEALTH_UNVERIFIED",code)
        self.assertNotIn("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED\"=true",code)
        self.assertNotIn("https://api.upstox.com",code)
        self.assertNotIn("/v3/order/place",code)


if __name__=="__main__":
    unittest.main()
