#!/usr/bin/env python3
"""Stage MintTick's ACM DNS validation in one verified Route 53 zone.

Only actions: inspect one minttick.com public zone, request/reuse one three-name
ACM certificate in us-east-1, and UPSERT the exact ACM-provided validation CNAMEs.
Never change apex/www/api website routing, NS/SOA, MX/TXT, or AWS networking.
"""
from __future__ import annotations

import json
import re
import subprocess
import time

DOMAIN = "minttick.com"
EXPECTED_DOMAINS = frozenset({DOMAIN, "www.minttick.com", "api.minttick.com"})
ACCOUNT = "098859530205"
REGION = "us-east-1"
CNAME_NAME = re.compile(r"^_[a-z0-9]+\.(?:(?:www|api)\.)?minttick\.com\.$")
CNAME_VALUE = re.compile(r"^_[a-z0-9_-]+\.acm-validations\.aws\.$")


def aws(*args: str) -> dict:
    result = subprocess.run(
        ["aws", *args, "--output", "json"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        # Do not echo raw AWS diagnostics, environment variables or headers.
        raise RuntimeError(f"AWS command failed at {args[0]} {args[1]} (exit {result.returncode})")
    return json.loads(result.stdout)


def resolve_zone() -> tuple[str, list[str]]:
    data = aws("route53", "list-hosted-zones-by-name", "--dns-name", DOMAIN + ".")
    matching = [
        zone for zone in data.get("HostedZones", [])
        if zone["Name"].rstrip(".") == DOMAIN and not zone["Config"]["PrivateZone"]
    ]
    if len(matching) != 1:
        raise RuntimeError(
            f"Expected exactly one public {DOMAIN} hosted zone; found {len(matching)}. "
            "Avoid duplicate hosted-zone charges and unsafe DNS cutover."
        )
    zone_id = matching[0]["Id"].split("/")[-1]
    details = aws("route53", "get-hosted-zone", "--id", zone_id)
    nameservers = details["DelegationSet"]["NameServers"]
    if len(nameservers) != 4:
        raise RuntimeError("Route 53 did not return exactly four nameservers")
    return zone_id, nameservers


def certificate_matches(arn: str) -> bool:
    result = aws("acm", "describe-certificate", "--region", REGION, "--certificate-arn", arn)
    cert = result["Certificate"]
    names = {cert["DomainName"], *cert.get("SubjectAlternativeNames", [])}
    return (names == EXPECTED_DOMAINS
            and cert["Type"] == "AMAZON_ISSUED"
            and cert["Status"] in {"ISSUED", "PENDING_VALIDATION"})


def ensure_certificate() -> str:
    listed = aws(
        "acm", "list-certificates", "--region", REGION,
        "--certificate-statuses", "ISSUED", "PENDING_VALIDATION",
    )
    matching = []
    for summary in listed.get("CertificateSummaryList", []):
        if summary["DomainName"] == DOMAIN:
            arn = summary["CertificateArn"]
            if certificate_matches(arn):
                matching.append(arn)
    if len(matching) > 1:
        raise RuntimeError("Multiple matching ACM certificates; choose an existing one before continuing")
    if matching:
        print("Reusing exact existing MintTick certificate in us-east-1")
        return matching[0]
    created = aws(
        "acm", "request-certificate", "--region", REGION,
        "--domain-name", DOMAIN,
        "--subject-alternative-names", "www.minttick.com", "api.minttick.com",
        "--validation-method", "DNS",
        "--idempotency-token", "minttickdomain2026",
    )
    print("Requested one three-name MintTick ACM certificate in us-east-1")
    return created["CertificateArn"]


def get_validation_records(arn: str) -> tuple[list[dict], str]:
    for attempt in range(12):
        cert = aws("acm", "describe-certificate", "--region", REGION,
                   "--certificate-arn", arn)["Certificate"]
        records = {}
        for entry in cert.get("DomainValidationOptions", []):
            if "ResourceRecord" not in entry:
                continue
            record = entry["ResourceRecord"]
            # ACM may omit the DNS root dot. Canonicalize before applying the
            # strict allowlist rather than treating a valid DNS name as unsafe.
            domain = entry["DomainName"].lower().rstrip(".")
            name = record["Name"].strip().lower().rstrip(".") + "."
            value = record["Value"].strip().lower().rstrip(".") + "."
            if domain not in EXPECTED_DOMAINS:
                raise RuntimeError("ACM validation rejected: unexpected domain")
            if record["Type"] != "CNAME":
                raise RuntimeError("ACM validation rejected: unexpected record type")
            if not CNAME_NAME.fullmatch(name):
                raise RuntimeError("ACM validation rejected: unexpected CNAME name shape")
            if name.split(".", 1)[1].rstrip(".") != domain:
                raise RuntimeError("ACM validation rejected: record/domain mismatch")
            if not CNAME_VALUE.fullmatch(value):
                labels = value.rstrip(".").split(".")
                first = labels[0] if labels else ""
                raise RuntimeError(
                    "ACM validation rejected: unexpected CNAME target shape"
                    f" (labels={len(labels)}, leading_underscore={first.startswith('_')},"
                    f" suffix_acm={value.endswith('.acm-validations.aws.')},"
                    f" token_chars_valid={bool(re.fullmatch(r'_[a-z0-9_-]+', first))})"
                )
            if name in records and records[name] != value:
                raise RuntimeError("ACM supplied conflicting validation CNAMEs")
            records[name] = value
        if len({x["DomainName"] for x in cert.get("DomainValidationOptions", [])}) == 3 and records:
            return ([{"Name": name, "Type": "CNAME", "TTL": 300,
                      "ResourceRecords": [{"Value": value}]}
                     for name, value in sorted(records.items())], cert["Status"])
        if attempt < 11:
            time.sleep(10)
    raise RuntimeError("ACM did not provide all three validation-domain entries")


def stage_cnames(zone_id: str, records: list[dict]) -> None:
    existing = aws("route53", "list-resource-record-sets", "--hosted-zone-id", zone_id)
    by_name = {r["Name"].lower(): r for r in existing.get("ResourceRecordSets", [])}
    for record in records:
        old = by_name.get(record["Name"])
        if old is not None and (
            old["Type"] != "CNAME"
            or len(old.get("ResourceRecords", [])) != 1
            or old["ResourceRecords"][0]["Value"].lower() != record["ResourceRecords"][0]["Value"]
        ):
            raise RuntimeError("A different DNS record already occupies an ACM validation name")
    changes = [{"Action": "UPSERT", "ResourceRecordSet": record} for record in records]
    aws("route53", "change-resource-record-sets", "--hosted-zone-id", zone_id,
        "--change-batch", json.dumps({"Comment": "MintTick ACM DNS validation only",
                                      "Changes": changes}))
    print(f"Staged {len(changes)} exact ACM CNAME validation records in Route 53")


def main() -> None:
    identity = aws("sts", "get-caller-identity")
    if identity["Account"] != ACCOUNT:
        raise RuntimeError("Wrong AWS account; DNS and ACM operations blocked")
    zone_id, nameservers = resolve_zone()
    arn = ensure_certificate()
    records, status = get_validation_records(arn)
    stage_cnames(zone_id, records)
    print(f"Verified single public hosted zone for {DOMAIN} (ID: {zone_id})")
    print("Hostinger one-time nameserver replacements (not yet made):")
    for nameserver in nameservers:
        print("  " + nameserver.rstrip("."))
    print(f"ACM status before public delegation: {status}")
    print(f"Certificate ARN: {arn}")
    print("No apex, www, api application DNS routing or Hostinger records changed.")


if __name__ == "__main__":
    main()
