#!/usr/bin/env python3
"""Offline, default-deny Golden Source all-listing/model prerequisite planner.

Never connects to Angel One, AWS, PostgreSQL, market data or brokerage; it only
reads the canonical source-requirement contract and an optional independently
reviewed listing-episode *inventory* export. It is NOT a rights verifier, source
entitlement, trading decision or model-readiness attestation.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import sys
import uuid
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "docs/architecture/mintick-mind-golden-source-requirements.v1.json"
START = dt.date(2006, 11, 10)
END = dt.date(2026, 11, 10)
MAX_VENDOR_DAYS = 2000
MAX_CUSTODY_DAYS = 31
DIGEST = re.compile(r"^[a-f0-9]{64}$")
INVENTORY_SCHEMA = "minttick.golden.listing-episode-preflight.v1"
MAX_LISTINGS = 20000
BLOCKERS = (
    "NO_VERIFIED_LICENSED_RAW_RETENTION",
    "NO_VERIFIED_MODEL_TRAINING_AND_INFERENCE_RIGHTS",
    "NO_20_YEAR_VERIFIED_EXCHANGE_CALENDAR",
    "NO_POINT_IN_TIME_CORPORATE_ACTION_DATASET",
    "NO_PHYSICAL_VERSIONED_ARCHIVE_ACTIVE",
    "NO_INDEPENDENT_HISTORICAL_LISTING_RECONCILIATION",
    "NO_24_DOMAIN_MODEL_DATASETS_VERIFIED",
)


def date(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise ValueError("invalid ISO date")
    try:
        return dt.date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError("invalid calendar date") from exc


def windows(start: dt.date, end: dt.date, days: int):
    if not 1 <= days <= MAX_VENDOR_DAYS or start > end:
        raise ValueError("invalid bounded window")
    while start <= end:
        stop = min(start + dt.timedelta(days=days - 1), end)
        yield start, stop
        start = stop + dt.timedelta(days=1)


def require_strict_inventory(path: pathlib.Path):
    raw = path.read_bytes()
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError("inventory exceeds bounded 4 MiB preflight input")
    inventory = json.loads(raw)
    if not isinstance(inventory, dict) or set(inventory) != {
        "schema", "reference_digest", "listings"
    } or inventory["schema"] != INVENTORY_SCHEMA or not isinstance(
        inventory.get("reference_digest"), str
    ) or not DIGEST.fullmatch(inventory["reference_digest"]):
        raise ValueError("missing independently reviewable inventory reference digest")
    listings = inventory["listings"]
    if not isinstance(listings, list) or len(listings) > MAX_LISTINGS:
        raise ValueError("invalid or oversized listing inventory")
    seen = set()
    result = []
    for item in listings:
        if not isinstance(item, dict) or set(item) != {
            "listing_id", "exchange", "listed_from", "delisted_on",
            "identity_evidence_digest"
        }:
            raise ValueError("unsupported historical listing evidence schema")
        identity = item["listing_id"]
        if not isinstance(identity, str) or str(uuid.UUID(identity)) != identity:
            raise ValueError("permanent listing UUID required; broker token prohibited")
        if identity in seen or item["exchange"] not in ("NSE", "BSE"):
            raise ValueError("duplicate historical listing ID or unsupported exchange")
        seen.add(identity)
        born = date(item["listed_from"])
        delisted = None if item["delisted_on"] is None else date(item["delisted_on"])
        if (born > END or delisted is not None and delisted <= born
                or not isinstance(item["identity_evidence_digest"], str)
                or not DIGEST.fullmatch(item["identity_evidence_digest"])):
            raise ValueError("invalid or unevidenced historical listing episode")
        result.append((identity, item["exchange"], born, delisted))
    return result


def plan_inventory(listings):
    """Plan vendor-request counts only; never creates backfill jobs or API calls."""
    planned = 0
    archive_chunks = 0
    by_exchange = Counter()
    eligible = 0
    blocked_pre_window = 0
    sample = []
    for identity, exchange, born, delisted in listings:
        first = max(START, born)
        last = min(END, delisted - dt.timedelta(days=1)) if delisted else END
        if first > last:
            blocked_pre_window += 1
            continue
        eligible += 1
        by_exchange[exchange] += 1
        # Critical efficiency: broker 2000-day source windows versus separately
        # chunked 31-day immutable custody. Do NOT make 31-day broker API calls.
        provider_windows = list(windows(first, last, MAX_VENDOR_DAYS))
        planned += len(provider_windows)
        archive_chunks += sum(1 for _ in windows(first, last, MAX_CUSTODY_DAYS))
        if len(sample) < 5:
            sample.append({
                "listing_id": identity,
                "exchange": exchange,
                "episode_first": first.isoformat(),
                "episode_last": last.isoformat(),
                "vendor_windows": [
                    {"from": x.isoformat(), "through": y.isoformat()}
                    for x, y in provider_windows
                ],
            })
    return {
        "listing_episodes_supplied": len(listings),
        "listing_episodes_intersecting_target": eligible,
        "episodes_outside_target": blocked_pre_window,
        "by_exchange": dict(sorted(by_exchange.items())),
        "planned_2000_day_provider_reads_if_approved": planned,
        "planned_31_day_archive_segments_if_approved": archive_chunks,
        "sample_plans_not_executable": sample,
    }


def contract_requirements(contract):
    if (contract.get("contract_version") !=
            "minttick.mind.golden-source-requirements.v1"):
        raise ValueError("unexpected audit contract version")
    sources = contract["data_sources"]
    models = contract["models"]
    screens = contract["screens"]
    families = contract["strategy_families"]
    if (len(sources) != 24 or len(models) != 18 or len(screens) != 43
            or len(families) != 20 or sum(len(f["templates"]) for f in families) != 232):
        raise ValueError("unexpected model/UI/source contract drift")
    for kind, entries in (("model", models), ("screen", screens),
                          ("strategy family", families)):
        for entry in entries:
            required = entry["required_sources"]
            if len(required) != len(set(required)) or not set(required) <= set(sources):
                raise ValueError("unregistered or duplicate " + kind + " source")
    source_consumers = {
        source: {
            "required_by_models": [m["name"] for m in models
                                   if source in m["required_sources"]],
            "required_by_screens": [s["id"] for s in screens
                                    if source in s["required_sources"]],
            "required_by_strategy_families": [f["family"] for f in families
                                              if source in f["required_sources"]],
            "field_count_declared_not_validated": len(detail["fields"]),
            "audit_state_not_data_readiness": detail["state"],
            "history_requirement": detail["history"],
            "rights_status": "NOT_VERIFIED",
            "data_status": "NOT_INDEPENDENTLY_VERIFIED",
        } for source, detail in sorted(sources.items())
    }
    return {
        "source_families": source_consumers,
        "models": [{
            "model": m["name"],
            "required_sources": m["required_sources"],
            "optional_sources": m.get("optional_sources", []),
            "missing_verified_sources": list(m["required_sources"]),
            "model_data_ready": False,
            "training_authorized": False,
            "inference_authorized": False,
        } for m in models],
        "screens_total": len(screens),
        "strategy_families_total": len(families),
        "strategy_templates_total": sum(len(f["templates"]) for f in families),
        "data_families_total": len(sources),
        "no_models_claimed_ready": all(m.get("model_authorizes_orders") is False
                                      for m in models),
    }


def generate(inventory=None):
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    requirements = contract_requirements(contract)
    listings = [] if inventory is None else require_strict_inventory(inventory)
    return {
        "schema": "minttick.golden.fleet-readiness-preflight.v1",
        "target_date_start": START.isoformat(),
        "target_date_end": END.isoformat(),
        "execution": "OFFLINE_PLANNING_ONLY",
        "historical_angelone_probe_for_one_sample_stock": {
            "instrument": "RELIANCE-EQ",
            "date_range": "2006-11-10_to_2026-11-09",
            "bars_returned_across_four_probes": 4955,
            "stored_bars": 0,
            "proven_20_year_fleet_coverage": False,
        },
        "inventory_input_status": ("ABSENT" if inventory is None
                                   else "SUPPLIED_NOT_INDEPENDENTLY_ATTESTED"),
        "planning": plan_inventory(listings),
        "requires_review_before_api_or_storage": list(BLOCKERS),
        "eligible_to_start_bulk_source_download": False,
        "eligible_to_publish_model_features": False,
        **requirements,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=pathlib.Path,
                        help="Optional reviewed V77 listing-episode export; never current tokens")
    args = parser.parse_args(argv)
    try:
        data = generate(args.inventory)
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print("Golden Source preflight blocked: " + str(exc), file=sys.stderr)
        return 2
    json.dump(data, sys.stdout, sort_keys=True, indent=2)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
