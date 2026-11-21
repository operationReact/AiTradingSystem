#!/usr/bin/env python3
"""Optional atomic, metadata-only F&O backfill progress snapshot.

No HTTP, AWS, SSM, broker orders, or market-data retention. An operator-owned
future runner must explicitly call export_progress after journal changes.
Not a scheduler; no executable CLI, and no provider/source rights granted.
"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile

from fno_history_backfill import MetadataQueue, SOURCE

SCHEMA = "minttick.fno.history.progress.v1"
SCOPE = "CURRENT_JOURNAL_BATCHES_ONLY"


def build_snapshot(queue: MetadataQueue, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError("FNO_PROGRESS_TIMEZONE_REQUIRED")
    summary = queue.summary()
    counts = summary["counts"]
    total = summary["total_batches"]
    done = summary["completed_batches"]
    if (total < 0 or done < 0 or done > total or
            sum(counts.values()) != total):
        raise ValueError("FNO_PROGRESS_CORRUPT_JOURNAL")
    finished = summary["last_completed_utc_epoch"]
    last_completed = (datetime.fromtimestamp(finished,timezone.utc)
                      .isoformat().replace("+00:00","Z")
                      if finished is not None else None)
    return {
        "schema": SCHEMA,
        "source": SOURCE,
        "planScope": SCOPE,
        "state": "PREPARATION_ONLY",
        "retentionRightsVerified": False,
        "storageActive": False,
        "generatedAt": now.astimezone(timezone.utc).isoformat().replace("+00:00","Z"),
        "totalBatches": total if total else None,
        "completedBatches": done if total else None,
        "completionPercent": summary["completion_pct"] if total else None,
        "rowsWritten": summary["rows_written"] if total else None,
        "lastCompletedAt": last_completed,
        "counts": counts,
    }


def export_progress(queue: MetadataQueue, destination: Path,
                    now: datetime | None = None) -> dict:
    """Create/replace only a local snapshot. No candle prices, keys or tokens."""
    target = Path(destination)
    parent = target.parent
    if (parent.is_symlink() or not parent.is_dir() or
            target.is_symlink() or (target.exists() and not target.is_file())):
        raise ValueError("FNO_PROGRESS_UNSAFE_DESTINATION")
    value = build_snapshot(queue, now)
    serialized = json.dumps(value,sort_keys=True,separators=(",",":")).encode("utf-8")
    if len(serialized) > 4096:
        raise ValueError("FNO_PROGRESS_EXCEEDS_LIMIT")
    tmp = None
    try:
        fd, name = tempfile.mkstemp(prefix=".fno-progress-",suffix=".tmp",dir=parent)
        tmp = Path(name)
        with os.fdopen(fd,"wb") as stream:
            os.fchmod(stream.fileno(),0o600)
            stream.write(serialized)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp,target)
        tmp = None
    finally:
        if tmp is not None:
            tmp.unlink(missing_ok=True)
    return value


# No main() and no scheduled export. The status file is generated only when
# an authorized operational integration explicitly invokes export_progress.
