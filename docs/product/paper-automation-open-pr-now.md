# PAPER automation Draft PR opening decision

Decision: open the PR now as Draft to obtain repository-wide exact-head CI and review feedback while continuing implementation.

This does not waive any merge blocker. The branch is intentionally incomplete at production persistence and rendered Flutter UI layers.

After opening, inspect exact-head CI. Fix concrete failures at source. In parallel/next, inspect authoritative persisted services and integrate rather than extending temporary in-process state.
