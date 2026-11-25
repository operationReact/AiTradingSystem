"""V78: fail-closed research worker startup, independent of public HTTP health.

When the operator requires strict bootstrap, the container refuses to start
without PAPER mode, a strong private token and a verified full allowlist of
locally pinned CatBoost/LightGBM model artifact bytes. No secrets, weights or
file paths are printed. This is NOT a market license or deployment attestation.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from .research_artifact_inference import InferenceUnavailable, inspect_worker_roster


def verify_strict_worker_bootstrap() -> bool:
    """Return True only for a successful strict roster validation.

    Non-strict use is limited to local development / existing synthetic tests.
    The AWS PAPER worker task definition must always set strict=true.
    """
    enabled = os.getenv("AI_RESEARCH_STRICT_BOOT_REQUIRED", "false")
    if enabled not in {"true", "false"}:
        raise InferenceUnavailable("strict worker startup setting invalid")
    if enabled == "false":
        return False

    if os.getenv("TRADING_MODE") != "PAPER" or "APP_TRADING_MODE" in os.environ:
        raise InferenceUnavailable("worker must be explicitly PAPER")
    token = os.getenv("AI_RESEARCH_WORKER_TOKEN", "")
    if not 32 <= len(token) <= 4096:
        raise InferenceUnavailable("strong private worker token required")
    raw_path = os.getenv("AI_RESEARCH_MODEL_MANIFEST", "")
    if not raw_path or len(raw_path) > 4096:
        raise InferenceUnavailable("local research artifact manifest required")
    path = Path(raw_path)
    if not path.is_absolute():
        raise InferenceUnavailable("research manifest must use an absolute path")

    roster = inspect_worker_roster(path)
    if not roster.local_artifacts_validated or roster.can_authorize_capital:
        raise InferenceUnavailable("research model roster cannot be accepted")
    # Any token/manifest/source rights are independently reviewable later.
    return True


def main() -> int:
    try:
        strict = verify_strict_worker_bootstrap()
    except (InferenceUnavailable, ValueError, OSError, TypeError):
        print("V78 worker bootstrap BLOCKED (sanitized)", file=sys.stderr)
        return 2

    if strict:
        print("V78 local artifact bootstrap checked; research-only")
    else:
        print("V78 strict bootstrap disabled; local development only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
