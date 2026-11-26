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
        self.assertTrue(proxy.allowed("GET","/api/customer/brokers/upstox/configured-login"))
        self.assertTrue(proxy.allowed("POST","/api/customer/brokers/upstox/connect-configured"))
        self.assertFalse(proxy.allowed("GET","/api/customer/brokers/upstox/connect-configured"))
        self.assertFalse(proxy.allowed("POST","/api/customer/brokers/upstox/configured-login"))
        self.assertTrue(proxy.allowed("GET","/api/customer/brokers/upstox/holdings"))
        self.assertTrue(proxy.allowed("GET","/api/customer/portfolio/records"))
        self.assertTrue(proxy.allowed("GET","/api/customer/mind/v1/verified-news-evidence"))
        self.assertFalse(proxy.allowed("POST","/api/customer/mind/v1/verified-news-evidence"))
        self.assertFalse(proxy.allowed("DELETE","/api/customer/mind/v1/verified-news-evidence"))
        self.assertFalse(proxy.allowed("GET","/api/customer/mind/v1/verified-news-evidence/raw"))
        self.assertTrue(proxy.allowed("GET","/api/customer/home/v1/summary"))
        self.assertTrue(proxy.allowed("GET","/api/customer/home/v1/activity"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-intelligence/articles"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-intelligence/sources"))
        self.assertFalse(proxy.allowed("POST","/api/customer/market-intelligence/articles"))
        self.assertFalse(proxy.allowed("GET","/api/customer/market-intelligence/sources/raw"))
        self.assertTrue(proxy.allowed("GET","/api/customer/owner-history/v1/progress"))
        self.assertTrue(proxy.allowed("GET","/api/customer/owner-upstox-analytics/v1/progress"))
        self.assertTrue(proxy.allowed("GET","/api/customer/owner-upstox-analytics/v1/persisted-receipts"))
        self.assertFalse(proxy.allowed("POST","/api/customer/owner-upstox-analytics/v1/persisted-receipts"))
        self.assertFalse(proxy.allowed("GET","/api/customer/owner-upstox-analytics/v1/persisted-receipts/raw"))
        self.assertFalse(proxy.allowed("POST","/api/customer/owner-upstox-analytics/v1/progress"))
        self.assertFalse(proxy.allowed("GET","/api/customer/owner-upstox-analytics/v1/progress/raw"))
        self.assertFalse(proxy.allowed("GET","/api/admin/market-data/upstox-analytics/probe"))
        self.assertFalse(proxy.allowed("POST","/api/customer/home/v1/activity"))
        self.assertFalse(proxy.allowed("POST","/api/customer/owner-history/v1/progress"))
        self.assertFalse(proxy.allowed("GET","/api/customer/owner-history/v1/progress/raw"))
        self.assertFalse(proxy.allowed("POST","/api/customer/home/v1/summary"))
        self.assertFalse(proxy.allowed("POST","/api/customer/market-intelligence/articles"))
        self.assertFalse(proxy.allowed("GET","/api/customer/market-intelligence/articles/raw"))
        self.assertFalse(proxy.allowed("POST","/api/customer/portfolio/records"))
        self.assertTrue(proxy.allowed("GET",proxy.UPSTOX_CALLBACK))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/instruments"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/instruments/status"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/universe-facets"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/company-profile"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/fundamentals"))
        self.assertFalse(proxy.allowed("POST","/api/customer/market-data/fundamentals"))
        self.assertFalse(proxy.allowed("GET","/api/customer/market-data/fundamentals/private"))
        self.assertFalse(proxy.allowed("POST","/api/customer/market-data/company-profile"))
        self.assertFalse(proxy.allowed("GET","/api/customer/market-data/company-profile/raw"))
        self.assertFalse(proxy.allowed("POST","/api/customer/market-data/universe-facets"))
        self.assertTrue(proxy.allowed("GET","/api/customer/market-data/quotes"))
        for path in ("/api/customer/market-data/quotes/detail", "/api/customer/market-data/overview", "/api/customer/market-data/snapshot", "/api/customer/market-data/candles", "/api/customer/watchlist"):
            self.assertTrue(proxy.allowed("GET", path), path)
        self.assertTrue(proxy.allowed("POST", "/api/customer/watchlist"))
        for path in ("/api/customer/market-data/board", "/api/customer/market-data/board/summary", "/api/customer/market-data/full-quote-coverage"):
            self.assertTrue(proxy.allowed("GET", path), path)
            self.assertFalse(proxy.allowed("POST", path), path)
        self.assertFalse(proxy.allowed("GET", "/api/customer/market-data/board/raw"))
        self.assertTrue(proxy.allowed("DELETE", "/api/customer/watchlist/items/123"))
        self.assertFalse(proxy.allowed("DELETE", "/api/customer/watchlist/items/abc"))
        self.assertTrue(proxy.allowed("GET","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/paper/account"))
        self.assertTrue(proxy.allowed("PUT","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/name"))
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
            ("POST","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/name"),
            ("PUT","/api/customer/trading-rooms"),
            ("PUT","/api/customer/trading-rooms/00000000-0000-4000-8000-000000000001/paper/account"),
            ("PUT","/api/customer/brokers/upstox/holdings"),
            ("PUT","/api/customer/trading-rooms/non-uuid/name"),
            ("DELETE","/api/customer/trading-rooms"),
            ("GET","/v3/api-docs"),
            ("GET","/api/health"),
        ]:
            self.assertFalse(proxy.allowed(method,path), f"accidentally allowed {path}")


    def test_owner_history_progress_requires_jwt_and_backend_owner_gate(self):
        # Only safe, read-only metadata may traverse the existing Cognito edge.
        # Exact owner-sub authorization is enforced again by the private backend.
        path = "/api/customer/owner-history/v1/progress"
        for method, endpoint in (
            ("POST", path), ("GET", path + "/raw"),
            ("GET", "/api/customer/owner-history/v1/archive"),
        ):
            with mock.patch.object(proxy.urllib.request, "urlopen") as urlopen:
                response = proxy.handler(event(method, endpoint), None)
            self.assertEqual(response["statusCode"], 404)
            urlopen.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen") as urlopen:
            denied = proxy.handler(event("GET", path, jwt={"claims": {}}), None)
        self.assertEqual(denied["statusCode"], 401)
        urlopen.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as urlopen:
            response = proxy.handler(event("GET", path), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(response["headers"]["cache-control"], "no-store")
        req = urlopen.call_args.args[0]
        self.assertEqual(req.full_url, proxy.BACKEND + path)
        self.assertEqual(req.get_method(), "GET")
        self.assertEqual(req.headers.get("Authorization"), "Bearer public-test-token")
        self.assertIsNone(req.data)

        # Backend owner-sub denial must not be downgraded to a public success.
        rejection = proxy.urllib.error.HTTPError(
            proxy.BACKEND + path, 403, "Forbidden", {},
            io.BytesIO(b'{"error":"OWNER_ONLY"}'))
        with mock.patch.object(proxy.urllib.request, "urlopen", side_effect=rejection):
            response = proxy.handler(event("GET", path), None)
        self.assertEqual(response["statusCode"], 403)
        self.assertEqual(json.loads(response["body"])["error"], "OWNER_ONLY")
        self.assertEqual(response["headers"]["cache-control"], "no-store")

    def test_owner_history_daily_chart_is_owner_checked_read_only(self):
        # Lambda authenticates Cognito; backend enforces exact owner subject,
        # instrument identity, bounded date range and private archive integrity.
        path = "/api/customer/owner-history/v1/candles"
        self.assertTrue(proxy.allowed("GET", path))
        self.assertTrue(proxy.allowed("GET", "/api/customer/owner-history/v1/monthly-candles"))
        self.assertTrue(proxy.allowed("GET", "/api/customer/owner-history/v1/intraday-candles"))
        self.assertFalse(proxy.allowed("POST", "/api/customer/owner-history/v1/intraday-candles"))
        self.assertFalse(proxy.allowed("GET", "/api/customer/owner-history/v1/intraday-candles/raw"))
        self.assertFalse(proxy.allowed("POST", "/api/customer/owner-history/v1/monthly-candles"))
        self.assertFalse(proxy.allowed("GET", "/api/customer/owner-history/v1/monthly-candles/raw"))
        for method, endpoint in (
            ("POST", path), ("PUT", path), ("DELETE", path),
            ("GET", path + "/raw"),
            ("GET", "/api/customer/owner-history/v1/archive"),
        ):
            with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
                result = proxy.handler(event(method, endpoint), None)
            self.assertEqual(result["statusCode"], 404)
            upstream.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
            denied = proxy.handler(event("GET", path, jwt={"claims": {}}), None)
        self.assertEqual(denied["statusCode"], 401)
        upstream.assert_not_called()

        query = "instrumentId=123&fromDate=2026-01-01&toDate=2026-11-20"
        request_event = event("GET", path)
        request_event["rawQueryString"] = query
        with mock.patch.object(proxy.urllib.request, "urlopen",
                               return_value=Reply()) as upstream:
            response = proxy.handler(request_event, None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(response["headers"]["cache-control"], "no-store")
        forwarded = upstream.call_args.args[0]
        self.assertEqual(forwarded.full_url, proxy.BACKEND + path + "?" + query)
        self.assertEqual(forwarded.get_method(), "GET")
        self.assertEqual(forwarded.headers.get("Authorization"),
                         "Bearer public-test-token")
        self.assertIsNone(forwarded.data)

        # A different customer's token must not be elevated by the edge.
        rejection = proxy.urllib.error.HTTPError(
            proxy.BACKEND + path, 403, "Forbidden", {},
            io.BytesIO(b'{"error":"OWNER_ONLY"}'))
        with mock.patch.object(proxy.urllib.request, "urlopen",
                               side_effect=rejection):
            denied = proxy.handler(request_event, None)
        self.assertEqual(denied["statusCode"], 403)
        self.assertEqual(json.loads(denied["body"])["error"], "OWNER_ONLY")
        self.assertEqual(denied["headers"]["cache-control"], "no-store")

    def test_upstox_analytics_monitor_accepts_only_owner_checked_get(self):
        # Route is HTTPS Cognito-authorized and the Spring backend validates
        # exact JWT subject against MINTTICK_ANGEL_ONE_OWNER_SUB.
        # The proxy must never expose analytics probe/admin endpoints.
        path = "/api/customer/owner-upstox-analytics/v1/progress"
        forbidden = (
            ("POST", path),
            ("PUT", path),
            ("DELETE", path),
            ("GET", path + "/raw"),
            ("GET", "/api/admin/market-data/upstox-analytics/probe"),
        )
        for method, endpoint in forbidden:
            with mock.patch.object(proxy.urllib.request, "urlopen") as urlopen:
                response = proxy.handler(event(method, endpoint), None)
            self.assertEqual(response["statusCode"], 404, endpoint)
            urlopen.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen") as urlopen:
            response = proxy.handler(event("GET", path, jwt={"claims": {}}), None)
        self.assertEqual(response["statusCode"], 401)
        urlopen.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as urlopen:
            response = proxy.handler(event("GET", path), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(response["headers"]["cache-control"], "no-store")
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, proxy.BACKEND + path)
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.headers.get("Authorization"), "Bearer public-test-token")
        self.assertIsNone(request.data)

        backend_owner_denial = proxy.urllib.error.HTTPError(
            proxy.BACKEND + path, 403, "Forbidden", {},
            io.BytesIO(b'{"error":"OWNER_ONLY"}'))
        with mock.patch.object(proxy.urllib.request, "urlopen",
                               side_effect=backend_owner_denial):
            response = proxy.handler(event("GET", path), None)
        self.assertEqual(response["statusCode"], 403)
        self.assertEqual(json.loads(response["body"])["error"], "OWNER_ONLY")
        self.assertEqual(response["headers"]["cache-control"], "no-store")

    def test_persisted_upstox_receipts_are_owner_checked_and_get_only(self):
        path = "/api/customer/owner-upstox-analytics/v1/persisted-receipts"
        for method, endpoint in (
                ("POST", path), ("DELETE", path), ("PUT", path),
                ("GET", path + "/raw"),
                ("GET", "/api/admin/market-data/upstox-analytics/receipts")):
            with mock.patch.object(proxy.urllib.request, "urlopen") as forwarded:
                result = proxy.handler(event(method, endpoint), None)
            self.assertEqual(result["statusCode"], 404)
            forwarded.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen") as forwarded:
            result = proxy.handler(event("GET", path, jwt={"claims": {}}), None)
        self.assertEqual(result["statusCode"], 401)
        forwarded.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as forwarded:
            result = proxy.handler(event("GET", path), None)
        self.assertEqual(result["statusCode"], 200)
        self.assertEqual(result["headers"]["cache-control"], "no-store")
        request = forwarded.call_args.args[0]
        self.assertEqual(request.full_url, proxy.BACKEND + path)
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.headers.get("Authorization"), "Bearer public-test-token")
        self.assertIsNone(request.data)

        rejection = proxy.urllib.error.HTTPError(
            proxy.BACKEND + path, 403, "Forbidden", {},
            io.BytesIO(b'{"error":"OWNER_ONLY"}'))
        with mock.patch.object(proxy.urllib.request, "urlopen", side_effect=rejection):
            denied = proxy.handler(event("GET", path), None)
        self.assertEqual(denied["statusCode"], 403)
        self.assertEqual(json.loads(denied["body"])["error"], "OWNER_ONLY")

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

    def test_room_rename_put_forwards_only_validated_name_with_owner_jwt(self):
        room = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012"
        endpoint = room + "/name"
        self.assertTrue(proxy.allowed("PUT", endpoint))
        self.assertFalse(proxy.allowed("POST", endpoint))
        self.assertFalse(proxy.allowed("PUT", room + "/orders/place"))
        self.assertFalse(proxy.allowed("PUT", "/api/customer/trading-rooms"))

        for payload in (
                "", "{}", "[]", "null", "broken-json",
                '{"name":null}', '{"name":12}', '{"name":""}',
                '{"name":"   "}', '{"name":"\\u0001"}',
                '{"name":"OK","id":"do-not-change"}',
                json.dumps({"name": "x" * 81})):
            with self.subTest(payload=payload):
                with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                    response = proxy.handler(
                        event("PUT", endpoint, body=payload), None)
                self.assertEqual(response["statusCode"], 400)
                send.assert_not_called()

        valid = json.dumps({"name": "Swing Trade Updated"})
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            invalid_auth = proxy.handler(event("PUT", endpoint,
                jwt={"claims":{}}, body=valid), None)
        self.assertEqual(invalid_auth["statusCode"], 401)
        send.assert_not_called()

        expected_id = "12345678-1234-1234-1234-123456789012"
        class RenameReply(Reply):
            def read(self, max_bytes):
                return json.dumps({
                    "id": expected_id, "name": "Swing Trade Updated",
                    "mode": "PAPER", "status": "PAPER_FUNDED",
                    "focus": "SWING", "plannedBudgetInr": 30000,
                    "riskPreference": "BALANCED",
                }).encode()

        with mock.patch.object(proxy.urllib.request, "urlopen",
                               return_value=RenameReply()) as send:
            response = proxy.handler(event("PUT", endpoint, body=valid), None)
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(response["headers"]["cache-control"], "no-store")
        self.assertEqual(json.loads(response["body"])["name"],
                         "Swing Trade Updated")
        request = send.call_args.args[0]
        self.assertEqual(request.get_method(), "PUT")
        self.assertEqual(request.full_url, proxy.BACKEND + endpoint)
        self.assertEqual(request.get_header("Authorization"),
                         "Bearer public-test-token")
        self.assertEqual(request.get_header("Content-type"), "application/json")
        self.assertEqual(json.loads(request.data), {"name": "Swing Trade Updated"})

    def test_rename_backend_404_is_preserved_only_after_forwarding_to_owner_api(self):
        endpoint = ("/api/customer/trading-rooms/"
                    "12345678-1234-1234-1234-123456789012/name")
        error = proxy.urllib.error.HTTPError(
            proxy.BACKEND + endpoint, 404, "Not Found",
            {}, io.BytesIO(b'{"code":"ROOM_NOT_FOUND"}'))
        with mock.patch.object(proxy.urllib.request, "urlopen",
                               side_effect=error) as send:
            response = proxy.handler(event("PUT", endpoint,
                body='{"name":"New Swing"}'), None)
        self.assertEqual(response["statusCode"], 404)
        self.assertEqual(json.loads(response["body"])["code"], "ROOM_NOT_FOUND")
        send.assert_called_once()

    def test_live_broker_selection_is_only_an_owner_preference_not_order_route(self):
        room = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012"
        endpoint = room + "/execution/broker-selection"
        self.assertTrue(proxy.allowed("POST", endpoint))
        self.assertFalse(proxy.allowed("GET", endpoint))
        self.assertFalse(proxy.allowed("DELETE", endpoint))
        self.assertFalse(proxy.allowed("POST", room + "/orders/execute"))
        for payload in (
                "", "{}", '{"broker":"ZERODHA"}',
                '{"broker":"UPSTOX","token":"secret"}',
                '{"broker":null}', '{"broker":["UPSTOX"]}'):
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                result = proxy.handler(event("POST", endpoint, body=payload), None)
                self.assertEqual(result["statusCode"], 400, payload)
                send.assert_not_called()
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            result = proxy.handler(event("POST", endpoint,
                jwt={"claims":{}}, body='{"broker":"UPSTOX"}'), None)
            self.assertEqual(result["statusCode"], 401)
            send.assert_not_called()
        with mock.patch.object(proxy.urllib.request, "urlopen",
            return_value=Reply()) as send:
            result = proxy.handler(event("POST", endpoint,
                body='{"broker":"UPSTOX"}'), None)
            self.assertEqual(result["statusCode"], 200)
            request = send.call_args.args[0]
            self.assertEqual(request.get_method(), "POST")
            self.assertEqual(request.get_header("Authorization"),
                             "Bearer public-test-token")
            self.assertEqual(json.loads(request.data), {"broker": "UPSTOX"})

    def test_paper_manual_order_requires_jwt_and_idempotency_no_live_routes(self):
        room = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012"
        paper = room + "/paper"
        for action in ("account", "ledger", "orders", "positions"):
            self.assertTrue(proxy.allowed("GET", paper + "/" + action))
        for action in ("activate", "resume", "pause", "orders"):
            self.assertTrue(proxy.allowed("POST", paper + "/" + action))
        self.assertTrue(proxy.allowed("GET", room + "/execution/readiness"))
        for method,path in (
                ("GET",paper+"/pause"),("POST",paper+"/positions"),
                ("POST",room+"/orders/execute"),("POST",room+"/orders/place"),
                ("POST","/api/customer/brokers/upstox/orders")):
            self.assertFalse(proxy.allowed(method,path))

        payload = json.dumps({"instrumentId":42,"side":"BUY",
                              "quantity":1,"limitPrice":100.05})
        with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
            wrong = proxy.handler(event("POST",paper+"/orders",body=payload),None)
        self.assertEqual(wrong["statusCode"],400)
        upstream.assert_not_called()

        request = event("POST",paper+"/orders",body=payload)
        request["headers"]["idempotency-key"]="paper_test_request_1234"
        with mock.patch.object(proxy.urllib.request, "urlopen",return_value=Reply()) as upstream:
            ok=proxy.handler(request,None)
        self.assertEqual(ok["statusCode"],200)
        self.assertEqual(ok["headers"]["cache-control"],"no-store")
        req=upstream.call_args.args[0]
        self.assertEqual(req.get_method(),"POST")
        self.assertEqual(req.get_header("Idempotency-key"),"paper_test_request_1234")
        self.assertEqual(req.headers.get("Authorization"),"Bearer public-test-token")
        self.assertEqual(json.loads(req.data),json.loads(payload))

        with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
            denied=proxy.handler(event("POST",paper+"/orders",jwt={"claims":{}},
                body=payload),None)
        self.assertEqual(denied["statusCode"],401)
        upstream.assert_not_called()

    def test_mind_fno_live_proposal_is_read_only_and_jwt_required(self):
        path="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/mind/fno/live-proposal"
        self.assertTrue(proxy.allowed("GET", path))
        for method in ("POST","PUT","DELETE"):
            self.assertFalse(proxy.allowed(method,path))
        self.assertFalse(proxy.allowed("GET",path+"/orders"))
        self.assertFalse(proxy.allowed("GET","/api/customer/trading-rooms/invalid/mind/fno/live-proposal"))
        with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
            denied=proxy.handler(event("GET",path,jwt={"claims":{}}),None)
        self.assertEqual(denied["statusCode"],401)
        upstream.assert_not_called()

    def test_mind_fno_paper_automation_is_room_scoped_and_no_live(self):
        base="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/mind/fno/paper-automation"
        self.assertTrue(proxy.allowed("GET",base))
        self.assertTrue(proxy.allowed("GET",base+"/history"))
        self.assertTrue(proxy.allowed("POST",base+"/control"))
        self.assertFalse(proxy.allowed("POST",base))
        self.assertFalse(proxy.allowed("GET",base+"/control"))
        self.assertFalse(proxy.allowed("POST",base+"/history"))
        self.assertFalse(proxy.allowed("POST",base+"/live"))
        self.assertFalse(proxy.allowed("GET","/api/customer/trading-rooms/bad/mind/fno/paper-automation"))
        with mock.patch.object(proxy.urllib.request,"urlopen") as remote:
            denied=proxy.handler(event("POST",base+"/control",jwt={"claims":{}}),None)
        self.assertEqual(denied["statusCode"],401)
        remote.assert_not_called()

    def test_mind_fno_virtual_cycle_is_post_only_and_jwt_required(self):
        path="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/mind/fno/paper-cycles"
        self.assertTrue(proxy.allowed("POST",path))
        for method in ("GET","PUT","DELETE"):
            self.assertFalse(proxy.allowed(method,path))
        self.assertFalse(proxy.allowed("POST",path+"/live"))
        self.assertFalse(proxy.allowed("POST","/api/customer/trading-rooms/not-a-uuid/mind/fno/paper-cycles"))
        with mock.patch.object(proxy.urllib.request,"urlopen") as upstream:
            response=proxy.handler(event("POST",path,jwt={"claims":{}}),None)
        self.assertEqual(response["statusCode"],401)
        upstream.assert_not_called()

    def test_mind_fno_research_is_authenticated_and_get_only(self):
        path = "/api/customer/mind/v1/fno-evaluation"
        self.assertTrue(proxy.allowed("GET", path))
        for method in ("POST", "PUT", "DELETE"):
            self.assertFalse(proxy.allowed(method, path))
        self.assertFalse(proxy.allowed("GET", path + "/orders"))
        with mock.patch.object(proxy.urllib.request, "urlopen") as urlopen:
            denied = proxy.handler(event("GET", path, jwt={"claims": {}}), None)
        self.assertEqual(denied["statusCode"], 401)
        urlopen.assert_not_called()

    def test_mind_research_engines_is_get_only(self):
        path="/api/customer/mind/v1/research-engines"
        self.assertTrue(proxy.allowed("GET",path))
        self.assertFalse(proxy.allowed("POST",path))
        self.assertFalse(proxy.allowed("PUT",path))

    def test_mind_assistant_accepts_only_a_question_and_is_read_only(self):
        path="/api/customer/mind/v1/assistant/query"
        self.assertTrue(proxy.allowed("POST",path))
        self.assertFalse(proxy.allowed("GET",path))
        self.assertFalse(proxy.allowed("POST",path+"/execute"))
        for body in (json.dumps({"question":"x"*281}),json.dumps({"question":""}),
                     json.dumps({"question":"buy 1","side":"BUY"}),json.dumps({"question":"bad\u0001"}),
                     json.dumps({"question":42})):
            with mock.patch.object(proxy.urllib.request,"urlopen") as upstream:
                self.assertEqual(proxy.handler(event("POST",path,body=body),None)["statusCode"],400,body)
            upstream.assert_not_called()
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as upstream:
            self.assertEqual(proxy.handler(event("POST",path,body=json.dumps({"question":"what is my pnl?"})),None)["statusCode"],200)
        self.assertEqual(json.loads(upstream.call_args.args[0].data),{"question":"what is my pnl?"})

    def test_mind_reads_cycle_and_notes_are_exact_and_never_write_status(self):
        for path in ("overview","evidence","strategy-replay","ma-walkforward","prospective-cohort",
                     "prospective-signals","prospective-outcomes","prospective-evidence-completeness",
                     "progress","paper-portfolio","paper-journal"):
            self.assertTrue(proxy.allowed("GET","/api/customer/mind/v1/"+path),path)
            self.assertFalse(proxy.allowed("POST","/api/customer/mind/v1/"+path),path)
        item="/api/customer/mind/v1/progress/items/strategy.STR-001"
        self.assertTrue(proxy.allowed("GET",item))
        self.assertTrue(proxy.allowed("GET","/api/customer/mind/v1/progress/items/paper.vertical-slice"))
        self.assertTrue(proxy.allowed("POST",item+"/notes"))
        for method,path in (("POST",item),("PUT",item),("DELETE",item),("GET",item+"/notes"),
                ("POST",item+"/status"),("GET","/api/customer/mind/v1/progress/items/../admin"),
                ("GET","/api/customer/mind/v1/progress/items/strategy..x"),
                ("POST","/api/customer/mind/v1/admin"),("GET","/api/customer/mind/v1/demo")):
            self.assertFalse(proxy.allowed(method,path),(method,path))
        cycles="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/mind/paper-cycles"
        self.assertTrue(proxy.allowed("GET",cycles))
        self.assertTrue(proxy.allowed("POST",cycles))
        self.assertFalse(proxy.allowed("PUT",cycles))
        self.assertFalse(proxy.allowed("POST",cycles+"/live"))

        good=json.dumps({"instrumentId":7,"interval":"FIFTEEN_MINUTE"})
        for headers,body in (({},good),
                ({"idempotency-key":"mindcycle-abcdef12"},json.dumps({"instrumentId":7,"interval":"ONE_MINUTE"})),
                ({"idempotency-key":"mindcycle-abcdef12"},json.dumps({"instrumentId":7,"interval":"ONE_DAY","side":"BUY"})),
                ({"idempotency-key":"mindcycle-abcdef12"},json.dumps({"instrumentId":"7","interval":"ONE_DAY"}))):
            request=event("POST",cycles,body=body)
            request["headers"].update(headers)
            with mock.patch.object(proxy.urllib.request,"urlopen") as upstream:
                self.assertEqual(proxy.handler(request,None)["statusCode"],400)
            upstream.assert_not_called()
        request=event("POST",cycles,body=good)
        request["headers"]["idempotency-key"]="mindcycle-abcdef12"
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as upstream:
            self.assertEqual(proxy.handler(request,None)["statusCode"],200)
        req=upstream.call_args.args[0]
        self.assertEqual(req.get_header("Idempotency-key"),"mindcycle-abcdef12")
        self.assertEqual(json.loads(req.data),json.loads(good))

        for body in (json.dumps({"type":"RUNTIME_VERIFIED"}),json.dumps({"type":"NOTE","status":"DONE"}),
                     json.dumps({"type":"NOTE","text":"x"*501})):
            request=event("POST",item+"/notes",body=body)
            request["headers"]["idempotency-key"]="mind-note-12345678"
            with mock.patch.object(proxy.urllib.request,"urlopen") as upstream:
                self.assertEqual(proxy.handler(request,None)["statusCode"],400)
            upstream.assert_not_called()
        request=event("POST",item+"/notes",body=json.dumps({"type":"RECHECK_REQUESTED"}))
        request["headers"]["idempotency-key"]="mind-note-12345678"
        with mock.patch.object(proxy.urllib.request,"urlopen",return_value=Reply()) as upstream:
            self.assertEqual(proxy.handler(request,None)["statusCode"],200)
        self.assertEqual(upstream.call_args.args[0].get_header("Idempotency-key"),"mind-note-12345678")
        with mock.patch.object(proxy.urllib.request,"urlopen") as upstream:
            denied=proxy.handler(event("GET","/api/customer/mind/v1/progress",jwt={"claims":{}}),None)
        self.assertEqual(denied["statusCode"],401)
        upstream.assert_not_called()

    def test_paper_activation_and_pause_never_accept_request_bodies(self):
        paper="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper"
        for action in ("activate","resume","pause"):
            with mock.patch.object(proxy.urllib.request,"urlopen") as upstream:
                result=proxy.handler(event("POST",paper+"/"+action,
                    body='{"enableLiveTrading":true}'),None)
            self.assertEqual(result["statusCode"],400)
            upstream.assert_not_called()

    def test_upstox_owner_secure_login_never_accepts_client_credentials(self):
        path = "/api/customer/brokers/upstox/connect-configured"
        with mock.patch.object(proxy.urllib.request, "urlopen") as backend:
            no_jwt = proxy.handler(event("POST", path, jwt={"claims":{}}), None)
            with_pin = proxy.handler(event("POST", path,
                body='{"pin":"123456","totp":"123456"}'), None)
        self.assertEqual(no_jwt["statusCode"], 401)
        self.assertEqual(with_pin["statusCode"], 400)
        backend.assert_not_called()

        with mock.patch.object(proxy.urllib.request, "urlopen",
                               return_value=Reply()) as backend:
            ok = proxy.handler(event("POST", path), None)
        self.assertEqual(ok["statusCode"], 200)
        self.assertEqual(ok["headers"]["cache-control"], "no-store")
        req=backend.call_args.args[0]
        self.assertEqual(req.data, b"")  # POST with zero credential bytes
        self.assertEqual(req.headers.get("Authorization"), "Bearer public-test-token")

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
            self.assertIn("unexpected authorization response", result["body"])
            if query:
                self.assertNotIn(query, result["body"])
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
        self.assertIn("Refresh connection",result["body"])
        self.assertNotIn("secret",result["body"])

    def test_callback_displays_only_allowlisted_internal_failure_categories(self):
        query="state="+"A"*43+"&code=one-use-value"
        event_data=event("GET",proxy.UPSTOX_CALLBACK,jwt={"claims":{}},bearer="")
        event_data["rawQueryString"]=query
        cases=(
            ("STATE_INVALID", "secure login session expired"),
            ("CODE_INVALID", "authorization code is invalid"),
            ("APP_CREDENTIALS", "developer application's credentials"),
            ("REDIRECT_REJECTED", "registered callback URL"),
            ("SEGMENTS_INACTIVE", "inactive trading segments"),
            ("PROFILE_REJECTED", "active account profile"),
            ("ALREADY_CONNECTED", "another MintTick user"),
            ("INVALID_TOKEN_RESPONSE", "unexpected token response"),
            ("TOKEN_CLIENT_FAILURE", "token-exchange client error"),
            ("BROKER_UNAVAILABLE", "token exchange connection"),
            ("UNVERIFIED", "Refresh connection"),
        )
        for outcome,expected in cases:
            error=proxy.urllib.error.HTTPError(
                "http://private-backend",400,"Bad Request",
                {"X-MintTick-Upstox-Outcome":outcome},
                io.BytesIO(b"<html>authorization-code=secret-do-not-reflect</html>"))
            with mock.patch.object(proxy.urllib.request,"urlopen",side_effect=error):
                result=proxy.handler(event_data,None)
            self.assertEqual(result["statusCode"],400,outcome)
            self.assertIn(expected,result["body"])
            self.assertNotIn(query,result["body"])
            self.assertNotIn("secret-do-not-reflect",result["body"])
            self.assertEqual(result["headers"]["cache-control"],"no-store")

    def test_callback_rejects_forged_broker_response_header(self):
        event_data=event("GET",proxy.UPSTOX_CALLBACK,jwt={"claims":{}},bearer="")
        event_data["rawQueryString"]="state="+"A"*43+"&code=one-use"
        invalid_header="STATE_INVALID <img src=https://example.com>"
        error=proxy.urllib.error.HTTPError(
            "http://private-backend",400,"Bad Request",
            {"X-MintTick-Upstox-Outcome":invalid_header},
            io.BytesIO(b"token=private-secret"))
        with mock.patch.object(proxy.urllib.request,"urlopen",side_effect=error):
            result=proxy.handler(event_data,None)
        self.assertEqual(result["statusCode"],400)
        self.assertIn("Refresh connection",result["body"])
        self.assertNotIn("<img",result["body"])
        self.assertNotIn("private-secret",result["body"])

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

    def test_fno_paper_reconciliation_routes_are_authenticated_get_only(self):
        room="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper/fno/"
        for suffix in ("capability", "reconciliation", "wallet-reconciliation"):
            endpoint=room+suffix
            self.assertTrue(proxy.allowed("GET", endpoint))
            for method in ("POST", "PUT", "PATCH", "DELETE", "OPTIONS"):
                self.assertFalse(proxy.allowed(method, endpoint))
                with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
                    rejected=proxy.handler(event(method,endpoint),None)
                self.assertEqual(rejected["statusCode"],404)
                upstream.assert_not_called()
            for invalid in (endpoint+"/raw", endpoint+"/status", endpoint+"?admin=true"):
                self.assertFalse(proxy.allowed("GET", invalid))
            with mock.patch.object(proxy.urllib.request, "urlopen") as upstream:
                denied=proxy.handler(event("GET",endpoint,jwt={"claims":{}}),None)
            self.assertEqual(denied["statusCode"],401)
            upstream.assert_not_called()
            with mock.patch.object(proxy.urllib.request, "urlopen",return_value=Reply()) as upstream:
                ok=proxy.handler(event("GET",endpoint),None)
            self.assertEqual(ok["statusCode"],200)
            self.assertEqual(ok["headers"]["cache-control"],"no-store")
            req=upstream.call_args.args[0]
            self.assertEqual(req.full_url,proxy.BACKEND+endpoint)
            self.assertEqual(req.get_method(),"GET")
            self.assertEqual(req.get_header("Authorization"),"Bearer public-test-token")
            self.assertIsNone(req.data)
            backend_denial=proxy.urllib.error.HTTPError(
                proxy.BACKEND+endpoint,404,"Not Found",{},
                io.BytesIO(b'{"error":"PAPER_ROOM_NOT_FOUND"}'))
            with mock.patch.object(proxy.urllib.request,"urlopen",side_effect=backend_denial):
                result=proxy.handler(event("GET",endpoint),None)
            self.assertEqual(result["statusCode"],404)
            self.assertEqual(json.loads(result["body"])["error"],"PAPER_ROOM_NOT_FOUND")
        self.assertFalse(proxy.allowed("GET",room+"admin"))
        self.assertFalse(proxy.allowed("GET",room+"wallet-reconciliation/live"))
        self.assertFalse(proxy.allowed("GET",room+"reconciliation/orders"))

    def test_fno_paper_post_order_idempotency_reaches_backend_without_live_trade(self):
        route = ("/api/customer/trading-rooms/"
                 "12345678-1234-1234-1234-123456789012/paper/fno/orders")
        payload = {"instrumentKey": "NSE_FO|61001", "expiry": "2026-11-17",
                   "side": "BUY", "lots": 1, "limitPrice": 100.05}
        request = event("POST", route, body=json.dumps(payload))
        request["headers"]["Idempotency-Key"] = "fno_buy_synthetic_001"
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as send:
            ok = proxy.handler(request, None)
        self.assertEqual(ok["statusCode"], 200)
        self.assertEqual(ok["headers"]["cache-control"], "no-store")
        send.assert_called_once()
        outgoing = send.call_args.args[0]
        self.assertEqual(outgoing.get_method(), "POST")
        self.assertEqual(outgoing.full_url, proxy.BACKEND + route)
        self.assertEqual(outgoing.get_header("Authorization"), "Bearer public-test-token")
        self.assertEqual(outgoing.get_header("Idempotency-key"), "fno_buy_synthetic_001")
        self.assertEqual(json.loads(outgoing.data), payload)

        sell = event("POST", route, body=json.dumps({**payload, "side": "SELL",
                     "limitPrice": 99.95}))
        sell["headers"]["idempotency-key"] = "fno_sell_synthetic_01"
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as send:
            result = proxy.handler(sell, None)
        self.assertEqual(result["statusCode"], 200)
        self.assertEqual(send.call_args.args[0].get_header("Idempotency-key"),
                         "fno_sell_synthetic_01")
        self.assertEqual(json.loads(send.call_args.args[0].data)["side"], "SELL")

        # Fail-closed before the proxy opens the private connection.
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            rejected = proxy.handler(event("POST",route,body=json.dumps(payload)),None)
        self.assertEqual(rejected["statusCode"], 400)
        self.assertIn("INVALID_FNO_PAPER_ORDER_KEY", rejected["body"])
        send.assert_not_called()
        for key in ("short", "unsafe input", "a"*65, "foo\\r\\nInjected:1"):
            bad = event("POST",route,body=json.dumps(payload))
            bad["headers"]["idempotency-key"]=key
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                denied=proxy.handler(bad,None)
            self.assertEqual(denied["statusCode"],400,key)
            send.assert_not_called()

        invalid = (
            {},
            {**payload, "side": "SHORT"},
            {**payload, "instrumentKey": "NSE_EQ|61001"},
            {**payload, "instrumentKey": "NSE_FO|bad/other"},
            {**payload, "expiry": "2026-11-31"},
            {**payload, "expiry": "2026-11-17T09:15:00"},
            {**payload, "lots": 0},
            {**payload, "lots": 21},
            {**payload, "lots": True},
            {**payload, "lots": "1"},
            {**payload, "limitPrice": -1},
            {**payload, "limitPrice": 100.00001},
            {**payload, "limitPrice": 1000001},
            {**payload, "limitPrice": None},
            {**payload, "limitPrice": True},
            {**payload, "limitPrice": "100.00"},
            {**payload, "strategyId": "unauthorized"},
        )
        for order in invalid:
            malformed=event("POST",route,body=json.dumps(order))
            malformed["headers"]["idempotency-key"]="fno_bad_request_01"
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                denied=proxy.handler(malformed,None)
            self.assertEqual(denied["statusCode"],400,order)
            send.assert_not_called()
        for malformed_body in ("", "[]", "null", "{missing-json", "NaN"):
            malformed=event("POST",route,body=malformed_body)
            malformed["headers"]["idempotency-key"]="fno_bad_request_02"
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                denied=proxy.handler(malformed,None)
            self.assertEqual(denied["statusCode"],400,malformed_body)
            send.assert_not_called()

        unauthorized=event("POST",route,body=json.dumps(payload),jwt={"claims":{}})
        unauthorized["headers"]["idempotency-key"]="fno_access_denied"
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            denied=proxy.handler(unauthorized,None)
        self.assertEqual(denied["statusCode"],401)
        send.assert_not_called()
        for method,path in (
            ("POST",route.replace("/paper/fno/","/live/")),
            ("POST",route.replace("/paper/fno/","/paper/fno/live/")),
            ("POST",route.replace("/paper/fno/","/paper/fno/execute/")),
            ("PUT",route),("DELETE",route),("POST",route+"/execute")):
            self.assertFalse(proxy.allowed(method,path), (method,path))

    def test_fno_paper_order_routes_never_enable_live_or_admin(self):
        route="/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper/fno/"
        for method, suffix in (("POST","orders"),("GET","orders"),("GET","positions"),("GET","market"),("GET","expiries")):
            self.assertTrue(proxy.allowed(method,route+suffix))
        for method,suffix in (("POST","positions"),("POST","market"),("POST","expiries"),("PUT","market"),("DELETE","market"),("DELETE","orders"),("DELETE","expiries"),
                              ("PUT","orders"),("POST","live"),("GET","execute")):
            self.assertFalse(proxy.allowed(method,route+suffix))
        self.assertFalse(proxy.allowed("POST",
            "/api/admin/market-data/upstox-analytics/fno/live/start"))
        self.assertFalse(proxy.allowed("POST",
            "/api/customer/brokers/upstox/orders"))

    def test_synthetic_sandbox_is_jwt_scoped_and_rejects_live_or_nse_inputs(self):
        route = ("/api/customer/trading-rooms/"
                 "12345678-1234-1234-1234-123456789012/paper/fno/sandbox/")
        self.assertTrue(proxy.allowed("GET", route + "state"))
        self.assertTrue(proxy.allowed("POST", route + "orders"))
        for method, suffix in (("POST", "state"), ("GET", "orders"),
                               ("PUT", "orders"), ("DELETE", "orders"),
                               ("POST", "live"), ("GET", "market")):
            self.assertFalse(proxy.allowed(method, route + suffix))
        valid = {"contractCode": "SIM_FNO_CE", "side": "BUY", "lots": 1}
        order = event("POST", route + "orders", body=json.dumps(valid))
        order["headers"]["idempotency-key"] = "synth_safe_0001"
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as send:
            result = proxy.handler(order, None)
        self.assertEqual(result["statusCode"], 200)
        send.assert_called_once()
        sent = send.call_args.args[0]
        self.assertEqual(sent.get_header("Idempotency-key"), "synth_safe_0001")
        self.assertNotIn("upstox", sent.full_url.lower())
        for invalid in (
            {**valid, "contractCode": "NSE_FO|NIFTY"},
            {**valid, "contractCode": "SIM_FNO_BAD"},
            {**valid, "side": "SHORT"},
            {**valid, "lots": 0},
            {**valid, "lots": True},
            {**valid, "lots": 21},
            {**valid, "expiry": "2026-11-17"},
        ):
            attempt = event("POST", route + "orders", body=json.dumps(invalid))
            attempt["headers"]["idempotency-key"] = "synth_safe_0001"
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                result = proxy.handler(attempt, None)
            self.assertEqual(result["statusCode"], 400, invalid)
            send.assert_not_called()
        unauth = event("POST", route + "orders", jwt={"claims": {}},
                       body=json.dumps(valid))
        unauth["headers"]["idempotency-key"] = "synth_safe_0001"
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            self.assertEqual(proxy.handler(unauth, None)["statusCode"], 401)
            send.assert_not_called()

    def test_live_fno_requires_owner_jwt_explicit_confirmation_and_one_lot(self):
        base = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/live/fno"
        self.assertTrue(proxy.allowed("GET", base + "/capability"))
        self.assertTrue(proxy.allowed("GET", base + "/orders"))
        self.assertTrue(proxy.allowed("GET", base + "/expiries"))
        self.assertTrue(proxy.allowed("GET", base + "/market"))
        self.assertFalse(proxy.allowed("POST", base + "/market"))
        self.assertFalse(proxy.allowed("POST", base + "/expiries"))
        self.assertTrue(proxy.allowed("POST", base + "/orders"))
        self.assertTrue(proxy.allowed("POST", base + "/orders/12345678-1234-1234-1234-123456789012/reconcile"))
        for suffix in ("/market", "/expiries"):
            no_jwt = event("GET", base + suffix, jwt={"claims": {}})
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                self.assertEqual(proxy.handler(no_jwt, None)["statusCode"], 401)
                send.assert_not_called()
        for method, path in (
            ("POST", base + "/capability"),
            ("PUT", base + "/orders"),
            ("DELETE", base + "/orders"),
            ("GET", base + "/orders/12345678-1234-1234-1234-123456789012/reconcile"),
            ("POST", base + "/orders/execute"),
            ("POST", "/api/customer/brokers/upstox/orders"),
            ("POST", "/api/admin/market-data/upstox-analytics/fno/live/start"),
        ):
            self.assertFalse(proxy.allowed(method, path))
        payload = {"instrumentKey": "NSE_FO|12345", "expiry": "2026-11-17",
                   "side": "BUY", "lots": 1, "limitPrice": 120.05}
        attempt = event("POST", base + "/orders", body=json.dumps(payload))
        attempt["headers"]["idempotency-key"] = "real_live_0001"
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            denied = proxy.handler(attempt, None)
        self.assertEqual(denied["statusCode"], 428)
        send.assert_not_called()

        attempt["headers"]["x-confirm-live-order"] = "CONFIRM_LIVE_UPSTOX_NFO"
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as send:
            accepted = proxy.handler(attempt, None)
        self.assertEqual(accepted["statusCode"], 200)
        sent = send.call_args.args[0]
        self.assertEqual(sent.get_header("Idempotency-key"), "real_live_0001")
        self.assertEqual(sent.get_header("X-confirm-live-order"), "CONFIRM_LIVE_UPSTOX_NFO")
        self.assertTrue(sent.full_url.endswith(base + "/orders"))
        self.assertEqual(sent.get_method(), "POST")

        malformed = ({**payload, "lots": 2}, {**payload, "side": "SHORT"},
                     {**payload, "limitPrice": "120.05"},
                     {**payload, "instrumentKey": "SIM_FNO_CE"},
                     {**payload, "expiry": "2026-02-31"},
                     {**payload, "extra": "broker-execute"})
        for order in malformed:
            bad = event("POST", base + "/orders", body=json.dumps(order))
            bad["headers"].update(attempt["headers"])
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                response = proxy.handler(bad, None)
            self.assertEqual(response["statusCode"], 400, order)
            send.assert_not_called()

        unauthorized = event("POST", base + "/orders", jwt={"claims": {}},
                             body=json.dumps(payload))
        unauthorized["headers"].update(attempt["headers"])
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            self.assertEqual(proxy.handler(unauthorized, None)["statusCode"], 401)
            send.assert_not_called()

        reconcile = event("POST", base + "/orders/12345678-1234-1234-1234-123456789012/reconcile")
        with mock.patch.object(proxy.urllib.request, "urlopen", return_value=Reply()) as send:
            self.assertEqual(proxy.handler(reconcile, None)["statusCode"], 200)
        send.assert_called_once()
        reconcile["body"] = '{"retry":true}'
        with mock.patch.object(proxy.urllib.request, "urlopen") as send:
            self.assertEqual(proxy.handler(reconcile, None)["statusCode"], 400)
            send.assert_not_called()

    def test_fno_market_discovery_is_read_only_and_requires_owner_jwt(self):
        root = "/api/customer/markets/fno/"
        for route in ("capability", "expiries", "market"):
            path = root + route
            self.assertTrue(proxy.allowed("GET", path))
            for forbidden in ("POST", "PUT", "PATCH", "DELETE"):
                self.assertFalse(proxy.allowed(forbidden, path))
            attempt = event("GET", path, jwt={"claims": {}})
            with mock.patch.object(proxy.urllib.request, "urlopen") as send:
                result = proxy.handler(attempt, None)
            self.assertEqual(result["statusCode"], 401)
            send.assert_not_called()
            normal = event("GET", path)
            with mock.patch.object(proxy.urllib.request, "urlopen",
                                   return_value=Reply()) as send:
                result = proxy.handler(normal, None)
            self.assertEqual(result["statusCode"], 200)
            self.assertEqual(send.call_args.args[0].get_method(), "GET")
        # There is deliberately no broker order path beneath global discovery.
        self.assertFalse(proxy.allowed("POST", root + "orders"))
        self.assertFalse(proxy.allowed("GET", root + "orders"))

    def test_template_embeds_identical_reviewed_python_source(self):
        text=Path(__file__).with_name("bootstrap-account-api.yml").read_text()
        anchor="        ZipFile: |\n"
        self.assertEqual(text.count(anchor),1)
        after=text.split(anchor,1)[1].split("\n  AccountHttpApi:",1)[0]
        embedded="\n".join(row[10:] for row in after.rstrip("\n").split("\n")).rstrip()+"\n"
        self.assertEqual(embedded,SOURCE.read_text().rstrip()+"\n")

if __name__ == "__main__":
    unittest.main()
