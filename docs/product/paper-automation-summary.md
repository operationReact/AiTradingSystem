# PAPER automation foundation summary

This branch establishes the intended customer/product behavior for the next AiTradingSystem slice while preserving the release safety boundary.

Customer model: **PAPER/LIVE × Manual/Automated**. Current release capability: **PAPER available, Automated available after explicit account opt-in, LIVE unavailable, broker submission unavailable**.

Manual and Automated PAPER intents share a server risk boundary. Automated adds opportunity eligibility/confidence and account opt-in. The executable foundation covers idempotent simulated fills, positions, mark-to-market P&L, capital accounting, target/stop/emergency exits, realized P&L and account-scoped history. Tests explicitly prove no customer mode combination enables broker submission.

This is not yet merge-ready production integration. Temporary in-process adapters must be reconciled with existing persisted account/risk/PAPER services; actual Flutter controls/lifecycle UI, exact-head CI, canonical Figma phone evidence and Android APK packaging remain required.
