# MintTick Flutter branding integration

The brand lockup reproduces the approved rising-M and market-bars concept as resolution-independent Flutter paths and an SVG (not as a screenshot).

## Brand colors

| Token | Color | Use |
|---|---|---|
| Mint | #00E676 | Dark-mode interaction and mark |
| Teal | #00BFA5 | Supporting brand gradient |
| Navy | #0B0F1A | Dark brand surfaces |
| Ice | #F8FAFC | Light surfaces and dark-mode text |
| Readable green | #007A52 | Light-mode controls and text |

Preserve both existing Flutter design profiles. Product risk states keep distinct semantic colors; trading permissions, API protocols, PAPER-only execution and credential handling are unchanged. React remains a legacy reference.

The generated image was a brand concept, not vector-master or trademark proof. Before app-store release: review the mark at 24px, produce and test native adaptive Android and iOS AppIcon resources, validate on physical devices, complete connected platform tests and clear the name and trademark.

**Name consistency check:** the most recent spoken message used "Minty", but the approved logo says "MintTick". UI follows the approved MintTick artwork for this PR; legal, domain, package and native identifiers are unchanged pending confirmation. No deployment or live execution authorization is inferred.

Validation: from apps/trading_client, run flutter analyze, flutter test and flutter build web --release.
