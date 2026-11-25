# FC-03 connected runtime acceptance

This gate closes the gap between compile-only Flutter evidence and the real Spring Boot/PostgreSQL contract.

## Corrected contract

The protected backend contract uses `X-API-Key`. FC-02 initially sent `X-Operator-Key`; compile/widget tests could not detect that mismatch. FC-03 corrects the client and adds an exact request-contract regression.

## Evidence layers

1. Start the production Spring Boot application against PostgreSQL 16 with Flyway and external providers/ingestion disabled.
2. Prove unauthenticated `/api/operations/health` is rejected and valid `X-API-Key` succeeds.
3. Run the actual `HttpHealthRepository` against that process, requiring PAPER, LIVE unavailable, healthy database evidence, and non-HEALTHY aggregate status on an otherwise empty CI environment.
4. Run the actual Flutter UI integration journey on Android emulator and iOS Simulator against the same backend semantics. The journey covers positive auth, explicit refresh, degraded/UNKNOWN-safe evidence, design-style rebuild, theme rebuild and negative authentication without credential leakage.

Physical devices, store distribution/signing, background suspension and hostile network-transition testing remain later P11/P13/P14 acceptance obligations and are not inferred from simulator evidence.
