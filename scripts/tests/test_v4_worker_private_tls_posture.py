"""V86 synthetic AWS metadata cases: private TLS is posture only, never release."""
from __future__ import annotations

import copy
import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v4_worker_private_tls_posture import evaluate  # noqa: E402

REGION = "us-east-1"
ACCOUNT = "816344830716"
CLUSTER = "ai-trading-system"
DOMAIN = "research-worker.maargglobal.com"
LB_ARN = (f"arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:"
          "loadbalancer/app/aitrading-rsrch-private/hash")
LISTENER_ARN = (f"arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:"
                "listener/app/aitrading-rsrch-private/hash/listenerid")
TG_ARN = (f"arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:"
          "targetgroup/aitrading-rsrch-8090/hash")
CERT_ARN = f"arn:aws:acm:{REGION}:{ACCOUNT}:certificate/unique"
LB_HOST = "internal-aitrading-rsrch-private-123.us-east-1.elb.amazonaws.com"
SHA = "a" * 40
VPC = "vpc-2345"
JAVA = "sg-java"
EDGE = "sg-edge"
WORKER = "sg-worker"
TEST_TIME = datetime(2026, 11, 5, tzinfo=timezone.utc)


def perm(port: int, source: str) -> dict:
    return {
        "IpProtocol": "tcp",
        "FromPort": port,
        "ToPort": port,
        "UserIdGroupPairs": [{"GroupId": source, "UserId": ACCOUNT}],
        "IpRanges": [],
        "Ipv6Ranges": [],
    }


def service(name: str, groups: list[str], desired: int = 0) -> dict:
    return {
        "serviceName": name,
        "status": "ACTIVE",
        "clusterArn": f"arn:aws:ecs:{REGION}:{ACCOUNT}:cluster/{CLUSTER}",
        "desiredCount": desired,
        "runningCount": 0,
        "pendingCount": 0,
        "enableExecuteCommand": False,
        "networkConfiguration": {
            "awsvpcConfiguration": {
                "subnets": ["subnet-one", "subnet-two"],
                "securityGroups": groups,
                "assignPublicIp": "DISABLED",
            },
        },
    }


def fixture() -> dict:
    return {
        "identity": {"Account": ACCOUNT, "Arn": "SENSITIVE-ID-OMITTED"},
        "load_balancers": {"LoadBalancers": [{
            "LoadBalancerName": "aitrading-rsrch-private",
            "LoadBalancerArn": LB_ARN,
            "Scheme": "internal",
            "Type": "application",
            "VpcId": VPC,
            "State": {"Code": "active"},
            "AvailabilityZones": [
                {"SubnetId": "subnet-one"}, {"SubnetId": "subnet-two"},
            ],
            "SecurityGroups": [EDGE],
            "DNSName": LB_HOST,
            "CanonicalHostedZoneId": "ZALBID",
        }]},
        "listeners": {"Listeners": [{
            "ListenerArn": LISTENER_ARN,
            "LoadBalancerArn": LB_ARN,
            "Port": 443,
            "Protocol": "HTTPS",
            "SslPolicy": "ELBSecurityPolicy-TLS13-1-2-2021-06",
            "DefaultActions": [{"Type": "fixed-response",
                                "FixedResponseConfig": {"StatusCode": "403"}}],
            "Certificates": [{"CertificateArn": CERT_ARN, "IsDefault": True}],
        }]},
        "rules": {"Rules": [
            {"RuleArn": LISTENER_ARN + "/rule1", "IsDefault": True,
             "Priority": "default",
             "Actions": [{"Type": "fixed-response",
                          "FixedResponseConfig": {"StatusCode": "403"}}]},
            {"RuleArn": LISTENER_ARN + "/rule2", "IsDefault": False,
             "Priority": "10",
             "Actions": [{"Type": "forward", "TargetGroupArn": TG_ARN}],
             "Conditions": [{"Field": "path-pattern",
                             "PathPatternConfig": {"Values": [
                                 "/v1/research/artifact-readiness",
                                 "/v1/research/artifact-inference",
                             ]}}],
             },
        ]},
        "target_groups": {"TargetGroups": [{
            "TargetGroupArn": TG_ARN,
            "TargetGroupName": "aitrading-rsrch-8090",
            "TargetType": "ip",
            "VpcId": VPC,
            "Port": 8090,
            "Protocol": "HTTP",
            "HealthCheckPath": "/health",
        }]},
        "certificate": {"Certificate": {
            "CertificateArn": CERT_ARN,
            "DomainName": DOMAIN,
            "Status": "ISSUED",
            "SubjectAlternativeNames": [DOMAIN],
            "NotAfter": "2027-01-01T00:00:00+00:00",
            "InUseBy": [LB_ARN],
            "SecretMaterial": "CONFIDENTIAL-DO-NOT-REPORT",
        }},
        "zones": {"HostedZones": [{
            "Name": DOMAIN + ".",
            "Id": "/hostedzone/ZPRIV",
            "Config": {"PrivateZone": True},
        }]},
        "private_zone": {"HostedZone": {
            "Name": DOMAIN + ".",
            "Id": "/hostedzone/ZPRIV",
            "Config": {"PrivateZone": True},
        }, "VPCs": [{"VPCId": VPC, "VPCRegion": REGION}]},
        "record_sets": {"ResourceRecordSets": [{
            "Name": DOMAIN + ".",
            "Type": "A",
            "AliasTarget": {
                "HostedZoneId": "ZALBID",
                "DNSName": LB_HOST + ".",
                "EvaluateTargetHealth": False,
            },
        }]},
        "security_groups": {"SecurityGroups": [
            {"GroupName": "ai-trading-system-worker-java-identity",
             "GroupId": JAVA, "VpcId": VPC, "IpPermissions": [],
             "IpPermissionsEgress": []},
            {"GroupName": "ai-trading-system-worker-https-alb",
             "GroupId": EDGE, "VpcId": VPC, "IpPermissions": [perm(443, JAVA)],
             "IpPermissionsEgress": [perm(8090, WORKER)]},
            {"GroupName": "ai-trading-system-research-worker",
             "GroupId": WORKER, "VpcId": VPC, "IpPermissions": [perm(8090, EDGE)],
             "IpPermissionsEgress": []},
        ]},
        "subnets": {"Subnets": [
            {"SubnetId": "subnet-one", "VpcId": VPC, "MapPublicIpOnLaunch": False},
            {"SubnetId": "subnet-two", "VpcId": VPC, "MapPublicIpOnLaunch": False},
        ]},
        "services": {"services": [
            service("ai-trading-system-backend", ["sg-tasks", JAVA]),
            service("ai-trading-system-frontend", ["sg-tasks"]),
            service("ai-trading-system-research-worker", [WORKER]),
        ], "failures": []},
        "expected_sha": SHA,
        "now": TEST_TIME,
    }


class WorkerPrivateTlsTopologyTest(unittest.TestCase):
    def blocked(self, data: dict, code: str):
        result = evaluate(**data)
        self.assertEqual(result["verdict"], "BLOCKED")
        self.assertIn(code, result["blocker_codes"])
        self.assertFalse(result["can_authorize_capital"])
        self.assertFalse(result["live_execution_enabled"])

    def test_complete_dormant_private_posture_never_authorizes_dispatch(self):
        data = fixture()
        result = evaluate(**data)
        self.assertTrue(result["private_worker_tls_metadata_verified"])
        self.assertEqual(result["verdict"],
                         "PRIVATE_TLS_TOPOLOGY_CONFIGURED_WORKER_DORMANT_ONLY")
        self.assertFalse(result["worker_running"])
        for flag in ("private_tls_handshake_from_java_verified",
                     "worker_artifacts_or_token_verified",
                     "paper_shadow_soak_accepted",
                     "independent_market_data_rights_verified",
                     "live_execution_enabled", "can_authorize_capital"):
            self.assertFalse(result[flag])
        public = json.dumps(result)
        for secret in ("CONFIDENTIAL-DO-NOT-REPORT", "SENSITIVE-ID-OMITTED",
                       LB_ARN, CERT_ARN, VPC, JAVA):
            self.assertNotIn(secret, public)

    def test_wrong_account_public_alb_and_public_subnet_block(self):
        f = fixture()
        f["identity"]["Account"] = "000000000000"
        self.blocked(f, "AWS_ACCOUNT_UNVERIFIED")
        f = fixture()
        f["load_balancers"]["LoadBalancers"][0]["Scheme"] = "internet-facing"
        self.blocked(f, "INTERNAL_ALB_UNVERIFIED")
        f = fixture()
        f["subnets"]["Subnets"][0]["MapPublicIpOnLaunch"] = True
        self.blocked(f, "PRIVATE_ALB_SUBNET_METADATA_UNVERIFIED")

    def test_untrusted_frontend_caller_or_public_rule_blocks(self):
        f = fixture()
        f["security_groups"]["SecurityGroups"][1]["IpPermissions"][0][
            "UserIdGroupPairs"][0]["GroupId"] = "sg-tasks"
        self.blocked(f, "ALB_INGRESS_EGRESS_UNVERIFIED")
        f = fixture()
        f["services"]["services"][1]["networkConfiguration"][
            "awsvpcConfiguration"]["securityGroups"].append(JAVA)
        self.blocked(f, "DORMANT_ECS_BACKEND_ISOLATION_UNVERIFIED")
        f = fixture()
        f["security_groups"]["SecurityGroups"][1]["IpPermissions"][0][
            "IpRanges"] = [{"CidrIp": "0.0.0.0/0"}]
        self.blocked(f, "ALB_INGRESS_EGRESS_UNVERIFIED")
        f = fixture()
        f["security_groups"]["SecurityGroups"][1]["IpPermissionsEgress"].append(
            perm(443, "sg-external")
        )
        self.blocked(f, "ALB_INGRESS_EGRESS_UNVERIFIED")

    def test_http_direct_worker_bypass_and_worker_startup_block(self):
        f = fixture()
        f["security_groups"]["SecurityGroups"][2]["IpPermissions"].append(
            perm(8090, JAVA)
        )
        self.blocked(f, "PYTHON_DIRECT_HTTP_BYPASS_UNVERIFIED")
        f = fixture()
        f["services"]["services"][2]["desiredCount"] = 1
        self.blocked(f, "DORMANT_ECS_BACKEND_ISOLATION_UNVERIFIED")
        f = fixture()
        f["services"]["services"][2]["enableExecuteCommand"] = True
        self.blocked(f, "DORMANT_ECS_BACKEND_ISOLATION_UNVERIFIED")

    def test_https_listener_and_only_exact_research_paths(self):
        f = fixture()
        f["listeners"]["Listeners"][0]["Protocol"] = "HTTP"
        self.blocked(f, "HTTPS_LISTENER_DEFAULT_DENY_UNVERIFIED")
        f = fixture()
        f["listeners"]["Listeners"][0]["DefaultActions"][0]["Type"] = "forward"
        self.blocked(f, "HTTPS_LISTENER_DEFAULT_DENY_UNVERIFIED")
        f = fixture()
        f["rules"]["Rules"][1]["Conditions"][0]["PathPatternConfig"]["Values"] = ["/*"]
        self.blocked(f, "RESEARCH_PATH_ALLOWLIST_UNVERIFIED")
        f = fixture()
        f["rules"]["Rules"].append(copy.deepcopy(f["rules"]["Rules"][1]))
        self.blocked(f, "RESEARCH_PATH_ALLOWLIST_UNVERIFIED")
        f = fixture()
        f["target_groups"]["TargetGroups"][0]["Protocol"] = "HTTPS"
        self.blocked(f, "PRIVATE_WORKER_TARGET_GROUP_UNVERIFIED")

    def test_unissued_or_expired_certificate_and_public_dns_fail(self):
        f = fixture()
        f["certificate"]["Certificate"]["Status"] = "PENDING_VALIDATION"
        self.blocked(f, "TRUSTED_ACM_CERTIFICATE_UNVERIFIED")
        f = fixture()
        f["certificate"]["Certificate"]["NotAfter"] = "2020-01-01T00:00:00+00:00"
        self.blocked(f, "TRUSTED_ACM_CERTIFICATE_UNVERIFIED")
        f = fixture()
        f["zones"]["HostedZones"][0]["Config"]["PrivateZone"] = False
        self.blocked(f, "VPC_ASSOCIATED_PRIVATE_DNS_ZONE_UNVERIFIED")
        f = fixture()
        f["private_zone"]["VPCs"][0]["VPCId"] = "vpc-wrong"
        self.blocked(f, "VPC_ASSOCIATED_PRIVATE_DNS_ZONE_UNVERIFIED")
        f = fixture()
        f["record_sets"]["ResourceRecordSets"][0]["AliasTarget"]["DNSName"] = (
            "public.attacker.example."
        )
        self.blocked(f, "PRIVATE_WORKER_DNS_ALIAS_UNVERIFIED")

    def test_missing_worker_never_passes_as_success(self):
        f = fixture()
        f["load_balancers"]["LoadBalancers"] = []
        self.blocked(f, "INTERNAL_ALB_UNVERIFIED")
        f = fixture()
        f["services"]["services"] = f["services"]["services"][:-1]
        self.blocked(f, "DORMANT_ECS_BACKEND_ISOLATION_UNVERIFIED")


if __name__ == "__main__":
    unittest.main()
