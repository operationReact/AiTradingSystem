# P09-W03 overview and widget controllers

OverviewPage owns range/widget preferences, core queries and news/provider/RSS controllers above Pro/Clear composition. Shared workspaces and widget views receive projections/callbacks; they import no API client or data hook. Hidden widgets disable their news/provider queries without removing controller state. Experience changes do not remount queries, lose widget selections or clear a separately authenticated RSS token draft.

News analysis and provider testing remain explicit operator actions, guarded against missing authentication and pending duplicates. Reading or switching a view never tests a provider connection or launches analysis. The RSS bearer token stays in page-controller memory and is sent only in its existing Authorization header on explicit Load feed; it is not saved to browser storage. Existing provider configuration state is not presented as demo data, and persisted deterministic signals are labeled research-only.

The browser journey advances cached widget data past staleness before switching designs, then checks no duplicate status query, no implicit provider/analysis call, preserved RSS input and explicit bounded feed load. It verifies widget/range continuity, no credential in browser storage, and malformed persisted preference fallback. Existing full browser contracts run in the same P9 CI workflow.

This completes overview/news/provider controller reconciliation. Instrument/market/settings controller surfaces remain before P09-W03 completion. It does not certify final Figma appearance/asset composition or the full state/viewport inventory. No schema, broker operation, deployment or LIVE activation is included by this refactor.

CI also sampled an accessibility defect during the emergency-stop action's danger-to-secondary color interpolation. That safety-state button now changes colors immediately, preserving readable contrast while authority updates instead of interpolating opposing foreground/background palettes. Existing risk accessibility evidence covers the changed state.
