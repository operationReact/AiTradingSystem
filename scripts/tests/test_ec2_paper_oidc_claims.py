"""Offline tests for exact GitHub OIDC dev audience, no JWT disclosure."""
from __future__ import annotations

import base64
import importlib.util
import json
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT / "infra/ec2-paper/verify_oidc_claims.py"
spec=importlib.util.spec_from_file_location("ec2_oidc_claims",SRC)
assert spec and spec.loader
gate=importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def fake_token(claims):
    body=base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    return "hdr."+body+".sig"


class EC2OIDCClaims(unittest.TestCase):
    def test_exact_user_repo_dev_push_identity(self):
        gate.verify(gate.EXPECTED.copy())
        self.assertEqual(gate.decode_claims(fake_token(gate.EXPECTED)),gate.EXPECTED)
        for key,wrong in (
            ("iss","https://example.com"),
            ("aud","other.audience"),
            ("sub","repo:operationReact/AiTradingSystem:ref:refs/heads/main"),
            ("repository","operationReact/other"),
            ("ref","refs/heads/main"),
            ("event_name","workflow_dispatch"),
        ):
            altered={**gate.EXPECTED,key:wrong}
            with self.subTest(field=key),self.assertRaises(ValueError):
                gate.verify(altered)
        with self.assertRaises(ValueError):
            gate.verify({**gate.EXPECTED,"sub":["unexpected"]})

    def test_reject_corrupt_jwt_without_ever_printing_it(self):
        for bad in ("", "a.b", "header.%%%%.sig", "not-a-jwt", 1, "a"*17000):
            with self.subTest(token=str(bad)[:20]),self.assertRaises(ValueError):
                gate.decode_claims(bad)

    def test_preflight_only_check_before_sts_no_account_mutations(self):
        wf=(ROOT / ".github/workflows/ec2-paper-readonly-preflight.yml").read_text()
        self.assertIn("verify_oidc_claims.py",wf)
        self.assertLess(wf.index("verify_oidc_claims.py"),
                        wf.index("aws-actions/configure-aws-credentials@v4"))
        self.assertIn("id-token: write",wf)
        self.assertIn("role-to-assume: arn:aws:iam::098859530205:role/aitrading-paper-ec2-preflight",wf)
        self.assertNotIn("terraform apply",wf)
        self.assertNotIn("aws ec2 run-instances",wf)
        source=SRC.read_text()
        self.assertNotIn("print(claims)",source)
        self.assertNotIn("print(token)",source)
        self.assertNotIn("print(request_token)",source)


if __name__=="__main__":
    unittest.main()
