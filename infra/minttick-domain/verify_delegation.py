#!/usr/bin/env python3
"""Read-only public MintTick delegation and ACM issuance check."""
import json
import subprocess
import sys

EXPECTED_ACCOUNT = "098859530205"
DOMAIN = "minttick.com"
ZONE_ID = "Z007211128LFW2NUELIKC"
CERTIFICATE_ARN = "arn:aws:acm:us-east-1:098859530205:certificate/aa6654dd-3215-439d-8e83-b8f2e48531a1"
EXPECTED_NS = {
    "ns-218.awsdns-27.com",
    "ns-1936.awsdns-50.co.uk",
    "ns-1215.awsdns-23.org",
    "ns-548.awsdns-04.net",
}

def invoke(command):
    result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=30)
    if result.returncode != 0:
        raise RuntimeError("Read-only command failed: " + command[0] + " " + command[1])
    return result.stdout

def aws(*args):
    return json.loads(invoke(["aws", *args, "--output", "json"]))

def dig_ns(server):
    try:
        out = invoke(["dig", "+time=3", "+tries=2", "+short", "NS", DOMAIN, "@" + server])
    except (RuntimeError, subprocess.TimeoutExpired):
        return None
    return {row.strip().lower().rstrip(".") for row in out.splitlines() if row.strip() and not row.startswith(";")}

def state(actual):
    if actual is None:
        return "UNREACHABLE"
    if actual == EXPECTED_NS:
        return "MATCHED"
    if len(actual) == 0:
        return "NOT_RESOLVED"
    return "NOT_PROPAGATED_OR_MISMATCH"

def main():
    identity = aws("sts", "get-caller-identity")
    if identity.get("Account") != EXPECTED_ACCOUNT:
        raise RuntimeError("AWS account mismatch")
    zone = aws("route53", "get-hosted-zone", "--id", ZONE_ID)
    if zone["HostedZone"]["Name"].rstrip(".") != DOMAIN or zone["HostedZone"]["Config"]["PrivateZone"]:
        raise RuntimeError("Wrong or private hosted zone")
    zone_ns = {row.lower().rstrip(".") for row in zone["DelegationSet"]["NameServers"]}
    if zone_ns != EXPECTED_NS:
        raise RuntimeError("AWS hosted zone name servers differ from reviewed Hostinger values")
    cert = aws("acm", "describe-certificate", "--region", "us-east-1",
               "--certificate-arn", CERTIFICATE_ARN)["Certificate"]
    names = {cert["DomainName"], *cert.get("SubjectAlternativeNames", [])}
    if names != {"minttick.com", "www.minttick.com", "api.minttick.com"}:
        raise RuntimeError("ACM certificate domain set mismatch")
    if cert["Type"] != "AMAZON_ISSUED":
        raise RuntimeError("ACM certificate must be Amazon-issued")
    print("PASS: Exact expected Route 53 public hosted zone and AWS account")
    for server in ("a.gtld-servers.net", "1.1.1.1", "8.8.8.8"):
        result = dig_ns(server)
        print("DNS", server, state(result))
        if result and result != EXPECTED_NS:
            print("  Registrar/recursive resolver currently reports:", ", ".join(sorted(result)))
    print("ACM certificate status:", cert["Status"])
    if cert["Status"] == "ISSUED":
        print("PASS: AWS certificate issued")
    else:
        print("WAIT: ACM issuance can lag behind registrar propagation")
    print("Read-only check: no zone/record/certificate/application resources modified")

if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        print("BLOCKED:", str(exc))
        sys.exit(2)
