# PAPER automation safety invariants

These invariants must remain true through persistence/UI integration:

1. PAPER is the only executable trading mode.
2. LIVE availability is false.
3. Broker submission availability is false.
4. Manual and Automated use the same server-authoritative risk path.
5. Automated requires explicit account opt-in.
6. Emergency stop overrides both intent sources.
7. Accepted work is idempotent across retries.
8. Account state is isolated and resolved from authenticated server identity.
9. Persisted backend state is authoritative across restart/reconnect.
10. PAPER simulation has no broker order dependency.
11. Angel One is read-only in this release.
12. Customer UI does not expose raw internal/provider/operator vocabulary.
13. CI green alone is not UI/device acceptance.
14. No production/cloud deployment is part of this slice.
