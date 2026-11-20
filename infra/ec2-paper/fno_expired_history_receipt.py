#!/usr/bin/env python3
"""Post only a bounded, secret-free source-probe receipt to recovery issue #1504.

Never attach stdout/stderr, candle data, instrument IDs, bearer/API credentials,
stack traces, provider messages, or an unreviewed source status to GitHub.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

REPOSITORY = "operationReact/AiTradingSystem"
ISSUE = 1504
# Source-side states are reviewed and finite; never relay arbitrary provider text.
from fno_expired_history_probe import STATES as SOURCE_STATES
SUPPORTED = frozenset(SOURCE_STATES)
HTTP_CODES = {
    "BAD_REQUEST": 400, "UNAUTHORIZED": 401, "FORBIDDEN": 403,
    "NOT_FOUND": 404, "UNPROCESSABLE": 422, "RATE_LIMITED": 429,
    "INTERNAL_ERROR": 500, "BAD_GATEWAY": 502,
    "SERVICE_UNAVAILABLE": 503, "GATEWAY_TIMEOUT": 504,
}
PATTERN = re.compile(
    r"^FNO EXPIRED SOURCE: ([A-Z_]+), expiries=(\d{1,3}), "
    r"futures=(\d{1,3}), dailyBars=(\d{1,3}); "
    r"rights=UNKNOWN; persisted=FALSE; orders=FALSE$",
    re.MULTILINE,
)


def safe_receipt(text: str) -> tuple[str, int, int, int] | None:
    if not isinstance(text, str) or len(text) > 16000:
        return None
    matches = PATTERN.findall(text)
    if len(matches) != 1:
        return None
    status, *numbers = matches[0]
    counts = [int(number) for number in numbers]
    if status not in SUPPORTED or any(n > 128 for n in counts):
        return None
    if status == "HISTORY_SOURCE_VERIFIED" and any(n < 1 for n in counts):
        return None
    return status, *counts


def safe_issue_message(receipt: tuple[str, int, int, int] | None,
                       run_id: str) -> str:
    if not isinstance(run_id, str) or not re.fullmatch(r"\d{1,20}", run_id):
        raise ValueError("FNO_UNTRUSTED_RUN_ID")
    link = f"https://github.com/{REPOSITORY}/actions/runs/{run_id}"
    if receipt is None:
        result = ("**NO_SANITIZED_RECEIPT** — check protected workflow run. "
                  "No source availability, retained history or restart is verified.")
    else:
        status, expired_dates, futures, daily_bars = receipt
        state = "SOURCE_SAMPLE_VERIFIED" if status == "HISTORY_SOURCE_VERIFIED" else "SOURCE_BLOCKED"
        detail = ""
        for stage in ("EXPIRIES", "CONTRACTS", "CANDLES"):
            for reason, http_code in HTTP_CODES.items():
                if status == stage + "_" + reason:
                    detail = f" (HTTP {http_code} at {stage.lower()} endpoint)"
        result = (
            f"**{state}** — source status \`{status}\`{detail}; expired expiries: "
            f"{expired_dates}; matching futures contracts: {futures}; "
            f"verified sample daily bars: {daily_bars}."
        )
    return (
        "### F&O expired-history source receipt (bounded read-only)\n\n"
        + result + "\n\n"
        "This is **not evidence of bulk NSE_FO history persistence, provider "
        "storage/research rights or trading activation**. No dataset counts, "
        "market-data payloads, keys or credentials are published.\n\n"
        f"GitHub workflow: {link}"
    )


def publish(log_path: Path) -> None:
    if (os.environ.get("GH_REPOSITORY") != REPOSITORY
            or not re.fullmatch(r"\d{1,20}", os.environ.get("GH_RUN_ID", ""))):
        raise ValueError("FNO_ISSUE_WRONG_CONTEXT")
    raw = ""
    if (log_path.is_file() and not log_path.is_symlink()
            and log_path.stat().st_size <= 16000):
        raw = log_path.read_text(encoding="utf-8", errors="replace")
    body = safe_issue_message(safe_receipt(raw), os.environ["GH_RUN_ID"])
    token = os.environ.get("GH_TOKEN", "")
    if not token or any(ch.isspace() for ch in token):
        raise ValueError("FNO_ISSUE_GITHUB_TOKEN_UNAVAILABLE")
    url = f"https://api.github.com/repos/{REPOSITORY}/issues/{ISSUE}/comments"
    request = urllib.request.Request(
        url, method="POST", data=json.dumps({"body": body}).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
            "Accept": "application/vnd.github+json",
            "User-Agent": "MintTick-FNO-Source-Safe-Receipt/1",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            if response.status != 201:
                raise ValueError("FNO_ISSUE_RECEIPT_NOT_ACCEPTED")
    except (OSError, urllib.error.URLError, urllib.error.HTTPError):
        raise ValueError("FNO_ISSUE_RECEIPT_UNAVAILABLE") from None
    print("FNO_ISSUE_SAFE_RECEIPT_POSTED", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("FNO_ISSUE_INVALID_ARGUMENT")
    try:
        publish(Path(sys.argv[1]))
    except (ValueError, OSError):
        raise SystemExit("FNO_ISSUE_RECEIPT_UNAVAILABLE") from None
