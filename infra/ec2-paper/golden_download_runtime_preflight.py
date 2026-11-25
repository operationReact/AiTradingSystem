#!/usr/bin/env python3
"""Private read-only Golden Source readiness inspection on approved EC2 host.

No broker login/candle download, data rows, credentials, secret names, IAM
changes, S3 bucket changes or order calls. Only bounded aggregate counts are
returned to GitHub. This check is NOT a source licence verification.
"""
from __future__ import annotations

import re
import subprocess

TABLES = {
    "SOURCE_PROVIDERS": "golden_data_provider",
    "SOURCE_RIGHTS": "golden_source_rights_receipt",
    "LISTING_IDENTITIES": "golden_listing_identity",
    "HISTORICAL_CALENDAR": "golden_historical_exchange_session_evidence",
    "VENDOR_PAGE_PLAN": "golden_vendor_page_plan",
    "IMMUTABLE_MANIFEST": "golden_dataset_object_manifest",
    "BACKFILL_CHUNKS": "golden_dataset_backfill_chunk",
}
SQL_PREDICATE = """
    p.source_kind = 'EXTERNAL'
    AND r.source_domain = 'daily_bars'
    AND r.permitted_use = 'RAW_RETENTION'
    AND r.receipt_status = 'VERIFIED'
    AND r.verified_at IS NOT NULL
    AND r.verified_by IS NOT NULL
    AND r.revoked_at IS NULL
    AND r.captured_at <= now()
    AND r.verified_at <= now()
    AND r.valid_from <= now()
    AND (r.valid_to IS NULL OR r.valid_to > now())
    AND NOT EXISTS (
        SELECT 1 FROM golden_source_rights_receipt rev
         WHERE rev.provider_id = r.provider_id
           AND rev.source_domain = r.source_domain
           AND rev.permitted_use = r.permitted_use
           AND rev.receipt_status = 'REVOKED'
           AND rev.revoked_at <= now()
           AND rev.captured_at <= now()
    )
"""
QUERIES = {
    "SOURCE_PROVIDERS": "SELECT count(*) FROM golden_data_provider WHERE source_kind='EXTERNAL'",
    "SOURCE_RIGHTS": "SELECT count(*) FROM golden_source_rights_receipt",
    "LISTING_IDENTITIES": "SELECT count(*) FROM golden_listing_identity",
    "HISTORICAL_CALENDAR": "SELECT count(*) FROM golden_historical_exchange_session_evidence",
    "VENDOR_PAGE_PLAN": "SELECT count(*) FROM golden_vendor_page_plan",
    "IMMUTABLE_MANIFEST": "SELECT count(*) FROM golden_dataset_object_manifest",
    "BACKFILL_CHUNKS": "SELECT count(*) FROM golden_dataset_backfill_chunk",
    "APPROVED_RETENTION_PROVIDERS": """
        SELECT count(DISTINCT p.provider_id)
          FROM golden_data_provider p
          JOIN golden_source_rights_receipt r ON r.provider_id=p.provider_id
         WHERE """ + SQL_PREDICATE,
    "APPROVED_RETENTION_AND_TRAINING_PROVIDERS": """
        SELECT count(DISTINCT p.provider_id)
          FROM golden_data_provider p
          JOIN golden_source_rights_receipt r ON r.provider_id=p.provider_id
         WHERE """ + SQL_PREDICATE + """
           AND EXISTS (
             SELECT 1 FROM golden_source_rights_receipt t
              WHERE t.provider_id=p.provider_id AND t.source_domain='daily_bars'
                AND t.permitted_use='MODEL_TRAINING'
                AND t.receipt_status='VERIFIED'
                AND t.verified_at IS NOT NULL
                AND t.verified_by IS NOT NULL
                AND t.revoked_at IS NULL
                AND t.captured_at<=now() AND t.verified_at<=now()
                AND t.valid_from<=now() AND (t.valid_to IS NULL OR t.valid_to>now())
                AND NOT EXISTS (
                    SELECT 1 FROM golden_source_rights_receipt rv
                     WHERE rv.provider_id=t.provider_id
                       AND rv.source_domain=t.source_domain
                       AND rv.permitted_use=t.permitted_use
                       AND rv.receipt_status='REVOKED'
                       AND rv.revoked_at<=now() AND rv.captured_at<=now()
                )
           )
    """,
    "STAGED_PAGES": "SELECT count(*) FROM golden_vendor_page_plan WHERE status='STAGED'",
    "VERIFIED_CUSTODY_CHUNKS": (
        "SELECT count(*) FROM golden_dataset_backfill_chunk WHERE status='VERIFIED'"
    ),
}


def command(args, timeout=8):
    try:
        result = subprocess.run(args, capture_output=True, text=True,
                                check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def db_container():
    name = command([
        "docker", "ps", "-q",
        "--filter", "label=com.docker.compose.project=aitradingsystem-paper",
        "--filter", "label=com.docker.compose.service=postgres",
    ])
    return name if name is not None and re.fullmatch(r"[0-9a-f]{12,64}", name) else None


def scalar(container, sql):
    if container is None:
        return None
    if not (sql.lstrip().upper().startswith("SELECT")
            and not any(word in sql.upper() for word in (
                "INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "COPY ", "TRUNCATE "
            ))):
        raise ValueError("read-only SQL required")
    raw = command([
        "docker", "exec", container, "psql", "-X", "-U", "aitrading",
        "-d", "aitrading", "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql,
    ], timeout=9)
    if raw is None or not re.fullmatch(r"[0-9]{1,9}", raw):
        return None
    return int(raw)


def inspect():
    container = db_container()
    result = {
        "DATABASE": "AVAILABLE" if container else "UNAVAILABLE",
    }
    if not container:
        return result
    for label, table in TABLES.items():
        # to_regclass returns no table name, only a count of matching tables.
        available = scalar(container,
            "SELECT count(*) FROM pg_class "
            "WHERE oid = to_regclass('public." + table + "')")
        result["TABLE_" + label] = ("PRESENT" if available == 1
                                      else "MISSING_OR_UNAVAILABLE")
    for label, sql in QUERIES.items():
        tables_ok = all(result.get("TABLE_" + key) == "PRESENT"
                        for key in (
                            ("SOURCE_PROVIDERS", "SOURCE_RIGHTS")
                            if label.startswith("APPROVED_") else
                            ("SOURCE_PROVIDERS", "SOURCE_RIGHTS")
                            if label == "SOURCE_RIGHTS" else
                            (label.replace("STAGED_PAGES", "VENDOR_PAGE_PLAN")
                                  .replace("VERIFIED_CUSTODY_CHUNKS", "BACKFILL_CHUNKS"),)
                        ))
        if not tables_ok:
            result["COUNT_" + label] = "UNAVAILABLE"
            continue
        value = scalar(container, sql)
        result["COUNT_" + label] = ("UNAVAILABLE" if value is None
                                    else str(min(value, 999_999_999)))
    # A database receipt is necessary, NOT proof of external provider agreement.
    result["LEGAL_AGREEMENT_INDEPENDENTLY_VERIFIED"] = "NOT_DETERMINED"
    result["STORAGE_BUCKET_KMS_VERSIONED_CONFIRMED"] = "NOT_DETERMINED"
    result["BULK_DOWNLOAD_EXECUTED"] = "NO"
    return result


if __name__ == "__main__":
    statuses = inspect()
    for label in sorted(statuses):
        value = statuses[label]
        if not re.fullmatch(r"[A-Z0-9_]{2,60}", label):
            continue
        if not (re.fullmatch(r"[0-9]{1,9}", value)
                or value in ("AVAILABLE", "UNAVAILABLE", "PRESENT",
                             "MISSING_OR_UNAVAILABLE", "NOT_DETERMINED", "NO")):
            continue
        print("GOLDEN_" + label + ": " + value, flush=True)
