# MintTick owner-private history: SSM audit-only request

An owner-only, opt-in way to audit existing root-private SQLite and ONE_DAY files with no Angel One request. Reuses the reviewed GitHub OIDC role, fixed Mumbai PAPER EC2, SSM Online gate, SHA256-pinned script transfer, and root-only archive. No instance start, stop, resize, cost changes, IAM or order permissions.

## Invocation

Commit EXACTLY ONE added or updated file on the latest remote dev branch:
    infra/ec2-paper/requests/owner-personal-history-audit.json

The file must contain precisely this object, with no extra fields:

    {
      "application": "MintTick",
      "account": "098859530205",
      "region": "ap-south-1",
      "instance_id": "i-09c01b470fb39b85a",
      "private_ec2_only": true,
      "no_redistribution": true,
      "enable_trading": false,
      "operation": "owner-personal-angelone-daily-history-audit",
      "audit_only": true,
      "max_provider_calls": 0
    }

This request file is intentionally NOT created in the code PR. Merely merging
the workflow code does not trigger a run. Only a subsequent, distinct one-file
owner-approved dev-branch change runs the audit, before 21:20 IST.

The workflow refuses any other changed file, altered request fields, EC2 that
is not already running and SSM Online, missing scheduled night stop, or a
locked/altered archive. It securely installs the exact reviewed scripts and
executes only:
    /opt/aitradingsystem-paper/run_owner_personal_history.sh audit-only

The 22:00 Asia/Kolkata stop is not extended.

## Count rules

Success requires a real SHA256/SQLite audit of the stored archive and ALL
of these allowlisted markers:
    PERSONAL_ARCHIVE_VERIFIED_PAGES: [integer]
    PERSONAL_ARCHIVE_VERIFIED_ROWS: [integer]
    PERSONAL_ARCHIVE_API_CALLS: 0
    PERSONAL_ARCHIVE_AUDIT_STATUS: VERIFIED

Without a successful audit do not count unknown 2nd-batch candles, and do not
trigger additional source requests. Never expose raw OHLCV, provider responses,
tokens or account secrets in GitHub Actions logs.

The existing bounded download remains a separate exact one-file request with
five equities, at most 20 provider requests and 10-second spacing. Integrity
alone does not establish historical token/listing mapping, corporate-action
adjustments, session completeness, model readiness or redistribution rights.
