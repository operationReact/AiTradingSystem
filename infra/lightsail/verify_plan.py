#!/usr/bin/env python3
"""Fail-closed AWS Lightsail 4GB PAPER instance plan, no AWS SDK or mutations.

All AWS JSON is runner-local; output is a bounded result used by a manually
dispatched GitHub Actions workflow. Never create a second/costlier instance or
adopt an existing instance with a mismatching owner/plan.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ACCOUNT = "098859530205"
REGION = "us-east-1"
AZ = "us-east-1a"
INSTANCE = "aitradingsystem-paper-dev"
BUNDLE = "medium_3_0"
BLUEPRINT = "ubuntu_24_04"
TAGS = {"Application": "ai-trading-system", "Environment": "paper"}


def verify(bundles: dict, blueprints: dict, regions: dict, instances: dict) -> str:
    plans = bundles.get("bundles", [])
    expected = [x for x in plans if isinstance(x, dict) and x.get("bundleId") == BUNDLE]
    if len(expected) != 1:
        raise ValueError("expected Lightsail bundle missing/ambiguous")
    bundle = expected[0]
    if not (
        bundle.get("isActive") is True
        and bundle.get("ramSizeInGb") == 4
        and bundle.get("cpuCount") == 2
        and bundle.get("diskSizeInGb") == 80
        and bundle.get("price") == 24
        and "LINUX_UNIX" in bundle.get("supportedPlatforms", [])
        and bundle.get("publicIpv4AddressCount", 0) >= 1
    ):
        raise ValueError("Lightsail budget or IPv4 configuration mismatch")
    imgs = [x for x in blueprints.get("blueprints", [])
            if isinstance(x, dict) and x.get("blueprintId") == BLUEPRINT]
    if len(imgs) != 1 or imgs[0].get("isActive") is not True:
        raise ValueError("Ubuntu 24.04 blueprint not available")
    available = [
        zone.get("zoneName")
        for region in regions.get("regions", [])
        if region.get("name") == REGION
        for zone in region.get("availabilityZones", [])
    ]
    if AZ not in available:
        raise ValueError("expected Lightsail availability zone not available")
    inventory = instances.get("instances", [])
    same = [x for x in inventory if x.get("name") == INSTANCE]
    if len(same) > 1:
        raise ValueError("ambiguous Lightsail instance")
    if not same:
        return "absent"
    entry = same[0]
    labels = {t.get("key"): t.get("value") for t in entry.get("tags", [])
              if isinstance(t, dict)}
    hardware = entry.get("hardware", {})
    if not (
        all(labels.get(k) == v for k, v in TAGS.items())
        and entry.get("bundleId") == BUNDLE
        and entry.get("blueprintId") == BLUEPRINT
        and entry.get("location", {}).get("regionName") == REGION
        and entry.get("location", {}).get("availabilityZone") == AZ
        and hardware.get("cpuCount") == 2
        and hardware.get("ramSizeInGb") == 4
    ):
        raise ValueError("existing Lightsail instance is not the approved PAPER host")
    return "present"


def main() -> int:
    parser = argparse.ArgumentParser()
    for part in ("bundles", "blueprints", "regions", "instances"):
        parser.add_argument("--" + part, type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    try:
        inputs = {k: json.loads(getattr(args, k).read_text(encoding="utf-8"))
                  for k in ("bundles", "blueprints", "regions", "instances")}
        if not all(isinstance(x, dict) for x in inputs.values()):
            raise ValueError("AWS inventory must be JSON objects")
        status = verify(**inputs)
        # Only sanitized state; do not emit other instance names or credentials.
        with args.github_output.open("a", encoding="utf-8") as file:
            file.write("paper_instance_state=" + status + "\n")
        print("Lightsail PAPER 4GB plan verified; instance " + status)
        return 0
    except (ValueError, OSError, TypeError, KeyError, AttributeError,
            json.JSONDecodeError):
        print("Lightsail PAPER 4GB plan BLOCKED (sanitized)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
