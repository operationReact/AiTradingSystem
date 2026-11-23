"""No-network tests: one isolated virtual PAPER room and after-close abstention."""
from __future__ import annotations

import contextlib
import datetime as dt
import importlib.util
import io
from pathlib import Path
import unittest
from zoneinfo import ZoneInfo

SPEC=importlib.util.spec_from_file_location(
    "bootstrap_fno_paper_verify_room",Path(__file__).with_name("bootstrap_fno_paper_verify_room.py"))
s=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(s)
ROOM="b1469dc1-1c30-42c4-a64b-2de0eaf09873"
AUTH="TEST_COGNITO_SECRET_NEVER_PRINTED"
IST=ZoneInfo("Asia/Kolkata")


def room():
    return {
        "id":ROOM,"name":s.ROOM_NAME,"focus":"INTRADAY",
        "mode":"PAPER","marketScope":"FNO","status":"PAPER_FUNDED",
        "plannedBudgetInr":s.ROOM_BUDGET_INR,
        "privateEmail":"PRIVATE_EMAIL_DO_NOT_LOG",
    }


class Fake:
    def __init__(self,existing=None,paper=True,enabled=False):
        self.calls=[]
        self.existing=existing if existing is not None else []
        self.paper=paper
        self.enabled=enabled
    def __call__(self,path,token,method="GET",data=None):
        assert token==AUTH
        self.calls.append((path,method,data))
        if path==s.ROOT and method=="GET":
            return self.existing
        if path==s.ROOT and method=="POST":
            assert data["mode"]=="PAPER"
            assert data["marketScope"]=="FNO"
            assert data["plannedBudgetInr"]==100000
            self.existing=[room()]
            return room()
        if path.endswith("/paper/fno/capability"):
            return {"paperConfigured":self.paper,"liveExecutionAuthorized":False}
        if path.endswith("/paper/fno/reconciliation"):
            return {"status":"NO_FILLS","recordedFills":0}
        if path.endswith("/paper/fno/wallet-reconciliation"):
            return {"cashAndRealizedPnlReconciled":True}
        if path.endswith("/mind/fno/paper-automation"):
            return {"enabled":self.enabled,"liveMoney":False}
        if path.endswith("/mind/fno/paper-cycles"):
            assert method=="POST" and data is None
            return {"status":"NO_TRADE","reasonCode":"PAPER_ENTRY_CUTOFF",
                    "action":"NONE","liveMoney":False}
        raise AssertionError(path)


class VerificationRoomTests(unittest.TestCase):
    def test_create_once_and_read_all_ledger_gates(self):
        fake=Fake()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(s.observe(AUTH,fake),0)
        self.assertEqual(len([c for c in fake.calls if c[1]=="POST"]),1)
        report=output.getvalue()
        self.assertIn("verificationRoom=CREATED_VIRTUAL_ONLY",report)
        self.assertIn("bookStatus=NO_FILLS;walletReconciled=true",report)
        self.assertIn("scheduler=DISABLED_SAFE",report)
        for secret in (AUTH,ROOM,"PRIVATE_EMAIL","100000"):
            self.assertNotIn(secret,report)

    def test_repeat_is_idempotent_and_has_zero_writes(self):
        fake=Fake(existing=[room()])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(s.observe(AUTH,fake),0)
        self.assertTrue(all(method=="GET" for _,method,_ in fake.calls))
        self.assertIn("verificationRoom=REUSED_VIRTUAL_ONLY",output.getvalue())

    def test_afterclose_cycle_only_virtual_abstention(self):
        fake=Fake()
        after=dt.datetime(2026,11,23,23,15,tzinfo=IST)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(s.observe(AUTH,fake,allow_cycle=True,now=after),0)
        posts=[x for x in fake.calls if x[1]=="POST"]
        self.assertEqual(len(posts),2)
        self.assertTrue(posts[-1][0].endswith("/mind/fno/paper-cycles"))
        self.assertIsNone(posts[-1][2])
        self.assertIn("cycle=NO_ORDER_AFTER_CLOSE",output.getvalue())
        self.assertIn("independentE2E=false",output.getvalue())

    def test_midday_cycle_refuses_post(self):
        fake=Fake(existing=[room()])
        midday=dt.datetime(2026,11,24,11,0,tzinfo=IST)
        with self.assertRaisesRegex(s.VerifyBlocked,"PAPER_CYCLE_ONLY_AFTER_MARKET_CLOSE"):
            s.observe(AUTH,fake,allow_cycle=True,now=midday)
        self.assertFalse(any(m=="POST" for _,m,_ in fake.calls))

    def test_config_disabled_skips_order_and_returns_diagnostic(self):
        fake=Fake(existing=[room()],paper=False)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(s.observe(AUTH,fake,allow_cycle=True,
                now=dt.datetime(2026,11,23,23,15,tzinfo=IST)),0)
        self.assertFalse(any(m=="POST" for _,m,_ in fake.calls))
        self.assertIn("SKIPPED_CAPABILITY_BLOCKED",output.getvalue())

    def test_worker_previously_enabled_blocks_test(self):
        fake=Fake(existing=[room()],enabled=True)
        with self.assertRaisesRegex(s.VerifyBlocked,"UNEXPECTED_VERIFICATION_SCHEDULER_ENABLED"):
            s.observe(AUTH,fake)
        self.assertFalse(any(m=="POST" for _,m,_ in fake.calls))

    def test_reject_untrusted_room_preexisting_identity(self):
        item=room()
        item["mode"]="LIVE"
        fake=Fake(existing=[item])
        with self.assertRaisesRegex(s.VerifyBlocked,"VERIFICATION_ROOM_IDENTITY_MISMATCH"):
            s.ensure_room(AUTH,fake)
        self.assertFalse(any(m=="POST" for _,m,_ in fake.calls))

    def test_only_two_post_routes_ever_allowed_and_getter_pinned(self):
        for path,method,data in [
            ("/api/customer/markets/fno/capability","POST",{}),
            (s.ROOT+"/OTHER","POST",{}),
            (s.ROOT+"/"+ROOM+"/mind/fno/paper-automation/control","POST",{"enabled":True}),
            (s.ROOT+"/"+ROOM+"/mind/fno/paper-cycles","POST",{"instrumentKey":"NSE_FO|1"}),
        ]:
            with self.subTest(path=path),self.assertRaises(s.VerifyBlocked):
                s.request(path,AUTH,method,data)
        self.assertTrue(s.NoRedirect().redirect_request(None,None,None,None,None,None) is None)

if __name__=="__main__":
    unittest.main()
