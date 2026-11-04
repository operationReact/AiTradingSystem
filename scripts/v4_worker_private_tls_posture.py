#!/usr/bin/env python3
"""V86: offline, sanitized inspection of a dormant private AWS worker HTTPS edge.

The supplied AWS JSON snapshots are *untrusted data*. This checks a limited
ALB/ACM/Route53/SG/ECS configuration, NOT a TLS handshake, ECR model custody,
application auth, an AWS deployment instruction or trading authorization.
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

ACCOUNT = "816344830716"
REGION = "us-east-1"
DOMAIN = "research-worker.maargglobal.com"
CLUSTER = "ai-trading-system"
ALB_NAME = "aitrading-rsrch-private"
TG_NAME = "aitrading-rsrch-8090"
SG_JAVA = "ai-trading-system-worker-java-identity"
SG_ALB = "ai-trading-system-worker-https-alb"
SG_WORKER = "ai-trading-system-research-worker"
SERVICE_BACKEND = "ai-trading-system-backend"
SERVICE_FRONTEND = "ai-trading-system-frontend"
SERVICE_WORKER = "ai-trading-system-research-worker"
ALLOW_PATHS = {
    "/v1/research/artifact-readiness",
    "/v1/research/artifact-inference",
}
VERSION = "v4-worker-private-tls-topology.v1"
SHA = re.compile(r"[a-f0-9]{40}\Z")


def _safe_one(items: object) -> dict:
    return items[0] if isinstance(items, list) and len(items) == 1 and (
        isinstance(items[0], dict)
    ) else {}


def _same_sg_rule(permission: dict, protocol: str, port: int, source: str) -> bool:
    if not isinstance(permission, dict):
        return False
    pairs = permission.get("UserIdGroupPairs", [])
    return (
        permission.get("IpProtocol") in (protocol, "6")
        and permission.get("FromPort") == port
        and permission.get("ToPort") == port
        and isinstance(pairs, list) and len(pairs) == 1
        and pairs[0].get("GroupId") == source
        and pairs[0].get("UserId") in (None, ACCOUNT)
        and not permission.get("IpRanges")
        and not permission.get("Ipv6Ranges")
        and not permission.get("PrefixListIds")
    )


def _sg_permissions(group: dict, kind: str) -> list:
    data = group.get(kind, [])
    return data if isinstance(data, list) else []


def _active_service(services: dict, name: str) -> dict:
    matches = [
        s for s in services.get("services", [])
        if isinstance(s, dict) and s.get("serviceName") == name
    ]
    return _safe_one(matches)


def evaluate(
    identity: dict, load_balancers: dict, listeners: dict, rules: dict,
    target_groups: dict, certificate: dict, zones: dict, private_zone: dict,
    record_sets: dict, security_groups: dict, subnets: dict, services: dict,
    expected_sha: str, now: datetime | None = None,
) -> dict:
    blockers: set[str] = set()

    def check(ok: bool, code: str) -> bool:
        if not ok:
            blockers.add(code)
        return bool(ok)

    now = now or datetime.now(timezone.utc)
    commit_ok = check(bool(SHA.fullmatch(expected_sha)), "COMMIT_IDENTITY_UNVERIFIED")
    account_ok = check(identity.get("Account") == ACCOUNT, "AWS_ACCOUNT_UNVERIFIED")

    alb = _safe_one(load_balancers.get("LoadBalancers"))
    lb_arn = alb.get("LoadBalancerArn", "")
    vpc = alb.get("VpcId", "")
    lb_az = alb.get("AvailabilityZones", [])
    alb_ok = check(
        alb.get("LoadBalancerName") == ALB_NAME
        and alb.get("Scheme") == "internal"
        and alb.get("Type") == "application"
        and alb.get("State", {}).get("Code") == "active"
        and isinstance(lb_arn, str)
        and lb_arn.startswith(
            f"arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:loadbalancer/app/{ALB_NAME}/"
        )
        and isinstance(vpc, str) and vpc.startswith("vpc-")
        and isinstance(lb_az, list) and len(lb_az) == 2
        and len({z.get("SubnetId") for z in lb_az}) == 2,
        "INTERNAL_ALB_UNVERIFIED",
    )

    required_subnets = {z.get("SubnetId") for z in lb_az if isinstance(z, dict)}
    subnet_rows = subnets.get("Subnets", [])
    matching_subnets = [
        row for row in subnet_rows if isinstance(row, dict)
        and row.get("SubnetId") in required_subnets
    ] if isinstance(subnet_rows, list) else []
    subnets_ok = check(
        alb_ok and len(matching_subnets) == 2
        and {row.get("SubnetId") for row in matching_subnets} == required_subnets
        and all(row.get("VpcId") == vpc and row.get("MapPublicIpOnLaunch") is False
                for row in matching_subnets),
        "PRIVATE_ALB_SUBNET_METADATA_UNVERIFIED",
    )

    groups = security_groups.get("SecurityGroups", [])
    groups = groups if isinstance(groups, list) else []
    byname = {g.get("GroupName"): g for g in groups if isinstance(g, dict)}
    java, edge, worker = (byname.get(name, {}) for name in (SG_JAVA, SG_ALB, SG_WORKER))
    java_id, edge_id, worker_id = (
        java.get("GroupId"), edge.get("GroupId"), worker.get("GroupId")
    )
    unique_groups = (
        len(groups) == 3 and len(byname) == 3
        and all(isinstance(x, str) and x.startswith("sg-")
                for x in (java_id, edge_id, worker_id))
        and len({java_id, edge_id, worker_id}) == 3
        and all(g.get("VpcId") == vpc for g in (java, edge, worker))
        and alb.get("SecurityGroups") == [edge_id]
    )
    sg_identity_ok = check(
        unique_groups
        and not _sg_permissions(java, "IpPermissions")
        and not _sg_permissions(java, "IpPermissionsEgress"),
        "DEDICATED_BACKEND_SG_UNVERIFIED",
    )
    alb_sg_ok = check(
        unique_groups
        and len(_sg_permissions(edge, "IpPermissions")) == 1
        and _same_sg_rule(edge["IpPermissions"][0], "tcp", 443, java_id)
        and len(_sg_permissions(edge, "IpPermissionsEgress")) == 1
        and _same_sg_rule(edge["IpPermissionsEgress"][0], "tcp", 8090, worker_id),
        "ALB_INGRESS_EGRESS_UNVERIFIED",
    )
    worker_sg_ok = check(
        unique_groups
        and len(_sg_permissions(worker, "IpPermissions")) == 1
        and _same_sg_rule(worker["IpPermissions"][0], "tcp", 8090, edge_id),
        "PYTHON_DIRECT_HTTP_BYPASS_UNVERIFIED",
    )

    listener = _safe_one(listeners.get("Listeners"))
    listener_arn = listener.get("ListenerArn", "")
    target = _safe_one(target_groups.get("TargetGroups"))
    target_arn = target.get("TargetGroupArn", "")
    tg_ok = check(
        target.get("TargetGroupName") == TG_NAME
        and target.get("Port") == 8090
        and target.get("Protocol") == "HTTP"
        and target.get("TargetType") == "ip"
        and target.get("VpcId") == vpc
        and target.get("HealthCheckPath") == "/health"
        and isinstance(target_arn, str)
        and target_arn.startswith(
            f"arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:targetgroup/{TG_NAME}/"
        ),
        "PRIVATE_WORKER_TARGET_GROUP_UNVERIFIED",
    )
    default = _safe_one(listener.get("DefaultActions"))
    listener_ok = check(
        alb_ok and listener.get("LoadBalancerArn") == lb_arn
        and isinstance(listener_arn, str) and listener_arn.startswith(lb_arn.replace(
            "loadbalancer/app/", "listener/app/"
        ).rsplit("/", 1)[0])
        and listener.get("Protocol") == "HTTPS"
        and listener.get("Port") == 443
        and listener.get("SslPolicy") == "ELBSecurityPolicy-TLS13-1-2-2021-06"
        and default.get("Type") == "fixed-response"
        and default.get("FixedResponseConfig", {}).get("StatusCode") == "403"
        and len(listener.get("Certificates", [])) == 1,
        "HTTPS_LISTENER_DEFAULT_DENY_UNVERIFIED",
    )
    default_cert = _safe_one(listener.get("Certificates")).get("CertificateArn")
    cert = certificate.get("Certificate", {})
    cert_valid_until = cert.get("NotAfter")
    try:
        if isinstance(cert_valid_until, (int, float)):
            expiration = datetime.fromtimestamp(cert_valid_until, timezone.utc)
        else:
            expiration = datetime.fromisoformat(cert_valid_until.replace("Z", "+00:00"))
        cert_not_expired = expiration > now
    except (ValueError, TypeError, OverflowError, AttributeError):
        cert_not_expired = False
    cert_ok = check(
        listener_ok and isinstance(default_cert, str)
        and default_cert.startswith(f"arn:aws:acm:{REGION}:{ACCOUNT}:certificate/")
        and cert.get("CertificateArn") == default_cert
        and cert.get("DomainName") == DOMAIN
        and cert.get("Status") == "ISSUED"
        and DOMAIN in cert.get("SubjectAlternativeNames", [])
        and lb_arn in cert.get("InUseBy", [])
        and cert_not_expired,
        "TRUSTED_ACM_CERTIFICATE_UNVERIFIED",
    )

    routing = rules.get("Rules", [])
    if not isinstance(routing, list):
        routing = []
    nondefault = [r for r in routing if isinstance(r, dict) and not r.get("IsDefault")]
    isdefault = [r for r in routing if isinstance(r, dict) and r.get("IsDefault")]
    forward = _safe_one(nondefault)
    path_condition = _safe_one(forward.get("Conditions"))
    paths = path_condition.get("PathPatternConfig", {}).get("Values", [])
    if not paths:
        paths = path_condition.get("Values", [])
    action = _safe_one(forward.get("Actions"))
    routing_ok = check(
        listener_ok and tg_ok and len(routing) == 2 and len(nondefault) == 1
        and len(isdefault) == 1
        and isdefault[0].get("RuleArn") != forward.get("RuleArn")
        and _safe_one(isdefault[0].get("Actions")).get("Type") == "fixed-response"
        and forward.get("Priority") == "10"
        and path_condition.get("Field") == "path-pattern"
        and isinstance(paths, list) and len(paths) == 2
        and set(paths) == ALLOW_PATHS
        and action.get("Type") == "forward"
        and action.get("TargetGroupArn") == target_arn,
        "RESEARCH_PATH_ALLOWLIST_UNVERIFIED",
    )

    hosted = zones.get("HostedZones", [])
    matches = [
        z for z in hosted if isinstance(z, dict)
        and z.get("Name") == DOMAIN + "." and z.get("Config", {}).get("PrivateZone") is True
    ] if isinstance(hosted, list) else []
    hosted_zone = _safe_one(matches)
    zone_id = hosted_zone.get("Id", "").split("/")[-1]
    owned_zone = private_zone.get("HostedZone", {})
    vpc_assoc = private_zone.get("VPCs", [])
    zone_ok = check(
        len(matches) == 1 and bool(zone_id)
        and owned_zone.get("Id", "").split("/")[-1] == zone_id
        and owned_zone.get("Config", {}).get("PrivateZone") is True
        and len(vpc_assoc) >= 1
        and any(a.get("VPCRegion") == REGION and a.get("VPCId") == vpc
                for a in vpc_assoc if isinstance(a, dict)),
        "VPC_ASSOCIATED_PRIVATE_DNS_ZONE_UNVERIFIED",
    )
    records = record_sets.get("ResourceRecordSets", [])
    a_records = [
        x for x in records if isinstance(x, dict)
        and x.get("Name") == DOMAIN + "." and x.get("Type") in ("A", "AAAA")
    ] if isinstance(records, list) else []
    record = _safe_one(a_records)
    alias = record.get("AliasTarget", {})
    expected_alb_dns = str(alb.get("DNSName", "")).rstrip(".").lower()
    configured_dns = str(alias.get("DNSName", "")).rstrip(".").lower()
    dns_ok = check(
        zone_ok and len(a_records) == 1 and record.get("Type") == "A"
        and not record.get("ResourceRecords")
        and alias.get("HostedZoneId") == alb.get("CanonicalHostedZoneId")
        and alias.get("EvaluateTargetHealth") is False
        and expected_alb_dns != ""
        and configured_dns.removeprefix("dualstack.") == expected_alb_dns.removeprefix(
            "dualstack."
        ),
        "PRIVATE_WORKER_DNS_ALIAS_UNVERIFIED",
    )

    svc = {name: _active_service(services, name) for name in (
        SERVICE_BACKEND, SERVICE_FRONTEND, SERVICE_WORKER
    )}
    def task_sgs(name: str) -> set[str]:
        rows = svc[name].get("networkConfiguration", {}).get("awsvpcConfiguration", {})
        return set(rows.get("securityGroups", []))

    ecs_ok = check(
        not services.get("failures")
        and all(s.get("status") == "ACTIVE"
                and s.get("clusterArn") == (
                    f"arn:aws:ecs:{REGION}:{ACCOUNT}:cluster/{CLUSTER}"
                ) for s in svc.values())
        and sg_identity_ok and java_id in task_sgs(SERVICE_BACKEND)
        and java_id not in task_sgs(SERVICE_FRONTEND)
        and java_id not in task_sgs(SERVICE_WORKER)
        and worker_id in task_sgs(SERVICE_WORKER)
        and edge_id not in task_sgs(SERVICE_BACKEND)
        and svc[SERVICE_WORKER].get("desiredCount") == 0
        and svc[SERVICE_WORKER].get("runningCount") == 0
        and svc[SERVICE_WORKER].get("pendingCount") == 0
        and svc[SERVICE_WORKER].get("enableExecuteCommand") is False
        and svc[SERVICE_WORKER].get("networkConfiguration", {})
        .get("awsvpcConfiguration", {}).get("assignPublicIp") == "DISABLED",
        "DORMANT_ECS_BACKEND_ISOLATION_UNVERIFIED",
    )

    verdict = "PRIVATE_TLS_TOPOLOGY_CONFIGURED_WORKER_DORMANT_ONLY" if not blockers else "BLOCKED"
    return {
        "contract_version": VERSION,
        "checked_at_utc": now.isoformat(),
        "expected_commit_sha": expected_sha if commit_ok else "invalid",
        "region": REGION,
        "aws_account_verified": account_ok,
        "internal_alb_verified": alb_ok,
        "private_subnet_metadata_verified": subnets_ok,
        "backend_only_sg_identity_verified": sg_identity_ok,
        "alb_security_group_boundaries_verified": alb_sg_ok,
        "direct_python_http_bypass_absent": worker_sg_ok,
        "https_listener_default_deny_verified": listener_ok,
        "research_api_paths_only_verified": routing_ok,
        "acm_certificate_metadata_verified": cert_ok,
        "worker_target_group_verified": tg_ok,
        "vpc_private_dns_zone_verified": zone_ok,
        "private_dns_alias_verified": dns_ok,
        "backend_frontend_worker_ecs_separation_verified": ecs_ok,
        "private_worker_tls_metadata_verified": not blockers,
        "private_tls_handshake_from_java_verified": False,
        "worker_artifacts_or_token_verified": False,
        "worker_running": False,
        "paper_shadow_soak_accepted": False,
        "independent_market_data_rights_verified": False,
        "live_execution_enabled": False,
        "can_authorize_capital": False,
        "verdict": verdict,
        "blocker_codes": sorted(blockers),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only worker TLS posture inspector")
    inputs = (
        "identity", "load_balancers", "listeners", "rules", "target_groups",
        "certificate", "zones", "private_zone", "record_sets",
        "security_groups", "subnets", "services",
    )
    for name in inputs:
        parser.add_argument("--" + name.replace("_", "-"), required=True, type=Path)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        snapshots = {
            name: json.loads(getattr(args, name).read_text(encoding="utf-8"))
            for name in inputs
        }
        if not all(isinstance(item, dict) for item in snapshots.values()):
            raise ValueError("invalid AWS snapshot")
        report = evaluate(**snapshots, expected_sha=args.expected_sha)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError):
        report = {
            "contract_version": VERSION,
            "verdict": "BLOCKED",
            "private_worker_tls_metadata_verified": False,
            "private_tls_handshake_from_java_verified": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "blocker_codes": ["INVALID_READONLY_INSPECTION_INPUT"],
        }
    args.output.write_text(
        json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print("V86 AWS private worker TLS metadata:", report["verdict"])
    return 0 if report["private_worker_tls_metadata_verified"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
