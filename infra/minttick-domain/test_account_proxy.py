"""Offline security tests for the private MintTick account API Lambda gateway."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest import mock

SOURCE = Path(__file__).with_name("account_proxy.py")
spec = importlib.util.spec_from_file_location("minttick_account_proxy", SOURCE)
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)

JWT = {"claims": {
    "token_use": "access",
    "iss": proxy.ISSUER,
    "client_id": proxy.CLIENT_ID,
    "sub": "fixed-test-subject",
}}

def event(method, path, *, jwt=None, bearer="Bearer public-test-token", body=None):
    return {
        "requestContext": {"http": {"method": method}, "authorizer": {"jwt": jwt or JWT}},
        "rawPath": path,
        "headers": {"authorization": bearer},
        "body": body or "",
        "isBase64Encoded": False,
        "rawQueryString": "",
    }

class Reply:
    status = 200
    def __enter__(self):
        return self
    def __exit__(self, *args):
        return False
    def read(self, max_bytes):
        return b'{"userId":"00000000-0000-4000-8000-000000000001","role":"CUSTOMER"}'

class AccountProxyTest(unittest.TestCase):
    def test_only_explicit_account_and_customer_routes_allowed(self):
        self.assertTrue(proxy.allowed("POST","/api/auth/cognito/provision"))
        self.assertTrue(proxy.allowed("GET","/api/auth/cognito/me"))
        self.assertTrue(proxy.allowed("GET","/api/customer/trading-rooms"))
        self.assertTrue(proxy.allowed("GET","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/paper/account"))
        for method, path in [
            ("GET","/api/admin/users"),
            ("GET","/api/risk/approvals"),
            ("POST","/api/customer/brokers/angel-one/connect"),
            ("POST","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/orders/intents"),
            ("DELETE","/api/customer/trading-rooms"),
            ("GET","/v3/api-docs"),
            ("GET","/api/health"),
        ]:
            self.assertFalse(proxy.allowed(method,path), f"accidentally allowed {path}")

    def test_rejects_missing_or_wrong_jwt_even_when_gateway_is_misconfigured(self):
        for claims in [
            {},
            {"token_use":"id","iss":proxy.ISSUER,"client_id":proxy.CLIENT_ID,"sub":"x"},
            {"token_use":"access","iss":"https://evil.test","client_id":proxy.CLIENT_ID,"sub":"x"},
            {"token_use":"access","iss":proxy.ISSUER,"client_id":"wrong","sub":"x"},
        ]:
            data=event("GET","/api/auth/cognito/me",jwt={"claims":claims})
            self.assertEqual(proxy.handler(data,None)["statusCode"],401)

    def test_rejects_admin_before_opening_any_socket(self):
        with mock.patch.object(proxy.urllib.request,"urlopen") as urlopen:
            result=proxy.handler(event("GET","/api/admin/users"),None)
        self.assertEqual(result["statusCode"],404)
        urlopen.assert_not_called()

    def test_provision_only_forwards_empty_body_and_bearer(self):
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as urlopen:
            result=proxy.handler(event("POST","/api/auth/cognito/provision"),None)
        self.assertEqual(result["statusCode"],200)
        request=urlopen.call_args.args[0]
        self.assertEqual(request.get_method(),"POST")
        self.assertEqual(request.full_url,"http://10.87.0.10:8081/api/auth/cognito/provision")
        self.assertEqual(request.headers.get("Authorization"),"Bearer public-test-token")
        self.assertEqual(proxy.handler(event("POST","/api/auth/cognito/provision",body='{"email":"x"}'),None)["statusCode"],400)

    def test_readiness_is_through_existing_private_backend(self):
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as urlopen:
            result=proxy.handler(event("GET","/actuator/health/readiness",jwt={"claims":{}},bearer=""),None)
        self.assertEqual(result["statusCode"],200)
        self.assertEqual(json.loads(result["body"]),{"status":"UP"})
        self.assertEqual(urlopen.call_args.args[0].full_url,"http://10.87.0.10:8081/api/health")

    def test_limits_body_and_rejects_transport_failures(self):
        data=event("POST","/api/customer/trading-rooms",body="x"*65537)
        self.assertEqual(proxy.handler(data,None)["statusCode"],413)
        with mock.patch.object(proxy.urllib.request,"urlopen",side_effect=OSError("private connect refused")):
            self.assertEqual(proxy.handler(event("GET","/api/auth/cognito/me"),None)["statusCode"],503)

    def test_template_embeds_identical_reviewed_python_source(self):
        text=Path(__file__).with_name("bootstrap-account-api.yml").read_text()
        anchor="        ZipFile: |\n"
        self.assertEqual(text.count(anchor),1)
        after=text.split(anchor,1)[1].split("\n  AccountHttpApi:",1)[0]
        embedded="\n".join(row[8:] for row in after.rstrip("\n").split("\n")).rstrip()+"\n"
        self.assertEqual(embedded,SOURCE.read_text().rstrip()+"\n")

if __name__ == "__main__":
    unittest.main()
