# PAPER automation CI cursor

After Draft PR creation, query workflow runs for the exact PR head. Treat queued/in-progress as pending. Treat failed jobs as concrete blockers to inspect and fix. Treat green as compilation/test evidence only, not merge authorization.

Do not use workflow evidence from PR #206 or an earlier head after this branch moves.
