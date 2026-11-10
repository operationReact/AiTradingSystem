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
        self.assertTrue(proxy.allowed("GET","/api/customer/brokers/angel-one/configured-login"))
        self.assertTrue(proxy.allowed("GET","/api/customer/brokers/angel-one/owner-link-reference"))
        self.assertTrue(proxy.allowed("POST","/api/customer/brokers/angel-one/connect-configured"))
        self.assertTrue(proxy.allowed("POST","/api/customer/brokers/angel-one/connect"))
        self.assertTrue(proxy.allowed("POST","/api/customer/brokers/angel-one/disconnect"))
        self.assertTrue(proxy.allowed("POST","/api/customer/brokers/upstox/start"))
        self.assertTrue(proxy.allowed("GET","/api/customer/brokers/upstox/holdings"))
        self.assertTrue(proxy.allowed("GET","/api/customer/portfolio/records"))
        self.assertTrue(proxy.allowed("GET","/api/customer/home/v1/summary"))
        self.assertFalse(proxy.allowed("POST","/api/customer/home/v1/summary"))
        self.assertFalse(proxy.allowed("POST","/api/customer/portfolio/records"))
        self.assertTrue(proxy.allowed("GET",proxy.UPSTOX_CALLBACK))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/instruments"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/instruments/status"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/quotes"))
        for path in ("/api/customer/market-data/quotes/detail", "/api/customer/market-data/overview", "/api/customer/market-data/snapshot", "/api/customer/market-data/candles", "/api/customer/watchlist"):
            self.assertTrue(proxy.allowed("GET", path), path)
        self.assertTrue(proxy.allowed("POST", "/api/customer/watchlist"))
        self.assertTrue(proxy.allowed("DELETE", "/api/customer/watchlist/items/123"))
        self.assertFalse(proxy.allowed("DELETE", "/api/customer/watchlist/items/abc"))
        self.assertTrue(proxy.allowed("GET","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/paper/account"))
        for method, path in [
            ("GET","/api/admin/users"),
            ("GET","/api/risk/approvals"),
            ("POST","/api/customer/brokers/angel-one/orders"),
            ("GET","/api/customer/brokers/angel-one/connect-configured"),
            ("GET","/api/customer/brokers/angel-one/connect"),
            ("POST",proxy.UPSTOX_CALLBACK),
            ("POST","/api/customer/brokers/upstox/orders"),
            ("POST","/api/customer/market-data/quotes"),
            ("POST","/api/customer/market-data/instruments/sync"),
            ("GET","/api/market-data/quotes"),
            ("GET","/api/customer/brokers/upstox/start"),
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

    def test_owner_link_reference_requires_cognito_and_no_store(self):
        path = "/api/customer/brokers/angel-one/owner-link-reference"
        unauthenticated = event("GET", path, bearer="")
        self.assertEqual(proxy.handler(unauthenticated, None)["statusCode"], 401)
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as urlopen:
            response = proxy.handler(event("GET", path), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(response["headers"]["cache-control"], "no-store")
        forwarded = urlopen.call_args.args[0]
        self.assertEqual(forwarded.full_url, proxy.BACKEND + path)
        self.assertEqual(forwarded.headers.get("Authorization"), "Bearer public-test-token")

    def test_owner_configured_broker_route_forwards_only_jwt_and_empty_post(self):
        # The backend independently checks exact Cognito owner-sub + CUSTOMER
        # and complete server-side SSM configuration. This edge does not carry
        # broker credentials, customer PIN or TOTP in the request.
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as urlopen:
            result=proxy.handler(event("POST","/api/customer/brokers/angel-one/connect-configured"),None)
        self.assertEqual(result["statusCode"],200)
        req=urlopen.call_args.args[0]
        self.assertEqual(req.get_method(),"POST")
        self.assertEqual(req.data,b"")
        self.assertEqual(req.headers.get("Authorization"),"Bearer public-test-token")
        self.assertEqual(proxy.handler(event("POST","/api/customer/brokers/angel-one/connect-configured",
            body='{"pin":"1234"}'),None)["statusCode"],400)
        self.assertEqual(proxy.handler(event("POST","/api/customer/brokers/angel-one/disconnect",
            body='{"credential":"bad"}'),None)["statusCode"],400)

    def test_manual_smartapi_connection_requires_account_jwt_and_six_digit_code(self):
        endpoint = "/api/customer/brokers/angel-one/connect"
        data = {"apiKey": "sample-test-value", "clientCode": "TEST1234",
                "pin": "1234", "totp": "123456"}
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as socket:
            result = proxy.handler(event("POST", endpoint, body=json.dumps(data)), None)
        self.assertEqual(result["statusCode"], 200)
        outgoing = socket.call_args.args[0]
        self.assertEqual(outgoing.full_url, proxy.BACKEND + endpoint)
        self.assertEqual(json.loads(outgoing.data), data)
        self.assertEqual(outgoing.headers.get("Authorization"), "Bearer public-test-token")
        for invalid in [
            {},
            {**data, "totp": "12345"},
            {**data, "totp": "1234567890"},
            {**data, "unknown": "not-allowed"},
        ]:
            with mock.patch.object(proxy.urllib.request, "urlopen") as denied:
                answer = proxy.handler(event("POST", endpoint, body=json.dumps(invalid)), None)
            self.assertEqual(answer["statusCode"], 400)
            denied.assert_not_called()
        with mock.patch.object(proxy.urllib.request, "urlopen") as denied:
            invalid_jwt = event("POST", endpoint, jwt={"claims":{}}, body=json.dumps(data))
            answer = proxy.handler(invalid_jwt, None)
        self.assertEqual(answer["statusCode"], 401)
        denied.assert_not_called()

    def test_invalid_base64_is_rejected_without_upstream_call(self):
        data=event("POST","/api/customer/trading-rooms",body="%%%")
        data["isBase64Encoded"]=True
        with mock.patch.object(proxy.urllib.request,"urlopen") as urlopen:
            self.assertEqual(proxy.handler(data,None)["statusCode"],400)
        urlopen.assert_not_called()

    def test_upstox_callback_is_one_time_state_only_no_jwt_or_reflection(self):
        state = "A" * 43
        value = "state=" + state + "&code=short-test-code"
        data = event("GET",proxy.UPSTOX_CALLBACK,jwt={"claims":{}},bearer="")
        data["rawQueryString"] = value
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as urlopen:
            result=proxy.handler(data,None)
        self.assertEqual(result["statusCode"],200)
        self.assertIn("Upstox connected",result["body"])
        self.assertNotIn(state,result["body"])
        self.assertNotIn("short-test-code",result["body"])
        req=urlopen.call_args.args[0]
        self.assertEqual(req.get_method(),"GET")
        self.assertEqual(req.full_url,proxy.BACKEND+proxy.UPSTOX_CALLBACK+"?"+value)
        self.assertNotIn("Authorization",req.headers)
        self.assertEqual(result["headers"]["referrer-policy"],"no-referrer")

    def test_upstox_callback_rejects_injected_or_duplicate_query_without_socket(self):
        for query in [
            "", "code=a", "state=" + "a"*43,
            "state=" + "a"*43 + "&code=x&code=y",
            "state=" + "a"*43 + "&code=x&redirect_uri=https://evil.example",
            "state=" + "a"*43 + "&code=x&error=y",
            "state=" + "a"*43 + "&code=" + "X"*2049,
            "state=" + "a"*43 + "&code=x&state=" + "b"*43,
        ]:
            data = event("GET",proxy.UPSTOX_CALLBACK,jwt={"claims":{}},bearer="")
            data["rawQueryString"]=query
            with mock.patch.object(proxy.urllib.request,"urlopen") as urlopen:
                result=proxy.handler(data,None)
            self.assertEqual(result["statusCode"],400,query[:40])
            urlopen.assert_not_called()

    def test_upstox_callback_returns_constant_failure_on_backend_rejection(self):
        state = "A"*43
        data=event("GET",proxy.UPSTOX_CALLBACK,jwt={"claims":{}},bearer="")
        data["rawQueryString"]="state="+state+"&error=access_denied"
        class Declined(Reply):
            status=400
            def read(self,max_bytes):
                return b"<html>Contains oauth code: secret</html>"
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Declined()):
            result=proxy.handler(data,None)
        self.assertEqual(result["statusCode"],400)
        self.assertNotIn("secret",result["body"])

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
        embedded="\n".join(row[10:] for row in after.rstrip("\n").split("\n")).rstrip()+"\n"
        self.assertEqual(embedded,SOURCE.read_text().rstrip()+"\n")

if __name__ == "__main__":
    unittest.main()
