"""No-network checks of one-room virtual scheduler enablement."""
from __future__ import annotations

import contextlib
import datetime as dt
import importlib.util
import io
from pathlib import Path
import sys
import unittest

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
SPEC=importlib.util.spec_from_file_location(
    "enable_fno_paper_verify_scheduler",HERE/"enable_fno_paper_verify_scheduler.py")
mod=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)
ROOM="b1469dc1-1c30-42c4-a64b-2de0eaf09873"
TOKEN="TEST_ONLY_COGNITO_TOKEN"
AFTER=dt.datetime(2026,11,23,23,30,tzinfo=mod.room.IST)


class Fake:
    def __init__(self,configured=True,empty=True,enabled=False):
        self.configured=configured;self.empty=empty;self.enabled=enabled;self.calls=[]
    def read(self,path,token):
        assert token==TOKEN
        self.calls.append(("GET",path))
        if path==mod.ROOT:
            return [{"name":mod.room.ROOM_NAME,"id":ROOM,
                     "mode":"PAPER","focus":"INTRADAY",
                     "status":"PAPER_FUNDED","marketScope":"FNO"}]
        if path.endswith("/paper/fno/capability"):
            return {"paperConfigured":self.configured,"liveExecutionAuthorized":False}
        if path.endswith("/paper/fno/reconciliation"):
            return {"status":"NO_FILLS" if self.empty else "INCONSISTENT",
                    "recordedFills":0 if self.empty else 1}
        if path.endswith("/paper/fno/wallet-reconciliation"):
            return {"cashAndRealizedPnlReconciled":self.empty,
                    "optionFills":0 if self.empty else 1}
        if path.endswith("/mind/fno/paper-automation"):
            return {"enabled":self.enabled,"liveMoney":False}
        raise AssertionError(path)
    def post(self,path,token,payload):
        assert token==TOKEN
        assert path==mod.ROOT+"/"+ROOM+"/mind/fno/paper-automation/control"
        assert payload in ({"enabled":True},{"enabled":False})
        self.calls.append(("POST",path))
        self.enabled=payload["enabled"]
        return {"enabled":self.enabled,"liveMoney":False}


class VerifySchedulerTests(unittest.TestCase):
    def test_only_virtual_reconciled_room_is_enabled(self):
        f=Fake()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(mod.arm(TOKEN,f.read,f.post,AFTER),0)
        self.assertEqual(sum(method=="POST" for method,_ in f.calls),1)
        self.assertIn("VIRTUAL_PAPER_VERIFICATION_WORKER_ENABLED",output.getvalue())
        self.assertIn("REAL_MONEY_ORDERS:ZERO",output.getvalue())
        self.assertNotIn(ROOM,output.getvalue())
        self.assertNotIn(TOKEN,output.getvalue())

    def test_idempotent_second_invocation_no_write(self):
        f=Fake(enabled=True)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(mod.arm(TOKEN,f.read,f.post,AFTER),0)
        self.assertEqual(sum(method=="POST" for method,_ in f.calls),0)
        self.assertIn("ALREADY_ENABLED",output.getvalue())

    def test_unsafe_capability_or_wallet_blocks_every_control_post(self):
        for flags in ({"configured":False},{"empty":False}):
            with self.subTest(flags=flags):
                f=Fake(**flags)
                with self.assertRaises(mod.Blocked):
                    mod.arm(TOKEN,f.read,f.post,AFTER)
                self.assertFalse(any(m=="POST" for m,_ in f.calls))

    def test_missing_room_never_creates_virtual_room(self):
        class Missing(Fake):
            def read(self,path,token):
                if path==mod.ROOT:
                    self.calls.append(("GET",path))
                    return []
                return super().read(path,token)
        f=Missing()
        with self.assertRaisesRegex(mod.Blocked,"VERIFICATION_ROOM_MISSING_OR_AMBIGUOUS"):
            mod.arm(TOKEN,f.read,f.post,AFTER)
        self.assertEqual(f.calls,[("GET",mod.ROOT)])

    def test_wrong_market_scope_rejected_before_control_write(self):
        class WrongScope(Fake):
            def read(self,path,token):
                if path==mod.ROOT:
                    self.calls.append(("GET",path))
                    return [{"name":mod.room.ROOM_NAME,"id":ROOM,
                             "mode":"PAPER","focus":"INTRADAY",
                             "status":"PAPER_FUNDED","marketScope":"EQUITY"}]
                return super().read(path,token)
        f=WrongScope()
        with self.assertRaisesRegex(mod.Blocked,"VERIFICATION_ROOM_SCOPE_MISMATCH"):
            mod.arm(TOKEN,f.read,f.post,AFTER)
        self.assertEqual(f.calls,[("GET",mod.ROOT)])

    def test_market_hours_cannot_turn_on_worker(self):
        f=Fake()
        with self.assertRaisesRegex(mod.Blocked,"MARKET_HOURS"):
            mod.arm(TOKEN,f.read,f.post,
                dt.datetime(2026,11,24,11,30,tzinfo=mod.room.IST))
        self.assertEqual(f.calls,[])

    def test_only_exact_control_post_route_and_boolean_payload(self):
        for path,payload in [
            (mod.ROOT,{"enabled":True}),
            (mod.ROOT+"/"+ROOM+"/mind/fno/paper-cycles",{"enabled":True}),
            (mod.ROOT+"/"+ROOM+"/extra/mind/fno/paper-automation/control",{"enabled":True}),
            (mod.ROOT+"/"+ROOM+"/mind/fno/paper-automation/control",{"live":True}),
        ]:
            with self.subTest(path=path),self.assertRaises(mod.Blocked):
                mod.post_control(path,TOKEN,payload)


if __name__=="__main__":
    unittest.main()
