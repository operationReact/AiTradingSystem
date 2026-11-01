# PAPER automation exact-head CI gates

Before merge, the exact PR head must pass the repository's existing fast required gates plus tests introduced by this slice.

Minimum evidence:

- backend compile/test baseline;
- PAPER/risk scenario completion gates relevant to current lineage;
- Flutter analyze and full unit/widget tests;
- customer-language/product contract tests;
- visual golden regression;
- dependency/security gate;
- Android APK packaging/delivery gate.

Any failure must be root-caused and fixed on a new exact head. Do not rerun an unchanged failure merely to seek green status.

Connected runtime/physical-device evidence remains a product acceptance requirement where this slice touches real backend connectivity. CI green by itself does not satisfy phone visual or device acceptance.
