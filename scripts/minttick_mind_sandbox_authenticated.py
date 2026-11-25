#!/usr/bin/env python3
"""Authenticated Mintick Mind research -> Upstox sandbox, explicitly opt-in.

This is a non-funded developer integration test, never LIVE or PAPER customer
execution. Read-only research comes from the exact HTTPS customer endpoint.
Each signal is claimed in a durable SQLite one-attempt journal BEFORE any
broker request. Unknown broker outcomes permanently block replay. A completed
sandbox ACK sequence is NOT trade-fill, position or P&L reconciliation.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import urllib.error
import urllib.request

import minttick_mind_upstox_sandbox_bridge as bridge
import upstox_fno_sandbox_lifecycle as sandbox
import upstox_fno_sandbox_ssm as ssm

MIND_ENDPOINT = "https://api.minttick.com/api/customer/mind/v1/fno-evaluation"
BEARER_ENV = "MINTTICK_CUSTOMER_ACCESS_TOKEN"
MAX_BODY = 20000


class RuntimeBlocked(ValueError):
    """Only fixed, non-sensitive reason codes."""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def get_authenticated_signal(token: str) -> dict:
    """Read one *server-computed* customer signal; never accept JSON fixtures."""
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._~+/-]{30,16000}", token):
        raise RuntimeBlocked("MIND_CUSTOMER_TOKEN_MISSING_OR_INVALID")
    request = urllib.request.Request(
        MIND_ENDPOINT, method="GET",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json",
            "User-Agent": "MintTick-Mind-Sandbox-ReadOnly/1.0",
            "Cache-Control": "no-store",
        },
    )
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(request, timeout=6) as response:
            if response.status != 200:
                raise RuntimeBlocked("MIND_CUSTOMER_GET_NON_200")
            body = response.read(MAX_BODY + 1)
    except urllib.error.HTTPError:
        # Includes 3xx redirects; never follow a token-bearing request.
        raise RuntimeBlocked("MIND_CUSTOMER_GET_HTTP_REJECTED") from None
    except (urllib.error.URLError, OSError, TimeoutError):
        raise RuntimeBlocked("MIND_CUSTOMER_GET_UNAVAILABLE") from None
    if len(body) > MAX_BODY:
        raise RuntimeBlocked("MIND_CUSTOMER_RESPONSE_TOO_LARGE")
    try:
        data = json.loads(body)
    except (TypeError, ValueError, UnicodeDecodeError):
        raise RuntimeBlocked("MIND_CUSTOMER_RESPONSE_INVALID") from None
    if not isinstance(data, dict):
        raise RuntimeBlocked("MIND_CUSTOMER_RESPONSE_NOT_OBJECT")
    return data


def signal_fingerprint(research: dict, intent: bridge.SandboxIntent) -> str:
    """One intent per strategy + completed crossover candle, not per quote tick."""
    close = research.get("latestCompletedCloseAt")
    if not isinstance(close, str):
        raise RuntimeBlocked("MIND_CROSSOVER_BAR_MISSING")
    identity = json.dumps(
        ["upstox-sandbox-only", intent.strategy_id, close],
        separators=(",", ":"),
    )
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _journal_path(path: Path | None) -> Path:
    if path is None or not path.is_absolute():
        raise RuntimeBlocked("MIND_PERSISTENT_JOURNAL_REQUIRED")
    if not path.parent.is_dir() or path.suffix != ".sqlite":
        raise RuntimeBlocked("MIND_PERSISTENT_JOURNAL_INVALID")
    # Never create a ledger within the Git checkout: it could be committed or
    # silently replaced by an ephemeral CI job. Use a backed-up persistent path.
    if path.resolve().is_relative_to(Path.cwd().resolve()):
        raise RuntimeBlocked("MIND_JOURNAL_INSIDE_CHECKOUT_FORBIDDEN")
    return path


def claim_one_attempt(path: Path, fingerprint: str, intent: bridge.SandboxIntent):
    """Atomic cross-process claim; return connection after COMMIT.

    A crash after this point leaves CLAIMED, so automatic recovery NEVER
    resubmits the possibly accepted sandbox order.
    """
    target = _journal_path(path)
    try:
        db = sqlite3.connect(str(target), timeout=5, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
        db.execute("""CREATE TABLE IF NOT EXISTS sandbox_attempt (
            fingerprint TEXT PRIMARY KEY,
            strategy_id TEXT NOT NULL,
            instrument_key TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN
                ('CLAIMED','BROKER_ACKS_ONLY','OUTCOME_UNKNOWN')),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )""")
        now = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                """INSERT INTO sandbox_attempt(
                    fingerprint,strategy_id,instrument_key,status,created_at,updated_at)
                    VALUES(?,?,?,'CLAIMED',?,?)""",
                (fingerprint, intent.strategy_id, intent.instrument_key, now, now),
            )
            db.execute("COMMIT")
        except sqlite3.IntegrityError:
            db.execute("ROLLBACK")
            db.close()
            raise RuntimeBlocked("MIND_SIGNAL_ALREADY_CLAIMED_NO_RETRY") from None
        except BaseException:
            db.execute("ROLLBACK")
            db.close()
            raise
        return db
    except sqlite3.Error:
        raise RuntimeBlocked("MIND_JOURNAL_UNAVAILABLE_NO_ORDER") from None


def record_ack_state(db, fingerprint: str, status: str) -> None:
    if status not in ("BROKER_ACKS_ONLY", "OUTCOME_UNKNOWN"):
        raise RuntimeBlocked("MIND_JOURNAL_STATUS_INVALID")
    try:
        db.execute("BEGIN IMMEDIATE")
        changed = db.execute(
            """UPDATE sandbox_attempt SET status=?,updated_at=?
               WHERE fingerprint=? AND status='CLAIMED'""",
            (status, dt.datetime.now(dt.timezone.utc).isoformat(), fingerprint),
        ).rowcount
        if changed != 1:
            db.execute("ROLLBACK")
            raise RuntimeBlocked("MIND_JOURNAL_STATE_CONFLICT")
        db.execute("COMMIT")
    except sqlite3.Error:
        raise RuntimeBlocked("MIND_JOURNAL_UPDATE_UNKNOWN") from None


def execute_once(research: dict, intent: bridge.SandboxIntent,
                 journal: Path) -> str:
    if sandbox.SANDBOX != "https://api-sandbox.upstox.com" or (
            sandbox.SANDBOX_USER_AGENT != "MintTick-Upstox-Sandbox/1.0"):
        raise RuntimeBlocked("MIND_SANDBOX_TRANSPORT_IDENTITY_MISMATCH")
    fingerprint = signal_fingerprint(research, intent)
    db = claim_one_attempt(journal, fingerprint, intent)
    try:
        # Even an SSM failure leaves the claimed intent locked. Never infer
        # that a failed step proves a safe retry or that an order was filled.
        try:
            token = ssm.load_sandbox_token()
            try:
                result = sandbox.run_cycle(
                    sandbox.sandbox_transport(token),
                    intent.instrument_key, intent.quantity,
                    str(intent.limit_price), str(intent.tick))
            finally:
                token = None
        except (ssm.SandboxBootstrapError, sandbox.SandboxError):
            record_ack_state(db, fingerprint, "OUTCOME_UNKNOWN")
            raise RuntimeBlocked("MIND_SANDBOX_OUTCOME_UNKNOWN_NO_RETRY") from None
        except Exception:
            record_ack_state(db, fingerprint, "OUTCOME_UNKNOWN")
            raise RuntimeBlocked("MIND_SANDBOX_OUTCOME_UNKNOWN_NO_RETRY") from None
        if not all(result.get(key) is True for key in ("placed", "modified", "cancelled")):
            record_ack_state(db, fingerprint, "OUTCOME_UNKNOWN")
            raise RuntimeBlocked("MIND_SANDBOX_INCOMPLETE_ACK_NO_RETRY")
        record_ack_state(db, fingerprint, "BROKER_ACKS_ONLY")
        return fingerprint[:12]
    finally:
        db.close()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluate", action="store_true",
                        help="Authenticated GET and validation only; never calls broker")
    parser.add_argument("--execute-sandbox", action="store_true",
                        help="One simulated order lifecycle only, requires journal and confirmation")
    parser.add_argument("--confirmation", default="DO_NOT_EXECUTE")
    parser.add_argument("--journal", type=Path)
    args = parser.parse_args(argv)
    if args.execute_sandbox and (
            args.evaluate or args.confirmation != "SANDBOX_ONLY" or args.journal is None):
        print("MIND_SANDBOX_EXECUTION_GUARD_NOT_SATISFIED")
        return 2
    if not args.execute_sandbox and not args.evaluate:
        print("MIND_SANDBOX_NO_MODE_SELECTED_NO_NETWORK")
        return 2
    if args.evaluate and args.journal is not None:
        print("MIND_SANDBOX_EVALUATION_JOURNAL_NOT_REQUIRED")
        return 2
    try:
        customer_token = os.environ.get(BEARER_ENV, "")
        research = get_authenticated_signal(customer_token)
        customer_token = None
        intent = bridge.intent_from_research(
            research, bridge.current_nse_master())
        print("MIND_RESEARCH_AUTHENTICATED_VALIDATED:" + intent.strategy_id)
        if args.evaluate:
            print("MIND_SANDBOX_EVALUATION_ONLY:NO_ORDER")
            return 0
        fingerprint = execute_once(research, intent, args.journal)
        print("MIND_SANDBOX_ACKS_ONLY:" + fingerprint)
        print("MIND_SANDBOX_FILLED_OR_RECONCILED:FALSE")
        return 0
    except (RuntimeBlocked, bridge.BridgeBlocked) as e:
        print("MIND_SANDBOX_BLOCKED:" + str(e))
        return 1
    except Exception:
        print("MIND_SANDBOX_BLOCKED:UNAVAILABLE_OR_UNEXPECTED")
        return 1


if __name__ == "__main__":
    sys.exit(main())
